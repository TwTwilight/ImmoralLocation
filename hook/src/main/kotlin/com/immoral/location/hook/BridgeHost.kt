package com.immoral.location.hook

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import com.immoral.location.core.protocol.Bridge
import de.robv.android.xposed.XC_MethodHook
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The framework-side end of the binder handshake, living inside `system_server`.
 *
 * There is deliberately **no registered system service and no world-readable state**: the only
 * thing that crosses the process boundary over the broadcast bus is one `IBinder` handed to the
 * app in a signature-permission, package-scoped reply. A service registered with
 * `ServiceManager.addService` would appear in `IServiceManager.listServices()` for every app to
 * see, which is the same class of fingerprint the old `Settings.Global` status row was.
 *
 * Registration timing is the hard part: the framework layer is injected while `ActivityThread` is
 * still bootstrapping system_server, long before `ActivityManager.getService()` exists, so every
 * `registerReceiver` goes through IActivityManager and the first attempt legitimately fails. The
 * same three-stage retry the old broadcast bridge used is kept:
 *  1. try immediately (works when injection is late),
 *  2. hook `ActivityManagerService#systemReady` / `#finishBooting` / `#systemRunning`,
 *  3. retry from the system main looper every 250 ms for ~20 s.
 */
object BridgeHost {

    /** Deferred registration loop: 250 ms x 80 = ~20 s, enough for a slow boot. */
    private const val RETRY_DELAY_MS = 250L
    private const val MAX_RETRIES = 80

    /** `Context.RECEIVER_EXPORTED` (API 33); kept literal so this links on 21. */
    private const val RECEIVER_EXPORTED = 0x2

    /** Key a previous build published into `Settings.Global`; removed once at install. */
    private const val LEGACY_STATE_KEY = "immoral_state"

    private val installed = AtomicBoolean(false)

    @Volatile
    private var context: Context? = null

    @Volatile
    private var pm: PackageManager? = null

    @Volatile
    private var receiver: BroadcastReceiver? = null

    @Volatile
    private var appPackages: List<String> = emptyList()

    /**
     * UIDs of the app APK(s), resolved lazily.
     *
     * The framework layer is injected before `PackageManagerService` finishes coming up, so the
     * first `getPackageUid` during install can fail (measured on device: `uids=[]`, which silently
     * disabled every binder call until the next boot). Resolution therefore retries on first use.
     */
    @Volatile
    private var appUids: Set<Int> = emptySet()

    /** True once the world-readable row a previous build published has been removed. */
    @Volatile
    private var legacyRowCleared = false

    @Volatile
    var installedOk: Boolean = false
        private set

    fun packageManager(): PackageManager? = pm ?: context?.packageManager

    fun hostContext(): Context? = context

    /** UIDs of the app APK(s); only these may drive the engine. Resolved on first use. */
    fun appUids(): Set<Int> {
        appUids.takeIf { it.isNotEmpty() }?.let { return it }
        synchronized(this) {
            appUids.takeIf { it.isNotEmpty() }?.let { return it }
            val resolved = resolveUids()
            if (resolved.isNotEmpty()) appUids = resolved
            return appUids
        }
    }

    private fun resolveUids(): Set<Int> {
        val manager = pm ?: context?.packageManager
        val packages = appPackages.ifEmpty { ModuleInfo.candidateAppPackages }
        if (manager == null) return emptySet()
        return packages.mapNotNull { pkg ->
            try {
                manager.getPackageUid(pkg, 0).takeIf { it > 0 }
            } catch (_: Throwable) {
                null
            }
        }.toSet()
    }

    fun install(): Boolean {
        if (!installed.compareAndSet(false, true)) return installedOk
        val ctx = resolveContext()
        if (ctx == null) {
            Log.e("BridgeHost: no system_server context available — handshake disabled")
            return false
        }
        context = ctx
        pm = try {
            ctx.packageManager
        } catch (_: Throwable) {
            null
        }
        Scope.attachPackageManager(pm)
        resolvePackages()
        removeLegacyStatusRow(ctx)
        if (!attemptRegistration("initial")) {
            deferUntilActivityManager()
            scheduleRetries()
        }
        return installedOk
    }

    private fun attemptRegistration(tag: String): Boolean {
        if (installedOk) return true
        val ctx = context ?: return false
        if (registerReceiver(ctx)) {
            installedOk = true
            // Now that ActivityManagerService is up, PackageManagerService is too: retry the uid
            // resolution and the legacy-row cleanup that failed during the early injection.
            appUids = resolveUids()
            removeLegacyStatusRow(ctx)
            Log.hook(true, "BridgeHost.registerReceiver [$tag] ready -> $appPackages uids=$appUids")
            return true
        }
        Log.w("BridgeHost.registerReceiver [$tag] not ready yet")
        return false
    }

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
                        Log.e("BridgeHost: registration from AMS trigger failed", t)
                    }
                }
            }
            var hooked = 0
            for (name in arrayOf("systemReady", "finishBooting", "systemRunning")) {
                hooked += Refl.hookAll(ams, name, callback, "ActivityManagerService")
            }
            if (hooked == 0) Log.hook(false, "ActivityManagerService#systemReady", "no trigger method")
        } catch (t: Throwable) {
            Log.e("BridgeHost: deferring registration failed", t)
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
                            "BridgeHost: giving up after $attempts attempts " +
                                "(${MAX_RETRIES * RETRY_DELAY_MS / 1000}s) — handshake disabled",
                        )
                        return
                    }
                    handler.postDelayed(this, RETRY_DELAY_MS)
                }
            }
            handler.postDelayed(runnable, RETRY_DELAY_MS)
            Log.i("BridgeHost: registration deferred (retrying every ${RETRY_DELAY_MS}ms)")
        } catch (t: Throwable) {
            Log.e("BridgeHost: retry scheduling failed", t)
        }
    }

    // ------------------------------------------------------------------ context

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
                        Log.i("BridgeHost context: ActivityThread.getSystemContext()")
                        return systemContext
                    }
                }
                val app = Refl.invoke(
                    Refl.methodsNamed(activityThread, "currentApplication").firstOrNull { it.parameterTypes.isEmpty() },
                    null,
                )
                if (app is Context) {
                    Log.i("BridgeHost context: ActivityThread.currentApplication()")
                    return app
                }
            }
        } catch (t: Throwable) {
            Log.e("BridgeHost context (ActivityThread) failed", t)
        }
        try {
            val helper = Refl.findClassAnywhere("android.app.AppGlobals", null)
            val app = Refl.invoke(Refl.methodOf(helper, "getInitialApplication", 0), null)
            if (app is Context) {
                Log.i("BridgeHost context: AppGlobals.getInitialApplication()")
                return app
            }
        } catch (t: Throwable) {
            Log.e("BridgeHost context (AppGlobals) failed", t)
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
        if (found.isEmpty()) found.add(ModuleInfo.appPackage)
        appPackages = found
        ModuleInfo.appPackage = found.first()
        appUids = resolveUids()
        resolveVersion()
        Log.i("BridgeHost app targets=$appPackages uids=$appUids")
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

    /**
     * Drops the `immoral_state` row a previous build kept in `Settings.Global`.
     *
     * `Settings.Global` is persistent storage and world-readable, so an upgrade must not leave
     * the old row behind advertising a running engine to every app on the device.
     */
    private fun removeLegacyStatusRow(ctx: Context) {
        if (legacyRowCleared) return
        try {
            android.provider.Settings.Global.putString(ctx.contentResolver, LEGACY_STATE_KEY, null)
            legacyRowCleared = true
            Log.i("legacy status row removed from Settings.Global")
        } catch (t: Throwable) {
            // SettingsProvider is not necessarily up when the framework layer is injected; the
            // retry happens on registration success and on the first handshake.
            Log.once("bridgehost.legacy", "legacy status row cleanup failed: ${t.javaClass.simpleName}")
        }
    }

    // ----------------------------------------------------------------- receiver

    private fun registerReceiver(ctx: Context): Boolean {
        val filter = IntentFilter(Bridge.ACTION_HELLO)
        val rcv = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                handleHello(intent)
            }
        }
        receiver = rcv

        // Android 13+ requires an explicit exported flag; the 5-arg overload only exists there,
        // so try it first and fall back to the classic 4-arg one. The signature permission is what
        // actually guards the channel in every variant.
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
        )
        for ((index, attempt) in attempts.withIndex()) {
            try {
                attempt()
                Log.hook(true, "BridgeHost.registerReceiver (variant ${index + 1})")
                return true
            } catch (t: Throwable) {
                val cause = (t as? java.lang.reflect.InvocationTargetException)?.targetException ?: t
                Log.w(
                    "registerReceiver variant ${index + 1} failed: " +
                        "${cause.javaClass.simpleName}: ${cause.message}",
                )
            }
        }
        Log.hook(false, "BridgeHost.registerReceiver", "all variants failed")
        return false
    }

    private fun handleHello(intent: Intent?) {
        val requestId = intent?.getStringExtra(Bridge.EXTRA_REQUEST_ID)
        Log.i("hello received (requestId=$requestId) -> handing out the engine binder")
        // The first handshake is the last chance to fix up anything that was still unavailable
        // during early boot (SettingsProvider, PackageManagerService).
        context?.let { removeLegacyStatusRow(it) }
        appUids()
        deliverBinder(requestId)
    }

    /** Replies with the engine binder; package-scoped, so only our own APK can receive it. */
    fun deliverBinder(requestId: String?) {
        val ctx = context ?: return
        var sent = 0
        for (pkg in appPackages) {
            try {
                val extras = Bundle().apply { putBinder(Bridge.EXTRA_BINDER, FrameworkBinder.engineInterface.asBinder()) }
                val reply = Intent(Bridge.ACTION_HELLO_REPLY).apply {
                    setPackage(pkg)
                    putExtras(extras)
                    if (requestId != null) putExtra(Bridge.EXTRA_REQUEST_ID, requestId)
                    addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
                }
                // The reply carries our signature permission: the app's receiver requires the
                // *sender* to hold it (system_server does, as the system uid), and the app itself
                // must hold it to receive. Together with setPackage this means no other app can
                // ever obtain the engine binder.
                ctx.sendBroadcast(reply, Bridge.PERMISSION_CONTROL)
                sent++
            } catch (t: Throwable) {
                Log.rate("hello.fail", 10_000L, "hello reply to $pkg failed: ${t.javaClass.simpleName}")
            }
        }
        if (sent == 0) Log.rate("hello.none", 30_000L, "no hello target reachable ($appPackages)")
    }

    /** Debug helper used by the install summary line. */
    fun dumpTargets(): String = "targets=$appPackages uids=$appUids"
}
