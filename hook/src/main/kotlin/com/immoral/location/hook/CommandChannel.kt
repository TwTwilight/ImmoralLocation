package com.immoral.location.hook

import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import com.immoral.location.core.model.EngineState
import com.immoral.location.core.model.SimulationConfig
import com.immoral.location.core.protocol.Codec
import com.immoral.location.core.protocol.Command
import de.robv.android.xposed.XSharedPreferences
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The **prefs-mirror control channel**: the authoritative way the app drives the framework.
 *
 * The app writes into its own default `SharedPreferences`
 * (`<applicationId>_preferences`), which LSPosed exposes to the module through
 * [XSharedPreferences] (the manifest declares `xposedsharedprefs`). `system_server` polls that file
 * every 250 ms; a newer `sim_seq` means a new `sim_cmd` JSON document to decode and dispatch.
 *
 * This works even when no broadcast can be registered, which is why it is the primary channel.
 * Broadcasts stay as a low-latency optimisation for joystick moves.
 *
 * Frozen key contract (see also the `:app` writer):
 *  - `sim_seq`      Int    monotonically increasing command sequence
 *  - `sim_cmd`      String `Codec.encodeCommand(...)`
 *  - `sim_config`   String `SimulationConfig` JSON, written on every config change
 *  - `sim_active`   Boolean whether the engine should be running
 *  - `sim_lat`/`sim_lon`/`sim_alt`/`sim_steps` cheap display restoration
 *
 * Legacy/legacy-compatible keys written by the current app UI (`engineActive`, `stepSimulation`, …)
 * are still honoured here, so the app can keep its own key set during a migration.
 */
object CommandChannel {

    const val KEY_SEQ = "sim_seq"
    const val KEY_CMD = "sim_cmd"
    const val KEY_CONFIG = "sim_config"
    const val KEY_ACTIVE = "sim_active"
    const val KEY_LAT = "sim_lat"
    const val KEY_LON = "sim_lon"
    const val KEY_ALT = "sim_alt"
    const val KEY_STEPS = "sim_steps"

    /** Fallback keys the app already writes today. */
    private const val LEGACY_KEY_ACTIVE = "engineActive"

    private const val POLL_MS = 250L

    private val started = AtomicBoolean(false)

    @Volatile
    private var prefs: XSharedPreferences? = null

    @Volatile
    private var prefsPath: String? = null

    private var lastSeq: Int? = null
    private var lastConfigJson: String? = null
    private var lastActive: Boolean? = null
    private var lastCommandAtMs = 0L

    @Volatile
    private var loadFailures = 0

    /** Poll period; backs off to 2 s when the prefs file turns out to be unreadable. */
    @Volatile
    private var pollDelayMs = POLL_MS

    @Volatile
    private var unreadableReported = false

    fun start() {
        if (!started.compareAndSet(false, true)) return
        try {
            val thread = HandlerThread("ImmoralLocation-prefs").also { it.start() }
            val handler = Handler(thread.looper)
            val runnable = object : Runnable {
                override fun run() {
                    try {
                        poll()
                    } catch (t: Throwable) {
                        Log.once("prefschannel.err", "prefs channel poll failed: ${t.javaClass.simpleName}: ${t.message}")
                    }
                    handler.postDelayed(this, pollDelayMs)
                }
            }
            handler.postDelayed(runnable, POLL_MS)
            Log.i("prefs command channel started (poll ${POLL_MS}ms)")
        } catch (t: Throwable) {
            Log.e("prefs command channel could not start", t)
        }
    }

    /** Open the module app's default prefs file, once. */
    private fun openPrefs(): XSharedPreferences? {
        prefs?.let { return it }
        val candidates = ArrayList<String>(ModuleInfo.candidateAppPackages)
        if (!candidates.contains(ModuleInfo.appPackage)) candidates.add(0, ModuleInfo.appPackage)
        for (pkg in candidates) {
            try {
                val p = XSharedPreferences(pkg)
                val file = p.file
                if (file != null && file.exists()) {
                    prefs = p
                    prefsPath = file.absolutePath
                    Log.i("prefs command channel <- $pkg (${file.length()} bytes at ${file.absolutePath})")
                    return p
                }
            } catch (t: Throwable) {
                Log.once("prefs.open.$pkg", "XSharedPreferences($pkg) unavailable: ${t.javaClass.simpleName}")
            }
        }
        loadFailures++
        if (loadFailures == 1 || loadFailures % 40 == 0) {
            Log.w("prefs command channel: no module prefs file yet (attempt $loadFailures, looked at $candidates)")
        }
        return null
    }

    private fun poll() {
        val p = openPrefs() ?: return
        try {
            p.reload()
        } catch (t: Throwable) {
            Log.once("prefs.reload", "prefs reload failed: ${t.javaClass.simpleName}")
            return
        }
        val all: Map<String, *> = try {
            p.all
        } catch (t: Throwable) {
            emptyMap<String, Any?>()
        }
        if (all.isEmpty()) {
            // The file exists (we only get here after File.exists() returned true) but has no keys:
            // on HyperOS the app prefs live in /data/misc/apexdata/... with mode 0660 owned by the
            // app uid, so system_server (uid 1000) cannot read them. Report once, slow down, and let
            // the broadcast channel stay authoritative.
            if (!unreadableReported) {
                unreadableReported = true
                pollDelayMs = 2000L
                Log.w(
                    "prefs channel: file present but NOT readable from system_server (app prefs are 0660 in " +
                        "apexdata) — prefs commands unavailable here, broadcast channel is authoritative",
                )
            }
            return
        }

        // 1) sequenced commands (authoritative)
        val seq = parseInt(all[KEY_SEQ])
        val cmdJson = all[KEY_CMD] as? String
        if (seq != null && cmdJson != null) {
            val previous = lastSeq
            if (previous == null || seq > previous) {
                lastSeq = seq
                val command = Codec.decodeCommand(cmdJson)
                if (command != null) {
                    lastCommandAtMs = System.currentTimeMillis()
                    Log.i("prefs channel: command seq=$seq ${command.javaClass.simpleName}")
                    EngineRuntime.submit(command, "prefs#$seq")
                } else {
                    Log.w("prefs channel: undecodable sim_cmd at seq=$seq (${cmdJson.length} chars)")
                }
            }
        }

        // 2) config snapshots (cheap: only when the JSON text changes)
        val configJson = all[KEY_CONFIG] as? String
        if (configJson != null && configJson != lastConfigJson) {
            lastConfigJson = configJson
            val config = decodeConfig(configJson)
            if (config != null) {
                Log.i("prefs channel: config applied (${config.mode}, interval ${config.updateIntervalMs}ms)")
                EngineRuntime.submit(Command.SetConfig(config), "prefs-config")
            }
        }

        // 3) active flag as an edge-triggered Start/Stop fallback
        val active = parseBool(all[KEY_ACTIVE]) ?: parseBool(all[LEGACY_KEY_ACTIVE])
        if (active != null) {
            val previous = lastActive
            lastActive = active
            if (previous == null) {
                // First observation: an app that persisted sim_active=true wants the engine running
                // (it may have been written before system_server finished booting).
                if (active) {
                    Log.i("prefs channel: sim_active=true on first observation -> Start")
                    EngineRuntime.submit(Command.Start, "prefs-active-initial")
                }
            } else if (previous != active) {
                Log.i("prefs channel: sim_active -> $active")
                EngineRuntime.submit(if (active) Command.Start else Command.Stop, "prefs-active")
            }
        }
    }

    private fun decodeConfig(json: String): SimulationConfig? = try {
        jsonFormat.decodeFromString(SimulationConfig.serializer(), json)
    } catch (t: Throwable) {
        Log.once("prefs.config.decode", "config decode failed: ${t.javaClass.simpleName}: ${t.message}")
        null
    }

    fun debugPath(): String? = prefsPath

    private fun parseInt(value: Any?): Int? = when (value) {
        is Number -> value.toInt()
        is String -> value.trim().toIntOrNull()
        else -> null
    }

    private fun parseBool(value: Any?): Boolean? = when (value) {
        is Boolean -> value
        is Number -> value.toInt() != 0
        is String -> when (value.trim().lowercase()) {
            "true", "1" -> true
            "false", "0" -> false
            else -> null
        }
        else -> null
    }

    /** Lenient JSON reader for the `sim_config` snapshot (the Codec keeps its own instance). */
    private val jsonFormat = kotlinx.serialization.json.Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
        explicitNulls = false
    }
}

/**
 * Writes engine status for the app UI: `<app files dir>/fw_status.json`, refreshed once per second
 * while running. If SELinux denies it (system_server writing into app data), that is reported once
 * and the app falls back to its own deterministic engine for display.
 */
object StatusWriter {

    const val FILE_NAME = "fw_status.json"

    @Volatile
    private var appFiles: File? = null

    @Volatile
    private var didFail = false

    @Volatile
    private var lastWriteMs = 0L

    /** True once the world-readable status row has been removed after the engine stopped. */
    private var clearedWhileStopped = false

    /**
     * Removes any status row left behind by a previous boot. `Settings.Global` is persistent storage,
     * so a row written before a reboot survives it, and until the next publish it advertises the
     * module to every app on the device.
     */
    fun clearStaleRow() {
        clearedWhileStopped = true
        publishToSettingsGlobals("")
    }

    @Volatile
    var lastPath: String? = null
        private set

    @Volatile
    var writeDenied: Boolean = false
        private set

    fun onState(state: EngineState, force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (!force && now - lastWriteMs < 1000L) return
        lastWriteMs = now
        // `Settings.Global` is world-readable, so a stopped engine must not keep advertising itself:
        // the row is removed once when the engine stops and rewritten when it starts again.
        if (!state.running) {
            if (!clearedWhileStopped) {
                clearedWhileStopped = true
                publishToSettingsGlobals("")
            }
        } else {
            clearedWhileStopped = false
        }
        try {
            val json = buildString(160) {
                append("{\"running\":").append(state.running)
                append(",\"lat\":").append(state.point.lat)
                append(",\"lon\":").append(state.point.lon)
                append(",\"alt\":").append(state.point.alt)
                append(",\"speed\":").append(state.speedMps)
                append(",\"speedMps\":").append(state.speedMps)
                append(",\"bearing\":").append(state.bearingDeg)
                append(",\"bearingDeg\":").append(state.bearingDeg)
                append(",\"steps\":").append(state.steps)
                append(",\"moduleActive\":true")
                append(",\"ts\":").append(now)
                append(",\"moduleVersion\":\"").append(ModuleInfo.version).append("\"}")
            }
            // system_server cannot write into the app's private directory on this ROM (open failed:
            // EACCES, measured 2026-11-02), so the file below is only a fallback. Settings.Global is
            // the transport that actually works cross-uid - the cells channel already proves it.
            publishToSettingsGlobals(json)
            if (writeDenied) return
            val dir = appFiles ?: resolveAppFilesDir() ?: return
            val file = File(dir, FILE_NAME)
            file.writeText(json)
            lastPath = file.absolutePath
            if (didFail) {
                didFail = false
                writeDenied = false
                Log.i("status file recovered: ${file.absolutePath}")
            }
        } catch (t: Throwable) {
            if (!didFail) {
                didFail = true
                writeDenied = true
                Log.e(
                    "status file write DENIED (${t.javaClass.simpleName}: ${t.message}); " +
                        "app should display state from its own engine. path=${lastPath ?: "unresolved"}",
                )
            }
        }
    }

    /** `Settings.Global` key carrying the status JSON; any app can read it, unlike our private files. */
    const val STATE_KEY = "immoral_state"

    private fun publishToSettingsGlobals(json: String) {
        try {
            val context = BridgeServer.hostContext() ?: return
            // An empty payload means "forget us": putString(null) removes the row entirely, which is
            // what we want when the engine stops.
            val value = json.ifBlank { null }
            android.provider.Settings.Global.putString(context.contentResolver, STATE_KEY, value)
        } catch (t: Throwable) {
            // Swallowed on purpose: a failed status publish must never stop the ticker.
            Log.once("status.globals", "Settings.Global publish failed: ${t.javaClass.simpleName}")
        }
    }

    private fun resolveAppFilesDir(): File? {
        val context = BridgeServer.hostContext() ?: return null
        val packages = ArrayList<String>(ModuleInfo.candidateAppPackages)
        if (!packages.contains(ModuleInfo.appPackage)) packages.add(0, ModuleInfo.appPackage)
        for (pkg in packages) {
            try {
                val appContext: Context = context.createPackageContext(pkg, 0)
                val dir = appContext.filesDir
                appFiles = dir
                lastPath = File(dir, FILE_NAME).absolutePath
                Log.i("status file path resolved: ${lastPath}")
                return dir
            } catch (t: Throwable) {
                Log.once("status.ctx.$pkg", "createPackageContext($pkg) failed: ${t.javaClass.simpleName}")
            }
        }
        return null
    }
}
