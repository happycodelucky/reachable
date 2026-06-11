/*
 * Reachable — :reachable module.
 *
 * Headless KMP module: business logic only, no UI dependencies (CLAUDE.md
 * §1, §7). The module shape — ARM-only targets, apple intermediate source
 * set, Android library block, compiler options, SKIE settings — comes from
 * the `reachable.kmp-library` convention plugin; Maven Central publishing
 * comes from `reachable.publish` (both in /build-logic). This script keeps
 * only what is unique to this module: dependencies, the XCFramework
 * aggregator for the sample apps' local SPM path, and POM name/description.
 */

import org.jetbrains.kotlin.gradle.plugin.mpp.Framework
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget
import org.jetbrains.kotlin.gradle.plugin.mpp.apple.XCFramework

plugins {
    id("reachable.kmp-library")
    id("reachable.publish")
}

kotlin {
    // XCFramework aggregator over the per-target frameworks the convention
    // plugin declared. Bundles all three slices (iosArm64 device,
    // iosSimulatorArm64, macosArm64) into a single `Reachable.xcframework`
    // at `build/XCFrameworks/{debug,release}/`. The sample apps under
    // /iOSApp and /macOSApp consume that via the root `Package.swift` and
    // `.binaryTarget(path: …)` — see iOSApp/README.md.
    //
    // Maven Central distribution doesn't use the XCFramework — it publishes
    // the per-target klibs and `kotlinMultiplatform` metadata; KMP consumers
    // resolve those automatically. The aggregator exists purely for the
    // local-dev path that the sample apps depend on.
    val xcf = XCFramework("Reachable")
    targets.withType<KotlinNativeTarget>().configureEach {
        binaries.withType<Framework>().configureEach {
            xcf.add(this)
        }
    }

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

mavenPublishing {
    pom {
        name.set("Reachable")
        description.set(
            "Kotlin Multiplatform reachability and network-path monitoring " +
                "for iOS, macOS, and Android.",
        )
    }
}
