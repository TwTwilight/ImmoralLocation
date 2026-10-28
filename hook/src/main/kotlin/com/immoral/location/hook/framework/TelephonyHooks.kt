package com.immoral.location.hook.framework

import com.immoral.location.hook.EngineRuntime
import com.immoral.location.hook.Fakes
import com.immoral.location.hook.Log
import com.immoral.location.hook.Refl
import com.immoral.location.hook.Scope
import de.robv.android.xposed.XC_MethodHook
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Layer A (best effort) — cell info inside the telephony server.
 *
 * On this platform the cell API does not live in `TelephonyRegistry`; it is served by
 * `com.android.phone` (`PhoneInterfaceManager`). Both class names are probed, so this layer
 * activates only if the module is scoped into a process that actually hosts them. The primary,
 * always-available path is the client-side [com.immoral.location.hook.client.TelephonyClientHooks].
 */
object TelephonyHooks {

    private const val PHONE_INTERFACE = "com.android.phone.PhoneInterfaceManager"
    private const val TELEPHONY_REGISTRY = "com.android.server.TelephonyRegistry"

    private val installed = AtomicBoolean(false)
    private var resolved = 0
    private var missed = 0

    fun install(loader: ClassLoader?) {
        if (!installed.compareAndSet(false, true)) return
        for (className in arrayOf(PHONE_INTERFACE, TELEPHONY_REGISTRY)) {
            try {
                hookClass(loader, className)
            } catch (t: Throwable) {
                Log.e("TelephonyHooks: $className stage failed", t)
            }
        }
        Log.summary("framework/telephony", resolved, missed)
    }

    private fun hookClass(loader: ClassLoader?, className: String) {
        val cls = Refl.findClassAnywhere(className, loader)
        if (cls == null) {
            Log.hook(false, className, "class not present in this process")
            missed++
            return
        }
        Log.hook(true, "class $className")
        val methods = Refl.methodsWhere(cls) { m ->
            m.name in CELL_METHODS && List::class.java.isAssignableFrom(m.returnType)
        }
        if (methods.isEmpty()) {
            Log.hook(false, "$className#getAllCellInfo", "no matching method")
            missed++
            return
        }
        for (m in methods) {
            val ok = Refl.hook(m, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    try {
                        val snap = EngineRuntime.activeSnap() ?: return
                        if (!snap.config.cellSimulation) return
                        if (snap.cells.isEmpty()) return
                        // Skip calls that originate inside this very process (platform internals):
                        // only answer cross-process requests from apps.
                        if (Scope.callingUid() == android.os.Process.myUid()) return
                        if (!Scope.isCallerSpoofed()) return
                        val infos = Fakes.cellInfos(snap.cells)
                        if (infos.isEmpty()) return
                        param.result = infos
                        Log.rate("cell.replaced", 20_000L, "$className#${param.method?.name} -> ${infos.size} synthetic cells")
                    } catch (t: Throwable) {
                        Log.rate("cell.replace", 15_000L, "cell replace failed: ${t.javaClass.simpleName}")
                    }
                }
            }, className.substringAfterLast('.') + "#" + m.name)
            if (ok) resolved++ else missed++
        }
    }

    private val CELL_METHODS = setOf(
        "getAllCellInfo",
        "getCellInfoForSubscriber",
        "getCellInfoForSubscriberWithRenewal",
        "requestCellInfoUpdate",
    )
}
