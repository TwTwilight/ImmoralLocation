package com.immoral.location.hook

import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import com.immoral.location.core.engine.AccuracySynth
import com.immoral.location.core.engine.SimulationEngine
import com.immoral.location.core.model.EngineState
import com.immoral.location.core.model.GeoPoint
import com.immoral.location.core.model.RouteState
import com.immoral.location.core.model.Satellite
import com.immoral.location.core.model.SimulatedAp
import com.immoral.location.core.model.SimulatedCell
import com.immoral.location.core.model.SimulationConfig
import com.immoral.location.core.route.RoutePlan
import com.immoral.location.ipc.WireDiagnostics
import com.immoral.location.ipc.WireResult
import com.immoral.location.ipc.WireState
import com.immoral.location.ipc.toWire
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The engine host that lives **inside `system_server`**.
 *
 * Owns a [SimulationEngine] plus a dedicated [HandlerThread]; every mutating engine call (ticks
 * *and* commands) runs on that single thread because [SimulationEngine] is not thread-safe.
 * Hook code only ever reads the immutable [Snap] published here, which is a plain volatile read
 * on the hot location-report path.
 *
 * ## Command surface
 * There is no command *bus* any more: [FrameworkBinder] calls the typed methods below
 * ([startEngine], [stopEngine], [setPosition], …) on the engine thread and reports their
 * [WireResult] code back over binder. Each of those methods is idempotent and *sets* a state
 * instead of toggling it, which is what makes a lost or duplicated delivery harmless.
 */
object EngineRuntime {

    /** Immutable snapshot for hook code: no engine locks, no allocations on read. */
    class Snap(
        val running: Boolean,
        val lat: Double,
        val lon: Double,
        val alt: Double,
        /** Height above mean sea level, i.e. [alt] minus the local geoid separation. */
        val mslAltM: Double,
        val speedMps: Double,
        val bearingDeg: Double,
        val accuracyGpsM: Double,
        val accuracyNetworkM: Double,
        val accuracyFusedM: Double,
        val steps: Long,
        val headingDeg: Double,
        val updatedAtMs: Long,
        val config: SimulationConfig,
        val state: EngineState,
        val cells: List<SimulatedCell>,
        val accessPoints: List<SimulatedAp>,
        val satellites: List<Satellite>,
        val nmeaLines: List<String>,
        val nmeaBuiltAtMs: Long,
    ) {
        fun accuracyFor(provider: String?): Double = when (provider?.lowercase()) {
            "network" -> accuracyNetworkM
            "fused" -> accuracyFusedM
            else -> accuracyGpsM
        }
    }

    private val engine = SimulationEngine()
    private val started = AtomicBoolean(false)

    @Volatile
    private var thread: HandlerThread? = null

    @Volatile
    private var handler: Handler? = null

    /** Latest published snapshot; `null` until [boot] ran and the engine reported once. */
    @Volatile
    var snap: Snap? = null
        private set

    @Volatile
    var config: SimulationConfig = SimulationConfig()
        private set

    @Volatile
    var enginePresent: Boolean = false
        private set

    @Volatile
    var installedAtMs: Long = 0L
        private set

    /** State consumers (binder fan-out). */
    private val stateListeners = CopyOnWriteArrayList<(EngineState) -> Unit>()

    fun addStateListener(listener: (EngineState) -> Unit) {
        stateListeners.addIfAbsent(listener)
    }

    /** Framework-layer tick consumers (location injection, GNSS/NMEA push). */
    private val tickListeners = CopyOnWriteArrayList<(Snap) -> Unit>()

    private var ticking = false
    private var lastTickMs = 0L
    private var intervalMs = 1000L
    private var lastNmeaMs = 0L
    private var deltaUsedMs = 0L
    private var nmeaCache: List<String> = emptyList()

    /**
     * Full width of the tick-scheduling jitter window: ±3 % of the nominal interval
     * (`TICK_JITTER_SPAN / 2` on either side). See [nextTickDelayMs].
     */
    private const val TICK_JITTER_SPAN = 0.06

    /**
     * RNG for the scheduling jitter. Fixed seed on purpose: an acceptance replay must be able to
     * predict the same schedule, and the sequence — not its unpredictability — is what breaks the
     * metronome. Only ever touched from the single engine thread inside [tickRunnable].
     */
    private val tickRandom = java.util.Random(0x1BAD_5EEDL)

    /**
     * Scheduling delay for the next tick: the nominal interval with a small deterministic jitter.
     *
     * A perfectly constant period is a timer signature no GNSS chipset produces. Consecutive
     * `Location.getElapsedRealtimeNanos()` deltas from a real receiver wobble by a few percent
     * (binder/looper latency, UART readout, scheduler), while `postDelayed(intervalMs)` reproduces
     * the configured value to the millisecond for hours — a fingerprint that survives any check of
     * our own coordinates.
     *
     * Invariants this must not break:
     *  * **Only the schedule jitters.** [SimulationEngine.tick] still receives the *measured* delta
     *    (`SystemClock.elapsedRealtime()` difference), so movement stays exactly consistent with the
     *    reported coordinates and speed.
     *  * **The nominal value is untouched.** [intervalMs] keeps the configured value and the
     *    `tick.diag` line keeps its exact field order/format (`qa-e2e` parses it).
     *  * **The mean is unchanged.** The draw is uniform on `[nominal - s, nominal + s]` with
     *    `s = 3 % of nominal`, so `E[delay] = nominal` (the mean of a uniform draw is its mid-point)
     *    and the truncation to `Long` costs less than 1 ms per interval. Over any number of ticks the
     *    average cadence is therefore the configured one, not a drifted one.
     *  * **Bounded.** The window is clipped into
     *    [SimulationConfig.MIN_INTERVAL_MS]..[SimulationConfig.MAX_INTERVAL_MS], so a delay can never
     *    leave the range the rest of the engine guarantees. At the very edge of that range the window
     *    necessarily becomes one-sided (a delay below `MIN_INTERVAL_MS` is not allowed at all), which
     *    moves the mean at most 1.5 % of the nominal value *into* the legal range — the best a bounded
     *    delay can do, and only reachable when someone configures 100 ms or 10 s exactly.
     */
    private fun nextTickDelayMs(nominalMs: Long): Long {
        val nominal = nominalMs.coerceIn(
            SimulationConfig.MIN_INTERVAL_MS,
            SimulationConfig.MAX_INTERVAL_MS,
        )
        val swing = nominal * (TICK_JITTER_SPAN / 2.0)
        val low = (nominal - swing).coerceAtLeast(SimulationConfig.MIN_INTERVAL_MS.toDouble())
        val high = (nominal + swing).coerceAtMost(SimulationConfig.MAX_INTERVAL_MS.toDouble())
        val delay = (low + (high - low) * tickRandom.nextDouble()).toLong()
        return delay.coerceIn(SimulationConfig.MIN_INTERVAL_MS, SimulationConfig.MAX_INTERVAL_MS)
    }

    private val tickRunnable = object : Runnable {
        override fun run() {
            val current = handler ?: return
            val t0 = SystemClock.elapsedRealtime()
            var published: Snap? = null
            try {
                val delta = (t0 - lastTickMs).coerceAtLeast(0L)
                lastTickMs = t0
                deltaUsedMs = delta
                engine.tick(delta)
                published = buildSnap()
                // Feed the native system_server hook: it rewrites step events for every app, so
                // this is the only place the authoritative counter crosses into native code.
                published?.let { snap ->
                    NativeBridge.pushStepState(
                        steps = snap.steps,
                        cadenceBpm = snap.config.stepCadenceBpm,
                        enabled = snap.config.stepSimulation,
                    )
                }
            } catch (t: Throwable) {
                Log.rate("tick", 5_000L, "engine tick failed: ${t.javaClass.simpleName}: ${t.message}")
                Log.e("engine tick", t)
            }
            published?.let { snapValue ->
                publish(snapValue)
                // Per-tick evidence: delta actually used, speed and distance integrated.
                //
                // Throttled by the TICK INTERVAL, not by a fixed 5 s: the e2e oracle sums `moved`
                // over these lines to decide whether route playback advanced, so a 5 s throttle
                // made a 10 s window contain only 1-2 ticks and reported "advanced 6.8 m" for a
                // route that was demonstrably covering 6.2 m per second (false product bug —
                // 2026-11-02). One line per tick is ~1 line/s and only when detail logging is on
                // (Log.rate returns early otherwise). Field order is parsed by qa-e2e — keep it.
                Log.rate(
                    "tick.diag",
                    intervalMs.coerceAtLeast(200L),
                    "tick dt=" + deltaUsedMs + "ms interval=" + intervalMs + " engineDelta=" +
                        engine.lastTickDeltaMs + "ms appliedTicks=" + engine.appliedTickCount + " speed=" +
                        snapValue.speedMps + "m/s moved=" + (snapValue.speedMps * deltaUsedMs / 1000.0) +
                        "m mode=" + snapValue.config.mode + " running=" + snapValue.running,
                )
            }
            // The *schedule* carries a ±3 % jitter (see [nextTickDelayMs]); `engine.tick` above still
            // received the measured delta and `tick.diag` still reports the nominal interval.
            if (ticking) current.postDelayed(this, nextTickDelayMs(intervalMs))
        }
    }

    // ------------------------------------------------------------------ lifecycle

    /** Called once from the framework layer installer. */
    fun boot(moduleVersion: String) {
        if (!started.compareAndSet(false, true)) return
        installedAtMs = System.currentTimeMillis()
        enginePresent = true
        config = engine.config
        ensureThread()
        // Publish an initial (stopped) snapshot so hooks know the engine exists but is idle.
        post {
            try {
                publish(buildSnap())
            } catch (t: Throwable) {
                Log.e("engine boot publish", t)
            }
        }
        Log.i("EngineRuntime booted in ${android.os.Process.myPid()} (module $moduleVersion)")
    }

    fun addTickListener(listener: (Snap) -> Unit) {
        tickListeners.addIfAbsent(listener)
    }

    private fun ensureThread(): Handler {
        handler?.let { return it }
        synchronized(this) {
            handler?.let { return it }
            val t = HandlerThread("ImmoralLocation-engine").also { it.start() }
            val h = Handler(t.looper)
            thread = t
            handler = h
            lastTickMs = SystemClock.elapsedRealtime()
            return h
        }
    }

    /** Run [block] on the engine thread (creating it if needed). */
    fun post(block: () -> Unit) {
        try {
            ensureThread().post {
                try {
                    block()
                } catch (t: Throwable) {
                    Log.e("engine task", t)
                }
            }
        } catch (t: Throwable) {
            Log.e("engine post", t)
        }
    }

    fun looper(): Looper? = handler?.looper

    // ------------------------------------------------------------------ commands
    //
    // Every method below must be called on the engine thread (FrameworkBinder posts them) and
    // reports a WireResult code. They are "set state" operations: calling one twice is the same
    // as calling it once, and no method derives its behaviour from a cached copy of the client's
    // view of the world.

    fun startEngine(): Int {
        engine.running = true
        startTicker()
        publish(buildSnap())
        return WireResult.OK
    }

    /**
     * Stops the engine and every movement source with it.
     *
     * Releasing the joystick here (rather than as a separate client-side call) is what makes
     * "stop" a single, complete intent: a delayed joystick vector can no longer arrive after the
     * stop and silently restart the engine, because there is no vector left to resume from.
     */
    fun stopEngine(): Int {
        engine.running = false
        engine.releaseJoystick()
        stopTicker()
        // The native hook only learns about step state from the ticker, which has just stopped.
        NativeBridge.pushStepState(
            steps = engine.state.steps,
            cadenceBpm = config.stepCadenceBpm,
            enabled = false,
        )
        publish(buildSnap())
        Log.i("engine stopped; joystick released, step fabrication disarmed")
        return WireResult.OK
    }

    fun setPosition(point: GeoPoint): Int {
        if (!point.isValidLatLon()) return WireResult.INVALID_ARGUMENT
        engine.setPosition(point, true)
        Log.i("position set ${point.lat},${point.lon}")
        publish(buildSnap())
        return WireResult.OK
    }

    fun setConfig(next: SimulationConfig): Int {
        engine.config = next
        config = engine.config
        Log.i(
            "config applied: independent=${config.independentMode} " +
                "packages=${config.independentPackages.size} interval=${config.updateIntervalMs} " +
                "cell=${config.cellSimulation} wifi=${config.wifiSimulation} " +
                "gpsSignal=${config.gpsSignalSimulation} steps=${config.stepSimulation} " +
                "speed=${config.speed} effSpeed=${config.effectiveSpeedMps()}m/s " +
                "custom=${config.customSpeedMps}",
        )
        val wanted = config.updateIntervalMs.coerceIn(100L, 10_000L)
        if (wanted != intervalMs) {
            intervalMs = wanted
            if (ticking) restartTicker()
        }
        publish(buildSnap())
        return WireResult.OK
    }

    fun setJoystick(x: Float, y: Float) {
        engine.setJoystick(x, y)
        // A joystick push implies "move": start the ticker when the stick is off-centre so the
        // very first drag is not swallowed until someone sends start.
        val magnitude = kotlin.math.hypot(x.toDouble(), y.toDouble())
        if (magnitude > 0.02 && !engine.running) {
            engine.running = true
            startTicker()
        }
        // state only: the position moves on the next tick, not now
        publish(buildSnap(), pushLocation = false)
    }

    fun releaseJoystick() {
        engine.releaseJoystick()
        publish(buildSnap(), pushLocation = false)
    }

    fun setHeading(deg: Double) {
        engine.setHeading(deg)
    }

    fun startRoute(route: RoutePlan): Int {
        // A re-sent plan while the route is already PLAYING must not retune or restart it
        // (that silently reset the plan speed and made playback advance at 1/17 of the
        // configured speed). A re-sent plan in any other state is a genuine restart request:
        // after the route FINISHED its progress stays at 1.0, so "play this again" has to reset
        // it to 0 instead of instantly finishing again.
        val existing = engine.routePoints
        val previousState = engine.currentRouteState
        val samePlan = existing.isNotEmpty() && existing == route.points
        val restart = !samePlan || previousState != RouteState.PLAYING
        if (!restart) {
            Log.rate(
                "routerepeat",
                2_000L,
                "startRoute ignored (same plan already playing; state=" + previousState +
                    " progress=" + engine.state.routeProgress + ")",
            )
            publish(buildSnap())
            return WireResult.OK
        }
        engine.startRoute(route.points, route.repeat, route.speedMps)
        config = engine.config
        startTicker()
        Log.i(
            "route loaded: points=" + route.points.size +
                " speed=" + engine.activeRouteSpeedMps + "m/s repeat=" + route.repeat +
                " (was state=" + previousState + " samePlan=" + samePlan + ")",
        )
        publish(buildSnap())
        return WireResult.OK
    }

    fun playRoute(): Int {
        engine.playRoute()
        if (engine.running) startTicker()
        publish(buildSnap())
        return WireResult.OK
    }

    fun pauseRoute(): Int {
        engine.pauseRoute()
        publish(buildSnap())
        return WireResult.OK
    }

    fun stopRoute(): Int {
        engine.stopRoute()
        publish(buildSnap())
        return WireResult.OK
    }

    fun resetSteps(steps: Long): Int {
        engine.resetSteps(steps)
        publish(buildSnap())
        return WireResult.OK
    }

    /** Current state as a wire struct, or `null` before the first publish. */
    fun currentWireState(): WireState? = snap?.state?.toWire()

    /** Synthesized environment data for the diagnostics panel (already computed per tick). */
    fun currentDiagnostics(): WireDiagnostics {
        val current = snap ?: return WireDiagnostics(emptyList(), emptyList(), emptyList(), emptyList())
        return WireDiagnostics(
            satellites = current.satellites.map { it.toWire() },
            cells = current.cells.map { it.toWire() },
            accessPoints = current.accessPoints.map { it.toWire() },
            nmeaLines = current.nmeaLines,
        )
    }

    // ------------------------------------------------------------------ ticker

    private fun startTicker() {
        if (ticking) {
            // Idempotent: a second start / a joystick auto-start must never create a second tick
            // chain (that would integrate the position twice per interval and double the speed).
            Log.rate("ticker.dup", 5_000L, "ticker already running, not started again")
            return
        }
        val current = ensureThread()
        if (!ticking) {
            ticking = true
            intervalMs = config.updateIntervalMs.coerceIn(100L, 10_000L)
            lastTickMs = SystemClock.elapsedRealtime() - 1
            current.removeCallbacks(tickRunnable)
            current.post(tickRunnable)
            Log.rate("ticker", 1_000L, "ticker started (interval ${intervalMs}ms)")
            // Evidence that the cadence is not a metronome: name the jitter, its bound and what it
            // does NOT touch. Emitted once per session (the acceptance run greps the log for it).
            Log.once(
                "ticker.jitter",
                "ticker: scheduling delay jittered ±3% around the nominal ${intervalMs}ms " +
                    "(window ${(intervalMs * (1.0 - TICK_JITTER_SPAN / 2.0)).toLong()}.." +
                    "${(intervalMs * (1.0 + TICK_JITTER_SPAN / 2.0)).toLong()}ms, clipped to " +
                    "${SimulationConfig.MIN_INTERVAL_MS}..${SimulationConfig.MAX_INTERVAL_MS}ms); " +
                    "engine deltas stay measured, tick.diag keeps the nominal interval, mean cadence unchanged",
            )
        }
    }

    private fun stopTicker() {
        if (ticking) {
            ticking = false
            handler?.removeCallbacks(tickRunnable)
            Log.i("ticker stopped")
        }
    }

    private fun restartTicker() {
        val current = ensureThread()
        current.removeCallbacks(tickRunnable)
        current.post(tickRunnable)
    }

    // ------------------------------------------------------------------ snapshot

    private fun buildSnap(): Snap {
        val state = engine.state
        val cfg = engine.config
        val lat = state.point.lat
        val lon = state.point.lon
        val jitter = cfg.gpsJitterMeters
        val nowMs = System.currentTimeMillis()
        val sats = if (cfg.gpsSignalSimulation) {
            try {
                engine.satellites()
            } catch (_: Throwable) {
                emptyList()
            }
        } else {
            emptyList()
        }
        if (cfg.gpsSignalSimulation && nowMs - lastNmeaMs >= 900L) {
            nmeaCache = try {
                engine.nmea(nowMs)
            } catch (_: Throwable) {
                emptyList()
            }
            lastNmeaMs = nowMs
        } else if (!cfg.gpsSignalSimulation) {
            nmeaCache = emptyList()
        }
        // The accuracy an app reads has to follow from the constellation it also reads: a strong,
        // well-spread sky cannot coexist with a 25 m fix, and a constant number is a fingerprint in
        // itself. `AccuracySynth` derives it as UERE x HDOP over the same satellites plus a slow
        // deterministic wander, so the two channels agree and the value still moves over time.
        val gpsAccuracy = try {
            AccuracySynth.accuracyMeters(state.point, sats, nowMs, jitter)
        } catch (_: Throwable) {
            AccuracySynth.DEFAULT_HDOP * 3.0
        }
        // MSL height = ellipsoid height - local geoid separation. Both heights are part of a real
        // GNSS fix on Android 14+; reporting only the ellipsoid one is a "no geoid model" tell.
        val mslAltM = try {
            state.point.alt - engine.geoidSeparationMeters(state.point)
        } catch (_: Throwable) {
            state.point.alt
        }
        return Snap(
            running = state.running,
            lat = lat,
            lon = lon,
            alt = state.point.alt,
            mslAltM = mslAltM,
            speedMps = state.speedMps,
            bearingDeg = state.bearingDeg,
            accuracyGpsM = gpsAccuracy,
            accuracyNetworkM = AccuracySynth.networkAccuracyMeters(gpsAccuracy),
            accuracyFusedM = AccuracySynth.fusedAccuracyMeters(gpsAccuracy),
            steps = state.steps,
            headingDeg = state.headingDeg,
            updatedAtMs = nowMs,
            config = cfg,
            state = state,
            cells = if (cfg.cellSimulation) {
                try {
                    engine.cells()
                } catch (_: Throwable) {
                    emptyList()
                }
            } else {
                emptyList()
            },
            accessPoints = if (cfg.wifiSimulation) {
                try {
                    engine.accessPoints()
                } catch (_: Throwable) {
                    emptyList()
                }
            } else {
                emptyList()
            },
            satellites = sats,
            nmeaLines = nmeaCache,
            nmeaBuiltAtMs = lastNmeaMs,
        )
    }

    // ------------------------------------------------------------------ cells channel

    /** `Settings.Global` key the phone process reads (`TelephonyHooks.cellsFromSettings`). */
    private const val CELLS_KEY = "immoral_cells"

    /** Dedup marker for "no row at all"; real payloads always start with `1|`. */
    private const val CELLS_ABSENT = "<absent>"

    /**
     * A `Context` usable from the engine thread, resolved once and cached.
     *
     * This is what silently killed the cells channel. `ActivityThread.currentActivityThread()` is
     * still `null` while the process is attaching (the engine thread publishes within milliseconds
     * of `boot()`), so the reflective call fell through to `getSystemContext().invoke(null)` on an
     * **instance** method — a `NullPointerException`. The old code reported that exactly once with
     * `Log.once` and had already committed the dedup key, while `Settings.Global` kept whatever a
     * previous boot had left behind; the phone process therefore served one frozen cell set for
     * the whole session — the "position moves, cells don't" defect (`6-cells`).
     *
     * Order: a direct attempt on the calling thread (self-healing, works as soon as the activity
     * thread is up), then — once — a hop to the **main looper**, which is where `ActivityThread`
     * is guaranteed to be visible. Nothing here may throw into the ticker.
     */
    @Volatile
    private var cachedContext: android.content.Context? = null

    @Volatile
    private var contextRequested = false

    private fun systemContext(): android.content.Context? {
        cachedContext?.let { return it }
        resolveContextHere()?.let {
            cachedContext = it
            Log.once("cells.context.direct", "cells channel: Context resolved on the engine thread")
            return it
        }
        // Ask the main thread once. `currentActivityThread()` is non-null there even while the
        // engine thread sees null, and the answer arrives within a tick or two, so the publish
        // simply retries (the dedup key is not committed on failure).
        if (!contextRequested) {
            contextRequested = true
            try {
                Handler(Looper.getMainLooper()).post {
                    try {
                        val fromMain = resolveContextHere()
                        if (fromMain != null) {
                            cachedContext = fromMain
                            Log.i("cells channel: Context resolved on the main thread")
                        } else {
                            Log.once("cells.context.main.err", "cells channel: no Context on the main thread either")
                        }
                    } catch (t: Throwable) {
                        Log.once("cells.context.main.err", "cells channel: main-thread lookup failed: " + t.javaClass.simpleName)
                    }
                }
            } catch (t: Throwable) {
                contextRequested = false
                Log.once("cells.context.post", "cells channel: cannot post to the main looper: " + t.javaClass.simpleName)
            }
        }
        return null
    }

    /** Direct context lookup for the calling thread; `null` when this thread cannot see one. */
    private fun resolveContextHere(): android.content.Context? {
        try {
            val threadClass = Class.forName("android.app.ActivityThread")
            val current = threadClass.getMethod("currentActivityThread").invoke(null)
            if (current != null) {
                val system = threadClass.getMethod("getSystemContext").invoke(current)
                if (system is android.content.Context) return system
            }
            val app = threadClass.getMethod("currentApplication").invoke(null)
            if (app is android.content.Context) return app
        } catch (_: Throwable) {
        }
        try {
            val appGlobals = Class.forName("android.app.AppGlobals")
            val app = appGlobals.getMethod("getInitialApplication").invoke(null)
            if (app is android.content.Context) return app
        } catch (_: Throwable) {
        }
        return null
    }

    /**
     * Publishes the simulated cell set to `Settings.Global`.
     *
     * The cell getters live in `com.android.phone` (`PhoneInterfaceManager`), a different process
     * from the engine, and `TelephonyRegistry` in `system_server` has no cell *getter* at all — so
     * without a state channel the phone-side hooks are installed but inert. This is the one
     * cross-process channel that is not binder: that process hosts the platform API and cannot
     * host our interface.
     *
     * Contract with `TelephonyHooks.cellsFromSettings`:
     *  - simulation on  -> `"1|type,mcc,mnc,lac,cid,pci,level,dbm,reg;…"`;
     *  - simulation off -> the row is **deleted** (not rewritten as `0|`), so nothing about the
     *    simulation survives in a globally readable setting once the user turns it off.
     *
     * The dedup key is only committed after a successful write: a failed `putString` (boot races,
     * provider not ready) must not make the channel believe the value is already published.
     */
    private fun publishCells(value: Snap) {
        val serving = value.cells.firstOrNull { it.registered }
        val payload: String? = if (value.config.cellSimulation && value.cells.isNotEmpty()) {
            "1|" + value.cells.joinToString(";") { c ->
                "${c.type},${c.mcc},${c.mnc},${c.lac},${c.cid},${c.pci},${c.level},${c.dbm},${if (c.registered) 1 else 0}"
            }
        } else {
            null
        }
        val dedupKey = payload ?: CELLS_ABSENT
        if (dedupKey == lastCellsEncoded) return
        val context = systemContext()
        if (context == null) {
            // Retried on the next tick: this is the boot race that used to be permanent.
            logPublishStall("no Context on this thread yet")
            return
        }
        val wrote = try {
            android.provider.Settings.Global.putString(context.contentResolver, CELLS_KEY, payload)
            true
        } catch (t: Throwable) {
            logPublishStall(t.javaClass.simpleName + ": " + t.message)
            false
        }
        if (!wrote) return
        if (cellsPublishStalls > 0) {
            Log.i("cells channel recovered after " + cellsPublishStalls + " failed publish attempt(s)")
            cellsPublishStalls = 0
        }
        lastCellsEncoded = dedupKey
        if (payload == null) {
            Log.rate("cells.cleared", 10_000L, "cells channel cleared: " + CELLS_KEY + " row removed")
            return
        }
        // Acceptance evidence for `6-cells`: the line carries the position the set was derived
        // from *and* the serving identity, so "the published set follows the position" can be
        // read straight out of the log instead of being inferred from the app side.
        Log.rate(
            "cells.written",
            2_000L,
            "cells published to Settings.Global lat=" + trimCoord(value.lat) + "," + trimCoord(value.lon) +
                " serving=" + (serving?.let { "ci=" + it.cid + "/tac=" + it.lac } ?: "none") +
                " count=" + value.cells.size + " -> " + payload.take(80),
        )
        verifyCellsSetting(payload)
    }

    /**
     * Rate limited (60 s) but **never silent**: a boot where the Context never resolves must
     * leave a line behind, because that is the first thing to check if `6-cells` regresses. The
     * count is cumulative since the last successful write and only grows while publishes fail.
     */
    private fun logPublishStall(reason: String) {
        cellsPublishStalls++
        Log.rate(
            "cells.publish.stall",
            60_000L,
            "cells channel: publish NOT landing (" + reason + "); " + cellsPublishStalls +
                " failed attempt(s) so far, " + CELLS_KEY + " still holds the previous value — retrying every tick",
        )
    }

    private var cellsPublishStalls = 0

    /**
     * Reads the setting back once after a change.
     *
     * `Settings.Global.putString` reports nothing, so a silently dropped write would leave the
     * phone process serving the previous cell set forever. On a mismatch the dedup is cleared so
     * the next tick writes again.
     */
    private fun verifyCellsSetting(expected: String?) {
        val context = cachedContext ?: return
        try {
            val read = android.provider.Settings.Global.getString(context.contentResolver, CELLS_KEY)
            if (read == expected) {
                Log.once("cells.verify.ok", "cells setting reads back exactly what was published")
            } else {
                lastCellsEncoded = ""
                Log.rate(
                    "cells.verify.mismatch",
                    5_000L,
                    "cells setting read back " + (read?.take(60) ?: "null") + " instead of " +
                        (expected?.take(60) ?: "null") + " (retrying on the next tick)",
                )
            }
        } catch (t: Throwable) {
            Log.once("cells.verify.err", "cannot read the cells setting back: " + t.javaClass.simpleName)
        }
    }

    /** `31.2304`-style coordinate for log lines (6 decimals would only add noise). */
    private fun trimCoord(value: Double): String =
        (kotlin.math.round(value * 10_000.0) / 10_000.0).toString()

    private var lastCellsEncoded: String = ""

    /**
     * Publishes a snapshot.
     *
     * [pushLocation] controls whether the *location* consumers (framework injection, GNSS push)
     * run. Commands that do not move the position (setJoystick / releaseJoystick / setHeading)
     * publish state only: injecting a fix per command would flood apps with identical coordinates
     * at up to 10 Hz while the engine only advances once per tick, which makes any consumer that
     * derives speed from consecutive fixes compute ~0 m/s.
     */
    private fun publish(value: Snap, pushLocation: Boolean = true) {
        snap = value
        config = value.config
        publishCells(value)
        if (pushLocation) {
            for (listener in tickListeners) {
                try {
                    listener(value)
                } catch (t: Throwable) {
                    Log.rate("ticklistener", 10_000L, "tick listener failed: ${t.javaClass.simpleName}")
                }
            }
        }
        for (listener in stateListeners) {
            try {
                listener(value.state)
            } catch (t: Throwable) {
                Log.rate("statelistener", 10_000L, "state listener failed: ${t.javaClass.simpleName}")
            }
        }
    }

    /** Hot-path read used by every hook: one volatile read, `null` when nothing to spoof. */
    fun activeSnap(): Snap? {
        val current = snap ?: return null
        return if (current.running) current else null
    }

    /** Independent-mode policy, or `null` when the engine does not live in this process. */
    fun policyOrNull(): Pair<Boolean, Set<String>>? {
        if (!enginePresent) return null
        val cfg = config
        return cfg.independentMode to cfg.independentPackages
    }
}
