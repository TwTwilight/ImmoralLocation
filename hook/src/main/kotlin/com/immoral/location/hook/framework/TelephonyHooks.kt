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
                    val methodName = (param.method as? java.lang.reflect.Method)?.name ?: m.name
                    try {
                        val caller = Scope.callingUid()
                        // Skip calls that originate inside this very process (platform internals):
                        // only answer cross-process requests from apps.
                        if (caller == android.os.Process.myUid()) {
                            Log.rate("cells.skip.self", 60_000L, "cells hook entered #$methodName but the caller is this process (uid=$caller)")
                            return
                        }
                        if (!Scope.isCallerSpoofed()) {
                            Log.rate("cells.skip.scope", 15_000L, "cells hook entered #$methodName but uid=$caller is outside the policy")
                            return
                        }
                        val source = cellsFromEngine(caller, methodName) ?: run {
                            Log.rate(
                                "cells.skip.nostate",
                                15_000L,
                                "cells hook entered #$methodName uid=$caller but the state channel has no set " +
                                    "(row absent or simulation off) — " + lastReadNote,
                            )
                            return
                        }
                        val (cells, simulated) = source
                        if (!simulated || cells.isEmpty()) {
                            Log.rate(
                                "cells.skip.disabled",
                                15_000L,
                                "cells hook entered #$methodName uid=$caller but simulation is off (simulated=$simulated count=${cells.size})",
                            )
                            return
                        }
                        val infos = Fakes.cellInfos(cells)
                        if (infos.isEmpty()) {
                            Log.rate(
                                "cells.skip.build",
                                15_000L,
                                "cells hook entered #$methodName uid=$caller but no CellInfo could be built for ${cells.size} cells",
                            )
                            return
                        }
                        val value = asReturnShape(param, infos)
                        if (value === UNBUILDABLE) {
                            Log.rate("cells.skip.shape", 60_000L, "cells hook entered #$methodName but the return shape is unbuildable")
                            return
                        }
                        param.result = value
                        Log.rate(
                            "cell.replaced",
                            20_000L,
                            "$className#$methodName -> ${infos.size} synthetic cells (${m.returnType.simpleName}) serving=" + servingLabel(cells),
                        )
                    } catch (t: Throwable) {
                        Log.rate("cell.replace", 15_000L, "cell replace failed on #$methodName: ${t.javaClass.simpleName}")
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
    private fun cellsFromEngine(callerUid: Int, method: String): Pair<List<SimulatedCell>, Boolean>? {
        val snap = EngineRuntime.activeSnap()
        if (snap != null) return snap.cells to snap.config.cellSimulation
        // Different process: the engine cannot be reached, so read the state channel that
        // `EngineRuntime.publishCells` writes into Settings.Global.
        return cellsFromSettings(callerUid, method)
    }

    /** Last raw payload seen from the provider (`null` = row absent), and its parsed form. */
    @Volatile
    private var lastRaw: String? = null

    @Volatile
    private var lastParsed: Pair<List<SimulatedCell>, Boolean>? = null

    /** One line of state for the `cells.skip.nostate` log: what the last read actually saw. */
    @Volatile
    private var lastReadNote: String = "no read yet"

    private val hookCalls = java.util.concurrent.atomic.AtomicLong()

    /**
     * Reads and parses the cells state channel for **every** hooked call.
     *
     * Deliberately **no TTL cache**. A 500 ms (or longer) cache here is indistinguishable, from the
     * app's side, from "the engine does not follow the position" — the exact defect this channel
     * exists to fix — and a cell read is not a hot path like location delivery (a few calls per
     * second at most). What *is* cached is only the last successful parse, used when the provider
     * read fails: falling back to the last synthetic set keeps the spoof closed instead of leaking
     * the real cells.
     */
    private fun cellsFromSettings(callerUid: Int, method: String): Pair<List<SimulatedCell>, Boolean>? {
        val calls = hookCalls.incrementAndGet()
        val via = "read #$calls via $method caller=" + callerLabel(callerUid)
        val read = readCellsSetting()
        if (read.failed) {
            Log.rate(
                "cells.read.failed",
                15_000L,
                "cells channel: provider read FAILED ($via: ${read.error}) — keeping the last known set " +
                    "instead of leaking real cells",
            )
            lastReadNote = "$via failed: ${read.error}"
            return lastParsed
        }
        val raw = read.raw
        lastReadNote = "$via source=${read.source} -> " + describeRaw(raw)
        if (raw != lastRaw) {
            lastRaw = raw
            lastParsed = parseCells(raw)
            // One line per *change*: proves the phone process sees each new set as it is published,
            // and when. `first=` is the serving cell, i.e. what an app will report.
            Log.rate(
                "cells.probe.changed",
                1_000L,
                "cells.probe CHANGED $via source=${read.source}" + read.skipped() + " " + describeRaw(raw) +
                    " first=" + servingLabel(lastParsed?.first) +
                    " count=" + (lastParsed?.first?.size ?: 0),
            )
            // Cross-check the other read paths on the same call: if the provider query and the cached
            // `Settings.Global` accessor disagree, the cache is the stale half and this line says so.
            crossCheckReadPaths(raw)
        } else {
            Log.rate(
                "cells.probe.steady",
                10_000L,
                "cells.probe steady $via source=${read.source}" + read.skipped() + " " + describeRaw(raw) +
                    " first=" + servingLabel(lastParsed?.first) +
                    " count=" + (lastParsed?.first?.size ?: 0) + " (unchanged since the last change)",
            )
        }
        return lastParsed
    }

    /** ` skipped=…` when a preferred read path had to be bypassed (never silent). */
    private fun CellsReadResult.skipped(): String = if (error.isEmpty()) "" else " skipped=[" + error.trim() + "]"

    /** `uid:1000:android` — enough to tell the verifier apart from platform callers in the probe. */
    private fun callerLabel(uid: Int): String = "uid=$uid:" + (try {
        Scope.packageForUid(uid) ?: "?"
    } catch (_: Throwable) {
        "?"
    })

    /**
     * Parses `"<0|1>|type,mcc,mnc,lac,cid,pci,level,dbm,reg;…"`; `null` when there is no row (the
     * engine deletes the key when simulation is off, so nothing is left behind in a globally readable
     * setting).
     */
    private fun parseCells(raw: String?): Pair<List<SimulatedCell>, Boolean>? {
        if (raw.isNullOrEmpty()) return null
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
        return cells to enabled
    }

    /** `raw=hash=1a2b3c4d len=97` — short, stable and enough to tell two sets apart in a log line. */
    private fun describeRaw(raw: String?): String =
        if (raw == null) "raw=absent" else "raw=hash=" + Integer.toHexString(raw.hashCode()) + " len=" + raw.length

    /** `LTE/460/11/lac=50274/cid=129538244/pci=436` for the serving cell, or `none`. */
    private fun servingLabel(cells: List<SimulatedCell>?): String {
        val serving = cells?.firstOrNull { it.registered } ?: return "none"
        return serving.type + "/" + serving.mcc + "/" + serving.mnc + "/lac=" + serving.lac +
            "/cid=" + serving.cid + "/pci=" + serving.pci
    }

    /**
     * Three-way comparison of the read paths, run **on every change** of the channel payload.
     *
     * The engine writes the row in `system_server`; the reader here has three ways to see it, and
     * they can disagree on a vendor build:
     *  - `query`      — per-name provider query (`content://settings/global/immoral_cells`), no cache;
     *  - `collection` — provider query with a `name=?` selection on `content://settings/global`;
     *  - `settings`   — `Settings.Global.getString`, which goes through the cross-process
     *    `NameValueCache` and is the one known to freeze when the write does not bump the generation.
     *
     * One line names exactly which half diverged, so "the phone side is stale" stops being an
     * inference. Diagnostic only: the value actually served comes from [readCellsSetting].
     */
    private fun crossCheckReadPaths(expected: String?) {
        val context = cachedContext ?: return
        val collection = readCollectionQuery(context)
        val viaCache = try {
            android.provider.Settings.Global.getString(context.contentResolver, CELLS_KEY)
        } catch (t: Throwable) {
            Log.once("cells.probe.crosscheck.err", "cells.probe cross-check failed: " + t.javaClass.simpleName)
            return
        }
        val agree = collection == expected && viaCache == expected
        Log.rate(
            if (agree) "cells.probe.paths.agree" else "cells.probe.paths.diverge",
            5_000L,
            "cells.probe read paths: query=" + describeRaw(expected) +
                " collection=" + describeRaw(collection) +
                " settings=" + describeRaw(viaCache) +
                (
                    if (agree) {
                        " (all agree)"
                    } else {
                        " (DIVERGE: " +
                            (if (collection != expected) "collection " else "") +
                            (if (viaCache != expected) "Settings.Global.getString " else "") +
                            "is the stale half)"
                    }
                    ),
        )
    }

    /** Provider query with an explicit `name=?` selection (second, independent path). */
    private fun readCollectionQuery(context: android.content.Context): String? = try {
        context.contentResolver.query(
            android.provider.Settings.Global.CONTENT_URI,
            arrayOf(android.provider.Settings.NameValueTable.VALUE),
            android.provider.Settings.NameValueTable.NAME + "=?",
            arrayOf(CELLS_KEY),
            null,
        )?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
    } catch (t: Throwable) {
        Log.rate("cells.read.collection.err", 30_000L, "collection cells read failed: " + t.javaClass.simpleName)
        null
    }

    /**
     * Outcome of one state-channel read.
     *
     * @property raw the payload, `null` when the row is absent (simulation off)
     * @property failed `true` when no read path could answer at all
     * @property source which path answered: `query`, `collection` or `settings`
     * @property error why earlier paths were skipped, for the log line
     */
    private class CellsReadResult(
        val raw: String?,
        val failed: Boolean,
        val source: String = "query",
        val error: String = "",
    )

    /**
     * Reads the cells state channel, preferring paths that cannot be cached.
     *
     * Order: per-name provider query → collection query with a selection → `Settings.Global.getString`
     * (the `NameValueCache`, kept only as a last resort because it is the path that can freeze). The
     * `source` field of the result is logged on every change, so a fallback is never silent.
     */
    private fun readCellsSetting(): CellsReadResult {
        val context = settingsContext()
            ?: return CellsReadResult(null, true, "none", "no Context in this process")
        val notes = StringBuilder()
        // 1) per-name URI — the cheapest provider round trip and the least likely to be cached.
        try {
            context.contentResolver.query(
                android.provider.Settings.Global.getUriFor(CELLS_KEY),
                arrayOf(android.provider.Settings.NameValueTable.VALUE),
                null,
                null,
                null,
            )?.use { cursor ->
                return CellsReadResult(
                    if (cursor.moveToFirst()) cursor.getString(0) else null,
                    false,
                    "query",
                )
            }
            notes.append("per-name query returned a null cursor; ")
        } catch (t: Throwable) {
            notes.append("per-name query ").append(t.javaClass.simpleName).append(": ").append(t.message).append("; ")
            Log.rate("cells.read.query.err", 30_000L, "cells channel: per-name query failed: " + t.javaClass.simpleName + ": " + t.message)
        }
        // 2) collection URI with an explicit selection.
        try {
            context.contentResolver.query(
                android.provider.Settings.Global.CONTENT_URI,
                arrayOf(android.provider.Settings.NameValueTable.VALUE),
                android.provider.Settings.NameValueTable.NAME + "=?",
                arrayOf(CELLS_KEY),
                null,
            )?.use { cursor ->
                return CellsReadResult(
                    if (cursor.moveToFirst()) cursor.getString(0) else null,
                    false,
                    "collection",
                    notes.toString(),
                )
            }
            notes.append("collection query returned a null cursor; ")
        } catch (t: Throwable) {
            notes.append("collection query ").append(t.javaClass.simpleName).append(": ").append(t.message).append("; ")
        }
        // 3) the cached accessor: last resort only.
        return try {
            CellsReadResult(
                android.provider.Settings.Global.getString(context.contentResolver, CELLS_KEY),
                false,
                "settings",
                notes.toString(),
            )
        } catch (t: Throwable) {
            notes.append("settings accessor ").append(t.javaClass.simpleName).append(": ").append(t.message)
            CellsReadResult(null, true, "none", notes.toString())
        }
    }

    /** The `Settings.Global` key the engine publishes into and this process reads. */
    private const val CELLS_KEY = "immoral_cells"

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

