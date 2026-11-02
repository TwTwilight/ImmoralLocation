import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

/**
 * Signing material is never committed. Locally it comes from keystore/signing.properties, in CI
 * from the repository secrets, which the workflow writes to that same file (or exports as env).
 */
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore/signing.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

fun signing(key: String, env: String): String =
    System.getenv(env) ?: keystoreProps.getProperty(key) ?: ""

val signingStoreFile = rootProject.file(signing("storeFile", "IMMORAL_STORE_FILE").ifEmpty { "keystore/release.jks" })
val canSign = signingStoreFile.exists()

// A release tag drives both numbers, so the APK version always matches the published one.
val ciVersionName = System.getenv("IMMORAL_VERSION_NAME")
val ciVersionCode = System.getenv("IMMORAL_VERSION_CODE")?.toIntOrNull()

/**
 * ABIs that go into the APK.
 *
 * The module's own native layer (`libimmoralhook.so`, loaded by LSPosed inside system_server) is
 * built for arm64-v8a only, so a 32-bit build of this APK could not inject anything. Shipping
 * 高德's 32-bit `libAMapSDK_MAP_v7_1_0.so` next to a 64-bit-only hook library is therefore dead
 * weight — measured at 2 093 316 bytes (2.0 MB) of the compressed release APK.
 *
 * Escape hatches, so this stays a policy instead of a one-way door:
 *   -PimmoralAbis=arm64-v8a,armeabi-v7a   ship both (needs a 32-bit libimmoralhook.so to make sense)
 *   -PimmoralAbiSplits=true               emit per-ABI APKs instead of one universal APK
 */
val requestedAbis: List<String> =
    (project.findProperty("immoralAbis") as String?)
        ?.split(',')
        ?.map { it.trim() }
        ?.filter { it.isNotEmpty() }
        ?.takeIf { it.isNotEmpty() }
        ?: listOf("arm64-v8a")
val abiSplitsEnabled = (project.findProperty("immoralAbiSplits") as String?)?.toBoolean() ?: false

/**
 * 高德 style + indoor packs (`assets/cfg`, 1 623 749 B compressed) are dropped from release builds.
 *
 * **The in-app map is 高德.** `ReadyAMapSDK v1.0.4` + `3dmap 10.0.600` initialises with the key the
 * AAR ships and renders — device log `I AmapHost: amap map loaded (tiles rendered)`, screenshot
 * identical with and without `assets/cfg`. The osmdroid raster view is only the fallback for ROMs
 * where the SDK cannot initialise.
 *
 * Measured on device, four arms, each installed fresh (uninstall → install → cold start → frame):
 *   arm                            APK bytes   screenshot   `amap map loaded`   verdict
 *   control (assets in)            8 165 765   1 021 801 B  yes                 map renders
 *   cfg dropped                    6 542 016   1 020 639 B  yes                 renders identically
 *   ae dropped                     7 827 484     130 276 B  late               BLACK map
 *   all six dropped                5 274 854     130 197 B  no                 BLACK map
 *
 * `assets/ae/res.zip` is load-bearing (`java.io.FileNotFoundException: ae/res.zip` →
 * `NullPointerException … GLMapState.setMapZoomer` → black surface), so it stays. `map_assets`
 * (−602 041 B), `map_custom` (−84 047 B) and `offlinemapv4.png` + `amap_resource1_0_0.png`
 * (−242 742 B) were only ever tested inside the black-screen all-six arm, so they are NOT dropped;
 * each needs its own install + frame before it can be considered. `-PimmoralAmapAssets=false`
 * (release) / `=true` (debug, or any build that wants the packs back) switches the whole set.
 *
 * Mechanism: aapt2 `--ignore-assets`, applied per variant below. `packaging.resources.excludes` does
 * NOT touch `assets/` on AGP 9 (measured: same glob, all 15 `assets/cfg` entries still packaged).
 */
val amapAssetPatterns: List<String> = listOf(
    "cfg",
)
val amapAssetsRequested = (project.findProperty("immoralAmapAssets") as String?)?.toBoolean()

/**
 * Experiment-only knob for the asset A/B: extra aapt2 `--ignore-assets` patterns applied **on top of**
 * the shipping set in every variant, so one build changes exactly one variable.
 *
 *   -PimmoralAmapDropAssets=map_assets
 *   -PimmoralAmapDropAssets=map_custom
 *   -PimmoralAmapDropAssets=offlinemapv4.png,amap_resource1_0_0.png
 *
 * Absent ⇒ nothing changes (a normal build is byte-identical to the committed config). A group may
 * only be promoted into [amapAssetPatterns] after a device frame proved it render-identical; the
 * evidence table lives in docs/ROUND-NOTES.md §7 and docs/STEALTH.md §4.1.
 */
val amapAssetExperimentPatterns: List<String> =
    ((project.findProperty("immoralAmapDropAssets") as String?) ?: "")
        .split(',')
        .map { it.trim() }
        .filter { it.isNotEmpty() }

android {
    namespace = "com.immoral.location"
    compileSdk = 37

    defaultConfig {
        applicationId = "io.github.twtwilight.immorallocation"
        minSdk = 21
        targetSdk = 37
        versionCode = ciVersionCode ?: 1
        versionName = ciVersionName ?: "1.0.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables.useSupportLibrary = true

        // 高德 ships native libs for 4 ABIs; keep only what this product can actually use
        // (see [requestedAbis] — the hook layer is arm64-v8a only).
        ndk {
            abiFilters += requestedAbis
        }
    }

    /**
     * Optional per-ABI APKs; off by default so CI keeps producing one `app-release.apk`.
     * `-PimmoralAbiSplits=true` emits `app-arm64-v8a-release.apk` etc. instead.
     */
    splits {
        abi {
            isEnable = abiSplitsEnabled
            reset()
            include(*requestedAbis.toTypedArray())
            isUniversalApk = false
        }
    }

    /**
     * Ship only the locales this product actually speaks.
     *
     * 高德 SDK and osmdroid each carry dozens of translated strings that nobody in this app ever
     * shows; `localeFilters` drops them from the release APK (measured: ~0.3 MB off the compressed
     * release). Keep `zh` first because the default strings are Chinese.
     */
    androidResources {
        localeFilters += listOf("zh", "en")

        // A/B probing of 高德-only asset packs (包体积 experiment, see docs/ROUND-NOTES.md §7):
        //   .\gradlew.bat :app:assembleRelease -PimmoralDropAssets=cfg
        // Values are aapt2 `--ignore-assets` patterns (matched against asset path segments).
        // Empty by default, so a normal build ships whatever the SDK brings.
        ignoreAssetsPatterns += ((findProperty("immoralDropAssets") as String?) ?: "")
            .split(',').map { it.trim() }.filter { it.isNotEmpty() }
    }

    signingConfigs {
        if (canSign) {
            create("release") {
                storeFile = signingStoreFile
                storePassword = signing("storePassword", "IMMORAL_STORE_PASSWORD")
                keyAlias = signing("keyAlias", "IMMORAL_KEY_ALIAS")
                keyPassword = signing("keyPassword", "IMMORAL_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        debug {
            if (canSign) signingConfig = signingConfigs.getByName("release")
            applicationIdSuffix = ".debug"
        }
        release {
            if (canSign) signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources.excludes += setOf(
            "/META-INF/{AL2.0,LGPL2.1}",
            "META-INF/DEPENDENCIES",
            "META-INF/LICENSE*",
            "META-INF/NOTICE*",
            // Build-time only: per-library version markers AGP writes into AARs (~40 files) and the
            // kotlinx-coroutines debug agent. Nothing reads them at runtime.
            "META-INF/*.version",
            "DebugProbesKt.bin",
        )
        // Keep native libraries extractable: LSPosed must be able to System.load() our
        // libimmoralhook.so from inside system_server, which requires a real file under
        // <apk dir>/lib/<abi>/ rather than a page-aligned library inside the APK.
        jniLibs.useLegacyPackaging = true
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = true
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":core"))
    implementation(project(":hook"))

    implementation(platform("androidx.compose:compose-bom:2025.06.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("org.osmdroid:osmdroid-android:6.1.20")

    implementation("com.github.TwTwilight:ReadyAMapSDK:v1.0.4")

    compileOnly("de.robv.android.xposed:api:82")

    debugImplementation("androidx.compose.ui:ui-tooling")

    testImplementation(kotlin("test"))
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test:runner:1.7.0")
}

/**
 * Apply [amapAssetPatterns] per variant: release drops the packs (measured render-identical), debug
 * keeps them so a developer can bisect a map problem with the full SDK asset set in place.
 *
 * The Variant API is required because the module-level `androidResources.ignoreAssetsPatterns` is
 * variant-agnostic.
 */
androidComponents {
    onVariants { variant ->
        val keepAmapAssets = amapAssetsRequested ?: (variant.buildType != "release")
        val patterns = buildList {
            if (!keepAmapAssets) addAll(amapAssetPatterns)
            // Empty in every normal build; see [amapAssetExperimentPatterns].
            addAll(amapAssetExperimentPatterns)
        }
        if (patterns.isNotEmpty()) {
            variant.androidResources?.ignoreAssetsPatterns?.addAll(patterns)
        }
    }
}
