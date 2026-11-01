pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.PREFER_SETTINGS)
    repositories {
        google()
        mavenCentral()
        maven("https://jitpack.io")
        // Xposed API stubs (compileOnly only, never shipped)
        maven("https://api.xposed.info/")
    }
}

rootProject.name = "ImmoralLocation"

include(":core")
include(":hook")
include(":app")
// The acceptance verifier: an ordinary app that proves injection from the receiving side. It is part
// of the repository because every number in docs/ACCEPTANCE.md comes from it.
include(":verifier")

