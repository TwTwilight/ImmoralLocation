package com.immoral.location.core.engine

import com.immoral.location.core.geo.GeoMath
import com.immoral.location.core.model.EngineState
import com.immoral.location.core.model.GeoPoint
import com.immoral.location.core.model.MoveMode
import com.immoral.location.core.model.RouteState
import com.immoral.location.core.model.Satellite
import com.immoral.location.core.model.SimulatedAp
import com.immoral.location.core.model.SimulatedCell
import com.immoral.location.core.model.SimulationConfig
import com.immoral.location.core.protocol.CORE_ENGINE_VERSION
import com.immoral.location.core.route.RoutePlan
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * The simulation core: owns the spoofed position, the movement model and every deterministic
 * synthesizer (satellites, NMEA, cells, access points).
 *
 * ### Threading
 * **This class is not thread-safe.** It is designed to be owned and mutated by exactly one
 * ticker thread (the `HandlerThread` in `:hook`, or the JVM test thread), while other threads
 * only *read* [state], [position] or the synthesis methods. Every mutating call
 * ([tick], [setJoystick], [setPosition], …) must therefore come from that single thread. If a
 * caller needs to publish the state to other threads, it should copy the [EngineState] snapshot,
 * which is an immutable value.
 *
 * ### Route playback (longitudinal model)
 * A loaded [RoutePlan] is not a constant-speed cursor. [advanceRoute] integrates a small vehicle
 * model along the polyline: acceleration at [RoutePlan.ACCELERATION_MPS2] up to the plan's cruise
 * speed, braking at [RoutePlan.DECELERATION_MPS2] into the end of the route and into every
 * scheduled stop, plus the optional standstills described by [RoutePlan.trafficStops] (enabled by
 * [SimulationConfig.routeTrafficStops]).
 *
 * The model has one hard invariant, and everything else in this class is written around it:
 *
 * **The reported speed is the displacement.** [EngineState.speedMps] of a tick is
 * `(distance covered in that tick) / (tick length)` — the secant derivative of the position, not a
 * separately tracked "velocity" that could disagree with the coordinates. A consumer that
 * differentiates consecutive fixes and compares the result with `Location.getSpeed()` therefore
 * sees one number, in every phase: cruising, ramping, standing at a stop, or the tick that reaches
 * the last point. (This is the contradiction mock-provider detectors look for; see also the
 * Ornstein-Uhlenbeck jitter below, which is deliberately *slow* for the same reason.)
 *
 * Limits, stated up front: there is no road graph, no speed limit data and no intersection
 * knowledge. Stops are synthetic points on the polyline (see [RoutePlan.trafficStops]), the speed
 * profile is one constant cruise speed per plan, and lateral acceleration on a curve is not
 * modelled — only the bearing snaps to the segment direction.
 *
 * ### Determinism
 * Every synthesis function is a pure function of the current position (and the config), seeded
 * from the latitude/longitude rounded to four decimals. Two engines at the same position
 * therefore report byte-identical satellites, cells and access points, which is what lets the
 * UI preview exactly what the hook will inject. Route playback is deterministic in the same
 * sense: same plan + same tick deltas => same trajectory, because the stop schedule is seeded from
 * the geometry and the integrator has no clock and no unseeded randomness.
 *
 * @param initialPosition Starting position; ignored when it is not a valid latitude/longitude.
 * @param initialConfig Starting configuration; sanitized before use.
 */
class SimulationEngine(
    initialPosition: GeoPoint = GeoPoint.ZERO,
    initialConfig: SimulationConfig = SimulationConfig(),
) {
    private var basePosition: GeoPoint =
        if (initialPosition.isValidLatLon()) initialPosition else GeoPoint.ZERO

    private var currentConfig: SimulationConfig = initialConfig.sanitized()
    private var runningFlag: Boolean = false
    private var bearingValue: Double = GeoMath.normalizeBearing(currentConfig.fixedBearingDeg)
    private var headingValue: Double = 0.0
    private var speedValue: Double = 0.0
    private var stepsValue: Long = 0L
    private var stepFraction: Double = 0.0
    private var joystickX: Float = 0f
    private var joystickY: Float = 0f
    private var routePlan: RoutePlan? = null
    private var routeProgressValue: Double = 0.0
    private var routeStateValue: RouteState = RouteState.IDLE

    /**
     * Longitudinal state of the route player: distance already covered along the polyline, the
     * instantaneous speed at the end of the last tick, and the remaining standstill at a traffic
     * stop, in meters, m/s and seconds.
     *
     * These three are *not* derivable from [routeProgressValue]: the progress is a position, and
     * the whole point of this model is that the speed at that position depends on how the vehicle
     * got there (accelerating away from a stop vs. braking into one). They are advanced by
     * [advanceRoute] only, i.e. on the single ticker thread.
     */
    private var routeTravelledM: Double = 0.0
    private var routeLongitudinalSpeedMps: Double = 0.0
    private var routeDwellRemainingS: Double = 0.0

    private var tickCounter: Long = 0L
    private var lastTickDelta: Long = 0L
    private var updatedAtMs: Long = 0L
    private var impactCounterValue: Long = 0L

    /**
     * Simulated GPS error, as a correlated (Ornstein-Uhlenbeck) offset in the local east/north/up
     * frame, in meters.
     *
     * Why not "pick a random point within the radius every tick": that makes two consecutive fixes
     * jump by the whole radius, so any app that derives speed from consecutive coordinates computes
     * tens of m/s while the fix claims 1.4 m/s — the classic "mock provider" contradiction. A
     * receiver's error wanders *slowly* instead: it is a filtered random walk (an OU process), so a
     * 1 s step moves the reported position by centimeters to a meter, while reported speed stays
     * independently accurate. The parameters (σ = radius/3, mean reversion 0.05/s) follow the
     * standard three-sigma reading of "accuracy radius".
     *
     * Written only on the ticker thread, read by hook threads: volatile so a reader never observes
     * a half-updated offset.
     */
    @Volatile
    private var jitterEastM: Double = 0.0

    @Volatile
    private var jitterNorthM: Double = 0.0

    @Volatile
    private var jitterUpM: Double = 0.0

    /** Deterministic Gaussian source for [advanceJitter]; seeded from the starting position. */
    private val jitterRandom: SeededRandom = SeededRandom(
        seedFor(
            if (initialPosition.isValidLatLon()) initialPosition else GeoPoint.ZERO,
            SALT_JITTER,
        ),
    )

    /** Clock used for [EngineState.updatedAtMs]; injectable so tests can be fully deterministic. */
    internal var clock: () -> Long = System::currentTimeMillis

    /**
     * Current configuration. Assigning sanitizes the value ([SimulationConfig.sanitized]) and
     * re-seeds the loaded route player.
     *
     * Re-seeding is deliberately *change driven*: the plan's [RoutePlan.speedMps] and
     * [RoutePlan.repeat] are only overwritten when the new configuration actually differs from
     * the previous one in [SimulationConfig.routeSpeedMps] or [SimulationConfig.routeRepeat].
     *
     * The reason is a real on-device defect: the UI re-publishes its whole config (with default
     * `routeSpeedMps = 2.0`) every state refresh. With an unconditional re-seed that repeated
     * `SetConfig` silently retuned a route that had been started at, say, 6.0 m/s, so the
     * simulated movement was a fraction of `RoutePlan.speedMps`. Comparing against the previous
     * config keeps "the user moved the route-speed slider" working while making unrelated
     * config updates harmless.
     *
     * [SimulationConfig.routeTrafficStops] is deliberately *not* a re-seed trigger: the stop
     * schedule is a property of the plan geometry, so flipping the flag only starts or stops
     * serving the stops that already exist, and a running route keeps its progress and its speed.
     */
    var config: SimulationConfig
        get() = currentConfig
        set(value) {
            val sanitized = value.sanitized()
            val previous = currentConfig
            currentConfig = sanitized
            val loaded = routePlan
            if (loaded == null || loaded.points.isEmpty()) return
            val repeatChanged = sanitized.routeRepeat != previous.routeRepeat
            val speedChanged = sanitized.routeSpeedMps != previous.routeSpeedMps
            if (repeatChanged || speedChanged) {
                routePlan = loaded.copy(
                    repeat = if (repeatChanged) sanitized.routeRepeat else loaded.repeat,
                    speedMps = if (speedChanged) sanitized.routeSpeedMps else loaded.speedMps,
                )
            }
        }

    /** Whether ticks advance the simulation. Disabling it freezes the position and speed. */
    var running: Boolean
        get() = runningFlag
        set(value) {
            runningFlag = value
            if (!value) speedValue = 0.0
        }

    /**
     * The spoofed position, including GPS jitter when configured.
     *
     * The returned value is what the hook injects; internal movement math always uses the
     * jitter-free base position so jitter cannot accumulate into drift.
     */
    val position: GeoPoint
        get() = jitteredPosition()

    /** Immutable snapshot of the whole engine, ready to be serialized over the bridge. */
    val state: EngineState
        get() = EngineState(
            running = runningFlag,
            mode = currentConfig.mode,
            routeState = routeStateValue,
            point = jitteredPosition(),
            speedMps = speedValue,
            bearingDeg = bearingValue,
            steps = stepsValue,
            headingDeg = headingValue,
            routeProgress = routeProgressValue.coerceIn(0.0, 1.0),
            updatedAtMs = updatedAtMs,
            activePackages = if (currentConfig.independentMode) {
                currentConfig.independentPackages.sorted()
            } else {
                emptyList()
            },
            engineVersion = CORE_ENGINE_VERSION,
        )

    /** Number of teleports performed with `emitImpact = true`; lets the hook retrigger listeners. */
    val impactCounter: Long
        get() = impactCounterValue

    /** Current ground speed in meters/second. */
    val currentSpeedMps: Double
        get() = speedValue

    /** Current route playback state. */
    val currentRouteState: RouteState
        get() = routeStateValue

    /**
     * Number of [tick] calls applied so far.
     *
     * Intended for on-device diagnostics: logging this counter together with
     * [lastTickDeltaMs] once per second shows the real ticker frequency, which is the first
     * thing to check when the observed ground speed is a multiple of the configured one.
     */
    val appliedTickCount: Long
        get() = tickCounter

    /** Delta of the most recent [tick] call, in milliseconds (after clamping). */
    val lastTickDeltaMs: Long
        get() = lastTickDelta

    /** Points of the loaded route, or an empty list when no route is loaded. */
    val routePoints: List<GeoPoint>
        get() = routePlan?.points ?: emptyList()

    /**
     * Cruise ground speed the loaded route is played at, in meters/second.
     *
     * This is the plan's own [RoutePlan.speedMps] (the authoritative value), falling back to
     * [SimulationConfig.routeSpeedMps] when no route is loaded. It is *not* derived from
     * [SimulationConfig.speed], so a WALK/RUN/… level never slows a route down. It is the speed the
     * vehicle *cruises* at, not the instantaneous speed: ramps and traffic stops make
     * [currentSpeedMps] differ from it by design.
     */
    val activeRouteSpeedMps: Double
        get() = routePlan?.speedMps ?: currentConfig.routeSpeedMps

    /**
     * Teleports the simulation.
     *
     * @param point Target position; ignored when the latitude/longitude is out of range.
     * @param emitImpact When `true`, increments [impactCounter] so the hook can retrigger
     *   location listeners immediately instead of waiting for the next tick.
     */
    fun setPosition(point: GeoPoint, emitImpact: Boolean = true) {
        if (!point.isValidLatLon()) return
        basePosition = point
        speedValue = 0.0
        if (emitImpact) impactCounterValue++
    }

    /**
     * Sets the virtual joystick vector.
     *
     * @param x Horizontal axis: `+1` right, `-1` left; clamped into `-1..1`.
     * @param y Vertical axis: `+1` forward, `-1` backward; clamped into `-1..1`.
     *
     * The vector magnitude scales the configured speed (a half-pushed stick moves at half
     * speed), applied **once per [tick]**: calling this method repeatedly with the same vector
     * is idempotent and never accumulates speed or distance.
     *
     * With [SimulationConfig.followHeading] the direction is relative to the device heading,
     * otherwise it is relative to [SimulationConfig.fixedBearingDeg]. A non-zero vector while in
     * [MoveMode.STATIC] switches the engine to [MoveMode.JOYSTICK]; a loaded route keeps
     * [MoveMode.ROUTE], so steering the stick can never silently hijack a running route.
     */
    fun setJoystick(x: Float, y: Float) {
        joystickX = sanitizeAxis(x)
        joystickY = sanitizeAxis(y)
        if (currentConfig.mode == MoveMode.STATIC && joystickMagnitude() > 0.0) {
            currentConfig = currentConfig.copy(mode = MoveMode.JOYSTICK)
        }
    }

    /** Centres the joystick: the simulated position stops moving. */
    fun releaseJoystick() {
        joystickX = 0f
        joystickY = 0f
    }

    /**
     * Sets the device heading (rotation-vector azimuth) used by [SimulationConfig.followHeading].
     *
     * @param deg Heading in degrees clockwise from north; normalized into `[0,360)`.
     */
    fun setHeading(deg: Double) {
        headingValue = GeoMath.normalizeBearing(deg)
    }

    /**
     * Loads [points] as a route and immediately starts playing it.
     *
     * The engine switches to [MoveMode.ROUTE] and starts running, because a route that does not
     * advance would look like a frozen position to the hooked app. A plan with fewer than two
     * distinct points unloads the player instead.
     *
     * The plan keeps the speed given here; [SimulationConfig.routeSpeedMps] is only the default
     * for callers that omit [speedMps], so `config.speed` (WALK/RUN/…) never influences route
     * playback. The plan is authoritative until the caller actually *changes*
     * [SimulationConfig.routeSpeedMps] (see [config]).
     *
     * The vehicle starts **from rest**: the first ticks accelerate at [RoutePlan.ACCELERATION_MPS2]
     * until the plan speed is reached, so the reported speed and the coordinate delta both say
     * "1.5 m/s" on the first second of a 5 m/s route instead of the coordinate jumping to 5 m/s.
     * The distance lost to that ramp (`v² / 2a` = 4.2 m for 5 m/s) is the only difference from the
     * old constant-speed glide, and it is the honest way to start a drive.
     *
     * @param repeat Whether playback restarts after the last point.
     * @param speedMps Cruise ground speed; non-finite or non-positive values fall back to the
     *   configured [SimulationConfig.routeSpeedMps], and the result is clamped to `0.1..1000`.
     */
    fun startRoute(
        points: List<GeoPoint>,
        repeat: Boolean = config.routeRepeat,
        speedMps: Double = config.routeSpeedMps,
    ) {
        val speed = if (speedMps.isFinite() && speedMps > 0.0) {
            speedMps.coerceIn(
                SimulationConfig.MIN_CUSTOM_SPEED_MPS,
                SimulationConfig.MAX_CUSTOM_SPEED_MPS,
            )
        } else {
            currentConfig.routeSpeedMps
        }
        val plan = RoutePlan(points = points, repeat = repeat, speedMps = speed).normalized()
        if (plan.points.size < 2 || plan.totalDistanceMeters() <= 0.0) {
            routePlan = null
            routeProgressValue = 0.0
            routeStateValue = RouteState.IDLE
            resetRouteMotion()
            speedValue = 0.0
            return
        }
        routePlan = plan
        routeProgressValue = 0.0
        routeStateValue = RouteState.PLAYING
        resetRouteMotion()
        speedValue = 0.0
        runningFlag = true
        // Only the mode is published into the config: writing the plan's speed/repeat back would
        // make a later stale SetConfig look like a user change and retune the running route.
        currentConfig = currentConfig.copy(mode = MoveMode.ROUTE)
        applyRouteSample(plan, 0.0)
    }

    /**
     * Resumes a paused (or finished-with-repeat) route.
     *
     * Resuming assumes the vehicle is at rest: [pauseRoute] already dropped the reported speed to
     * zero, so the longitudinal model restarts from zero too and accelerates again. That keeps the
     * "speed equals the coordinate derivative, and it never changes faster than 3 m/s²" contract
     * true across the pause as well — a resume that instantly reported the old cruise speed would
     * contradict the position, which did not move while paused.
     */
    fun playRoute() {
        val plan = routePlan ?: return
        if (plan.points.size < 2) return
        routeStateValue = RouteState.PLAYING
        runningFlag = true
        routeLongitudinalSpeedMps = 0.0
    }

    /**
     * Pauses route playback, keeping the current progress.
     *
     * A pause is a vehicle coming to rest: the reported speed becomes zero, the longitudinal speed
     * is dropped, and a traffic-stop standstill that is in progress keeps its remaining wait (the
     * light does not turn green because the simulation was paused).
     */
    fun pauseRoute() {
        if (routeStateValue == RouteState.PLAYING) routeStateValue = RouteState.PAUSED
        speedValue = 0.0
        routeLongitudinalSpeedMps = 0.0
    }

    /**
     * Unloads the route and falls back to static movement.
     *
     * The joystick vector is centred as well: unloading a route must not leave a stale stick
     * deflection behind that would immediately start moving the position again.
     */
    fun stopRoute() {
        routePlan = null
        routeProgressValue = 0.0
        routeStateValue = RouteState.IDLE
        resetRouteMotion()
        speedValue = 0.0
        releaseJoystick()
        if (currentConfig.mode == MoveMode.ROUTE) {
            currentConfig = currentConfig.copy(mode = MoveMode.STATIC)
        }
    }

    /**
     * Overrides the step counter.
     *
     * @param steps New absolute value; negative values are raised to `0`.
     */
    fun resetSteps(steps: Long = 0L) {
        stepsValue = if (steps > 0L) steps else 0L
        stepFraction = 0.0
    }

    /**
     * Advances the simulation by [deltaMs] of wall clock and returns the new snapshot.
     *
     * Values above [MAX_TICK_DELTA_MS] are clamped, so a ticker that was suspended (screen off,
     * GC pause) cannot teleport the position by kilometers. Non-positive deltas only refresh
     * the timestamp.
     *
     * ### Exactly one distance application per call
     * The distance travelled is decided **once** per call and applied to the unjittered base
     * position: joystick movement uses `speed * deltaMs / 1000`, route playback integrates the
     * longitudinal profile over the same delta (in sub-steps, but in one pass — no state is carried
     * from one call to the next). The engine keeps no notion of "a second" of its own, so deltas
     * are additive: `tick(500) + tick(500)` moves as far as `tick(1000)` (exactly for the joystick,
     * to within sub-step discretisation — millimetres — for a route), and two `tick(1000)` calls
     * inside the same wall-clock second legitimately move twice as far as one. The caller (the
     * ticker) owns the cadence and must call this method exactly once per
     * [SimulationConfig.updateIntervalMs]; a duplicated ticker start therefore doubles the observed
     * ground speed even though this method is correct.
     *
     * @return the engine state after the tick.
     */
    fun tick(deltaMs: Long): EngineState {
        val delta = deltaMs.coerceIn(0L, MAX_TICK_DELTA_MS)
        tickCounter++
        lastTickDelta = delta
        updatedAtMs = clock()
        if (!runningFlag || delta == 0L) {
            speedValue = 0.0
            return state
        }
        when (resolvedMode()) {            MoveMode.ROUTE -> advanceRoute(delta)
            MoveMode.JOYSTICK -> advanceJoystick(delta)
            MoveMode.STATIC -> speedValue = 0.0
        }
        // The simulated receiver error keeps wandering while the engine runs, including when the
        // position is parked: a stationary phone still reports a slightly different coordinate every
        // second, and the wander is what makes that difference *small* instead of a jump.
        advanceJitter(delta)
        if (currentConfig.stepSimulation) {
            accumulateSteps(delta)
        } else {
            stepFraction = 0.0
        }
        return state
    }

    /**
     * Deterministic GNSS constellation for the current position **and the current epoch**.
     *
     * The visible set comes from a coarse sky anchor (~55 km) and each satellite follows its own
     * slow elevation/azimuth track, with C/N0 riding on the elevation plus a slow wander — so the
     * constellation is stable while you walk, physically continuous from one second to the next, and
     * still different in a different city. The result is a pure function of `(position, epoch)`,
     * where the epoch is the last tick's timestamp (not "now"), which is what keeps the GNSS status
     * and the NMEA stream of one epoch describing the *same* sky.
     *
     * Whether these values are injected at all is decided by the caller through
     * [SimulationConfig.gpsSignalSimulation].
     */
    fun satellites(): List<Satellite> = SatelliteSynth.synthesize(basePosition, skyEpochMs())

    /**
     * The epoch every synthesizer describes: the timestamp of the last [tick].
     *
     * Using a stored epoch instead of `clock()` at each call site matters: `buildSnap` asks for the
     * satellites and then for the NMEA lines, and if each call read the clock the GNSS status and the
     * GSV sentences of one published snapshot would describe skies microseconds apart. Before the
     * first tick the engine falls back to the wall clock so a preview still has a valid epoch.
     */
    private fun skyEpochMs(): Long = if (updatedAtMs > 0L) updatedAtMs else clock()

    /**
     * NMEA 0183 sentences describing the current state: fix data, recommended minimum, per-talker
     * GSA/VTG and the multi-constellation GSV stream.
     *
     * The coordinates are the *jittered* ones — the same value the hook puts into `Location` — so
     * `$GPGGA` and `getLatitude()/getLongitude()` of one epoch cannot disagree by the jitter radius.
     * The sky comes from [satellites], which uses the same stored epoch as [accessPoints].
     *
     * @param nowMs Wall clock in epoch milliseconds used for the UTC time/date fields.
     */
    fun nmea(nowMs: Long): List<String> = NmeaSynth.build(
        position = position,
        satellites = satellites(),
        speedMps = speedValue,
        bearingDeg = bearingValue,
        nowMs = nowMs,
    )

    /**
     * Height of the geoid above the WGS-84 ellipsoid at [point], in meters.
     *
     * A real GNSS fix carries **two** heights: `Location.getAltitude()` is the ellipsoid height and
     * (on Android 14+) `getMslAltitudeMeters()` is the height above mean sea level. They differ by
     * this geoid separation, which is a property of the place — roughly +1.5 m in Beijing, -20 m in
     * New York, +22 m in the Gulf of Guinea. The hook layer uses it to populate the MSL fields so a
     * device that normally reports both heights does not suddenly report only one.
     */
    fun geoidSeparationMeters(point: GeoPoint = basePosition): Double =
        NmeaSynth.geoidSeparationMeters(point)

    /**
     * Deterministic serving cell plus 1..3 neighbours around the current position.
     *
     * Gating on [SimulationConfig.cellSimulation] is the caller's responsibility.
     */
    fun cells(): List<SimulatedCell> = CellSynth.synthesize(basePosition)

    /**
     * Deterministic Wi-Fi scan result around the current position, from the access-point field.
     *
     * Same `(position, epoch)` contract as [satellites]: the access points around you persist while
     * you walk, RSSI changes with distance and fades slowly in time.
     *
     * Gating on [SimulationConfig.wifiSimulation] is the caller's responsibility.
     */
    fun accessPoints(): List<SimulatedAp> = WifiSynth.synthesize(basePosition, skyEpochMs())

    /**
     * Number of steps that [deltaMs] at the configured cadence produces.
     *
     * This is a pure prediction: it does not mutate the counter (the ticker owns that). Steps are
     * produced while [SimulationConfig.stepSimulation] is enabled and the engine is
     * [running] — independent of the movement mode and of the ground speed, because 步频模拟 is a
     * cadence (`stepCadenceBpm`), not a motion read-out: a user who turns the toggle on while the
     * engine sits in [MoveMode.STATIC] expects the counter to climb at the configured rate.
     */
    fun stepDelta(deltaMs: Long): Int {
        if (deltaMs <= 0L || !runningFlag || !currentConfig.stepSimulation) return 0
        val steps = currentConfig.stepCadenceBpm / 60.0 * deltaMs / 1_000.0
        return if (steps > 0.0) steps.toInt() else 0
    }

    /** Effective movement mode for the current tick, taking route/joystick state into account. */
    private fun resolvedMode(): MoveMode = when {
        currentConfig.mode == MoveMode.ROUTE && routePlan != null -> MoveMode.ROUTE
        currentConfig.mode == MoveMode.JOYSTICK -> MoveMode.JOYSTICK
        joystickMagnitude() > 0.0 -> MoveMode.JOYSTICK
        else -> MoveMode.STATIC
    }

    private fun joystickMagnitude(): Double {
        val magnitude = hypot(joystickX.toDouble(), joystickY.toDouble())
        return if (magnitude <= JOYSTICK_DEAD_ZONE) 0.0 else magnitude.coerceAtMost(1.0)
    }

    private fun advanceJoystick(deltaMs: Long) {
        val magnitude = joystickMagnitude()
        if (magnitude <= 0.0) {
            speedValue = 0.0
            return
        }
        // atan2(x, y): 0° = forward, +90° = right, independent of the joystick magnitude.
        val vectorBearing = Math.toDegrees(atan2(joystickX.toDouble(), joystickY.toDouble()))
        val base = if (currentConfig.followHeading) {
            headingValue
        } else {
            currentConfig.fixedBearingDeg
        }
        bearingValue = smoothBearing(bearingValue, base + vectorBearing, deltaMs)
        speedValue = currentConfig.effectiveSpeedMps() * magnitude
        val distance = speedValue * deltaMs / 1_000.0
        basePosition = GeoMath.destination(basePosition, bearingValue, distance)
    }

    /**
     * Advances the route player by one tick.
     *
     * The tick is integrated in sub-steps of [ROUTE_SUBSTEP_SECONDS] so the acceleration-limited
     * profile — including the braking curve into a stop, `v = sqrt(2·a·remaining)` — is followed
     * *inside* one tick. A single 1 s Euler step cannot do that: it would arrive at the stop with
     * several m/s left and then have to clamp the position, reporting a speed drop far steeper than
     * any car can brake.
     *
     * Per tick the player decides the speed for the whole tick and moves at it, and
     * [EngineState.speedMps] is set to `distance covered / tick length` — the exact secant
     * derivative of the position, which is what makes the reported speed and the coordinates tell
     * the same story. Consequences worth naming:
     *
     *  * the speed sequence of an accelerating drive is non-decreasing by at most
     *    `ACCELERATION_MPS2 * tickSeconds` per tick (interval averages of a Lipschitz profile), so
     *    a 1 s ticker never shows the speed jumping;
     *  * standing at a traffic stop reports exactly `0.0` and the position is bit-identical from
     *    one tick to the next;
     *  * the tick that reaches the last point reports the (small) speed it actually travelled at;
     *    every later tick sees [RouteState.FINISHED] and reports zero.
     */
    private fun advanceRoute(deltaMs: Long) {
        val plan = routePlan
        if (plan == null || routeStateValue != RouteState.PLAYING) {
            speedValue = 0.0
            return
        }
        val total = plan.totalDistanceMeters()
        if (total <= 0.0) {
            routeProgressValue = 1.0
            routeStateValue = RouteState.FINISHED
            speedValue = 0.0
            routeLongitudinalSpeedMps = 0.0
            return
        }
        val dt = deltaMs / 1_000.0
        val cruiseSpeed = plan.speedMps.coerceIn(
            SimulationConfig.MIN_CUSTOM_SPEED_MPS,
            SimulationConfig.MAX_CUSTOM_SPEED_MPS,
        )
        // The schedule is a property of the plan (deterministic); the flag is what switches it on.
        // Reading it per tick means the user can turn stops on/off mid-route without the plan
        // being retuned — the stop *positions* never change with the flag, only whether they are
        // served.
        val stops = if (currentConfig.routeTrafficStops) plan.trafficStops else emptyList()
        val repeat = plan.repeat

        var travelled = routeTravelledM
        if (!travelled.isFinite()) travelled = 0.0
        travelled = travelled.coerceIn(0.0, total)
        var speed = if (routeLongitudinalSpeedMps.isFinite()) {
            routeLongitudinalSpeedMps.coerceAtLeast(0.0)
        } else {
            0.0
        }
        var dwell = if (routeDwellRemainingS.isFinite()) {
            routeDwellRemainingS.coerceAtLeast(0.0)
        } else {
            0.0
        }
        var moved = 0.0
        var budget = dt
        var finished = false
        var segments = 0

        while (budget > TIME_EPSILON_SECONDS && segments++ < MAX_ROUTE_SEGMENTS_PER_TICK) {
            if (dwell > 0.0) {
                // Standing at a stop: no distance at all, so the reported speed is a hard zero.
                val used = min(dwell, budget)
                dwell -= used
                budget -= used
                if (dwell <= TIME_EPSILON_SECONDS) dwell = 0.0
                continue
            }
            val nextStop = stops.firstOrNull {
                it.distanceMeters > travelled + ARRIVAL_EPSILON_METERS
            }
            val eventAt = nextStop?.distanceMeters
                ?: if (repeat) Double.POSITIVE_INFINITY else total
            val remaining = eventAt - travelled
            if (remaining <= ARRIVAL_EPSILON_METERS) {
                if (nextStop != null) {
                    dwell = nextStop.waitSeconds
                    continue
                }
                // Standing exactly on the last point of a non-repeating route.
                finished = true
                break
            }
            val stepSeconds = min(ROUTE_SUBSTEP_SECONDS, budget)
            // Slowest speed from which the vehicle can still stop inside `remaining` meters.
            val stopLimit = sqrt(2.0 * RoutePlan.DECELERATION_MPS2 * remaining)
            val target = min(cruiseSpeed, stopLimit)
            val nextSpeed = if (speed < target) {
                min(speed + RoutePlan.ACCELERATION_MPS2 * stepSeconds, target)
            } else {
                max(speed - RoutePlan.DECELERATION_MPS2 * stepSeconds, target)
            }
            val averageSpeed = (speed + nextSpeed) * 0.5
            val stepDistance = averageSpeed * stepSeconds
            if (stepDistance >= remaining) {
                // Arrive exactly on the event point instead of overshooting it. Time is charged for
                // the distance actually covered, so the leftover of this tick goes to the stop.
                val used = if (averageSpeed > 0.0) remaining / averageSpeed else stepSeconds
                moved += remaining
                travelled = eventAt
                budget -= used
                speed = 0.0
                if (nextStop != null) {
                    dwell = nextStop.waitSeconds
                    continue
                }
                finished = true
                break
            }
            moved += stepDistance
            travelled += stepDistance
            budget -= stepSeconds
            speed = nextSpeed
            if (repeat && travelled >= total) {
                // The loop seam is not an event: a repeating route is a circuit, so the position
                // wraps at cruise speed instead of braking to a standstill that the geometry does
                // not contain. `%` (not `-`) because one sub-step can span more than a lap when a
                // fast plan is played on a very short polyline.
                travelled %= total
            }
        }

        routeTravelledM = if (finished) total else travelled.coerceIn(0.0, total)
        routeLongitudinalSpeedMps = if (finished) 0.0 else speed
        routeDwellRemainingS = if (finished) 0.0 else dwell
        if (finished) {
            routeProgressValue = 1.0
            routeStateValue = RouteState.FINISHED
        } else {
            routeProgressValue = (routeTravelledM / total).coerceIn(0.0, 1.0)
        }
        speedValue = if (dt > 0.0) moved / dt else 0.0
        applyRouteSample(plan, routeProgressValue)
    }

    /** Puts the longitudinal model at rest, without touching the loaded plan. */
    private fun resetRouteMotion() {
        routeTravelledM = 0.0
        routeLongitudinalSpeedMps = 0.0
        routeDwellRemainingS = 0.0
    }

    private fun applyRouteSample(plan: RoutePlan, progress: Double) {
        val (point, bearing) = plan.sample(progress)
        basePosition = point
        bearingValue = GeoMath.normalizeBearing(bearing)
    }

    private fun smoothBearing(current: Double, target: Double, deltaMs: Long): Double {
        val maxTurn = MAX_TURN_RATE_DEG_PER_SEC * deltaMs / 1_000.0
        val delta = GeoMath.shortestAngleDelta(current, target)
        val applied = if (delta > maxTurn) {
            maxTurn
        } else if (delta < -maxTurn) {
            -maxTurn
        } else {
            delta
        }
        return GeoMath.normalizeBearing(current + applied)
    }

    /**
     * Advances the step counter for [deltaMs] of simulated time.
     *
     * Cadence-driven, not motion-driven: `stepCadenceBpm / 60 * deltaMs` steps are accumulated
     * whenever the ticker runs with [SimulationConfig.stepSimulation] enabled, in every
     * [MoveMode] and at every speed. Fractional steps carry over between ticks, so a 500 ms tick
     * at 120 bpm adds 1 step every other tick instead of rounding to 0 (or to 2) every time.
     *
     * The engine being stopped is handled by [tick], which returns before this is called: a
     * stopped simulation must not keep counting steps (that would make the counter rise while the
     * user sees "stopped" everywhere else).
     */
    private fun accumulateSteps(deltaMs: Long) {
        val stepsPerSecond = currentConfig.stepCadenceBpm / 60.0
        stepFraction += stepsPerSecond * deltaMs / 1_000.0
        val whole = stepFraction.toLong()
        if (whole > 0L) {
            stepsValue += whole
            stepFraction -= whole.toDouble()
        }
    }

    private fun jitteredPosition(): GeoPoint {
        val gpsRadius = currentConfig.gpsJitterMeters
        val altitudeRadius = currentConfig.altitudeJitterMeters
        if (gpsRadius <= 0.0 && altitudeRadius <= 0.0) return basePosition
        val east = if (gpsRadius > 0.0) jitterEastM else 0.0
        val north = if (gpsRadius > 0.0) jitterNorthM else 0.0
        val up = if (altitudeRadius > 0.0) jitterUpM else 0.0
        if (east == 0.0 && north == 0.0 && up == 0.0) return basePosition
        val horizontal = GeoMath.offsetMeters(basePosition, east, north)
        return if (up == 0.0) horizontal else horizontal.copy(alt = basePosition.alt + up)
    }

    /**
     * Advances the simulated receiver error by [deltaMs] of simulated time.
     *
     * Discrete Ornstein-Uhlenbeck step per axis: `x += σ·√dt·N(0,1) − α·x·dt`, with `σ` set so that
     * three sigma equals the configured radius (the reading every Android location setting implies)
     * and `α = 0.05 /s` as the mean reversion, then clamped to the radius. Properties this buys:
     *
     * * consecutive fixes differ by centimeters to a meter, not by the whole radius, so a consumer
     *   that differentiates consecutive coordinates does not compute a 30 m/s "speed" from a 1.4 m/s
     *   fix (that contradiction is the cheapest mock-provider tell there is);
     * * the error is stationary — it does not drift away over a long walk;
     * * the sequence is deterministic in the tick history, because the Gaussian source is a seeded
     *   PRNG owned by this engine instance.
     *
     * A zero radius pins the corresponding axes to zero, so disabling jitter restores an exact
     * position immediately instead of leaving the last offset behind.
     */
    private fun advanceJitter(deltaMs: Long) {
        val gpsRadius = currentConfig.gpsJitterMeters
        val altitudeRadius = currentConfig.altitudeJitterMeters
        if (gpsRadius <= 0.0) {
            jitterEastM = 0.0
            jitterNorthM = 0.0
        }
        if (altitudeRadius <= 0.0) jitterUpM = 0.0
        val dt = (deltaMs / 1_000.0).coerceIn(MIN_JITTER_STEP_S, MAX_TICK_DELTA_MS / 1_000.0)
        val decay = JITTER_MEAN_REVERSION_PER_S * dt
        // Discretisation of an OU process with stationary standard deviation `radius/3`:
        // Var(x∞) = σ²/(2α), so the per-step diffusion coefficient is σ_step = σ_stat·√(2α).
        // Using σ_stat directly (the naive reading) would make one 1 s step as large as the whole
        // envelope, which is the very defect this model removes.
        val stepFraction = JITTER_SIGMA_FRACTION * sqrt(2.0 * JITTER_MEAN_REVERSION_PER_S)
        if (gpsRadius > 0.0) {
            val sigma = gpsRadius * stepFraction
            jitterEastM += sigma * sqrt(dt) * nextGaussian() - decay * jitterEastM
            jitterNorthM += sigma * sqrt(dt) * nextGaussian() - decay * jitterNorthM
            val magnitude = hypot(jitterEastM, jitterNorthM)
            if (magnitude > gpsRadius) {
                val scale = gpsRadius / magnitude
                jitterEastM *= scale
                jitterNorthM *= scale
            }
        }
        if (altitudeRadius > 0.0) {
            val sigma = altitudeRadius * stepFraction
            jitterUpM += sigma * sqrt(dt) * nextGaussian() - decay * jitterUpM
            jitterUpM = jitterUpM.coerceIn(-altitudeRadius, altitudeRadius)
        }
    }

    /** Standard normal sample (Box-Muller) from the deterministic per-engine source. */
    private fun nextGaussian(): Double {
        val u1 = jitterRandom.nextDouble().coerceAtLeast(1e-12)
        val u2 = jitterRandom.nextDouble()
        return sqrt(-2.0 * kotlin.math.ln(u1)) * kotlin.math.cos(2.0 * Math.PI * u2)
    }

    private fun sanitizeAxis(value: Float): Float = when {
        value.isNaN() -> 0f
        value > 1f -> 1f
        value < -1f -> -1f
        else -> value
    }

    companion object {
        /**
         * Longest delta a single [tick] will integrate, in milliseconds.
         *
         * Longer deltas are clamped so a suspended ticker cannot teleport the position.
         */
        const val MAX_TICK_DELTA_MS: Long = 5_000L

        /** Maximum bearing change per second, in degrees; keeps heading turns smooth. */
        const val MAX_TURN_RATE_DEG_PER_SEC: Double = 360.0

        /**
         * Integration step of the route's longitudinal model, in seconds.
         *
         * 50 ms is short enough that the braking curve into a stop is followed to within a
         * centimetre per step at city speeds, and long enough that a 5 s (clamped) tick costs only
         * 100 steps. It is *not* a tick: the engine still applies exactly one distance per [tick].
         */
        const val ROUTE_SUBSTEP_SECONDS: Double = 0.05

        /** Distance from a scheduled event at which the vehicle is considered to have arrived, in meters. */
        const val ARRIVAL_EPSILON_METERS: Double = 1e-6

        /** Time quantum below which a tick is considered exhausted, in seconds. */
        const val TIME_EPSILON_SECONDS: Double = 1e-9

        /** Hard cap on the segments one route tick may integrate (dwell phases + sub-steps). */
        const val MAX_ROUTE_SEGMENTS_PER_TICK: Int = 512

        /** Joystick magnitudes below this are treated as "not moving". */
        const val JOYSTICK_DEAD_ZONE: Double = 0.02

        /**
         * Fraction of the configured jitter radius that equals one standard deviation.
         *
         * "Accuracy radius" in the Android UI is read as a three-sigma bound, so a 5 m setting
         * means the receiver error is N(0, (5/3)²) per axis.
         */
        const val JITTER_SIGMA_FRACTION: Double = 1.0 / 3.0

        /** Mean reversion of the simulated GPS error, per second (Ornstein-Uhlenbeck `α`). */
        const val JITTER_MEAN_REVERSION_PER_S: Double = 0.05

        /** Shortest integration step for the jitter process, in seconds. */
        const val MIN_JITTER_STEP_S: Double = 0.01

        /**
         * Ground speed that used to gate the step counter, in meters/second.
         *
         * **No longer consulted.** 步频模拟 is cadence-driven (see [accumulateSteps]): the counter
         * advances whenever the engine runs with `stepSimulation = true`, in every movement mode.
         * Kept as a named constant because the on-device acceptance harness and older call sites
         * still refer to the value when they describe "walking speed".
         */
        const val STEP_MIN_SPEED_MPS: Double = 0.5
    }
}
