package com.immoral.location.data

import android.content.Context
import com.immoral.location.core.model.EngineState
import com.immoral.location.core.model.SimulationConfig
import com.immoral.location.core.protocol.Codec
import com.immoral.location.core.protocol.Command
import kotlinx.serialization.json.Json

/**
 * The authoritative control channel to the framework.
 *
 * A broadcast receiver inside `system_server` cannot always be registered (boot timing, LSPosed
 * application timing), but the framework can *read* this app's default SharedPreferences file at
 * any time. So every command is mirrored here as a small "port":
 *
 * ```
 * sim_seq      Int      incremented for every command (the hook polls this counter)
 * sim_cmd      String   Codec.encodeCommand(command) JSON of that command
 * sim_config   String   JSON of the current SimulationConfig
 * sim_active   Boolean  whether the engine should be running
 * sim_lat/lon/alt/steps  latest simulated values (UI restore + independent-mode hooks)
 * ```
 *
 * Writes use [android.content.SharedPreferences.Editor.commit] because the hook polls at 250 ms and
 * `apply()` may land much later. The sequence bump is synchronized so two threads can never publish
 * the same seq with different payloads.
 *
 * Joystick vectors are throttled: at 60 Hz of drag events we would fsync 60 times per second. The
 * hook only samples every 250 ms, so ~10 Hz is more than enough and keeps the drag smooth.
 */
class CommandChannel(
    context: Context,
    private val configSupplier: () -> SimulationConfig,
) {

    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(
        appContext.packageName + PREFS_SUFFIX,
        Context.MODE_PRIVATE,
    )
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val lock = Any()
    private var seq: Int = prefs.getInt(KEY_SEQ, 0)
    private var lastJoystickWriteAt = 0L
    private var lastStateWriteAt = 0L

    /** Publishes [command] and bumps `sim_seq`. Returns the sequence number that was written. */
    fun publish(command: Command, active: Boolean? = null): Int {
        if (command is Command.SetJoystick) {
            val now = android.os.SystemClock.elapsedRealtime()
            if (now - lastJoystickWriteAt < JOYSTICK_MIN_INTERVAL_MS) return seq
            lastJoystickWriteAt = now
        }
        val payload = try {
            Codec.encodeCommand(command)
        } catch (t: Throwable) {
            return seq
        }
        val configJson = try {
            json.encodeToString(SimulationConfig.serializer(), configSupplier())
        } catch (t: Throwable) {
            null
        }
        synchronized(lock) {
            seq += 1
            val editor = prefs.edit()
                .putInt(KEY_SEQ, seq)
                .putString(KEY_CMD, payload)
            if (configJson != null) editor.putString(KEY_CONFIG, configJson)
            if (active != null) editor.putBoolean(KEY_ACTIVE, active)
            val point = currentPoint()
            if (point != null) {
                editor.putString(KEY_LAT, format(point.first))
                editor.putString(KEY_LON, format(point.second))
            }
            editor.commit()
            return seq
        }
    }

    /** Writes `sim_config` alone (used when the user edits a setting, no command involved). */
    fun publishConfig(config: SimulationConfig) {
        try {
            val payload = json.encodeToString(SimulationConfig.serializer(), config)
            prefs.edit().putString(KEY_CONFIG, payload).commit()
        } catch (t: Throwable) {
            // ignore
        }
    }

    /** Mirrors the latest simulated values for UI restore and the client-process hooks. */
    fun publishState(state: EngineState?) {
        if (state == null) return
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastStateWriteAt < STATE_MIN_INTERVAL_MS) return
        lastStateWriteAt = now
        try {
            prefs.edit()
                .putString(KEY_LAT, format(state.point.lat))
                .putString(KEY_LON, format(state.point.lon))
                .putString(KEY_ALT, format(state.point.alt))
                .putString(KEY_STEPS, state.steps.toString())
                .putBoolean(KEY_ACTIVE, state.running)
                .commit()
        } catch (t: Throwable) {
            // ignore
        }
    }

    /** Clears `sim_active` without publishing a command (engine went away). */
    fun publishInactive() {
        try {
            prefs.edit().putBoolean(KEY_ACTIVE, false).commit()
        } catch (t: Throwable) {
            // ignore
        }
    }

    private fun currentPoint(): Pair<Double, Double>? {
        val lat = prefs.getString(KEY_LAT, null)?.toDoubleOrNull() ?: return null
        val lon = prefs.getString(KEY_LON, null)?.toDoubleOrNull() ?: return null
        return lat to lon
    }

    private fun format(value: Double): String =
        if (value.isFinite()) String.format(java.util.Locale.US, "%.6f", value) else "0.000000"

    companion object {
        const val PREFS_SUFFIX = "_preferences"

        const val KEY_SEQ = "sim_seq"
        const val KEY_CMD = "sim_cmd"
        const val KEY_CONFIG = "sim_config"
        const val KEY_ACTIVE = "sim_active"
        const val KEY_LAT = "sim_lat"
        const val KEY_LON = "sim_lon"
        const val KEY_ALT = "sim_alt"
        const val KEY_STEPS = "sim_steps"

        private const val JOYSTICK_MIN_INTERVAL_MS = 100L

        /** State mirroring is for the UI/hook, not for smooth animation — 1 Hz is plenty. */
        private const val STATE_MIN_INTERVAL_MS = 1_000L
    }
}
