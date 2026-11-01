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
        // Unconditional: proves install() is reached at all, before any hook/type matching runs.
        Log.i("framework/telephony: install() reached in " + (runCatching { com.immoral.location.hook.ModuleInfo.selfProcessName() }.getOrDefault("?"))
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
        // Name **and** return shape, for the same reason the wifi hook needed it: modern AIDL hands
        // cell lists back as a `ParceledListSlice`, which is not a `java.util.List`, and this ROM does
        // not use the AOSP method names either. A List-only match reports "no matching method" for a
        // method that exists and is perfectly hookable (that is exactly what happened to the wifi
        // seam — see docs/AMAP-STABILITY.md §12).
        val methods = Refl.methodsWhere(cls) { m ->
            val ret = m.returnType.name
            val listLike = List::class.java.isAssignableFrom(m.returnType) ||
                ret.contains("ParceledListSlice") || ret.endsWith("Slice")
            listLike && m.name.lowercase().contains("cell")
        }
        if (methods.isEmpty()) {
            val candidates = Refl.methodsWhere(cls) { m -> m.name.lowercase().contains("cell") }
                .map { it.name + ":" + it.returnType.simpleName }
                .distinct()
                .sorted()
                .take(40)
                .joinToString(", ")
            Log.hook(false, "$className#*cell*", "no matching method; cell* candidates: $candidates")
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
                        val value = asReturnShape(param, infos)
                        if (value === UNBUILDABLE) return
                        param.result = value
                        Log.rate(
                            "cell.replaced",
                            20_000L,
                            "$className#${param.method?.name} -> ${infos.size} synthetic cells (${m.returnType.simpleName})",
                        )
                    } catch (t: Throwable) {
                        Log.rate("cell.replace", 15_000L, "cell replace failed: ${t.javaClass.simpleName}")
                    }
                }
            }, className.substringAfterLast('.') + "#" + m.name)
            if (ok) {
                resolved++
                Log.hook(true, "$className#${m.name} -> ${m.returnType.name}")
            } else {
                missed++
            }
        }
    }

    /** Sentinel: the return type is a container we cannot synthesise, so the call is left untouched. */
    private val UNBUILDABLE = Any()

    /** Reply in the shape the hooked method actually returns (`List` or a `ParceledListSlice`). */
    private fun asReturnShape(param: XC_MethodHook.MethodHookParam, items: List<Any>): Any? {
        val type = (param.method as? java.lang.reflect.Method)?.returnType ?: return items
        if (List::class.java.isAssignableFrom(type)) return items
        val name = type.name
        if (!(name.contains("ParceledListSlice") || name.endsWith("Slice"))) return items
        try {
            return type.getConstructor(List::class.java).newInstance(items)
        } catch (_: Throwable) {
        }
        try {
            return type.getConstructor(Collection::class.java).newInstance(items)
        } catch (_: Throwable) {
        }
        Log.rate(
            "cell.slice.unavailable",
            60_000L,
            "cannot build an empty ${type.name} — leaving this call untouched (fail open, logged once)",
        )
        return UNBUILDABLE
    }
}

