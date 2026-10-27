package com.immoral.location.hook

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import com.immoral.location.core.model.EngineState
import com.immoral.location.core.protocol.Bridge
import com.immoral.location.core.protocol.BridgeReply
import com.immoral.location.core.protocol.Codec
import com.immoral.location.core.protocol.Command
import de.robv.android.xposed.XC_MethodHook
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The framework-side end of the app bridge: a dynamically registered
 * [BroadcastReceiver] living inside `system_server`.
 *
 * Security: the receiver is registered with [Bridge.PERMISSION_CONTROL] as required broadcast
 * permission. That permission is declared at `signature` level by the module APK, which means only
 * apps signed with the same key (the module itself) can drive the engine.
 */
object BridgeServer {

    /** Extra echoed back to the app so it can correlate its own commands. */
    const val EXTRA_REQUEST_ID = "com.immoral.location.extra.REQUEST_ID"

    /** Deferred registration loop: 250 ms x 80 = ~20 s, enough for a slow boot. */
    private const val RETRY_DELAY_MS = 250L
    private const val MAX_RETRIES = 80

    private val installed = AtomicBoolean(false)

    @Volatile
    private var context: Context? = null

    @Volatile
    private var pm: PackageManager? = null

    @Volatile
    private var receiver: BroadcastReceiver? = null

    @Volatile
    private var replyTargets: List<String> = emptyList()

    @Volatile
    private var lastStatePushMs: Long = 0L

    @Volatile
    var installedOk: Boolean = false
        private set

    fun packageManager(): PackageManager? = pm ?: context?.packageManager

    fun hostContext(): Context? = context

    /**
     * Installs the bridge.
     *
     * Timing matters: the framework layer is injected while `ActivityThread` is still bootstrapping
     * system_server, long before `ActivityManager.getService()` exists. Every
     * `registerReceiver`/`sendBroadcast` from this process is routed through IActivityManager, so
     * the very first attempt legitimately fails with a null service. We therefore:
     *  1. try immediately (works if we are late),
     *  2. hook `ActivityManagerService#systemReady` / `#finishBooting` and retry from there,
     *  3. retry from the system main looper every 250 ms (up to ~20 s) as a safety net.
     */
    fun install(): Boolean {
        if (!installed.compareAndSet(false, true)) return installedOk
        val ctx = resolveContext()
        if (ctx == null) {
            Log.e("BridgeServer: no system_server context available — bridge disabled")
            return false
        }
        context = ctx
        pm = try {
            ctx.packageManager
        } catch (t: Throwable) {
            null
        }
        Scope.attachPackageManager(pm)
        resolvePackages()
        if (!attemptRegistration("initial")) {
            deferUntilActivityManager()
            scheduleRetries()
        }
        return installedOk
    }

    /** One registration attempt; logs the failure reason (that line is our evidence). */
    private fun attemptRegistration(tag: String): Boolean {
        if (installedOk) return true
        val ctx = context ?: return false
        if (registerReceiver(ctx)) {
            installedOk = true
            EngineRuntime.stateSink = { state -> pushState(state) }
            Log.hook(true, "BridgeServer.registerReceiver [$tag] ready, replies -> $replyTargets")
            return true
        }
        Log.w("BridgeServer.registerReceiver [$tag] not ready yet")
        return false
    }

    /**
     * Retry gate: once ActivityManagerService is up, registration can succeed. Both hooks are
     * installed with hookAllMethods so vendor overloads are covered too.
     */
    private fun deferUntilActivityManager() {
        try {
            val ams = Refl.findClassAnywhere("com.android.server.am.ActivityManagerService", null)
            if (ams == null) {
                Log.hook(false, "ActivityManagerService (registration trigger)", "class not found")
                return
            }
            val callback = object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    try {
                        if (installedOk) return
                        attemptRegistration("ams#" + (param.method?.name ?: "?"))
                    } catch (t: Throwable) {
                        Log.e("BridgeServer: registration from AMS trigger failed", t)
                    }
                }
            }
            var hooked = 0
            for (name in arrayOf("systemReady", "finishBooting", "systemRunning")) {
                hooked += Refl.hookAll(ams, name, callback, "ActivityManagerService")
            }
            if (hooked == 0) Log.hook(false, "ActivityManagerService#systemReady", "no trigger method")
        } catch (t: Throwable) {
            Log.e("BridgeServer: deferring registration failed", t)
        }
    }

    private fun scheduleRetries() {
        try {
            val handler = Handler(Looper.getMainLooper())
            val runnable = object : Runnable {
                var attempts = 0
                override fun run() {
                    if (installedOk) return
                    attempts++
                    if (attemptRegistration("retry#$attempts")) return
                    if (attempts >= MAX_RETRIES) {
                        Log.e(
                            "BridgeServer: giving up after $attempts attempts " +
                                "(${MAX_RETRIES * RETRY_DELAY_MS / 1000}s) — bridge disabled",
                        )
                        return
                    }
                    handler.postDelayed(this, RETRY_DELAY_MS)
                }
            }
            handler.postDelayed(runnable, RETRY_DELAY_MS)
            Log.i("BridgeServer: registration deferred (retrying every ${RETRY_DELAY_MS}ms)")
        } catch (t: Throwable) {
            Log.e("BridgeServer: retry scheduling failed", t)
        }
    }

    // ------------------------------------------------------------------ context

    /**
     * Context lookup. In system_server `ActivityThread.getSystemContext()` is the right one; the
     * `currentApplication()` variants are kept as fallbacks for other hosts.
     */
    private fun resolveContext(): Context? {
        try {
            val activityThread = Refl.findClassAnywhere("android.app.ActivityThread", null)
            if (activityThread != null) {
                val current = Refl.invoke(
                    Refl.methodsNamed(activityThread, "currentActivityThread").firstOrNull { it.parameterTypes.isEmpty() },
                    null,
                )
                if (current != null) {
                    val systemContext = Refl.invoke(
                        Refl.methodsNamed(activityThread, "getSystemContext").firstOrNull { it.parameterTypes.isEmpty() },
                        current,
                    )
                    if (systemContext is Context) {
                        Log.i("BridgeServer context: ActivityThread.getSystemContext()")
                        return systemContext
                    }
                }
                val app = Refl.invoke(
                    Refl.methodsNamed(activityThread, "currentApplication").firstOrNull { it.parameterTypes.isEmpty() },
                    null,
                )
                if (app is Context) {
                    Log.i("BridgeServer context: ActivityThread.currentApplication()")
                    return app
                }
            }
        } catch (t: Throwable) {
            Log.e("BridgeServer context (ActivityThread) failed", t)
        }
        try {
            val helper = Refl.findClassAnywhere("android.app.AndroidAppHelper", null)
            val app = Refl.invoke(Refl.methodsNamed(helper, "currentApplication").firstOrNull { it.parameterTypes.isEmpty() }, null)
            if (app is Context) {
                Log.i("BridgeServer context: AndroidAppHelper.currentApplication()")
                return app
            }
        } catch (t: Throwable) {
            Log.e("BridgeServer context (AndroidAppHelper) failed", t)
        }
        return null
    }

    private fun resolvePackages() {
        val manager = pm
        val found = ArrayList<String>(2)
        for (candidate in ModuleInfo.candidateAppPackages) {
            try {
                manager?.getPackageInfo(candidate, 0)
                found.add(candidate)
            } catch (_: Throwable) {
            }
        }
        if (found.isEmpty()) {
            // Nothing installed under either name (e.g. release candidate): still try the default.
            found.add(ModuleInfo.appPackage)
        }
        replyTargets = found
        ModuleInfo.appPackage = found.first()
        resolveVersion()
    }

    private fun resolveVersion() {
        val path = ModuleInfo.modulePath
        val manager = pm
        if (path != null && manager != null) {
            try {
                val info = manager.getPackageArchiveInfo(path, 0)
                val name = info?.versionName
                if (!name.isNullOrEmpty()) {
                    ModuleInfo.version = name
                    Log.i("module version $name (${info.packageName})")
                    return
                }
            } catch (_: Throwable) {
            }
        }
        Log.i("module version unknown, using ${ModuleInfo.version}")
    }

    // ------------------------------------------------------------------ receiver

    private fun registerReceiver(ctx: Context): Boolean {
        val filter = IntentFilter(Bridge.ACTION_COMMAND)
        val rcv = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                handleCommand(intent)
            }
        }
        receiver = rcv

        // Android 13+ (targetSdk 34+ hosts) require an explicit exported flag; the 5-arg overload
        // only exists there, so try it first and fall back to the classic 4-arg one.
        val attempts = listOf<() -> Unit>(
            {
                val m = Context::class.java.getMethod(
                    "registerReceiver",
                    BroadcastReceiver::class.java,
                    IntentFilter::class.java,
                    String::class.java,
                    Handler::class.java,
                    Int::class.javaPrimitiveType,
                )
                m.invoke(ctx, rcv, filter, Bridge.PERMISSION_CONTROL, null, RECEIVER_EXPORTED)
            },
            { ctx.registerReceiver(rcv, filter, Bridge.PERMISSION_CONTROL, null) },
            { ctx.registerReceiver(rcv, filter, null, null) },
        )
        for ((index, attempt) in attempts.withIndex()) {
            try {
                attempt()
                Log.hook(true, "BridgeServer.registerReceiver (variant ${index + 1})")
                return true
            } catch (t: Throwable) {
                val cause = (t as? java.lang.reflect.InvocationTargetException)?.targetException ?: t
                Log.w(
                    "registerReceiver variant ${index + 1} failed: " +
                        "${cause.javaClass.simpleName}: ${cause.message}",
                )
            }
        }
        Log.hook(false, "BridgeServer.registerReceiver", "all variants failed")
        return false
    }

    // RECEIVER_EXPORTED mirrors Context.RECEIVER_EXPORTED (API 33); kept literal so it links on 21.
    private const val RECEIVER_EXPORTED = 0x2

    private fun handleCommand(intent: Intent?) {
        if (intent == null) return
        try {
            val payload = intent.getStringExtra(Bridge.EXTRA_PAYLOAD) ?: run {
                Log.w("command received: action=" + intent.action + " but WITHOUT the payload extra")
                return
            }
            // Permanent diagnostic: proves the inbound path end to end (this is the line e2e greps).
            Log.i(
                "command received: action=" + intent.action + " payloadChars=" + payload.length +
                    " senderUid=" + try {
                        android.os.Binder.getCallingUid()
                    } catch (_: Throwable) {
                        -1
                    },
            )
            val requestId = intent.getStringExtra(EXTRA_REQUEST_ID)
            val command: Command = Codec.decodeCommand(payload) ?: run {
                Log.rate("badcmd", 5_000L, "undecodable command payload (${payload.length} chars)")
                sendReply(
                    BridgeReply(ok = false, error = "bad command payload", engineLoaded = true, moduleVersion = ModuleInfo.version),
                    requestId,
                )
                return
            }
            Log.i("command decoded: " + command.javaClass.simpleName)
            EngineRuntime.submit(command, requestId)
        } catch (t: Throwable) {
            Log.e("BridgeServer.onReceive failed", t)
        }
    }

    // ------------------------------------------------------------------ replies

    fun sendReply(reply: BridgeReply, requestId: String?) {
        val ctx = context ?: return
        val json = try {
            Codec.encodeReply(reply)
        } catch (t: Throwable) {
            Log.e("encodeReply failed", t)
            return
        }
        var sent = false
        for (pkg in replyTargets) {
            try {
                val intent = Intent(Bridge.ACTION_REPLY).apply {
                    setPackage(pkg)
                    putExtra(Bridge.EXTRA_PAYLOAD, json)
                    if (requestId != null) putExtra(EXTRA_REQUEST_ID, requestId)
                    addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
                }
                ctx.sendBroadcast(intent)
                sent = true
            } catch (t: Throwable) {
                Log.rate("replyfail", 10_000L, "reply to $pkg failed: ${t.javaClass.simpleName}")
            }
        }
        if (!sent) Log.rate("replynone", 30_000L, "no reply target reachable ($replyTargets)")
    }

    /** Throttled (~2 Hz) state stream while the engine runs. */
    private fun pushState(state: EngineState) {
        val now = System.currentTimeMillis()
        if (now - lastStatePushMs < 450L) return
        lastStatePushMs = now
        sendReply(
            BridgeReply(ok = true, state = state, engineLoaded = true, moduleVersion = ModuleInfo.version),
            null,
        )
    }

    /** Debug helper: dump the reply targets into the module log. */
    fun dumpTargets(): String = "targets=$replyTargets app=${ModuleInfo.appPackage}"

    @Suppress("unused")
    private fun explicitComponent(pkg: String): ComponentName? = pm?.let {
        try {
            val info = it.getPackageInfo(pkg, 0)
            ComponentName(info.packageName, "com.immoral.location.bridge.BridgeReplyReceiver")
        } catch (_: Throwable) {
            null
        }
    }
}

