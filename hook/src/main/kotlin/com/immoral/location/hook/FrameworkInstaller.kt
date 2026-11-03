package com.immoral.location.hook

import de.robv.android.xposed.callbacks.XC_LoadPackage
import com.immoral.location.hook.framework.GnssHooks
import com.immoral.location.hook.framework.LocationHooks
import com.immoral.location.hook.framework.TelephonyHooks
import com.immoral.location.hook.framework.WifiHooks
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Layer A installer: everything that runs inside `system_server`.
 *
 * Each layer is installed independently and defensively — one broken hook target must never take
 * the others (or system_server) down.
 */
object FrameworkInstaller {

    private val installed = AtomicBoolean(false)

    fun install(lpparam: XC_LoadPackage.LoadPackageParam, process: String) {
        if (!installed.compareAndSet(false, true)) {
            Log.i("framework layer already installed in $process (pid ${android.os.Process.myPid()})")
            return
        }
        val t0 = System.currentTimeMillis()
        Log.i(
            "=== installing framework layer in process=$process pid=${android.os.Process.myPid()} " +
                "sdk=${android.os.Build.VERSION.SDK_INT} ===",
        )
        val loader = lpparam.classLoader

        step("probes") { Fakes.probeOnce() }
        step("native") {
            // Native layer (xmake + C++23 + ShadowHook): hooks
            // SensorService::SensorEventConnection::sendEvents inside system_server, so step
            // events are spoofed for every app without scoping third-party processes.
            if (NativeBridge.install()) {
                Log.i("native sensor hook active: ${NativeBridge.status()}")
            } else {
                Log.w("native hook unavailable (${NativeBridge.status()}); step spoofing falls back to the client layer")
            }
        }
        step("engine") { EngineRuntime.boot(ModuleInfo.version) }
        // The binder engine: one interface, handed to the app by BridgeHost after the HELLO
        // broadcast. Installed before the host so a handshake can never arrive without an engine.
        step("binder") { FrameworkBinder.install() }
        // Handshake receiver: retried until ActivityManagerService is up.
        step("bridge-host") { BridgeHost.install() }
        step("location") { LocationHooks.install(loader) }
        step("gnss") { GnssHooks.install(loader) }
        step("wifi") { WifiHooks.install(loader) }
        step("telephony") { TelephonyHooks.install(loader) }

        Log.i(
            "framework layer installed in ${System.currentTimeMillis() - t0}ms; " +
                "engine=${EngineRuntime.enginePresent} handshake=${BridgeHost.installedOk} " +
                BridgeHost.dumpTargets(),
        )
    }

    private inline fun step(name: String, block: () -> Unit) {
        try {
            block()
        } catch (t: Throwable) {
            Log.e("framework layer '$name' install failed", t)
        }
    }
}

