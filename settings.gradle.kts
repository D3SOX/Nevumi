pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        // NewPipe Extractor and its JSON parser are only published on JitPack.
        exclusiveContent {
            forRepository { maven("https://jitpack.io") }
            filter { includeGroup("com.github.TeamNewPipe") }
        }
    }
}

rootProject.name = "Nevumi"
include(":shared", ":androidApp", ":desktopApp")
