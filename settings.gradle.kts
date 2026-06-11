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
    // Convention plugins (`reachable.kmp-library`, `reachable.publish`) live
    // in gradle/plugins; versions still come from gradle/libs.versions.toml,
    // which gradle/plugins shares.
    includeBuild("gradle/plugins")
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

// :reachable-testing — public, scriptable `FakeReachability` plus the
// `withFakeReachability { … }` helper that installs/uninstalls it as
// `Reachability.shared` for the lifetime of a test. Headless KMP module;
// same targets as `:reachable`; published as a sibling Maven Central
// artifact (`com.happycodelucky.reachable:reachable-testing`). Consumers
// wire it on `testImplementation` (or KMP `commonTest` deps).
include(":reachable-testing")

// --- Sample apps (CLAUDE.md §4) -----------------------------------------------
// The Android sample is a normal Gradle subproject because Compose + AGP play
// best inside the same Gradle build that produces the AAR. The iOS and macOS
// samples are standalone Xcode projects under /apps/ios and /apps/macos; they
// consume the shared module via SPM, NOT Gradle, and so are deliberately not
// included here. See apps/ios/README.md and apps/macos/README.md.
include(":androidApp")
project(":androidApp").projectDir = file("apps/android")
