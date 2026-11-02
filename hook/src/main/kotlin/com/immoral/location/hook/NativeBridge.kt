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
        if (!ensureLoaded()) {
            // No native layer: still wire the framework-side step diagnostics, so the acceptance
            // run can prove *which* half of 步频模拟 is missing instead of reading delta=0 blind.
            SensorBoot.install()
            return false
        }
        hooked = runCatching { nativeInit() }.getOrElse {
            Log.e("native init threw", it)
            false
        }
        val err = runCatching { nativeLastError() }.getOrNull().orEmpty()
        Log.i("native hooks hooked=$hooked version=${runCatching { nativeVersion() }.getOrNull()} error=$err")
        SensorBoot.install()
        return hooked
    }

    /** Pushes the engine's authoritative step state into the native hook (called every tick). */
    fun pushStepState(steps: Long, cadenceBpm: Int, enabled: Boolean) {
        if (!hooked) return
        runCatching { nativeSetStepState(steps, cadenceBpm, enabled) }
    }

    /**
     * Tells the native layer the HAL handles of `TYPE_STEP_COUNTER`, which is what makes
     * **fabrication** possible (see `native/src/sensor_hook.cpp`).
     *
     * Rewriting alone cannot satisfy 步频模拟 on a stationary device: `TYPE_STEP_COUNTER` reports
     * on change, so a phone on a desk produces no event at all and there is nothing to rewrite.
     * With the handles, the native hook can hand fabricated events to `SensorService`'s own
     * per-connection `sendEvents`, which delivers one only to apps that registered that sensor.
     *
     * **All** handles of the type are pushed, not just `getDefaultSensor`'s: this device exposes a
     * non-wakeup (`0xbf`) and a wake-up (`0xc0`) step counter, and measured on 2026-11-02 injecting
     * only the default handle produced 27 injections and zero app-visible events — the app was
     * registered for the other variant and `SensorEventConnection`'s handle filter dropped ours.
     *
     * Best effort: an empty array simply disarms fabrication.
     */
    fun setStepSensorHandles(handles: IntArray) {
        if (!hooked) return
        runCatching { nativeSetStepSensors(handles) }
            .onFailure { Log.once("native.step.handles", "nativeSetStepSensors failed: ${it.javaClass.simpleName}") }
    }

    /** Fabrication counters (`handles=… observed=… injected=…`), for the acceptance-run seam line. */
    fun stepSeam(): String =
        runCatching { if (hooked) nativeStepSeam() else "not hooked" }.getOrDefault("n/a")

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

    private var privateDir: File? = null

    /**
     * A private, randomly named directory the current process can execute from.
     *
     * A predictable path is a fingerprint: any app can stat `/data/local/tmp/libimmoralhook-*.so`
     * and learn that a framework-level spoofer is installed. Instead we extract into a 0700
     * directory with a random name, created once per process, and prune directories left behind by
     * earlier boots. `system_server` runs as `system`, so 0700 keeps the library unreadable and
     * unexecutable for every other uid.
     */
    private fun tempDir(): File {
        privateDir?.let { return it }
        val root = File("/data/local/tmp")
        if (root.isDirectory && root.canWrite()) {
            pruneStaleDirs(root)
            val dir = File(root, "l" + java.lang.Long.toHexString(java.security.SecureRandom().nextLong()))
            if (dir.mkdirs()) {
                dir.setReadable(false, false)
                dir.setWritable(false, false)
                dir.setExecutable(false, false)
                dir.setReadable(true, true)
                dir.setWritable(true, true)
                dir.setExecutable(true, true)
                privateDir = dir
                return dir
            }
        }
        val fallback = File(System.getProperty("java.io.tmpdir") ?: "/data/local/tmp")
        if (fallback.isDirectory && fallback.canWrite()) return fallback
        return root.also { it.mkdirs() }
    }

    /** Best effort: remove `l<hex>` directories from earlier boots that still hold our library. */
    private fun pruneStaleDirs(root: File) {
        try {
            for (candidate in root.listFiles() ?: return) {
                if (!candidate.isDirectory) continue
                if (!candidate.name.matches(Regex("l[0-9a-f]{8,16}"))) continue
                val children = candidate.listFiles() ?: continue
                // Only touch a directory that is unambiguously ours.
                if (children.none { it.name.startsWith("lib$LIB_NAME") }) continue
                children.forEach { it.delete() }
                candidate.delete()
            }
        } catch (t: Throwable) {
            // A leftover directory is cosmetic; never let cleanup break loading.
        }
    }

    // ---------------------------------------------------------------- native

    private external fun nativeInit(): Boolean

    private external fun nativeSetStepState(steps: Long, cadenceBpm: Int, enabled: Boolean)

    private external fun nativeSetStepSensors(handles: IntArray)

    private external fun nativeStepSeam(): String

    private external fun nativeStats(): String

    private external fun nativeVersion(): String

    private external fun nativeLastError(): String
}
