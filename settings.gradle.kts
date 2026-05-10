/*
 * Reachable — KMP reachability / network-status library.
 *
 * /reachable is the only subproject in v1: the headless KMP module that
 * exposes a `Reachability` interface and platform implementations over
 * `nw_path_monitor` (Apple) and `ConnectivityManager.NetworkCallback`
 * (Android). Platform apps live outside this Gradle build and consume
 * /reachable via KMMBridge → Maven → SPM on Apple (CLAUDE.md §9) and as
 * an AAR on Android.
 */

@file:Suppress("UnstableApiUsage")

pluginManagement {
    repositories {
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
    // Project-level repos win; subprojects must not redeclare.
    repositoriesMode = RepositoriesMode.FAIL_ON_PROJECT_REPOS
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
    }
}

rootProject.name = "reachable"

include(":reachable")

// --- Sample apps (CLAUDE.md §4) -----------------------------------------------
// The Android sample is a normal Gradle subproject because Compose + AGP play
// best inside the same Gradle build that produces the AAR. The iOS and macOS
// samples are standalone Xcode projects under /iOSApp and /macOSApp; they
// consume the shared module via SPM, NOT Gradle, and so are deliberately not
// included here. See iOSApp/README.md and macOSApp/README.md.
include(":androidApp")
project(":androidApp").projectDir = file("androidApp")
