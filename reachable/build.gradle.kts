/*
 * Reachable — :reachable module.
 *
 * Headless KMP module: business logic only, no UI dependencies (CLAUDE.md
 * §1, §7). The module shape — ARM-only targets, apple intermediate source
 * set, Android library block, compiler options, SKIE settings — comes from
 * the `reachable.kmp-library` convention plugin; Maven Central publishing
 * comes from `reachable.publish` (both in /build-logic). This script keeps
 * only what is unique to this module: dependencies, the KMMBridge SPM
 * distribution config, and POM name/description.
 */

plugins {
    id("reachable.kmp-library")
    id("reachable.publish")
    // KMMBridge (CLAUDE.md §9): aggregates the per-target frameworks the
    // convention plugin declared into `Reachable.xcframework`
    // (build/XCFrameworks/{debug,release}/), publishes the release zip as a
    // GitHub Release asset, and regenerates the root /Package.swift. The
    // `.github` plugin variant is a superset of the core plugin in 1.2.x —
    // applying both produces a duplicate-extension error, so only this one.
    //
    // Do NOT redeclare `XCFramework("Reachable")` in the kotlin { } block:
    // KMMBridge auto-creates the aggregator from the framework binaries at
    // config time (it provides `assembleReachable{Debug,Release}XCFramework`),
    // and a second declaration collides on those task names.
    alias(libs.plugins.kmmbridge.github)
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.atomicfu)
        }

        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.turbine)
            // `:reachable-testing` provides the public `FakeReachability`
            // we use in StateFlowReachabilityTest / NonClosingReachabilityTest.
            // Conceptually the dependency loop is acceptable: the testing
            // module `api`s `:reachable`'s `main` configuration, and the
            // back-edge here is on `commonTest`, not `main` — Gradle resolves
            // both without a circular `main` dependency.
            implementation(project(":reachable-testing"))
        }

        androidMain.dependencies {
            implementation(libs.kotlinx.coroutines.android)
            // androidx.startup hosts the bundled `ReachabilityInitializer`
            // that attaches `Reachability.shared` to the application Context
            // during the InitializationProvider ContentProvider pass —
            // before `Application.onCreate`. See `ReachabilityInitializer.kt`
            // and the matching `<provider>` entry in `AndroidManifest.xml`.
            implementation(libs.androidx.startup.runtime)
        }

        // androidHostTest source set is created by the convention plugin's
        // withHostTestBuilder. Configure its deps here.
        getByName("androidHostTest").dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.turbine)
            implementation(project(":reachable-testing"))
        }
    }
}

skie {
    build {
        // Xcode 26 requires .swiftinterface files in every framework slice before
        // xcodebuild -create-xcframework will accept them (exit 70 otherwise).
        // produceDistributableFramework() enables Swift library evolution so SKIE
        // emits .swiftinterface alongside .swiftmodule, satisfying the requirement
        // for both debug and release XCFramework builds. `:reachable-testing`
        // doesn't need this — it isn't shipped as an XCFramework.
        produceDistributableFramework()
    }
}

// --- KMMBridge: XCFramework → GitHub Release asset → SPM (CLAUDE.md §9) ------
//
// Two distribution channels run from this module, and they don't overlap:
//
//   1. Maven Central (`reachable.publish` convention plugin) — Android AAR,
//      `kotlinMultiplatform` metadata, and per-target klibs. KMP consumers
//      resolve these from `commonMain`; no XCFramework involved.
//   2. GitHub Releases (this block) — the SKIE-enhanced `Reachable.xcframework`
//      zip for pure-Swift consumers, referenced from the root /Package.swift
//      by URL + checksum so `swift package resolve` needs no local Gradle
//      build and no authentication.
//
// GitHub *Releases*, not GitHub *Packages*: Packages requires a PAT even to
// download from public repos (every SPM consumer would need a ~/.netrc),
// and Maven Central can't host the zip either — KMMBridge has no Central
// Portal artifact manager, and Central's staging + sync delay would leave
// the freshly-pushed tag referencing a URL that doesn't resolve yet.
// Release assets are public, immediate, and checksum-pinned by SPM.
//
// `gitHubReleaseArtifacts` uploads `Reachable.xcframework.zip` to the GitHub
// Release tagged `v${project.version}`, creating the release if it doesn't
// exist. (`releasString` [sic] is KMMBridge 1.2.x's parameter name; without
// it the release tag would be the bare version, breaking the repo's `vX.Y.Z`
// tag convention.) Publishing is CI-only: the `kmmBridgePublish` umbrella
// task is only registered when `-PENABLE_PUBLISHING=true` is passed, and the
// upload reads the `GITHUB_REPO` / `GITHUB_PUBLISH_TOKEN` Gradle properties —
// .github/workflows/release.yml supplies all three. Local builds skip the
// publish wiring entirely; the `spmDevBuild` task (always registered) is the
// local-dev entry point — see mise task `spm:dev`.
gitHubReleaseArtifacts(releasString = "v${project.version}")

kmmbridge {
    // The XCFramework's Swift module name. Must match the `baseName` the
    // convention plugin sets on each framework binary, or the generated
    // Package.swift references a binary that doesn't exist.
    frameworkName.set("Reachable")

    // `swiftToolVersion = "6.0"` because the platform constants `.iOS(.v18)`
    // and `.macOS(.v15)` need PackageDescription 6.0; KMMBridge defaults to
    // 5.3, which can't compile them.
    //
    // Platform floors match `gradle/libs.versions.toml`
    // (ios-deployment-target = 18.0, macos-deployment-target = 15.0). They're
    // spelled "18" / "15" here because KMMBridge emits `.iOS(.v$value)`
    // verbatim — "18.0" would produce the non-existent constant `.v18.0`.
    spm(swiftToolVersion = "6.0") {
        iOS { v("18") }
        macOS { v("15") }
    }
}

mavenPublishing {
    pom {
        name.set("Reachable")
        description.set(
            "Kotlin Multiplatform reachability and network-path monitoring " +
                "for iOS, macOS, and Android.",
        )
    }
}
