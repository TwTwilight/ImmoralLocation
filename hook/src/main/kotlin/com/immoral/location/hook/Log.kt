package com.immoral.location.hook

import de.robv.android.xposed.XposedBridge
import java.util.concurrent.ConcurrentHashMap

/**
 * Logging facade. Everything goes through [de.robv.android.xposed.XposedBridge.log] so the lines
 * show up in LSPosed's log as well as logcat (tag "Xposed").
 *
 * Never throws: logging must not be able to break a hook.
 *
 * ## 隐蔽性 / verbosity policy
 *
 * Every line carries the tag `[ImmoralLocation]`, which is a module fingerprint: whoever reads the
 * log (adb, root, LSPosed's own log viewer — a normal app cannot read another process' logcat since
 * Android 4.1) sees that this device runs a fake-location module and *which* hooks it installed.
 * The install banner is ~40 lines per boot and the per-call lines run at engine rate, so a release
 * build should not paint that picture by default.
 *
 * Two levels exist:
 *
 *  * **detail** ([i], [w], [once], [rate], [hook], [summary], [dump]) — install evidence plus the
 *    per-call lines the e2e matrix asserts on. Emitted when
 *      - `debug.immoral.log=1` (root/adb escape hatch, see `PROP_VERBOSE`), or
 *      - the module APK path is a `.debug` build (what `e2e/install.ps1` installs).
 *  * **errors** ([e]) — always emitted, deduplicated by the callers, so a broken hook stays
 *    diagnosable on a production install.
 *
 * A release build is therefore silent in logcat unless the property is set, and quietness never
 * changes behaviour: this object only decides whether text is written.
 *
 * [extrasEnabled] is a **separate, stricter** switch for the `immoral.*` keys inside a fabricated
 * `android.location.Location`'s extras: those are readable by *any* app that receives a fix, so
 * they stay off unless `debug.immoral.extras=1` — in every build, independently of the log level.
 * See `docs/STEALTH.md`.
 */
object Log {
    const val TAG = "[ImmoralLocation]"

    /** `setprop debug.immoral.log 1` -> emit the detail level (install banner + per-call lines). */
    private const val PROP_VERBOSE = "debug.immoral.log"

    /** `setprop debug.immoral.extras 0|1` -> force the `immoral.*` location extras off/on. */
    private const val PROP_EXTRAS = "debug.immoral.extras"

    @Volatile
    private var detailMemo: Boolean? = null

    @Volatile
    private var extrasMemo: Boolean? = null

    /** hook targets we resolved, in install order — dumped as acceptance evidence. */
    private val resolved = ArrayList<String>()
    private val once = ConcurrentHashMap<String, Boolean>()
    private val rateStamps = ConcurrentHashMap<String, Long>()
    @Volatile
    private var startMs = -1L

    /**
     * Reads a system property without linking against the hidden `android.os.SystemProperties`
     * class (absent from the compile classpath, and called from processes whose hidden-API policy
     * we do not control). Returns null when unset or unavailable.
     */
    private fun systemProp(name: String): String? = try {
        val cls = Class.forName("android.os.SystemProperties")
        val get = cls.getMethod("get", String::class.java, String::class.java)
        (get.invoke(null, name, "") as? String)?.trim()?.takeIf { it.isNotEmpty() }
    } catch (_: Throwable) {
        System.getProperty(name)?.trim()?.takeIf { it.isNotEmpty() }
    }

    /** `null` when the property is unset, so the caller can fall through to its own default. */
    private fun flagOf(name: String): Boolean? = when (systemProp(name)?.lowercase()) {
        null -> null
        "1", "true", "yes", "on" -> true
        else -> false
    }

    /**
     * True for the `.debug` APK that `e2e/install.ps1` installs.
     *
     * LSPosed hands the module APK path to `IXposedHookZygoteInit`, which [ModuleInfo.modulePath]
     * records. The path looks like
     * `/data/app/~~<hash>==/io.github.twtwilight.immorallocation.debug-<hash>==/base.apk`, so the
     * package directory carries the applicationId — a `.debug` suffix is recognisable without a
     * build-time flag the hook module cannot have (it does not generate a BuildConfig). Derived
     * from the path instead of a hard-coded id so a package rename cannot silently disable the
     * e2e's log evidence.
     */
    private val debugBuild: Boolean
        get() {
            val dir = ModuleInfo.modulePath?.substringBeforeLast('/') ?: return false
            val packageDir = dir.substringAfterLast('/')
            return packageDir.substringBefore('-').endsWith(".debug")
        }

    /** Install banner + per-call detail. See the class doc for the policy. */
    val detail: Boolean
        get() = detailMemo ?: (flagOf(PROP_VERBOSE) ?: debugBuild).also { detailMemo = it }

    /**
     * Whether fabricated Locations carry the `immoral.*` state-bus extras.
     *
     * **Defaults to OFF, in every build.** Any app that receives a fix can read those keys
     * (`location.extras`) without any permission, and they name the module plus its per-feature
     * switches — the worst leak this module has. The e2e harness owns the switch: `e2e/install.ps1`
     * sets `debug.immoral.extras=1` before a matrix run and clears it afterwards
     * (`docs/STEALTH.md` S1, `docs/E2E.md`).
     *
     * Emits exactly one rate-limited line naming the active mode, so a log from a future run can
     * prove which side of the switch it was on (visible at the detail level, see [detail]).
     */
    fun extrasEnabled(): Boolean {
        extrasMemo?.let { return it }
        val enabled = flagOf(PROP_EXTRAS) ?: false
        extrasMemo = enabled
        rate(
            "extras.mode",
            60_000L,
            if (enabled) {
                "location extras: immoral.* INCLUDED ($PROP_EXTRAS=1)"
            } else {
                "location extras: immoral.* OMITTED (production default; set $PROP_EXTRAS=1 for e2e)"
            },
        )
        return enabled
    }

    fun i(msg: String) {
        if (!detail) return
        safe {
            if (startMs < 0) startMs = System.currentTimeMillis()
            XposedBridge.log("$TAG $msg")
        }
    }

    /** A hook target line: `[ImmoralLocation] hook OK  LocationProviderManager#onReportLocation(LocationResult)` */
    fun hook(ok: Boolean, target: String, detail: String = "") {
        if (!this.detail) return
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

    /** Errors are always emitted: a production install must stay diagnosable. */
    fun e(msg: String, t: Throwable? = null) = safe {
        XposedBridge.log("$TAG E $msg")
        if (t != null) XposedBridge.log(t)
    }

    fun w(msg: String) = i("W $msg")

    /** Log only the first time a key is seen — for per-call failure paths. */
    fun once(key: String, msg: String) {
        if (!detail) return
        if (once.putIfAbsent(key, true) == null) i("once/$key $msg")
    }

    /** Rate limited log, for hot paths that could otherwise spam logcat. */
    fun rate(key: String, minIntervalMs: Long, msg: String) {
        if (!detail) return
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
