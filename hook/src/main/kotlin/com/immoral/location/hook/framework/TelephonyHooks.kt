package com.immoral.location.hook.framework

import com.immoral.location.core.model.SimulatedCell
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
        Log.i("framework/telephony: install() reached (uid=" + android.os.Process.myUid() + ")")
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

    /**
     * Is this the name of a method that *returns cell information*?
     *
     * Measured on this ROM (HyperOS, `com.android.phone` pid 4940) the real getters are
     * `getAllCellInfo`, `getCachedCellInfo` and `getNeighboringCellInfo` — all three plain
     * `java.util.List` — while a naive "contains cell" match also catches
     * `getCellBroadcastIdRanges`, which returns broadcast-id ranges and must **never** be replaced
     * with synthetic `CellInfo`s (that would hand the platform objects of the wrong type).
     */
    private fun isCellInfoGetter(name: String): Boolean {
        val n = name.lowercase()
        if (n.contains("broadcast")) return false
        if (n.contains("simultaneous")) return false
        return n.contains("cellinfo") || n == "getcelllocation"
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
            listLike && isCellInfoGetter(m.name)
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
                        // Skip calls that originate inside this very process (platform internals):
                        // only answer cross-process requests from apps.
                        if (Scope.callingUid() == android.os.Process.myUid()) return
                        if (!Scope.isCallerSpoofed()) return
                        val source = cellsFromEngine() ?: return
                        val (cells, simulated) = source
                        if (!simulated || cells.isEmpty()) return
                        val infos = Fakes.cellInfos(cells)
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

    /**
     * Where do the cells come from in *this* process?
     *
     * `EngineRuntime` lives in `system_server` while the cell API on this ROM is served from
     * `com.android.phone` (`PhoneInterfaceManager`, 3 matching getters resolved), so in the phone
     * process `EngineRuntime.activeSnap()` is null and the state channel is the only source:
     * `EngineRuntime.publishCells` writes `Settings.Global.immoral_cells` and [readCellsSetting]
     * reads it. The engine-present branch is kept because `system_server` also hosts cell getters on
     * some builds.
     */
    private fun cellsFromEngine(): Pair<List<SimulatedCell>, Boolean>? {
        val snap = EngineRuntime.activeSnap()
        if (snap != null) return snap.cells to snap.config.cellSimulation
        // Different process: the engine cannot be reached, so read the state channel that
        // `EngineRuntime.publishCells` writes into Settings.Global.
        return cellsFromSettings()
    }

    private var lastCellsReadAt = 0L
    private var cachedCells: Pair<List<SimulatedCell>, Boolean>? = null

    /** Parses `immoral_cells` (`"<0|1>|type,mcc,mnc,lac,cid,pci,level,dbm,reg;…"`), cached 500 ms. */
    private fun cellsFromSettings(): Pair<List<SimulatedCell>, Boolean>? {
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastCellsReadAt < 500L) return cachedCells
        lastCellsReadAt = now
        val raw = readCellsSetting()
        val parsed = if (raw.isNullOrEmpty()) {
            // No row at all means "cell simulation is off" (the engine deletes the key instead of
            // writing `0|`, so nothing is left behind in a globally readable setting).
            null
        } else {
            val parts = raw.split('|', limit = 2)
            val enabled = parts[0] == "1"
            val cells = if (!enabled || parts.size < 2 || parts[1].isBlank()) {
                emptyList()
            } else {
                parts[1].split(';').mapNotNull { row ->
                    val f = row.split(',')
                    if (f.size < 9) {
                        null
                    } else {
                        SimulatedCell(
                            type = f[0],
                            mcc = f[1],
                            mnc = f[2],
                            lac = f[3].toIntOrNull() ?: 0,
                            cid = f[4].toLongOrNull() ?: 0L,
                            pci = f[5].toIntOrNull() ?: 0,
                            level = f[6].toIntOrNull() ?: 3,
                            dbm = f[7].toIntOrNull() ?: -75,
                            registered = f[8] == "1",
                        )
                    }
                }
            }
            cells to enabled
        }
        cachedCells = parsed
        return parsed
    }

    /**
     * Reads the cells state channel.
     *
     * The value is written by `EngineRuntime.publishCells` in `system_server` and read here in
     * `com.android.phone`, so two caches sit between the two processes. The one that matters is
     * `Settings.Global.getString`, which goes through the platform's cross-process
     * `NameValueCache`: when that cache is not invalidated (a vendor build that does not bump
     * `sys.settings_global_version`, a write that landed without a generation bump) this process
     * keeps serving the value it read first, forever — an app then sees one frozen cell set while
     * the engine publishes new ones. So the primary read is a **direct provider query** on the
     * per-name URI, which has no cache in front of it, and the cached `Settings` accessor is only
     * the fallback.
     */
    private fun readCellsSetting(): String? {
        val context = settingsContext() ?: return null
        val uri = android.provider.Settings.Global.getUriFor("immoral_cells")
        try {
            context.contentResolver.query(
                uri,
                arrayOf(android.provider.Settings.NameValueTable.VALUE),
                null,
                null,
                null,
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val value = cursor.getString(0)
                    Log.rate("cells.read.direct", 60_000L, "cells channel read via a direct provider query")
                    return value
                }
                // Row deleted: simulation is off.
                Log.rate("cells.read.absent", 60_000L, "cells channel: no " + "immoral_cells" + " row (simulation off)")
                return null
            }
        } catch (t: Throwable) {
            Log.once("cells.read.direct.err", "direct cells read failed: " + t.javaClass.simpleName)
        }
        return try {
            android.provider.Settings.Global.getString(context.contentResolver, "immoral_cells")
        } catch (t: Throwable) {
            Log.once("cells.read.err", "cannot read the cells setting: " + t.javaClass.simpleName)
            null
        }
    }

    /**
     * A `Context` for this (**binder**) thread, resolved once and cached.
     *
     * `ActivityThread.currentActivityThread()` is null until the process has finished attaching, and
     * `Method.invoke(null)` on the instance method `getSystemContext()` then throws — the same boot
     * race that used to kill the engine-side write. Resolve lazily, cache, and never throw.
     */
    @Volatile
    private var cachedContext: android.content.Context? = null

    private fun settingsContext(): android.content.Context? {
        cachedContext?.let { return it }
        try {
            val threadClass = Class.forName("android.app.ActivityThread")
            val current = threadClass.getMethod("currentActivityThread").invoke(null)
            if (current != null) {
                val system = threadClass.getMethod("getSystemContext").invoke(current)
                if (system is android.content.Context) {
                    cachedContext = system
                    return system
                }
            }
            val app = threadClass.getMethod("currentApplication").invoke(null)
            if (app is android.content.Context) {
                cachedContext = app
                return app
            }
        } catch (_: Throwable) {
        }
        try {
            val appGlobals = Class.forName("android.app.AppGlobals")
            val app = appGlobals.getMethod("getInitialApplication").invoke(null)
            if (app is android.content.Context) {
                cachedContext = app
                return app
            }
        } catch (_: Throwable) {
        }
        Log.rate("cells.context.miss", 30_000L, "cells channel: no Context in this process yet, cells not replaced")
        return null
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

