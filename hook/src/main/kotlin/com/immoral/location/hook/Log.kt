package com.immoral.location.hook

import de.robv.android.xposed.XposedBridge
import java.util.concurrent.ConcurrentHashMap

/**
 * Logging facade. Everything goes through [de.robv.android.xposed.XposedBridge.log] so the lines
 * show up in LSPosed's log as well as logcat (tag "Xposed").
 *
 * Never throws: logging must not be able to break a hook.
 */
object Log {
    const val TAG = "[ImmoralLocation]"

    /** hook targets we resolved, in install order — dumped as acceptance evidence. */
    private val resolved = ArrayList<String>()
    private val once = ConcurrentHashMap<String, Boolean>()
    private val rateStamps = ConcurrentHashMap<String, Long>()
    @Volatile
    private var startMs = -1L

    fun i(msg: String) = safe {
        if (startMs < 0) startMs = System.currentTimeMillis()
        XposedBridge.log("$TAG $msg")
    }

    /** A hook target line: `[ImmoralLocation] hook OK  LocationProviderManager#onReportLocation(LocationResult)` */
    fun hook(ok: Boolean, target: String, detail: String = "") {
        val line = if (ok) "hook OK   $target" else "hook MISS $target" + if (detail.isEmpty()) "" else " ($detail)"
        synchronized(resolved) { if (resolved.size < 400) resolved.add(line) }
        i(line)
    }

    /** Whole-target summary; used at the end of an installer run. */
    fun summary(layer: String, resolvedCount: Int, missedCount: Int) {
        i("==== $layer: $resolvedCount hook targets resolved, $missedCount missed ====")
    }

    fun dump(targets: List<String>) {
        targets.forEach { i("  - $it") }
    }

    fun e(msg: String, t: Throwable? = null) = safe {
        XposedBridge.log("$TAG E $msg")
        if (t != null) XposedBridge.log(t)
    }

    fun w(msg: String) = i("W $msg")

    /** Log only the first time a key is seen — for per-call failure paths. */
    fun once(key: String, msg: String) {
        if (once.putIfAbsent(key, true) == null) i("once/$key $msg")
    }

    /** Rate limited log, for hot paths that could otherwise spam logcat. */
    fun rate(key: String, minIntervalMs: Long, msg: String) {
        val now = System.currentTimeMillis()
        val last = rateStamps[key]
        if (last != null && now - last < minIntervalMs) return
        rateStamps[key] = now
        i(msg)
    }

    private inline fun safe(block: () -> Unit) {
        try {
            block()
        } catch (_: Throwable) {
            // Last resort: never let logging escape.
        }
    }
}

