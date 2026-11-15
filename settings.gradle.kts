pluginManagement {
    repositories {
        // Only Google's groups, so other lookups never reach dl.google.com, which sometimes fails builds on CI runners.
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
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
