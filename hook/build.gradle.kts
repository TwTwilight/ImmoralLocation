plugins {
    id("com.android.library")
}

android {
    namespace = "com.immoral.location.hook"
    compileSdk = 37

    defaultConfig {
        minSdk = 21
        consumerProguardFiles("consumer-rules.pro")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = false
    }

    buildFeatures {
        buildConfig = false
    }

    lint {
        abortOnError = false
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    api(project(":core"))
    // Provided at runtime by LSPosed / Xposed; never packaged.
    compileOnly("de.robv.android.xposed:api:82")
    testImplementation(kotlin("test"))
}
