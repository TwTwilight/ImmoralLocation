package com.immoral.location.core.protocol

import com.immoral.location.core.model.EngineState
import com.immoral.location.core.model.GeoPoint
import com.immoral.location.core.model.SimulationConfig
import com.immoral.location.core.route.RoutePlan
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Constant identifiers of the app <-> Xposed bridge.
 *
 * The app broadcasts an [Bridge.ACTION_COMMAND] intent carrying an [Codec.encodeCommand] JSON
 * payload; the framework (running inside `system_server`) answers with [Bridge.ACTION_REPLY]
 * and an [Codec.encodeReply] payload. Both sides guard with [Bridge.PERMISSION_CONTROL].
 */
object Bridge {
    /** Package of the Xposed module / app APK. */
    const val MODULE_PACKAGE: String = "io.github.twtwilight.immorallocation"

    /** Signature-level permission the framework requires from control broadcasts. */
    const val PERMISSION_CONTROL: String = "io.github.twtwilight.immorallocation.permission.CONTROL"

    /** app -> framework command broadcast. */
    const val ACTION_COMMAND: String = "com.immoral.location.action.COMMAND"

    /** framework -> app reply broadcast. */
    const val ACTION_REPLY: String = "com.immoral.location.action.REPLY"

    /** Intent extra holding the JSON payload described above. */
    const val EXTRA_PAYLOAD: String = "com.immoral.location.extra.PAYLOAD"
}

/** APK version of the core engine, reported back in [BridgeReply.moduleVersion]. */
const val CORE_ENGINE_VERSION: String = "1.0.0"

/**
 * Every message the app can send to the framework.
 *
 * The wire format is JSON with `"cmd"` as the class discriminator, for example
 * `{"cmd":"SetPosition","point":{"lat":39.9,"lon":116.4,"alt":50.0}}`.
 */
@Serializable
sealed interface Command {
    /** Liveness probe; answered before the engine is even touched. */
    @Serializable
    @SerialName("Ping")
    data object Ping : Command

    /** Requests a full [EngineState] snapshot. */
    @Serializable
    @SerialName("GetState")
    data object GetState : Command

    /**
     * Tells the framework whether the app's own UI is on screen. The engine status row in
     * `Settings.Global` is world-readable, so it is only published while the app is visible: a row
     * that keeps updating in the background would confirm to any app that a spoofer is installed.
     */
    @Serializable
    @SerialName("SetUiVisible")
    data class SetUiVisible(val visible: Boolean) : Command

    /** Replaces the whole simulation configuration (sanitized by the engine). */
    @Serializable
    @SerialName("SetConfig")
    data class SetConfig(val config: SimulationConfig) : Command

    /** Enables spoofing. */
    @Serializable
    @SerialName("Start")
    data object Start : Command

    /** Disables spoofing and restores the real position. */
    @Serializable
    @SerialName("Stop")
    data object Stop : Command

    /** Teleports the simulated position. */
    @Serializable
    @SerialName("SetPosition")
    data class SetPosition(val point: GeoPoint) : Command

    /** Joystick vector: `x` right (+1), `y` forward (+1), each in `-1..1`. */
    @Serializable
    @SerialName("SetJoystick")
    data class SetJoystick(val x: Float, val y: Float) : Command

    /** Stops joystick-driven movement (vector becomes zero). */
    @Serializable
    @SerialName("ReleaseJoystick")
    data object ReleaseJoystick : Command

    /** Device heading in degrees, used to rotate the joystick vector. */
    @Serializable
    @SerialName("SetHeading")
    data class SetHeading(val deg: Double) : Command

    /** Loads and immediately plays [plan]. */
    @Serializable
    @SerialName("StartRoute")
    data class StartRoute(val plan: RoutePlan) : Command

    /** Resumes route playback. */
    @Serializable
    @SerialName("PlayRoute")
    data object PlayRoute : Command

    /** Pauses route playback, keeping the current progress. */
    @Serializable
    @SerialName("PauseRoute")
    data object PauseRoute : Command

    /** Unloads the route and returns to plain static movement. */
    @Serializable
    @SerialName("StopRoute")
    data object StopRoute : Command

    /** Overrides the step counter value. */
    @Serializable
    @SerialName("ResetSteps")
    data class ResetSteps(val steps: Long = 0L) : Command
}

/**
 * Framework answer to a [Command].
 *
 * @property ok `false` when the command was rejected; [error] then explains why.
 * @property state Engine snapshot, when the command produced one.
 * @property engineLoaded `true` when the framework side actually answered, which proves the
 *   Xposed module is active in the target process.
 * @property moduleVersion Version of the loaded module APK.
 */
@Serializable
data class BridgeReply(
    val ok: Boolean = true,
    val error: String? = null,
    val state: EngineState? = null,
    val engineLoaded: Boolean = false,
    val moduleVersion: String = "",
)

/**
 * JSON codec for the bridge protocol.
 *
 * Both directions use the same [Json] instance, so encoding and decoding are guaranteed to be
 * symmetric. Unknown JSON keys are tolerated (forward compatibility between app and module
 * versions), and malformed input is reported as `null` — this codec never throws.
 */
object Codec {
    /** Discriminator property holding the [Command] subtype name. */
    const val DISCRIMINATOR: String = "cmd"

    private val json: Json = Json {
        classDiscriminator = DISCRIMINATOR
        ignoreUnknownKeys = true
        encodeDefaults = true
        isLenient = false
        explicitNulls = false
    }

    /** Serializes [command] to its JSON wire form. */
    fun encodeCommand(command: Command): String = json.encodeToString(Command.serializer(), command)

    /**
     * Parses a [Command].
     *
     * @return the decoded command, or `null` when [json] is malformed, not an object, or carries
     *   an unknown / missing discriminator.
     */
    fun decodeCommand(json: String): Command? = runCatching {
        Codec.json.decodeFromString(Command.serializer(), json)
    }.getOrNull()

    /** Serializes [reply] to its JSON wire form. */
    fun encodeReply(reply: BridgeReply): String = json.encodeToString(BridgeReply.serializer(), reply)

    /**
     * Parses a [BridgeReply].
     *
     * @return the decoded reply, or `null` when the payload is malformed or not a reply object.
     */
    fun decodeReply(json: String): BridgeReply? = runCatching {
        Codec.json.decodeFromString(BridgeReply.serializer(), json)
    }.getOrNull()
}
