package com.immoral.location.bridge

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import com.immoral.location.core.model.EngineState
import com.immoral.location.core.model.GeoPoint
import com.immoral.location.core.model.SimulationConfig
import com.immoral.location.core.protocol.Bridge
import com.immoral.location.core.protocol.Codec
import com.immoral.location.core.protocol.Command
import com.immoral.location.core.route.RoutePlan
import com.immoral.location.data.CommandChannel
import com.immoral.location.data.FrameworkStatus
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** What the UI shows in the "模块已激活 / 未激活" chip. */
enum class ModuleLink {
    /** No signal at all yet — we are still probing. */
    CHECKING,

    /** The framework answered: at least one liveness signal is fresh. */
    ACTIVE,

    /** Every liveness signal is stale: the module is not enabled for this app. */
    INACTIVE,
}

/**
 * The app-side control channel.
 *
 * Three transports, in order of reliability:
 *  1. **SharedPreferences port** ([CommandChannel]): every command is mirrored into
 *     `<package>_preferences` (`sim_seq`/`sim_cmd`/`sim_active`/`sim_config`). The framework polls
 *     it every 250 ms. This is the authoritative path — it also works when a `system_server`
 *     broadcast receiver could not be registered.
 *  2. **Broadcasts** (`Bridge.ACTION_COMMAND` → `Bridge.ACTION_REPLY`): kept as an optimisation so
 *     state comes back without waiting for a poll; never blocks the UI.
 *  3. **Files** ([FrameworkStatus]): `filesDir/fw_status.json` for authoritative engine state and
 *     `filesDir/hook_heartbeat` for module liveness.
 *
 * The engine never runs in the app process: this class only encodes intents and decodes status.
 */
class BridgeClient(
    context: Context,
    private val configSupplier: () -> SimulationConfig = { SimulationConfig() },
) {

    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** The SharedPreferences port the framework polls. */
    val channel: CommandChannel = CommandChannel(appContext, configSupplier)
    private val frameworkStatus = FrameworkStatus(appContext)

    private var pingJob: Job? = null
    private var watchJob: Job? = null
    private var registered = false
    private val receiver = BridgeReplyReceiver()

    @Volatile
    private var lastReplyAt = 0L

    @Volatile
    private var everHadSignal = false

    /** Last configuration the engine acknowledged, used to make [setConfig] idempotent. */
    @Volatile
    private var lastSentConfig: SimulationConfig? = null

    private val _engineState = MutableStateFlow<EngineState?>(null)
    /** Last known engine state, from `fw_status.json` when fresh, else from a broadcast reply. */
    val engineState: StateFlow<EngineState?> = _engineState.asStateFlow()

    private val _link = MutableStateFlow(ModuleLink.CHECKING)
    val link: StateFlow<ModuleLink> = _link.asStateFlow()

    private val _engineLoaded = MutableStateFlow(false)
    val engineLoaded: StateFlow<Boolean> = _engineLoaded.asStateFlow()

    private val _moduleVersion = MutableStateFlow("")
    val moduleVersion: StateFlow<String> = _moduleVersion.asStateFlow()

    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    private val _lastReplyAtMs = MutableStateFlow(0L)
    val lastReplyAtMs: StateFlow<Long> = _lastReplyAtMs.asStateFlow()

    private val _lastCommandAtMs = MutableStateFlow(0L)
    val lastCommandAtMs: StateFlow<Long> = _lastCommandAtMs.asStateFlow()

    private data class Pending(val label: String, val atMs: Long)

    /** Commands awaiting a reply, keyed by request id (the framework echoes it back). */
    private val pending = java.util.concurrent.ConcurrentHashMap<String, Pending>()

    private var watchdogJob: Job? = null

    private val _commandFailures = MutableSharedFlow<String>(extraBufferCapacity = 8)
    /** Emits a label whenever a user command got no reply within [REPLY_TIMEOUT_MS]. */
    val commandFailures: SharedFlow<String> = _commandFailures

    private val _pendingCount = MutableStateFlow(0)
    /** Commands still awaiting a reply — surfaced by the status panel when non-zero. */
    val pendingCount: StateFlow<Int> = _pendingCount.asStateFlow()

    private val startedAt = SystemClock.elapsedRealtime()

    // ------------------------------------------------------------- lifecycle

    /** Registers the reply receiver and starts the probe + status watchers. Idempotent. */
    fun start() {
        registerReceiver()
        if (pingJob?.isActive != true) {
            pingJob = scope.launch {
                while (isActive) {
                    send(Command.Ping)
                    delay(PROBE_INTERVAL_MS)
                }
            }
        }
        if (watchJob?.isActive != true) {
            watchJob = scope.launch {
                while (isActive) {
                    evaluate()
                    delay(WATCH_INTERVAL_MS)
                }
            }
        }
        if (watchdogJob?.isActive != true) {
            watchdogJob = scope.launch {
                while (isActive) {
                    val now = SystemClock.elapsedRealtime()
                    val stale = pending.entries.filter { now - it.value.atMs > REPLY_TIMEOUT_MS }
                    if (stale.isNotEmpty()) {
                        stale.forEach { pending.remove(it.key) }
                        val label = stale.maxByOrNull { it.value.atMs }?.value?.label ?: "命令"
                        _lastError.value = "命令未送达：$label"
                        _commandFailures.tryEmit(label)
                    }
                    _pendingCount.value = pending.size
                    delay(WATCHDOG_INTERVAL_MS)
                }
            }
        }
    }

    fun stop() {
        pingJob?.cancel()
        pingJob = null
        watchJob?.cancel()
        watchJob = null
        watchdogJob?.cancel()
        watchdogJob = null
    }

    /** Re-runs the liveness evaluation immediately (used by the refresh affordance). */
    fun probeNow() {
        send(Command.Ping)
        send(Command.GetState)
        evalSynchronously()
    }

    private fun registerReceiver() {
        if (registered) return
        try {
            ContextCompat.registerReceiver(
                appContext,
                receiver,
                IntentFilter(Bridge.ACTION_REPLY),
                // The reply comes from system_server (another uid), so the receiver is exported;
                // the payload is validated by Codec before use.
                ContextCompat.RECEIVER_EXPORTED,
            )
            registered = true
        } catch (t: Throwable) {
            _lastError.value = t.message
        }
    }

    // ---------------------------------------------------------------- sending

    /**
     * Sends [command]. The **broadcast is authoritative and goes out first**: the framework's
     * receiver lives in `system_server`, and on HyperOS the SharedPreferences mirror is not
     * readable from there at all (`/data/misc/apexdata/.../prefs/...` is 0660 app-owned), so the
     * mirror is only kept as best-effort redundancy — a UI action must never depend on it.
     *
     * User-relevant commands are registered in [pending]; if no reply carrying their request id
     * arrives within [REPLY_TIMEOUT_MS] the watchdog emits into [commandFailures], which the UI
     * surfaces as a snackbar instead of silently doing nothing.
     *
     * @return `true` when the broadcast was handed to the system.
     */
    fun send(command: Command): Boolean {
        val payload = try {
            Codec.encodeCommand(command)
        } catch (t: Throwable) {
            _lastError.value = t.message
            return false
        }
        val requestId = UUID.randomUUID().toString()
        val broadcasted = try {
            appContext.sendBroadcast(
                Intent(Bridge.ACTION_COMMAND)
                    // Deliberately NOT package-scoped: the framework registers its receiver on
                    // the system_server ("android") context, so setPackage() would make it drop
                    // the intent. The receiver's signature permission is what guards the channel.
                    .putExtra(Bridge.EXTRA_PAYLOAD, payload)
                    .putExtra(EXTRA_REQUEST_ID, requestId),
            )
            true
        } catch (t: Throwable) {
            _lastError.value = t.message
            false
        }

        if (waitForReply(command)) {
            pending[requestId] = Pending(labelOf(command), SystemClock.elapsedRealtime())
            _pendingCount.value = pending.size
        }

        // Best-effort redundancy only; the framework logs show it cannot read this file on
        // HyperOS, which is exactly why the broadcast above is the authoritative path.
        try {
            channel.publish(command, activeFlagFor(command))
        } catch (t: Throwable) {
            _lastError.value = t.message
        }

        if (broadcasted) {
            _lastCommandAtMs.value = SystemClock.elapsedRealtime()
            Log.d(TAG, "send ${labelOf(command)} (${payload.length}B) pending=${pending.size}")
        }
        return broadcasted
    }

    /** Probes and high-rate streams are fire-and-forget; the rest are user intents. */
    private fun waitForReply(command: Command): Boolean = when (command) {
        Command.Ping, Command.GetState -> false
        is Command.SetHeading, is Command.SetJoystick, Command.ReleaseJoystick -> false
        else -> true
    }

    /** Human-readable label used by the "not delivered" diagnostic. */
    private fun labelOf(command: Command): String = when (command) {
        Command.Ping -> "Ping"
        Command.GetState -> "读取状态"
        is Command.Start -> "开始模拟"
        is Command.Stop -> "停止模拟"
        is Command.SetPosition -> "切换位置"
        is Command.SetJoystick -> "摇杆"
        is Command.ReleaseJoystick -> "松开摇杆"
        is Command.SetHeading -> "朝向"
        is Command.SetConfig -> "应用设置"
        is Command.StartRoute -> "开始路线"
        is Command.PlayRoute -> "播放路线"
        is Command.PauseRoute -> "暂停路线"
        is Command.StopRoute -> "结束路线"
        is Command.ResetSteps -> "重置步数"
    }

    /** Mirrors a config change without sending a command (settings screens call this). */
    fun publishConfig(config: SimulationConfig) {
        try {
            channel.publishConfig(config)
        } catch (t: Throwable) {
            _lastError.value = t.message
        }
    }

    private fun activeFlagFor(command: Command): Boolean? = when (command) {
        Command.Start -> true
        Command.Stop -> false
        is Command.StartRoute -> true
        else -> null
    }

    // ---------------------------------------------------------------- replies

    /** Called by [BridgeReplyReceiver]; safe from any thread. */
    internal fun handleReplyIntent(intent: Intent?) {
        if (intent == null) return
        val raw = intent.getStringExtra(Bridge.EXTRA_PAYLOAD) ?: return
        val reply = Codec.decodeReply(raw) ?: run {
            _lastError.value = "无法解析框架回复"
            return
        }
        // A reply proves the broadcast channel works — resolve the command it answers.
        intent.getStringExtra(EXTRA_REQUEST_ID)?.let { pending.remove(it) }
        _pendingCount.value = pending.size
        lastReplyAt = SystemClock.elapsedRealtime()
        everHadSignal = true
        _lastReplyAtMs.value = lastReplyAt
        _link.value = ModuleLink.ACTIVE
        _engineLoaded.value = reply.engineLoaded
        if (reply.moduleVersion.isNotBlank()) _moduleVersion.value = reply.moduleVersion
        _lastError.value = reply.error
        reply.state?.let { state ->
            _engineState.value = state
            channel.publishState(state)
        }
    }

    // --------------------------------------------------------------- liveness

    private fun evalSynchronously() {
        try {
            evaluate()
        } catch (t: Throwable) {
            // ignore
        }
    }

    /**
     * Combines the three liveness signals. Runs at 2 Hz so the chip follows reality within a
     * second of the framework starting or stopping.
     */
    private fun evaluate() {
        val snapshot = try {
            frameworkStatus.read()
        } catch (t: Throwable) {
            FrameworkStatus.Snapshot()
        }

        if (snapshot.state != null) {
            _engineState.value = snapshot.state
            everHadSignal = true
            channel.publishState(snapshot.state)
        }

        val now = SystemClock.elapsedRealtime()
        val replyAge = if (lastReplyAt == 0L) Long.MAX_VALUE else now - lastReplyAt
        // The heartbeat term is gone: nothing writes `hook_heartbeat` in a framework-only build, so
        // liveness is `fw_status.json` freshness or a recent broadcast reply.
        val alive = snapshot.statusFresh || replyAge <= REPLY_FRESH_MS

        if (alive) {
            everHadSignal = true
            _engineLoaded.value = true
            if (_link.value != ModuleLink.ACTIVE) {
                // A fresh engine may have lost our config (module/process restart): forget what we
                // sent so the next setConfig() re-publishes it instead of being deduplicated.
                lastSentConfig = null
            }
            _link.value = ModuleLink.ACTIVE
        } else {
            val sinceStart = now - startedAt
            _link.value = if (!everHadSignal && sinceStart < GRACE_PERIOD_MS) {
                ModuleLink.CHECKING
            } else {
                ModuleLink.INACTIVE
            }
            _engineLoaded.value = false
        }
    }

    // ------------------------------------------------------- typed shortcuts

    fun requestState() = send(Command.GetState)

    fun setPosition(point: GeoPoint) = send(Command.SetPosition(point))

    fun setJoystick(x: Float, y: Float) = send(Command.SetJoystick(x, y))

    fun releaseJoystick() = send(Command.ReleaseJoystick)

    fun setHeading(deg: Double) = send(Command.SetHeading(deg))

    /**
     * Pushes the configuration, but only when it actually changed since the last acknowledged
     * push. Re-sending an identical config on every tick used to make the app fight the engine
     * (a stale `routeSpeedMps` re-seeded the running route plan).
     */
    fun setConfig(config: SimulationConfig): Boolean {
        val sanitized = config.sanitized()
        if (sanitized == lastSentConfig) return true
        val sent = send(Command.SetConfig(sanitized))
        if (sent) lastSentConfig = sanitized
        return sent
    }

    fun startEngine() = send(Command.Start)

    fun stopEngine() = send(Command.Stop)

    /** Loads and plays [plan]. Call this only on an explicit user action — never on a state tick. */
    fun startRoute(plan: RoutePlan) = send(Command.StartRoute(plan))

    fun playRoute() = send(Command.PlayRoute)

    fun pauseRoute() = send(Command.PauseRoute)

    fun stopRoute() = send(Command.StopRoute)

    fun resetSteps(steps: Long = 0L) = send(Command.ResetSteps(steps))

    companion object {
        private const val TAG = "ImmoralBridge"

        /** Extra carrying the request id, echoed back by cooperative framework builds. */
        const val EXTRA_REQUEST_ID: String = "com.immoral.location.extra.REQUEST_ID"

        /** Broadcast probe period (the prefs port is polled by the framework, not by us). */
        const val PROBE_INTERVAL_MS: Long = 5_000L

        /** Liveness evaluation period. */
        const val WATCH_INTERVAL_MS: Long = 500L

        /** How long a user command may wait for its reply before the UI reports 命令未送达. */
        const val REPLY_TIMEOUT_MS: Long = 1_000L

        /** How often the pending-command watchdog runs. */
        private const val WATCHDOG_INTERVAL_MS: Long = 250L

        /** A broadcast reply younger than this keeps the link alive. */
        const val REPLY_FRESH_MS: Long = 8_000L

        /** Startup grace period before the UI can say 未激活. */
        const val GRACE_PERIOD_MS: Long = 6_000L

        /** Consecutive unanswered probes before the UI reports 未激活 (contract wording). */
        const val MISSED_PROBES_BEFORE_INACTIVE: Int = 3

        @Volatile
        private var activeClient: BridgeClient? = null

        internal fun attach(client: BridgeClient) {
            activeClient = client
        }

        /** Entry point for the statically declared [BridgeReplyReceiver]. */
        fun dispatch(intent: Intent?) {
            activeClient?.handleReplyIntent(intent)
        }
    }

    init {
        attach(this)
    }
}
