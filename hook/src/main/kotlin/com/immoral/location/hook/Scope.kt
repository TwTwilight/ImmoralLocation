package com.immoral.location.hook

import android.os.Binder
import android.os.Process

/**
 * Package / uid resolution and the independent-mode allow list (独立模拟).
 *
 * The framework layer runs in `system_server` and owns the authoritative config; this object is the
 * only place that turns a uid/package into a "should this caller see the fake position?" decision.
 * There is no client-layer mirror any more — the module is framework-only, so the policy always
 * comes from the engine that lives in `system_server`.
 */
object Scope {

    const val SYSTEM_UID = 1000

    @Volatile
    private var pm: android.content.pm.PackageManager? = null
    private val uidToPkg = HashMap<Int, String?>(64)
    private val pkgToUid = HashMap<String, Int>(64)
    @Volatile
    private var lastRefreshMs = 0L

    fun attachPackageManager(manager: android.content.pm.PackageManager?) {
        if (manager != null) {
            pm = manager
            synchronized(uidToPkg) {
                uidToPkg.clear()
                pkgToUid.clear()
            }
        }
    }

    fun packageManager(): android.content.pm.PackageManager? {
        val current = pm
        if (current != null) return current
        val fromBridge = BridgeHost.packageManager()
        if (fromBridge != null) {
            pm = fromBridge
            return fromBridge
        }
        return null
    }

    /** Binder.getCallingUid(), never throws. */
    fun callingUid(): Int = try {
        Binder.getCallingUid()
    } catch (_: Throwable) {
        Process.myUid()
    }

    /** Best-effort uid -> package. Cached; unknown uids refresh the cache once. */
    fun packageForUid(uid: Int): String? {
        if (uid == SYSTEM_UID || uid == 0) return "android"
        synchronized(uidToPkg) {
            if (uidToPkg.containsKey(uid)) return uidToPkg[uid]
        }
        val resolved = resolveUid(uid)
        synchronized(uidToPkg) { uidToPkg[uid] = resolved }
        return resolved
    }

    fun uidForPackage(pkg: String): Int {
        synchronized(pkgToUid) { pkgToUid[pkg]?.let { return it } }
        val manager = packageManager() ?: return -1
        val uid = try {
            manager.getApplicationInfo(pkg, 0).uid
        } catch (_: Throwable) {
            -1
        }
        if (uid >= 0) synchronized(pkgToUid) { pkgToUid[pkg] = uid }
        return uid
    }

    private fun resolveUid(uid: Int): String? {
        val manager = packageManager() ?: return null
        try {
            manager.getNameForUid(uid)?.let { name ->
                if (name.contains(':')) {
                    // Multi-process app: strip the process suffix, keep the package.
                    return name.substringBefore(':')
                }
                return name
            }
        } catch (_: Throwable) {
        }
        try {
            return manager.getPackagesForUid(uid)?.firstOrNull()
        } catch (_: Throwable) {
        }
        return null
    }

    fun refresh() {
        synchronized(uidToPkg) {
            uidToPkg.clear()
            pkgToUid.clear()
        }
        lastRefreshMs = System.currentTimeMillis()
    }

    // ------------------------------------------------------------ policy

    /** Config used for independent mode. The engine is the only source of truth. */
    private fun policy(): Pair<Boolean, Set<String>> {
        EngineRuntime.policyOrNull()?.let { return it }
        // No engine in this process (should not happen for framework hooks): spoof everything.
        return false to emptySet()
    }

    /**
     * Should locations for [packageName] be spoofed?
     *
     * Unknown/system callers (system_server, providers, uids we cannot resolve) are treated as
     * spoofed: they are the plumbing that carries our fake fix to the app.
     */
    fun isSpoofedPackage(packageName: String?): Boolean {
        if (packageName == null || packageName == "android" || packageName == "com.android.phone") return true
        if (ModuleInfo.isModulePackage(packageName)) return true
        val (independent, allowed) = policy()
        if (!independent) return true
        if (allowed.isEmpty()) return false
        if (allowed.contains(packageName)) return true
        // allow sub-packages explicitly listed, and process names
        return allowed.any { packageName == it || packageName.startsWith("$it:") }
    }

    fun isSpoofedUid(uid: Int): Boolean {
        if (uid == 0 || uid == SYSTEM_UID || uid == Process.SYSTEM_UID) return true
        val pkg = packageForUid(uid)
        if (pkg == null) return true
        return isSpoofedPackage(pkg)
    }

    /** Is the package currently calling (through this method) subject to spoofing? */
    fun isCallerSpoofed(): Boolean = isSpoofedUid(callingUid())

}
