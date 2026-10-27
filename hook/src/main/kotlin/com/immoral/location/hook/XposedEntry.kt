package com.immoral.location.hook

import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.IXposedHookZygoteInit
import de.robv.android.xposed.callbacks.XC_LoadPackage

/**
 * LSPosed / Xposed entry point (registered in `assets/xposed_init`).
 *
 * **Framework only.** Exactly two install targets exist:
 *  - the `system_server` process (reported package `android`/`system`) -> framework layer: the
 *    simulation engine, the bridge server and the global location / wifi / gnss / telephony
 *    interception;
 *  - `com.android.phone`, which hosts the cell-info API (`PhoneInterfaceManager`) for every app.
 *
 * Every other process is deliberately left **untouched**: no hooks, no injected classes, no
 * rewritten methods. That keeps the target app's process indistinguishable from an unhooked one
 * (an app can enumerate its own class loaders and method bodies), and it removes a whole class of
 * "our own client hook fed synthetic data back into a platform service" bugs.
 *
 * This class must keep its name: `assets/xposed_init` points at it.
 */
class XposedEntry : IXposedHookLoadPackage, IXposedHookZygoteInit {

    override fun initZygote(startupParam: IXposedHookZygoteInit.StartupParam) {
        try {
            ModuleInfo.modulePath = startupParam.modulePath
            // Newer frameworks expose moduleVersion here; api-82 does not, so read it reflectively.
            val version = try {
                startupParam.javaClass.getField("moduleVersion").get(startupParam) as? String
            } catch (_: Throwable) {
                null
            }
            if (!version.isNullOrEmpty()) ModuleInfo.version = version
            var startsSystemServer = false
            try {
                startsSystemServer = startupParam.startsSystemServer
            } catch (_: Throwable) {
            }
            Log.i(
                "initZygote modulePath=${startupParam.modulePath} version=${ModuleInfo.version} " +
                    "startsSystemServer=$startsSystemServer sdk=${android.os.Build.VERSION.SDK_INT}",
            )
        } catch (t: Throwable) {
            Log.e("initZygote failed", t)
        }
    }

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            val pkg = lpparam.packageName ?: return
            val reported = lpparam.processName ?: pkg
            val self = ModuleInfo.selfProcessName()
            val platformUid = ModuleInfo.isPlatformUid()
            val systemServer = ModuleInfo.isSystemServerProcess(reported)
            if (pkg == "android" || pkg == "system") {
                // LSPosed records the framework scope as "system"; zygote-side code still sees
                // packageName == "android". Accept both, and only in the system_server process.
                if (systemServer) {
                    FrameworkInstaller.install(lpparam, self.ifEmpty { reported })
                } else {
                    Log.i("framework scope in process $reported/$self — engine host is system_server, skipping")
                }
                return
            }
            // com.android.phone hosts the cell-info API (PhoneInterfaceManager): framework hooks only,
            // it is a platform process serving cell state to every app.
            if (pkg == "com.android.phone") {
                try {
                    com.immoral.location.hook.framework.TelephonyHooks.install(lpparam.classLoader)
                } catch (t: Throwable) {
                    Log.e("framework/telephony install in com.android.phone failed", t)
                }
                return
            }
            // Framework-only by design: the module never installs hooks inside a third-party app.
            // Everything an app can observe (locations, GNSS, WiFi, cell info, sensors) is
            // intercepted in system_server / the platform services, so the target process sees no
            // Xposed class loader, no injected classes and no rewritten methods at all.
            if (systemServer || platformUid) {
                Log.i(
                    "framework-only: platform process pkg=$pkg reportedProcess=$reported " +
                        "realProcess=$self uid=${android.os.Process.myUid()} — no app hooks installed",
                )
            } else {
                Log.rate(
                    "framework-only:" + pkg,
                    60_000L,
                    "framework-only: no hooks installed in $reported (app process untouched)",
                )
            }
        } catch (t: Throwable) {
            Log.e("handleLoadPackage failed for ${lpparam.packageName}/${lpparam.processName}", t)
        }
    }
}
