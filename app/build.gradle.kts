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

        // 高德 ships native libs for 4 ABIs; keep only what the product needs (APK ~-8 MB).
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
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
