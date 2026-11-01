package com.immoral.location.hook

import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import com.immoral.location.core.engine.SimulationEngine
import com.immoral.location.core.model.EngineState
import com.immoral.location.core.model.Satellite
import com.immoral.location.core.model.SimulatedAp
import com.immoral.location.core.model.SimulatedCell
import com.immoral.location.core.model.SimulationConfig
import com.immoral.location.core.protocol.BridgeReply
import com.immoral.location.core.protocol.Command
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs

/**
 * The engine host that lives **inside `system_server`**.
 *
 * Owns a [SimulationEngine] plus a dedicated [HandlerThread]; every mutating engine call (ticks
 * *and* commands) runs on that single thread because [SimulationEngine] is not thread-safe.
 * Hook code only ever reads the immutable [Snap] published here, which is a plain volatile read
 * on the hot location-report path.
 */
object EngineRuntime {

    /** Immutable snapshot for hook code: no engine locks, no allocations on read. */
    class Snap(
        val running: Boolean,
        val lat: Double,
        val lon: Double,
        val alt: Double,
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

    /** Bridge state pushes (throttled inside [BridgeServer]). */
    @Volatile
    var stateSink: ((EngineState) -> Unit)? = null

    /** Extra state consumers (status file writer, …). */
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
                Log.rate(
                    "tick.diag",
                    5_000L,
                    "tick dt=" + deltaUsedMs + "ms interval=" + intervalMs + " engineDelta=" +
                        engine.lastTickDeltaMs + "ms appliedTicks=" + engine.appliedTickCount + " speed=" +
                        snapValue.speedMps + "m/s moved=" + (snapValue.speedMps * deltaUsedMs / 1000.0) +
                        "m mode=" + snapValue.config.mode + " running=" + snapValue.running,
                )
            }
            if (ticking) current.postDelayed(this, intervalMs.coerceIn(100L, 10_000L))
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

    /**
     * Entry point used by [BridgeServer]. Never blocks: the reply is produced and broadcast from
     * the engine thread.
     */
    fun submit(command: Command, requestId: String?) {
        post {
            val reply = handle(command, requestId ?: "broadcast")
            BridgeServer.sendReply(reply, requestId)
        }
    }

    private fun handle(command: Command, source: String = "broadcast"): BridgeReply {
        var error: String? = null
        try {
            when (command) {
                is Command.Ping -> Unit
                is Command.GetState -> publish(buildSnap())
                is Command.SetConfig -> {
                    engine.config = command.config
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
                }
                is Command.Start -> {
                    engine.running = true
                    startTicker()
                    publish(buildSnap())
                }
                is Command.Stop -> {
                    engine.running = false
                    stopTicker()
                    publish(buildSnap())
                }
                is Command.SetPosition -> {
                    if (!command.point.isValidLatLon()) {
                        error = "invalid position"
                    } else {
                        engine.setPosition(command.point, true)
                        Log.i("position source=" + source + " set " + command.point.lat + "," + command.point.lon)
                        publish(buildSnap())
                    }
                }
                is Command.SetJoystick -> {
                    engine.setJoystick(command.x, command.y)
                    // A joystick push implies "move": start the ticker when the stick is off-centre so
                    // the very first drag is not swallowed until someone sends Start.
                    val magnitude = kotlin.math.hypot(command.x.toDouble(), command.y.toDouble())
                    if (magnitude > 0.02 && !engine.running) {
                        engine.running = true
                        startTicker()
                    }
                    // state only: the position moves on the next tick, not now
                    publish(buildSnap(), pushLocation = false)
                }
                is Command.ReleaseJoystick -> {
                    engine.releaseJoystick()
                    publish(buildSnap(), pushLocation = false)
                }
                is Command.SetHeading -> {
                    engine.setHeading(command.deg)
                }
                is Command.StartRoute -> {
                    // The app re-sends its whole state on every refresh: replaying StartRoute must not
                    // retune or restart a route that is already loaded (that silently reset the plan
                    // speed and made route playback advance at 1/17 of the configured speed).
                    val existing = engine.routePoints
                    val samePlan = existing.isNotEmpty() && existing == command.plan.points &&
                        engine.currentRouteState != com.immoral.location.core.model.RouteState.IDLE
                    if (samePlan) {
                        Log.rate("routerepeat", 10_000L, "StartRoute ignored (same plan)")
                    } else {
                        engine.startRoute(
                            command.plan.points,
                            command.plan.repeat,
                            command.plan.speedMps,
                        )
                        config = engine.config
                        startTicker()
                        Log.i(
                            "route loaded: points=" + command.plan.points.size +
                                " speed=" + engine.activeRouteSpeedMps + "m/s repeat=" + command.plan.repeat,
                        )
                    }
                    publish(buildSnap())
                }
                is Command.PlayRoute -> {
                    engine.playRoute()
                    if (engine.running) startTicker()
                    publish(buildSnap())
                }
                is Command.PauseRoute -> {
                    engine.pauseRoute()
                    publish(buildSnap())
                }
                is Command.StopRoute -> {
                    engine.stopRoute()
                    publish(buildSnap())
                }
                is Command.ResetSteps -> {
                    engine.resetSteps(command.steps)
                    publish(buildSnap())
                }
            }
        } catch (t: Throwable) {
            Log.e("command ${command.javaClass.simpleName} failed", t)
            error = "${t.javaClass.simpleName}: ${t.message}"
        }
        return BridgeReply(
            ok = error == null,
            error = error,
            state = try {
                engine.state
            } catch (_: Throwable) {
                snap?.state
            },
            engineLoaded = true,
            moduleVersion = ModuleInfo.version,
        )
    }

    // ------------------------------------------------------------------ ticker

    private fun startTicker() {
        if (ticking) {
            // Idempotent: a second Start / a joystick auto-start must never create a second tick chain
            // (that would integrate the position twice per interval and double the measured speed).
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
        val baseAccuracy = 3.0 + (abs((lat * 10_000).toLong() + (lon * 10_000).toLong()) % 6).toDouble()
        val gpsAccuracy = (baseAccuracy + jitter).coerceIn(3.0, 60.0)
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
        return Snap(
            running = state.running,
            lat = lat,
            lon = lon,
            alt = state.point.alt,
            speedMps = state.speedMps,
            bearingDeg = state.bearingDeg,
            accuracyGpsM = gpsAccuracy,
            accuracyNetworkM = (gpsAccuracy + 18.0).coerceIn(15.0, 120.0),
            accuracyFusedM = (gpsAccuracy + 4.0).coerceIn(5.0, 40.0),
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

    /**
     * Publishes a snapshot.
     *
     * [pushLocation] controls whether the *location* consumers (framework injection, GNSS push) run.
     * Commands that do not move the position (SetJoystick / ReleaseJoystick / SetHeading) publish
     * state only: injecting a fix per command would flood apps with identical coordinates at up to
     * 10 Hz while the engine only advances once per tick, which makes any consumer that derives
     * speed from consecutive fixes compute ~0 m/s.
     */
    /**
     * Publishes the independent-mode policy to Settings.Global. system_server cannot write app data
     * (DAC) and the state bus only reaches apps we deliver to, so this is the only channel that lets a
     * fresh NON-whitelisted process know it must not fabricate anything.
     */
    private fun publishPolicy(config: com.immoral.location.core.model.SimulationConfig) {
        try {
            val threadClass = Class.forName("android.app.ActivityThread")
            val current = threadClass.getMethod("currentActivityThread").invoke(null)
            val context = threadClass.getMethod("getSystemContext").invoke(current) as android.content.Context
            val value = (if (config.independentMode) "1" else "0") + "|" +
                config.independentPackages.sorted().joinToString(",")
            android.provider.Settings.Global.putString(context.contentResolver, "immoral_policy", value)
            Log.rate("policy.written", 10_000L, "policy published to Settings.Global: " + value)
        } catch (t: Throwable) {
            Log.once("policy.write.err", "cannot publish the policy setting: " + t.javaClass.simpleName)
        }
    }

    /**
     * Publishes the simulated cell set to `Settings.Global`.
     *
     * The cell getters live in `com.android.phone` (`PhoneInterfaceManager`), a different process
     * from the engine, and `TelephonyRegistry` in `system_server` has no cell *getter* at all — so
     * without a state channel the phone-side hooks are installed but inert. This is the same trick
     * already used for [publishPolicy], and it is written only when the encoded value changes, so a
     * stationary engine costs zero writes.
     */
    private fun publishCells(value: Snap) {
        val encoded = try {
            buildString {
                if (value.config.cellSimulation) append("1|") else append("0|")
                if (value.config.cellSimulation) {
                    append(
                        value.cells.joinToString(";") { c ->
                            "${c.type},${c.mcc},${c.mnc},${c.lac},${c.cid},${c.pci},${c.level},${c.dbm},${if (c.registered) 1 else 0}"
                        },
                    )
                }
            }
        } catch (t: Throwable) {
            return
        }
        if (encoded == lastCellsEncoded) return
        val serving = value.cells.firstOrNull { it.registered }
        // Written **before** the log line and committed only on success: a failed `putString` must
        // not poison the dedup, otherwise the channel would keep whatever value the one successful
        // write stored and every later position would be invisible to the phone process.
        val wrote = try {
            val threadClass = Class.forName("android.app.ActivityThread")
            val current = threadClass.getMethod("currentActivityThread").invoke(null)
            val context = threadClass.getMethod("getSystemContext").invoke(current) as android.content.Context
            android.provider.Settings.Global.putString(context.contentResolver, "immoral_cells", encoded)
            true
        } catch (t: Throwable) {
            Log.once("cells.write.err", "cannot publish the cells setting: " + t.javaClass.simpleName)
            false
        }
        if (!wrote) return
        lastCellsEncoded = encoded
        // Bring-up evidence for acceptance check `6-cells`: the line carries the position the set
        // was derived from *and* the serving identity, so "the published set follows the position"
        // can be read straight out of the log instead of being inferred from the app side.
        Log.rate(
            "cells.written",
            2_000L,
            "cells published to Settings.Global lat=" + trimCoord(value.lat) + "," + trimCoord(value.lon) +
                " serving=" + (serving?.let { "ci=" + it.cid + "/tac=" + it.lac } ?: "none") +
                " count=" + value.cells.size + " -> " + encoded.take(80),
        )
        verifyCellsSetting(encoded)
    }

    /**
     * Reads the setting back once after a change.
     *
     * `Settings.Global.putString` reports nothing, so a silently dropped write (provider not ready
     * yet, a vendor policy rejecting the key) would leave the phone process serving the previous
     * cell set forever — exactly the "position moves, cells don't" defect this channel exists to
     * fix. On a mismatch the dedup is cleared so the next tick writes again.
     */
    private fun verifyCellsSetting(expected: String) {
        try {
            val threadClass = Class.forName("android.app.ActivityThread")
            val current = threadClass.getMethod("currentActivityThread").invoke(null)
            val context = threadClass.getMethod("getSystemContext").invoke(current) as android.content.Context
            val read = android.provider.Settings.Global.getString(context.contentResolver, "immoral_cells")
            if (read == expected) {
                Log.once("cells.verify.ok", "cells setting reads back exactly what was published")
            } else {
                lastCellsEncoded = ""
                Log.rate(
                    "cells.verify.mismatch",
                    5_000L,
                    "cells setting read back " + (read?.take(60) ?: "null") + " instead of " + expected.take(60) +
                        " (retrying on the next tick)",
                )
            }
        } catch (t: Throwable) {
            Log.once("cells.verify.err", "cannot read the cells setting back: " + t.javaClass.simpleName)
        }
    }

    /** `31.2304`-style coordinate for log lines (6 decimals would only add noise). */
    private fun trimCoord(value: Double): String {
        val scaled = (kotlin.math.round(value * 10_000.0) / 10_000.0).toString()
        return scaled
    }

    private var lastCellsEncoded: String = ""

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
        try {
            stateSink?.invoke(value.state)
        } catch (t: Throwable) {
            Log.rate("statesink", 10_000L, "state sink failed: ${t.javaClass.simpleName}")
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

    /** Force an immediate state push (used right after a teleport). */
    fun currentState(): EngineState? = snap?.state
}
