package com.immoral.location.hook

import java.io.File
import java.util.zip.ZipFile

/**
 * Bridge to the native layer (`libimmoralhook.so`, built with xmake + C++23 against
 * [ShadowHook](https://github.com/bytedance/android-inline-hook)).
 *
 * The native hook lives in `system_server` and rewrites step events inside
 * `SensorService::SensorEventConnection::sendEvents`, i.e. **before** they are delivered to any
 * app. That is what lets 步频模拟 work with the module scoped to `system` only, without touching
 * any third-party process (the Java client-layer hooks stay as a fallback for ROMs where the
 * native hook cannot install).
 *
 * Everything here is best effort: a missing or rejected library must never break the framework
 * layer, so every entry point returns a status instead of throwing.
 */
object NativeBridge {

    private const val LIB_NAME = "immoralhook"

    /** 0 = not tried, 1 = loaded, -1 = unavailable. */
    @Volatile
    private var loadState = 0

    @Volatile
    private var hooked = false

    @Volatile
    private var loadDetail: String = "not loaded"

    /** True once [ensureLoaded] brought the library in. */
    val isLoaded: Boolean get() = loadState == 1

    /** True when the native hooks installed in this process. */
    val isHooked: Boolean get() = hooked

    /** Human readable state for logs / diagnostics. */
    fun status(): String = "loaded=${isLoaded} hooked=$hooked ($loadDetail)"

    /**
     * Loads the native library, trying every path that works across ROMs:
     *  1. `System.loadLibrary` — succeeds when LSPosed gave the module class loader a native
     *     library search path (needs `android:extractNativeLibs="true"` in the manifest);
     *  2. `System.load(<apk dir>/lib/arm64/libimmoralhook.so)` — the installed APK's extracted
     *     library directory;
     *  3. extract the `.so` from the module APK into a writable temp file and `System.load` it.
     */
    @Synchronized
    fun ensureLoaded(): Boolean {
        if (loadState != 0) return loadState == 1
        val attempts = mutableListOf<String>()

        // 1) plain loadLibrary
        runCatching {
            System.loadLibrary(LIB_NAME)
            attempts += "loadLibrary ok"
            loadState = 1
        }.onFailure { attempts += "loadLibrary: ${it.javaClass.simpleName}: ${it.message}" }

        // 2) $MODULE_APK_DIR/lib/arm64/libimmoralhook.so
        if (loadState != 1) {
            moduleLibDirCandidates().forEach { file ->
                if (loadState != 1 && file.isFile) {
                    runCatching {
                        System.load(file.absolutePath)
                        attempts += "load(${file.absolutePath}) ok"
                        loadState = 1
                    }.onFailure { attempts += "load(${file.absolutePath}): ${it.javaClass.simpleName}: ${it.message}" }
                }
            }
        }

        // 3) extract from the APK
        if (loadState != 1) {
            runCatching {
                val extracted = extractFromModuleApk()
                if (extracted != null) {
                    System.load(extracted.absolutePath)
                    attempts += "extracted+load ok (${extracted.absolutePath})"
                    loadState = 1
                } else {
                    attempts += "extract: no entry"
                }
            }.onFailure { attempts += "extract: ${it.javaClass.simpleName}: ${it.message}" }
        }

        if (loadState != 1) loadState = -1
        loadDetail = "apk=${ModuleInfo.modulePath}; " + attempts.joinToString("; ")
        Log.i("native bridge ${if (loadState == 1) "loaded" else "UNAVAILABLE"}: $loadDetail")
        return loadState == 1
    }

    /** Installs the native hooks (idempotent). Returns true when they are live. */
    @Synchronized
    fun install(): Boolean {
        if (hooked) return true
        if (!ensureLoaded()) return false
        hooked = runCatching { nativeInit() }.getOrElse {
            Log.e("native init threw", it)
            false
        }
        val err = runCatching { nativeLastError() }.getOrNull().orEmpty()
        Log.i("native hooks hooked=$hooked version=${runCatching { nativeVersion() }.getOrNull()} error=$err")
        return hooked
    }

    /** Pushes the engine's authoritative step state into the native hook (called every tick). */
    fun pushStepState(steps: Long, cadenceBpm: Int, enabled: Boolean) {
        if (!hooked) return
        runCatching { nativeSetStepState(steps, cadenceBpm, enabled) }
    }

    /** Rewrite counters for the diagnostics line. */
    fun stats(): String = runCatching { if (hooked) nativeStats() else "not hooked" }.getOrDefault("n/a")

    // ---------------------------------------------------------------- paths / extraction

    private fun moduleLibDirCandidates(): List<File> {
        val out = mutableListOf<File>()
        val apkPath = ModuleInfo.modulePath
        if (!apkPath.isNullOrEmpty()) {
            val apk = File(apkPath)
            val dir = apk.parentFile
            if (dir != null) {
                for (abi in abiCandidates()) {
                    out += File(dir, "lib/$abi/lib$LIB_NAME.so")
                }
            }
        }
        return out
    }

    private fun abiCandidates(): List<String> = when {
        android.os.Build.SUPPORTED_64_BIT_ABIS.contains("arm64-v8a") -> listOf("arm64-v8a", "arm64")
        else -> listOf("armeabi-v7a", "arm")
    }

    private fun extractFromModuleApk(): File? {
        val apkPath = ModuleInfo.modulePath ?: return null
        for (abi in abiCandidates()) {
            val entryName = "lib/$abi/lib$LIB_NAME.so"
            ZipFile(apkPath).use { zip ->
                val entry = zip.getEntry(entryName) ?: return@use
                val targetDir = tempDir()
                val out = File(targetDir, "lib$LIB_NAME-$abi.so")
                java.io.FileOutputStream(out).use { fos ->
                    zip.getInputStream(entry).use { it.copyTo(fos) }
                }
                out.setReadable(true, false)
                out.setExecutable(true, false)
                return out
            }
        }
        return null
    }

    /**
     * A directory the current process can actually execute from. In `system_server` that is
     * usually `/data/local/tmp`; elsewhere the app's own cache dir works. Falls back to the
     * platform temp dir.
     */
    private fun tempDir(): File {
        val candidates = listOf(
            File("/data/local/tmp"),
            File(System.getProperty("java.io.tmpdir") ?: "/data/local/tmp"),
        )
        for (dir in candidates) {
            if (dir.isDirectory && dir.canWrite()) return dir
        }
        return File("/data/local/tmp").also { it.mkdirs() }
    }

    // ---------------------------------------------------------------- native

    private external fun nativeInit(): Boolean

    private external fun nativeSetStepState(steps: Long, cadenceBpm: Int, enabled: Boolean)

    private external fun nativeStats(): String

    private external fun nativeVersion(): String

    private external fun nativeLastError(): String
}
