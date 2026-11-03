package com.immoral.location.hook

import android.os.Binder
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Process
import android.os.RemoteException
import android.os.SystemClock
import com.immoral.location.core.protocol.CORE_ENGINE_VERSION
import com.immoral.location.core.protocol.IPC_API_VERSION
import com.immoral.location.ipc.IImmoralEngine
import com.immoral.location.ipc.IImmoralStateCallback
import com.immoral.location.ipc.WireConfig
import com.immoral.location.ipc.WireDiagnostics
import com.immoral.location.ipc.WirePoint
import com.immoral.location.ipc.WireRestart
import com.immoral.location.ipc.WireResult
import com.immoral.location.ipc.WireRoute
import com.immoral.location.ipc.WireState
import com.immoral.location.ipc.toCore
import com.immoral.location.ipc.toWire
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong

/**
 * The `IImmoralEngine` implementation handed to the app by [BridgeHost].
 *
 * ## Why this shape
 * Every mutating call is `oneway` and simply *sets* a state; results that matter arrive on
 * [IImmoralStateCallback.onResult]. There is no command bus, no mirrored command file, no
 * world-readable status row — so there is nothing that can be delivered twice, reordered, or
 * read back stale.
 *
 * ## Threading
 *  * binder threads run only [authorized] and [EngineRuntime.post]; they never touch the engine,
 *  * the engine thread publishes snapshots, which are coalesced per subscriber here,
 *  * a dedicated callback thread performs the outbound transactions, so a slow or dying client
 *    can never stall the engine (and the engine thread never blocks on binder).
 */
object FrameworkBinder {

    /** Hard cap for an incoming route, well below the 1 MB binder transaction limit. */
    private const val MAX_ROUTE_POINTS = 20_000

    /** Default coalescing floor when a client does not ask for one. */
    private const val DEFAULT_MIN_INTERVAL_MS = 250

    /** Monotonic snapshot sequence; the client keeps the newest and ignores older pushes. */
    private val sequence = AtomicLong()

    private val subscriptions = CopyOnWriteArrayList<Sub>()

    @Volatile
    private var dispatchHandler: Handler? = null

    private val engine: IImmoralEngine = object : IImmoralEngine.Stub() {

        override fun apiVersion(): Int {
            assertAuthorized()
            return IPC_API_VERSION
        }

        override fun ping() {
            // Deliberately the cheapest possible transaction: liveness only.
            if (!authorized()) return
        }

        override fun start(requestId: Long) = submit(requestId) { EngineRuntime.startEngine() }

        override fun stop(requestId: Long) = submit(requestId) { EngineRuntime.stopEngine() }

        override fun setPosition(point: WirePoint, requestId: Long) = submit(requestId) {
            EngineRuntime.setPosition(point.toCore())
        }

        override fun setConfig(config: WireConfig, requestId: Long) = submit(requestId) {
            EngineRuntime.setConfig(config.toCore())
        }

        override fun setJoystick(x: Float, y: Float) {
            if (!authorized()) return
            EngineRuntime.post { EngineRuntime.setJoystick(x, y) }
        }

        override fun releaseJoystick() {
            if (!authorized()) return
            EngineRuntime.post { EngineRuntime.releaseJoystick() }
        }

        override fun setHeading(deg: Double) {
            if (!authorized()) return
            EngineRuntime.post { EngineRuntime.setHeading(deg) }
        }

        override fun startRoute(route: WireRoute, requestId: Long) = submit(requestId) {
            if (route.points.size > MAX_ROUTE_POINTS) {
                WireResult.TOO_LARGE
            } else {
                EngineRuntime.startRoute(route.toCore())
            }
        }

        override fun playRoute(requestId: Long) = submit(requestId) { EngineRuntime.playRoute() }

        override fun pauseRoute(requestId: Long) = submit(requestId) { EngineRuntime.pauseRoute() }

        override fun stopRoute(requestId: Long) = submit(requestId) { EngineRuntime.stopRoute() }

        override fun resetSteps(steps: Long, requestId: Long) = submit(requestId) {
            EngineRuntime.resetSteps(steps)
        }

        override fun snapshot(): WireState {
            assertAuthorized()
            return EngineRuntime.currentWireState() ?: emptyState()
        }

        override fun diagnostics(): WireDiagnostics {
            assertAuthorized()
            return EngineRuntime.currentDiagnostics()
        }

        override fun registerCallback(callback: IImmoralStateCallback?, minIntervalMs: Int) {
            if (!authorized() || callback == null) return
            subscribe(callback, minIntervalMs)
        }

        override fun unregisterCallback(callback: IImmoralStateCallback?) {
            if (callback == null) return
            val token = try {
                callback.asBinder()
            } catch (_: Throwable) {
                return
            }
            subscriptions.firstOrNull { it.token === token }?.let { remove(it, "client unregistered") }
        }
    }

    /** The object handed out inside the handshake reply. */
    val engineInterface: IImmoralEngine get() = engine

    fun install() {
        EngineRuntime.addStateListener { state -> dispatchState(state) }
        Log.i("binder engine ready (api=$IPC_API_VERSION engine=$CORE_ENGINE_VERSION)")
    }

    // ------------------------------------------------------------------ dispatch

    private class Sub(
        val callback: IImmoralStateCallback,
        val token: IBinder,
        @Volatile var minIntervalMs: Int,
    ) {
        @Volatile
        var lastSentAtMs: Long = 0L

        @Volatile
        var pendingState: WireState? = null

        @Volatile
        var pendingSeq: Long = 0L

        @Volatile
        var inFlight: Boolean = false

        val deathRecipient: IBinder.DeathRecipient = IBinder.DeathRecipient {
            remove(this, "client binder died")
        }
    }

    private fun handler(): Handler {
        dispatchHandler?.let { return it }
        synchronized(this) {
            dispatchHandler?.let { return it }
            val thread = HandlerThread("ImmoralLocation-binder").also { it.start() }
            val h = Handler(thread.looper)
            dispatchHandler = h
            return h
        }
    }

    private fun subscribe(callback: IImmoralStateCallback, minIntervalMs: Int) {
        val token = try {
            callback.asBinder()
        } catch (t: Throwable) {
            Log.w("registerCallback without a binder: ${t.javaClass.simpleName}")
            return
        }
        subscriptions.firstOrNull { it.token === token }?.let { remove(it, "replaced") }
        val sub = Sub(callback, token, minIntervalMs.coerceIn(0, 10_000))
        try {
            token.linkToDeath(sub.deathRecipient, 0)
        } catch (t: RemoteException) {
            Log.w("registerCallback: client already gone (${t.javaClass.simpleName})")
            return
        }
        subscriptions.add(sub)
        Log.i("state callback registered (minInterval=${sub.minIntervalMs}ms, subs=${subscriptions.size})")
        // First contact: tell the client we are a fresh framework, then hand it a snapshot so it
        // never has to run on a guess before the next tick arrives. Both happen on the callback
        // thread, so a client that stalls on registration cannot block this binder thread.
        handler().post {
            try {
                callback.onEngineRestarted(WireRestart.FRAMEWORK_BOOT)
                EngineRuntime.currentWireState()?.let { state ->
                    callback.onState(state, sequence.incrementAndGet())
                }
            } catch (t: Throwable) {
                remove(sub, "initial push failed: ${t.javaClass.simpleName}")
            }
        }
    }

    private fun remove(sub: Sub, reason: String) {
        if (subscriptions.remove(sub)) {
            try {
                sub.token.unlinkToDeath(sub.deathRecipient, 0)
            } catch (_: Throwable) {
            }
            Log.i("state callback removed ($reason, subs=${subscriptions.size})")
        }
    }

    /**
     * Called on the engine thread by [EngineRuntime.publish].
     *
     * The per-subscriber interval is a **floor, not a filter**: the newest snapshot is always
     * kept as pending and sent when the floor expires. Dropping it instead was a real on-device
     * defect — a `stop()` published 220 ms after the last tick push was discarded, the ticker was
     * already stopped, and the UI kept showing 模拟中 forever because no later publish existed to
     * carry the stopped state.
     */
    private fun dispatchState(state: com.immoral.location.core.model.EngineState) {
        if (subscriptions.isEmpty()) return
        val wire = state.toWire()
        val seq = sequence.incrementAndGet()
        val now = SystemClock.elapsedRealtime()
        for (sub in subscriptions) {
            sub.pendingState = wire
            sub.pendingSeq = seq
            val wait = (sub.minIntervalMs - (now - sub.lastSentAtMs)).coerceAtLeast(0L)
            if (wait == 0L) {
                schedule(sub)
            } else {
                handler().postDelayed({ schedule(sub) }, wait)
            }
        }
    }

    private fun schedule(sub: Sub) {
        if (sub.inFlight) return
        sub.inFlight = true
        handler().post { drain(sub) }
    }

    private fun drain(sub: Sub) {
        var sentSeq = 0L
        try {
            val state = sub.pendingState
            if (state != null) {
                sentSeq = sub.pendingSeq
                sub.pendingState = null
                sub.callback.onState(state, sentSeq)
                sub.lastSentAtMs = SystemClock.elapsedRealtime()
            }
        } catch (t: Throwable) {
            remove(sub, "push failed: ${t.javaClass.simpleName}")
        } finally {
            sub.inFlight = false
        }
        if (sub.pendingState != null) {
            val wait = (sub.minIntervalMs - (SystemClock.elapsedRealtime() - sub.lastSentAtMs)).coerceAtLeast(0L)
            handler().postDelayed({ schedule(sub) }, wait)
        } else if (sentSeq != 0L) {
            Log.rate("binder.push", 10_000L, "state pushed seq=$sentSeq subs=${subscriptions.size}")
        }
    }

    // ------------------------------------------------------------------- results

    private fun submit(requestId: Long, block: () -> Int) {
        if (!authorized()) return
        EngineRuntime.post {
            val code = try {
                block()
            } catch (t: Throwable) {
                Log.e("binder command failed", t)
                WireResult.INTERNAL_ERROR
            }
            deliverResult(requestId, code)
        }
    }

    private fun deliverResult(requestId: Long, code: Int) {
        if (requestId == 0L) return
        val message = messageFor(code)
        // Hop off the engine thread: an outbound binder transaction must never stall the ticker.
        handler().post {
            for (sub in subscriptions) {
                try {
                    sub.callback.onResult(requestId, code, message)
                } catch (t: Throwable) {
                    remove(sub, "result push failed: ${t.javaClass.simpleName}")
                }
            }
        }
    }

    private fun messageFor(code: Int): String = when (code) {
        WireResult.OK -> ""
        WireResult.INVALID_ARGUMENT -> "参数无效"
        WireResult.UNAVAILABLE -> "模块不可用"
        WireResult.INTERNAL_ERROR -> "引擎内部错误"
        WireResult.NOT_RUNNING -> "引擎未运行"
        WireResult.TOO_LARGE -> "路线点过多"
        else -> "未知错误($code)"
    }

    // ------------------------------------------------------------------- callers

    /**
     * Only the module's own APK may drive the engine.
     *
     * The app and the module are the same APK, so the caller uid is exactly the app uid; a signed
     * permission check would need a `Context` on this thread, while the uid comparison is exact,
     * cheap and unforgeable. Everyone else is ignored (oneway methods) or rejected
     * ([assertAuthorized] on the synchronous ones).
     */
    private fun authorized(): Boolean {
        val uid = Binder.getCallingUid()
        if (uid == Process.myUid()) return true
        val expected = BridgeHost.appUids()
        if (expected.isEmpty()) {
            Log.rate("binder.uidunknown", 60_000L, "binder call from uid $uid while the app uid is unknown; refusing")
            return false
        }
        if (uid !in expected) {
            Log.rate(
                "binder.denied.$uid",
                30_000L,
                "binder call from uid $uid refused (only the module app, uids $expected, may drive the engine)",
            )
            return false
        }
        return true
    }

    private fun assertAuthorized() {
        val uid = Binder.getCallingUid()
        if (uid == Process.myUid()) return
        val expected = BridgeHost.appUids()
        if (uid !in expected) {
            throw SecurityException("uid $uid is not the module app (uids $expected)")
        }
    }

    private fun emptyState(): WireState = WireState(
        running = false,
        mode = 0,
        routeState = 0,
        point = WirePoint(0.0, 0.0, 50.0),
        speedMps = 0.0,
        bearingDeg = 0.0,
        steps = 0L,
        headingDeg = 0.0,
        routeProgress = 0.0,
        updatedAtMs = 0L,
        activePackages = emptyList(),
        engineVersion = CORE_ENGINE_VERSION,
    )
}
