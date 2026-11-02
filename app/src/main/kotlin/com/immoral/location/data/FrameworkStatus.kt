package com.immoral.location.data

import android.content.Context
import android.util.Log
import com.immoral.location.core.model.EngineState
import com.immoral.location.core.model.GeoPoint
import com.immoral.location.core.model.MoveMode
import com.immoral.location.core.model.RouteState
import com.immoral.location.core.protocol.Codec
import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * Reads the status files the framework maintains for us:
 *
 *  * `filesDir/fw_status.json` — written ~1 Hz by the `system_server` layer while the engine runs:
 *    the authoritative running flag, position, speed, bearing, steps and route progress.
 *  * `filesDir/hook_heartbeat` — **legacy, no writer any more.** The per-app client layer used to
 *    touch it every 10 s; the module is framework-only now and `system_server` cannot write into the
 *    app's data dir (DAC), so liveness comes from the broadcast reply instead. The field is kept so
 *    older builds keep parsing, but nothing depends on it.
 *
 * Everything here is defensive: missing files, stale files and malformed JSON all degrade to
 * "unknown" instead of throwing, because the app has to run fine on a device without LSPosed.
 */
class FrameworkStatus(context: Context) {

    private val resolver = context.contentResolver
    private val statusFile = File(context.filesDir, STATUS_FILE)
    private val heartbeatFile = File(context.filesDir, HEARTBEAT_FILE)
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        isLenient = true
    }

    /** Snapshot of one poll. */
    data class Snapshot(
        val state: EngineState? = null,
        val statusAgeMs: Long = Long.MAX_VALUE,
        val heartbeatAgeMs: Long = Long.MAX_VALUE,
    ) {
        val statusFresh: Boolean get() = statusAgeMs <= STATUS_FRESH_MS
        val heartbeatFresh: Boolean get() = heartbeatAgeMs <= HEARTBEAT_FRESH_MS
    }

    fun read(nowMs: Long = System.currentTimeMillis()): Snapshot {
        val state = readStatusState(nowMs)
        return Snapshot(
            state = state,
            statusAgeMs = ageOf(statusFile, nowMs),
            heartbeatAgeMs = ageOf(heartbeatFile, nowMs),
        )
    }

    private fun ageOf(file: File, nowMs: Long): Long = try {
        if (file.exists()) (nowMs - file.lastModified()).coerceAtLeast(0L) else Long.MAX_VALUE
    } catch (t: Throwable) {
        Long.MAX_VALUE
    }

    /**
     * The transport that actually works cross-uid: system_server cannot write into our private
     * directory on this ROM (EACCES), so the hook publishes the same JSON into Settings.Global and
     * we read it here. Without this the UI freezes whenever no command is being sent - which is
     * exactly what happens while a planned route drives the engine on its own.
     */
    private fun readGlobalState(nowMs: Long): EngineState? {
        val raw = try {
            android.provider.Settings.Global.getString(resolver, STATE_KEY_GLOBAL)
        } catch (t: Throwable) {
            Log.w(TAG, "Settings.Global read failed: " + t.javaClass.simpleName, t)
            return null
        } ?: return null
        if (raw.isBlank()) return null
        val ts = GLOBAL_TS.find(raw)?.groupValues?.get(1)?.toLongOrNull() ?: nowMs
        if (nowMs - ts > STATUS_FRESH_MS) return null
        return decodeState(raw) ?: decodeReplyState(raw) ?: decodeLoose(raw)
    }

    private fun readStatusState(nowMs: Long): EngineState? {
        readGlobalState(nowMs)?.let { return it }
        return try {
            if (!statusFile.exists()) return null
            if (nowMs - statusFile.lastModified() > STATUS_FRESH_MS) return null
            val text = statusFile.readText()
            if (text.isBlank()) return null
            // Two shapes are accepted: a full EngineState, or a BridgeReply wrapping one, or a
            // loose object with the same field names in snake_case.
            decodeState(text) ?: decodeReplyState(text) ?: decodeLoose(text)
        } catch (t: Throwable) {
            Log.w(TAG, "fw_status.json unreadable", t)
            null
        }
    }

    private fun decodeState(text: String): EngineState? = runCatching {
        json.decodeFromString(EngineState.serializer(), text)
    }.getOrNull()

    private fun decodeReplyState(text: String): EngineState? =
        Codec.decodeReply(text)?.state

    /** Last-resort parser: reads the handful of fields the UI needs from any JSON object. */
    private fun decodeLoose(text: String): EngineState? = runCatching {
        val root = json.parseToJsonElement(text).let {
            (it as? JsonObject) ?: return@runCatching null
        }
        fun num(vararg keys: String): Double? {
            for (key in keys) {
                val value = (root[key] as? kotlinx.serialization.json.JsonPrimitive) ?: continue
                value.doubleOrNull?.let { return it }
            }
            return null
        }
        fun bool(vararg keys: String): Boolean? {
            for (key in keys) {
                val value = (root[key] as? kotlinx.serialization.json.JsonPrimitive) ?: continue
                value.booleanOrNull?.let { return it }
            }
            return null
        }
        fun long(vararg keys: String): Long? {
            for (key in keys) {
                val value = (root[key] as? kotlinx.serialization.json.JsonPrimitive) ?: continue
                value.longOrNull?.let { return it }
            }
            return null
        }
        val lat = num("lat", "latitude") ?: return@runCatching null
        val lon = num("lon", "lng", "longitude") ?: return@runCatching null
        EngineState(
            running = bool("running", "active") ?: false,
            mode = when (root["mode"]?.jsonPrimitive?.content?.uppercase()) {
                "JOYSTICK" -> MoveMode.JOYSTICK
                "ROUTE" -> MoveMode.ROUTE
                else -> MoveMode.STATIC
            },
            routeState = when (root["routeState"]?.jsonPrimitive?.content?.uppercase()
                ?: root["route_state"]?.jsonPrimitive?.content?.uppercase()) {
                "PLAYING" -> RouteState.PLAYING
                "PAUSED" -> RouteState.PAUSED
                "FINISHED" -> RouteState.FINISHED
                else -> RouteState.IDLE
            },
            point = GeoPoint(lat, lon, num("alt", "altitude") ?: 50.0),
            speedMps = num("speedMps", "speed") ?: 0.0,
            bearingDeg = num("bearingDeg", "bearing", "bearing_deg") ?: 0.0,
            steps = long("steps", "stepCount") ?: 0L,
            headingDeg = num("headingDeg", "heading") ?: 0.0,
            routeProgress = num("routeProgress", "route_progress") ?: 0.0,
            updatedAtMs = long("updatedAtMs", "updated_at") ?: 0L,
            engineVersion = root["engineVersion"]?.jsonPrimitive?.content ?: "",
        )
    }.getOrNull()

    companion object {
        private const val TAG = "FrameworkStatus"

        /** Status file name; must match `:hook`'s writer. */
        const val STATUS_FILE = "fw_status.json"

        /** Heartbeat file name; must match `:hook`'s client layer. */
        const val HEARTBEAT_FILE = "hook_heartbeat"

    /** Settings.Global key the hook publishes the same status JSON into (works cross-uid). */
    const val STATE_KEY_GLOBAL = "immoral_state"

    val GLOBAL_TS = Regex("\"ts\"\\s*:\\s*(\\d{10,})")

        /** A status file older than this is ignored (the framework polls at 1 Hz). */
        const val STATUS_FRESH_MS = 3_500L

        /** The client layer touches the heartbeat every 10 s; 30 s of silence means inactive. */
        const val HEARTBEAT_FRESH_MS = 30_000L
    }
}





