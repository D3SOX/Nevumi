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
        // Maven Central rate-limits CI runners with 429s for longer than Gradle retries, so CI asks Google's mirror of
        // it first. Artifacts the mirror has not synced yet return 404 and fall through to Maven Central.
        if (System.getenv("CI") != null) maven("https://maven-central.storage-download.googleapis.com/maven2/")
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
        if (System.getenv("CI") != null) maven("https://maven-central.storage-download.googleapis.com/maven2/")
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
