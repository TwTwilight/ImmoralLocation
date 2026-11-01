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
// The acceptance verifier (`:verifier`): an ordinary app that proves injection from the receiving
// side and produces every number in docs/ACCEPTANCE.md. It is deliberately NOT published — its
// sources are gitignored and only exist on the machine that drives the device harness — so include
// it only when the directory is really there. A plain `include(":verifier")` makes Gradle refuse to
// configure a clean checkout ("Configuring project ':verifier' without an existing directory is not
// allowed"), which is exactly how CI broke the moment the module was re-included.
if (file("verifier").isDirectory) include(":verifier")

