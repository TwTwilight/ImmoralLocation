package com.immoral.location.hook.framework

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.immoral.location.hook.EngineRuntime
import com.immoral.location.hook.Fakes
import com.immoral.location.hook.Log
import com.immoral.location.hook.Refl
import com.immoral.location.hook.Scope
import de.robv.android.xposed.XC_MethodHook
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Layer A — Wi-Fi scan-result policy inside `system_server`.
 *
 * Two problems had to be solved for this hook to exist at all on HyperOS/Android 15:
 *
 * 1. **The classes are not resolvable by name.** The wifi stack is an APEX module, so
 *    `com.android.server.wifi.WifiServiceImpl` is not visible through the system-server class loader
 *    when `handleLoadPackage` runs. The module logged, run after run:
 *    `hook MISS com.android.server.wifi.WifiServiceImpl (class not found)` /
 *    `==== framework/wifi: 0 hook targets resolved, 2 missed ====`, i.e. every framework wifi hook
 *    was a silent no-op. The service is therefore resolved **by instance** and hooked through its own
 *    `javaClass`.
 * 2. **The instance does not exist yet.** `handleLoadPackage` runs before `WifiServiceImpl` calls
 *    `ServiceManager.addService("wifi", …)`, so a one-shot `getService` lookup only ever logs
 *    `not registered`. The hook is installed from three places instead: the `addService` callback
 *    (deterministic, fires the moment the service appears), a `getService` callback (covers a lazily
 *    resolved/restarted service), and a back-off retry chain as a safety net.
 *
 * Policy for every non-platform caller while a simulation runs (uid 0/1000 always see real data — the
 * safety invariant that keeps `WifiCloudSync` alive):
 *  * `wifiSimulation = true`  → the synthetic AP list (previous behaviour);
 *  * `wifiSimulation = false` → an **empty list** (`wifi.withheld …`), exactly what the platform
 *    returns with the radio off. The real BSSID list is a location fingerprint that the receiving
 *    process uploads to its own locator cloud, off-binder and out of reach of any hook; withholding it
 *    is what 高德地图 needs (see docs/AMAP-STABILITY.md);
 *  * the async path (`WifiScanner#startScan*`) is refused outright, so a listener-based scan cannot
 *    bypass the getter.
 */
object WifiHooks {

    private const val WIFI_SERVICE = "com.android.server.wifi.WifiServiceImpl"
    private const val SCAN_PROXY = "com.android.server.wifi.ScanRequestProxy"

    /** Service names we care about, in the order they appear on this ROM. */
    private val SERVICE_NAMES = arrayOf("wifi", "wifiscanner", "MiuiWifiService")

    private val installed = AtomicBoolean(false)
    private var resolved = 0
    private var missed = 0

    /** Implementation classes already hooked, so the three install paths cannot double-hook. */
    private val hookedClasses: MutableSet<String> = Collections.synchronizedSet(HashSet())
    private val hookedNames: MutableSet<String> = Collections.synchronizedSet(HashSet())

    @Volatile
    private var processStartMs = SystemClock.elapsedRealtime()

    fun install(loader: ClassLoader?) {
        if (!installed.compareAndSet(false, true)) return
        processStartMs = SystemClock.elapsedRealtime()
        try {
            hookClass(loader, WIFI_SERVICE, "WifiServiceImpl")
        } catch (t: Throwable) {
            Log.e("WifiHooks: class stage failed", t)
        }
        try {
            hookClass(loader, SCAN_PROXY, "ScanRequestProxy")
        } catch (t: Throwable) {
            Log.e("WifiHooks: scan-proxy stage failed", t)
        }
        try {
            hookRegistration()
        } catch (t: Throwable) {
            Log.e("WifiHooks: registration stage failed", t)
        }
        try {
            hookForNames()
        } catch (t: Throwable) {
            Log.e("WifiHooks: lookup stage failed", t)
        }
        try {
            retryWithBackoff()
        } catch (t: Throwable) {
            Log.e("WifiHooks: retry stage failed", t)
        }
        Log.summary("framework/wifi", resolved, missed)
    }

    // ------------------------------------------------------------------ name based (may miss)

    private fun hookClass(loader: ClassLoader?, className: String, shortName: String) {
        val cls = Refl.findClassAnywhere(className, loader)
        if (cls == null) {
            // Expected on APEX-loading ROMs; the instance path below is the real fix.
            Log.i("hook MISS $className (class not found) — will hook the service instance instead")
            missed++
            return
        }
        Log.hook(true, "class $className")
        hookScanResultMethods(cls, shortName)
    }

    // ------------------------------------------------------------------ instance based

    /**
     * Hook `ServiceManager#addService` and `#getService` so a service is hooked the moment it is
     * registered (deterministic) or first looked up (covers lazy/restarted services).
     */
    private fun hookRegistration() {
        val serviceManager = try {
            Class.forName("android.os.ServiceManager")
        } catch (_: Throwable) {
            Log.hook(false, "android.os.ServiceManager", "class not found")
            missed++
            return
        }
        val addService = Refl.methodsNamed(serviceManager, "addService").firstOrNull {
            it.parameterTypes.size == 2 && it.parameterTypes[0] == String::class.java
        }
        if (addService == null) {
            Log.hook(false, "ServiceManager#addService", "no matching method")
            missed++
        } else {
            val ok = Refl.hook(addService, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    try {
                        val name = param.args.getOrNull(0) as? String ?: return
                        if (name !in SERVICE_NAMES) return
                        val instance = param.args.getOrNull(1) ?: return
                        hookServiceInstance(name, instance, "addService")
                    } catch (_: Throwable) {
                    }
                }
            }, "ServiceManager#addService")
            if (ok) resolved++ else missed++
        }
        val getService = Refl.methodsNamed(serviceManager, "getService").firstOrNull {
            it.parameterTypes.size == 1 && it.parameterTypes[0] == String::class.java
        }
        if (getService == null) {
            Log.hook(false, "ServiceManager#getService", "no matching method")
            missed++
        } else {
            val ok = Refl.hook(getService, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    try {
                        val name = param.args.getOrNull(0) as? String ?: return
                        if (name !in SERVICE_NAMES) return
                        val instance = param.result ?: return
                        hookServiceInstance(name, instance, "getService")
                    } catch (_: Throwable) {
                    }
                }
            }, "ServiceManager#getService")
            if (ok) resolved++ else missed++
        }
    }

    /** Immediate lookup (works when the module is loaded late, e.g. after a scope re-activation). */
    private fun hookForNames() {
        for (name in SERVICE_NAMES) {
            val service = serviceOrNull(name)
            if (service == null) {
                Log.i("service '$name' not registered yet (hook will install when it appears)")
                continue
            }
            hookServiceInstance(name, service, "lookup")
        }
    }

    /** Safety net: try again at +3 s, +10 s, +30 s, +90 s, +300 s. */
    private fun retryWithBackoff() {
        val delays = longArrayOf(3_000L, 10_000L, 30_000L, 90_000L, 300_000L)
        val looper = try {
            Looper.getMainLooper()
        } catch (_: Throwable) {
            null
        }
        if (looper == null) return
        val handler = Handler(looper)
        for (delay in delays) {
            handler.postDelayed({
                try {
                    for (name in SERVICE_NAMES) {
                        if (hookedNames.contains(name)) continue
                        val service = serviceOrNull(name) ?: continue
                        hookServiceInstance(name, service, "retry+" + delay / 1000 + "s")
                    }
                } catch (_: Throwable) {
                }
            }, delay)
        }
    }

    private fun serviceOrNull(name: String): Any? = try {
        val serviceManager = Class.forName("android.os.ServiceManager")
        val getter = Refl.methodsNamed(serviceManager, "getService").firstOrNull {
            it.parameterTypes.size == 1 && it.parameterTypes[0] == String::class.java
        }
        if (getter == null) null else Refl.invoke(getter, null, name)
    } catch (_: Throwable) {
        null
    }

    private fun hookServiceInstance(name: String, instance: Any, via: String) {
        val cls = instance.javaClass
        if (!hookedNames.add(name)) return
        val elapsedS = (SystemClock.elapsedRealtime() - processStartMs) / 1000
        Log.i("service '$name' registered at +${elapsedS}s via $via -> ${cls.name} — hooking it now")
        hookScanResultMethods(cls, name)
        if (name == "wifiscanner") hookScannerStarts(cls, name)
        Log.i("service '$name' hooked: ${hookCountFor(cls)} scan-result method(s) on ${cls.name}")
    }

    private fun hookCountFor(cls: Class<*>): Int = try {
        Refl.methodsWhere(cls) { m -> m.name.startsWith("getScanResults") }.size
    } catch (_: Throwable) {
        0
    }

    /** Framework-side policy for scan results handed to *any* non-platform caller. */
    private fun hookScanResultMethods(cls: Class<*>, label: String) {
        if (!hookedClasses.add(cls.name + "#scanResults")) return
        var hooked = 0
        // Vendor builds rename this method (HyperOS exposes no `getScanResults*` on
        // WifiServiceImpl at all), so match structurally: anything that returns a List and whose
        // name talks about scans is a scan-result getter.
        for (m in Refl.methodsWhere(cls) { m ->
            List::class.java.isAssignableFrom(m.returnType) &&
                (m.name.lowercase().contains("scanresult") || m.name.lowercase().contains("scanlist"))
        }) {
            val ok = Refl.hook(m, withholdScanResults("$label#${m.name}"), "$label#${m.name}")
            if (ok) {
                hooked++
                resolved++
            } else {
                missed++
            }
        }
        if (hooked == 0) {
            // One-shot inventory so the next run says exactly what this ROM calls it.
            val listMethods = Refl.methodsWhere(cls) { m ->
                List::class.java.isAssignableFrom(m.returnType) && m.name.contains("et")
            }.map { it.name }.distinct().sorted().joinToString(", ")
            Log.hook(false, "$label#scanResults*", "no matching method on ${cls.name}; List-returning candidates: $listMethods")
            missed++
        } else {
            Log.hook(true, "$label#scanResults* (${hooked} methods on ${cls.name})")
        }
    }

    /** The async path: a `WifiScanner` listener never calls `getScanResults`, so refuse the request. */
    private fun hookScannerStarts(cls: Class<*>, label: String) {
        if (!hookedClasses.add(cls.name + "#startScan")) return
        for (m in Refl.methodsWhere(cls) { m ->
            (m.name == "startScan" || m.name.startsWith("startScanWith")) && m.parameterTypes.isNotEmpty()
        }) {
            val ok = Refl.hook(m, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    try {
                        val snap = EngineRuntime.activeSnap() ?: return
                        if (snap.config.wifiSimulation) return
                        val caller = Scope.callingUid()
                        if (caller == 0 || caller == Scope.SYSTEM_UID) return
                        if (!Scope.isCallerSpoofed()) return
                        param.result = null
                        Log.rate(
                            "wifi.scan.refused",
                            20_000L,
                            "$label#${m.name} refused for uid $caller (wifiSim=false, fail closed)",
                        )
                    } catch (_: Throwable) {
                    }
                }
            }, "$label#${m.name}")
            if (ok) resolved++ else missed++
        }
    }

    private fun withholdScanResults(label: String): XC_MethodHook = object : XC_MethodHook() {
        override fun beforeHookedMethod(param: MethodHookParam) {
            try {
                val snap = EngineRuntime.activeSnap() ?: return
                // Never fake results for the platform itself: WifiCloudSync and friends run inside
                // system_server and NPE on partially populated ScanResults.
                val caller = Scope.callingUid()
                if (caller == 0 || caller == Scope.SYSTEM_UID) return
                if (!Scope.isCallerSpoofed()) return
                if (snap.config.wifiSimulation) {
                    if (snap.accessPoints.isEmpty()) return
                    val results = Fakes.scanResults(snap.accessPoints)
                    if (results.isEmpty()) return
                    param.result = results
                    Log.rate("wifi.replaced", 20_000L, "$label -> ${results.size} synthetic APs for uid $caller")
                    return
                }
                param.result = emptyList<Any>()
                Log.rate("wifi.withheld", 20_000L, "$label -> 0 APs withheld from uid $caller (wifiSim=false)")
            } catch (t: Throwable) {
                Log.rate("wifi.replace", 15_000L, "wifi replace failed: ${t.javaClass.simpleName}")
            }
        }
    }
}
