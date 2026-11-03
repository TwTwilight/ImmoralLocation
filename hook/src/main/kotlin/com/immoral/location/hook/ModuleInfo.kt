package com.immoral.location.hook

/**
 * Facts about this module APK and about the current process, discovered once and shared by both
 * layers.
 */
object ModuleInfo {
    /** Path of the module APK, recorded in zygote. */
    @Volatile
    var modulePath: String? = null

    /** Version string reported by `IImmoralEngine.apiVersion()` diagnostics and the install log. */
    @Volatile
    var version: String = "dev"

    /** Package the framework talks back to (the installed APK, debug or release). */
    @Volatile
    var appPackage: String = "io.github.twtwilight.immorallocation.debug"

    val candidateAppPackages = listOf(
        "io.github.twtwilight.immorallocation.debug",
        "io.github.twtwilight.immorallocation",
    )

    @Volatile
    var startupTimestampMs: Long = System.currentTimeMillis()

    const val SYSTEM_UID = 1000

    /** Process name of the system server host process (varies: "system" on HyperOS). */
    fun isSystemServerProcess(processName: String?): Boolean {
        if (processName == "android" || processName == "system" || processName == "system_server") return true
        val self = selfProcessName()
        return self == "android" || self == "system" || self.startsWith("system_server")
    }

    fun isModulePackage(packageName: String?): Boolean =
        packageName != null && packageName in candidateAppPackages

    @Volatile
    private var selfName: String? = null

    /**
     * Real process name of the current process, read from `/proc/self/cmdline`.
     *
     * `XC_LoadPackage.LoadPackageParam.processName` is not trustworthy for packages hosted by
     * `system_server`: it reports the *package* name (e.g. `com.android.providers.settings`), which
     * is how the client layer ended up inside the platform process. The kernel view cannot lie.
     */
    fun selfProcessName(): String {
        selfName?.let { return it }
        val resolved = try {
            val bytes = java.io.File("/proc/self/cmdline").readBytes()
            var end = bytes.size
            while (end > 0 && bytes[end - 1].toInt() == 0) end--
            String(bytes, 0, end, Charsets.UTF_8)
        } catch (_: Throwable) {
            ""
        }
        selfName = resolved
        return resolved
    }

    /** True for the platform `system` user; `system_server` and every process it hosts run as it. */
    fun isPlatformUid(): Boolean = try {
        android.os.Process.myUid() == SYSTEM_UID
    } catch (_: Throwable) {
        false
    }
}
