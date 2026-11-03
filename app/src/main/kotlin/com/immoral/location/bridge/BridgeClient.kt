package com.immoral.location.bridge

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.IBinder
import android.os.RemoteException
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import com.immoral.location.core.model.EngineState
import com.immoral.location.core.model.GeoPoint
import com.immoral.location.core.model.SimulationConfig
import com.immoral.location.core.protocol.Bridge
import com.immoral.location.core.protocol.CORE_ENGINE_VERSION
import com.immoral.location.core.protocol.IPC_API_VERSION
import com.immoral.location.core.route.RoutePlan
import com.immoral.location.ipc.IImmoralEngine
import com.immoral.location.ipc.IImmoralStateCallback
import com.immoral.location.ipc.WireResult
import com.immoral.location.ipc.WireState
import com.immoral.location.ipc.toCore
import com.immoral.location.ipc.toWire
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
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
    /** No binder yet — we are still handshaking (or within the startup grace period). */
    CHECKING,

    /** A live `IImmoralEngine` is attached and answering. */
    ACTIVE,

    /** No engine: the module is not enabled for this app, or it died. */
    INACTIVE,
}

/**
 * The app-side end of the binder bridge.
 *
 * One transport, no fallback: the app broadcasts [Bridge.ACTION_HELLO] (a signature-permission
 * broadcast, **not** package-scoped, because the framework's receiver lives on the `system_server`
 * "android" context), the framework answers with [Bridge.ACTION_HELLO_REPLY] carrying the
 * `IImmoralEngine` binder in its extras, and everything after that — every command and every state
 * push — is a binder transaction. The engine is deliberately absent from `ServiceManager`, so this
 * greeting is the only way to reach it.
 *
 * Lifecycle:
 *  * [start] registers the reply receiver and kicks the handshake; the handshake is retried with an
 *    exponential backoff (1 s → 8 s) for as long as no engine is attached;
 *  * the engine is probed with `ping()` every [PROBE_INTERVAL_MS] — a dead transaction, a dead
 *    binder or a `SecurityException` all drop the link and restart the handshake;
 *  * commands are never queued. If there is no engine a command fails *now* with a message, so a
 *    link that comes back up cannot replay a batch of stale intents (the old SharedPreferences port
 *    did exactly that and moved the user's marker after a module restart).
 */
class BridgeClient(context: Context) {

    private val appContext: Context = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Volatile
    private var engine: IImmoralEngine? = null

    @Volatile
    private var engineBinder: IBinder? = null

    private var linkJob: Job? = null
    private var graceJob: Job? = null

    private var receiverRegistered = false

    /** Monotonic request id handed to every mutating call that reports an outcome. */
    private val requestIds = AtomicLong(0L)

    /** Highest state sequence adopted so far; older pushes are dropped. */
    private val lastSeq = AtomicLong(0L)

    private class PendingCall(val label: String, val atMs: Long)

    /** Commands awaiting `onResult`, keyed by request id. */
    private val pending = ConcurrentHashMap<Long, PendingCall>()

    /** Last configuration the engine was told about, used to make [setConfig] idempotent. */
    @Volatile
    private var lastSentConfig: SimulationConfig? = null

    private val _engineState = MutableStateFlow<EngineState?>(null)
    /** Newest engine snapshot known to the app; `null` until the first push / pull. */
    val engineState: StateFlow<EngineState?> = _engineState.asStateFlow()

    private val _link = MutableStateFlow(ModuleLink.CHECKING)
    val link: StateFlow<ModuleLink> = _link.asStateFlow()

    private val _moduleVersion = MutableStateFlow("")
    val moduleVersion: StateFlow<String> = _moduleVersion.asStateFlow()

    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    private val _commandFailures = MutableSharedFlow<String>(extraBufferCapacity = 8)
    /** A user command failed (rejected, refused or undeliverable); the UI raises it as a snackbar. */
    val commandFailures: SharedFlow<String> = _commandFailures

    // ------------------------------------------------------------- handshake

    private val helloReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent == null) return
            try {
                handleHelloReply(intent)
            } catch (t: Throwable) {
                _lastError.value = describe(t)
            }
        }
    }

    private val deathRecipient = IBinder.DeathRecipient {
        Log.w(TAG, "engine binder died")
        dropLink("模块连接已断开")
    }

    /**
     * Registers the handshake reply receiver and starts the link loop. Idempotent.
     */
    fun start() {
        registerReceiver()
        sendHello()
        if (linkJob?.isActive != true) {
            linkJob = scope.launch { runLink() }
        }
        if (graceJob?.isActive != true) {
            graceJob = scope.launch {
                delay(GRACE_PERIOD_MS)
                if (engine == null && _link.value == ModuleLink.CHECKING) {
                    _link.value = ModuleLink.INACTIVE
                }
            }
        }
    }

    fun stop() {
        linkJob?.cancel()
        linkJob = null
        graceJob?.cancel()
        graceJob = null
    }

    private fun registerReceiver() {
        if (receiverRegistered) return
        try {
            ContextCompat.registerReceiver(
                appContext,
                helloReceiver,
                IntentFilter(Bridge.ACTION_HELLO_REPLY),
                // Only a sender holding our signature permission may answer the greeting, so a
                // third-party app cannot hand us a hostile IImmoralEngine proxy.
                Bridge.PERMISSION_CONTROL,
                null,
                ContextCompat.RECEIVER_EXPORTED,
            )
            receiverRegistered = true
        } catch (t: Throwable) {
            _lastError.value = describe(t)
        }
    }

    /**
     * Asks the framework for the engine binder.
     *
     * Deliberately **not** package-scoped: the framework registers its receiver on the
     * `system_server` "android" context, and `setPackage()` would make the broadcast undeliverable.
     * The permission on both sides is the gate, not the package name.
     */
    private fun sendHello() {
        val requestId = UUID.randomUUID().toString()
        try {
            appContext.sendBroadcast(
                Intent(Bridge.ACTION_HELLO).putExtra(Bridge.EXTRA_REQUEST_ID, requestId),
            )
        } catch (t: Throwable) {
            _lastError.value = describe(t)
        }
    }

    private fun handleHelloReply(intent: Intent) {
        // `putBinder` writes a binder into the extras bundle, so `getBinder` is the matching read.
        val binder = intent.extras?.getBinder(Bridge.EXTRA_BINDER)
        if (binder == null) {
            _lastError.value = "模块回复中没有引擎接口"
            return
        }
        if (binder === engineBinder && engine != null) return
        val iface = IImmoralEngine.Stub.asInterface(binder)
        if (iface == null) {
            _lastError.value = "无法解析模块引擎接口"
            return
        }
        attach(binder, iface)
    }

    private fun attach(binder: IBinder, iface: IImmoralEngine) {
        // Parcelable layouts are positional: a framework built against another `Wire.kt` would
        // decode our structs into garbage instead of failing, so refuse a mismatched protocol.
        val version = try {
            iface.apiVersion()
        } catch (t: RemoteException) {
            _lastError.value = describe(t)
            return
        }
        if (version != IPC_API_VERSION) {
            Log.w(TAG, "engine apiVersion=$version expected=$IPC_API_VERSION")
            _lastError.value = "模块版本不匹配，请重启设备"
            _link.value = ModuleLink.INACTIVE
            return
        }
        try {
            binder.linkToDeath(deathRecipient, 0)
        } catch (t: RemoteException) {
            _lastError.value = "模块连接已断开"
            return
        }

        engineBinder = binder
        engine = iface
        _moduleVersion.value = CORE_ENGINE_VERSION
        // A re-attached framework may have lost our config along with its process: forget what was
        // pushed so the next setConfig() is not deduplicated away.
        lastSentConfig = null
        lastSeq.set(0L)

        try {
            iface.registerCallback(stateCallback, CALLBACK_INTERVAL_MS)
        } catch (t: Throwable) {
            dropLink(describe(t))
            return
        }
        pullSnapshot()
        _link.value = ModuleLink.ACTIVE
        _lastError.value = null
        Log.d(TAG, "engine attached (api=$version, version=$CORE_ENGINE_VERSION)")
    }

    /** Drops the current engine and lets the link loop start a new handshake. */
    private fun dropLink(reason: String?) {
        if (reason != null) _lastError.value = reason
        val binder = engineBinder
        engine = null
        engineBinder = null
        _moduleVersion.value = ""
        pending.clear()
        lastSentConfig = null
        lastSeq.set(0L)
        if (binder != null) {
            runCatching { binder.unlinkToDeath(deathRecipient, 0) }
        }
        _link.value = ModuleLink.INACTIVE
    }

    // ------------------------------------------------------------ keep-alive

    private suspend fun runLink() {
        var backoff = BACKOFF_MIN_MS
        while (currentCoroutineContext().isActive) {
            if (engine == null) {
                // [start] already greeted once; give the reply this window before greeting again,
                // then retry with an exponential backoff so a missing module costs one broadcast
                // per 8 s instead of a busy loop.
                delay(backoff)
                if (engine != null) {
                    backoff = BACKOFF_MIN_MS
                    continue
                }
                sendHello()
                backoff = (backoff * 2).coerceAtMost(BACKOFF_MAX_MS)
                continue
            }
            delay(PROBE_INTERVAL_MS)
            prunePending()
            val iface = engine ?: continue
            try {
                iface.ping()
            } catch (t: Throwable) {
                Log.w(TAG, "ping failed: ${describe(t)}")
                dropLink(describe(t))
                backoff = BACKOFF_MIN_MS
            }
        }
    }

    /** Forgets results that will never come, so a dead engine cannot leak labels forever. */
    private fun prunePending() {
        if (pending.isEmpty()) return
        val now = SystemClock.elapsedRealtime()
        for ((id, call) in pending) {
            if (now - call.atMs > PENDING_TTL_MS) pending.remove(id, call)
        }
    }

    // --------------------------------------------------------------- states

    private val stateCallback = object : IImmoralStateCallback.Stub() {

        override fun onState(state: WireState?, seq: Long) {
            if (state == null) return
            // Pushes are coalesced on the framework side and may arrive out of order; only a
            // strictly newer sequence may move the UI, so it never jumps backwards.
            while (true) {
                val previous = lastSeq.get()
                if (seq <= previous) return
                if (lastSeq.compareAndSet(previous, seq)) break
            }
            _engineState.value = state.toCore()
        }

        override fun onResult(requestId: Long, code: Int, message: String?) {
            val call = pending.remove(requestId) ?: return
            if (code == WireResult.OK) {
                _lastError.value = null
                return
            }
            val detail = message?.takeIf { it.isNotBlank() } ?: RESULT_TEXT[code] ?: "未知错误"
            _lastError.value = detail
            _commandFailures.tryEmit("${call.label} 失败：$detail")
        }

        override fun onEngineRestarted(reason: Int) {
            _engineState.value = null
            lastSeq.set(0L)
            pending.clear()
            lastSentConfig = null
            pullSnapshot()
        }
    }

    /**
     * Reads the snapshot without waiting for the next push (attach, restart, manual refresh).
     *
     * A push that lands while the transaction is in flight is newer by definition, so the pulled
     * snapshot is only adopted when no push advanced the sequence meanwhile.
     */
    private fun pullSnapshot() {
        val iface = engine ?: return
        scope.launch {
            val before = lastSeq.get()
            val state = try {
                iface.snapshot()
            } catch (t: Throwable) {
                dropLink(describe(t))
                return@launch
            }
            if (state != null && lastSeq.get() == before) {
                _engineState.value = state.toCore()
            }
        }
    }

    // ------------------------------------------------------------- commands

    /**
     * Runs one binder command.
     *
     * @param userFacing `true` for an explicit user intent: the request id is remembered so
     *   `onResult` can name the command in a snackbar, and an undeliverable command is reported.
     *   `false` for high-rate signals (joystick, heading) which must stay silent.
     * @return `true` when the transaction was handed to the engine.
     */
    private fun call(
        label: String,
        userFacing: Boolean,
        block: (IImmoralEngine, Long) -> Unit,
    ): Boolean {
        val iface = engine
        if (iface == null) {
            if (userFacing) {
                _lastError.value = "模块未连接"
                _commandFailures.tryEmit("$label 失败：模块未连接")
            }
            return false
        }
        val requestId = requestIds.incrementAndGet()
        if (userFacing) pending[requestId] = PendingCall(label, SystemClock.elapsedRealtime())
        return try {
            block(iface, requestId)
            true
        } catch (t: RemoteException) {
            failCommand(label, t, userFacing)
            false
        } catch (t: SecurityException) {
            failCommand(label, t, userFacing)
            false
        } catch (t: IllegalStateException) {
            failCommand(label, t, userFacing)
            false
        }
    }

    /** The transport itself broke: drop the link so the loop re-handshakes from scratch. */
    private fun failCommand(label: String, t: Throwable, userFacing: Boolean) {
        val message = describe(t)
        dropLink(message)
        if (userFacing) _commandFailures.tryEmit("$label 失败：$message")
    }

    private fun describe(t: Throwable): String =
        t.message?.takeIf { it.isNotBlank() } ?: t.javaClass.simpleName

    // ---------------------------------------------------- typed shortcuts

    /** Re-reads the engine snapshot (after the link came up, or from a refresh affordance). */
    fun requestState() {
        if (engine == null) sendHello() else pullSnapshot()
    }

    fun setPosition(point: GeoPoint) = call(LABEL_POSITION, userFacing = true) { iface, id ->
        iface.setPosition(point.toWire(), id)
    }

    fun setJoystick(x: Float, y: Float) = call(LABEL_JOYSTICK, userFacing = false) { iface, _ ->
        iface.setJoystick(x, y)
    }

    fun releaseJoystick() = call(LABEL_JOYSTICK_RELEASE, userFacing = false) { iface, _ ->
        iface.releaseJoystick()
    }

    fun setHeading(deg: Double) = call(LABEL_HEADING, userFacing = false) { iface, _ ->
        iface.setHeading(deg)
    }

    /**
     * Pushes the configuration, but only when it actually changed since the last successful push.
     * Re-sending an identical config on every tick used to make the app fight the engine (a stale
     * `routeSpeedMps` re-seeded the running route plan).
     *
     * @return whether the configuration is now known to the engine (`true` also when it was
     *   already up to date, or when there is no engine to push to — the caller has nothing to do).
     */
    fun setConfig(config: SimulationConfig): Boolean {
        val sanitized = config.sanitized()
        if (sanitized == lastSentConfig) return true
        val sent = call(LABEL_CONFIG, userFacing = true) { iface, id ->
            iface.setConfig(sanitized.toWire(), id)
        }
        if (sent) lastSentConfig = sanitized
        return sent
    }

    fun startEngine() = call(LABEL_START, userFacing = true) { iface, id -> iface.start(id) }

    fun stopEngine() = call(LABEL_STOP, userFacing = true) { iface, id -> iface.stop(id) }

    /** Loads and plays [plan]. Call this only on an explicit user action — never on a state tick. */
    fun startRoute(plan: RoutePlan) = call(LABEL_ROUTE_START, userFacing = true) { iface, id ->
        iface.startRoute(plan.toWire(), id)
    }

    fun playRoute() = call(LABEL_ROUTE_PLAY, userFacing = true) { iface, id ->
        iface.playRoute(id)
    }

    fun pauseRoute() = call(LABEL_ROUTE_PAUSE, userFacing = true) { iface, id ->
        iface.pauseRoute(id)
    }

    fun stopRoute() = call(LABEL_ROUTE_STOP, userFacing = true) { iface, id ->
        iface.stopRoute(id)
    }

    fun resetSteps(steps: Long = 0L) = call(LABEL_STEPS, userFacing = true) { iface, id ->
        iface.resetSteps(steps, id)
    }

    companion object {
        private const val TAG = "ImmoralBridge"

        /** Liveness probe period. */
        const val PROBE_INTERVAL_MS: Long = 5_000L

        /** Minimum callback push interval requested from the engine. */
        const val CALLBACK_INTERVAL_MS: Int = 250

        /** Startup window during which the chip stays 连接中 instead of claiming 未激活. */
        const val GRACE_PERIOD_MS: Long = 6_000L

        /** Handshake retry floor. */
        const val BACKOFF_MIN_MS: Long = 1_000L

        /** Handshake retry ceiling. */
        const val BACKOFF_MAX_MS: Long = 8_000L

        /** A command result older than this is presumed lost and its label is dropped. */
        private const val PENDING_TTL_MS: Long = 15_000L

        private const val LABEL_START = "开始模拟"
        private const val LABEL_STOP = "停止模拟"
        private const val LABEL_POSITION = "切换位置"
        private const val LABEL_CONFIG = "应用设置"
        private const val LABEL_JOYSTICK = "摇杆"
        private const val LABEL_JOYSTICK_RELEASE = "松开摇杆"
        private const val LABEL_HEADING = "朝向"
        private const val LABEL_ROUTE_START = "开始路线"
        private const val LABEL_ROUTE_PLAY = "播放路线"
        private const val LABEL_ROUTE_PAUSE = "暂停路线"
        private const val LABEL_ROUTE_STOP = "结束路线"
        private const val LABEL_STEPS = "重置步数"

        /** Fallback wording for the `onResult` codes the engine may answer with. */
        private val RESULT_TEXT: Map<Int, String> = mapOf(
            WireResult.INVALID_ARGUMENT to "参数无效",
            WireResult.UNAVAILABLE to "模块不可用",
            WireResult.INTERNAL_ERROR to "模块内部错误",
            WireResult.NOT_RUNNING to "引擎未运行",
            WireResult.TOO_LARGE to "数据过大",
        )
    }
}
