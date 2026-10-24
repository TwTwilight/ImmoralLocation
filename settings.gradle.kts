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
