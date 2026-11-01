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
 * ### Determinism
 * Every synthesis function is a pure function of the current position (and the config), seeded
 * from the latitude/longitude rounded to four decimals. Two engines at the same position
 * therefore report byte-identical satellites, cells and access points, which is what lets the
 * UI preview exactly what the hook will inject.
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
    private var tickCounter: Long = 0L
    private var lastTickDelta: Long = 0L
    private var jitterEpoch: Long = 0L
    private var updatedAtMs: Long = 0L
    private var impactCounterValue: Long = 0L

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
     * Ground speed the loaded route is actually played at, in meters/second.
     *
     * This is the plan's own [RoutePlan.speedMps] (the authoritative value), falling back to
     * [SimulationConfig.routeSpeedMps] when no route is loaded. It is *not* derived from
     * [SimulationConfig.speed], so a WALK/RUN/… level never slows a route down.
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
     * @param repeat Whether playback restarts after the last point.
     * @param speedMps Route ground speed; non-finite or non-positive values fall back to the
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
            speedValue = 0.0
            return
        }
        routePlan = plan
        routeProgressValue = 0.0
        routeStateValue = RouteState.PLAYING
        runningFlag = true
        // Only the mode is published into the config: writing the plan's speed/repeat back would
        // make a later stale SetConfig look like a user change and retune the running route.
        currentConfig = currentConfig.copy(mode = MoveMode.ROUTE)
        applyRouteSample(plan, 0.0)
    }

    /** Resumes a paused (or finished-with-repeat) route. */
    fun playRoute() {
        val plan = routePlan ?: return
        if (plan.points.size < 2) return
        routeStateValue = RouteState.PLAYING
        runningFlag = true
    }

    /** Pauses route playback, keeping the current progress. */
    fun pauseRoute() {
        if (routeStateValue == RouteState.PLAYING) routeStateValue = RouteState.PAUSED
        speedValue = 0.0
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
     * The distance travelled is `speed * deltaMs / 1000`, computed **once** per call and applied
     * to the unjittered base position. The engine keeps no notion of "a second" of its own, so
     * deltas are strictly additive: `tick(500) + tick(500)` moves exactly as far as
     * `tick(1000)`, and two `tick(1000)` calls inside the same wall-clock second legitimately
     * move twice as far as one. The caller (the ticker) owns the cadence and must call this
     * method exactly once per [SimulationConfig.updateIntervalMs]; a duplicated ticker start
     * therefore doubles the observed ground speed even though this method is correct.
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
        val positionBefore = basePosition
        when (resolvedMode()) {
            MoveMode.ROUTE -> advanceRoute(delta)
            MoveMode.JOYSTICK -> advanceJoystick(delta)
            MoveMode.STATIC -> speedValue = 0.0
        }
        // Jitter re-seeds only when the engine actually moved. A stationary or paused engine
        // therefore reports one stable position instead of wandering inside the jitter radius,
        // which used to look like "the route drifts while paused" on device.
        if (basePosition != positionBefore) jitterEpoch++
        if (currentConfig.stepSimulation) {
            accumulateSteps(delta)
        } else {
            stepFraction = 0.0
        }
        return state
    }

    /**
     * Deterministic GNSS constellation for the current position.
     *
     * 8..14 satellites spread over GPS/GLONASS/GALILEO/BEIDOU, C/N0 in 20..45 dB-Hz and 7..9 of
     * them flagged as used in the fix. The result is a pure function of the position, so the UI
     * and the hook agree. Whether these values are injected at all is decided by the caller
     * through [SimulationConfig.gpsSignalSimulation].
     */
    fun satellites(): List<Satellite> = SatelliteSynth.synthesize(basePosition)

    /**
     * NMEA 0183 sentences (`$GPGGA`, `$GPRMC`, `$GPGSV…`) describing the current state.
     *
     * @param nowMs Wall clock in epoch milliseconds used for the UTC time/date fields.
     */
    fun nmea(nowMs: Long): List<String> = NmeaSynth.build(
        position = basePosition,
        satellites = satellites(),
        speedMps = speedValue,
        bearingDeg = bearingValue,
        nowMs = nowMs,
    )

    /**
     * Deterministic serving cell plus 1..3 neighbours around the current position.
     *
     * Gating on [SimulationConfig.cellSimulation] is the caller's responsibility.
     */
    fun cells(): List<SimulatedCell> = CellSynth.synthesize(basePosition)

    /**
     * Deterministic Wi-Fi scan result (4..8 access points) around the current position.
     *
     * Gating on [SimulationConfig.wifiSimulation] is the caller's responsibility.
     */
    fun accessPoints(): List<SimulatedAp> = WifiSynth.synthesize(basePosition)

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
            return
        }
        val speed = plan.speedMps.coerceIn(
            SimulationConfig.MIN_CUSTOM_SPEED_MPS,
            SimulationConfig.MAX_CUSTOM_SPEED_MPS,
        )
        val deltaMeters = speed * deltaMs / 1_000.0
        val travelled = routeProgressValue * total + deltaMeters
        if (travelled >= total) {
            if (plan.repeat) {
                val wrapped = travelled % total
                routeProgressValue = (wrapped / total).coerceIn(0.0, 1.0)
                speedValue = speed
            } else {
                routeProgressValue = 1.0
                routeStateValue = RouteState.FINISHED
                speedValue = 0.0
            }
        } else {
            routeProgressValue = (travelled / total).coerceIn(0.0, 1.0)
            speedValue = speed
        }
        applyRouteSample(plan, routeProgressValue)
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
        val gpsJitter = currentConfig.gpsJitterMeters
        val altitudeJitter = currentConfig.altitudeJitterMeters
        if (gpsJitter <= 0.0 && altitudeJitter <= 0.0) return basePosition
        val seed = seedFor(basePosition, SALT_JITTER) xor (jitterEpoch * -0x61c8864680b583ebL)
        val random = SeededRandom(seed)
        var point = basePosition
        if (gpsJitter > 0.0) {
            point = GeoMath.destination(
                point,
                random.nextDouble() * 360.0,
                random.nextDouble() * gpsJitter,
            )
        }
        if (altitudeJitter > 0.0) {
            point = point.copy(alt = basePosition.alt + (random.nextDouble() * 2.0 - 1.0) * altitudeJitter)
        }
        return point
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

        /** Joystick magnitudes below this are treated as "not moving". */
        const val JOYSTICK_DEAD_ZONE: Double = 0.02

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
