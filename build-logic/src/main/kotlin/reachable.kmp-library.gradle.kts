/*
 * Convention plugin: the shared module shape for Reachable's published KMP
 * libraries (`:reachable`, `:reachable-testing`).
 *
 * Owns everything the two modules previously duplicated (CLAUDE.md §1, §2,
 * §4): the ARM-only target matrix, the apple intermediate source set, the
 * Android library block, compiler options, JVM target wiring, and the
 * SKIE settings that must match across modules. Per-module identity
 * (framework base name, bundle id, Android namespace) is derived from the
 * project name so adding a module means applying this plugin and nothing
 * else:
 *
 *   reachable          → framework "Reachable",        namespace com.happycodelucky.reachable
 *   reachable-testing  → framework "ReachableTesting", namespace com.happycodelucky.reachable.testing
 *
 * Module build scripts keep only what genuinely differs: dependencies,
 * the XCFramework aggregator (`:reachable` only), and POM name/description.
 */

import org.gradle.api.artifacts.VersionCatalogsExtension
import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinVersion
import org.jetbrains.kotlin.gradle.targets.jvm.KotlinJvmTarget

plugins {
    id("org.jetbrains.kotlin.multiplatform")
    id("com.android.kotlin.multiplatform.library")
    id("co.touchlab.skie")
    id("org.jetbrains.dokka")
}

// Typed `libs` accessors aren't generated inside precompiled script plugins;
// the named-lookup API reads the same catalog the main build uses.
val libs = the<VersionCatalogsExtension>().named("libs")

// reachable → "Reachable"; reachable-testing → "ReachableTesting".
val frameworkBaseName = name.split("-").joinToString("") { part -> part.replaceFirstChar(Char::uppercase) }

// reachable → com.happycodelucky.reachable; reachable-testing → ….reachable.testing.
// Doubles as the framework bundle id, pinned so SKIE doesn't fall back to the
// framework name.
val moduleNamespace = "com.happycodelucky." + name.replace("-", ".")

kotlin {
    // CLAUDE.md §4: applyDefaultHierarchyTemplate. Don't hand-roll source set
    // wiring. iosMain + macosMain coalesce into a shared "appleMain"
    // intermediate — both platforms share the `platform.Network.*` cinterop
    // bindings 1:1.
    @OptIn(ExperimentalKotlinGradlePluginApi::class)
    applyDefaultHierarchyTemplate {
        common {
            group("apple") {
                withIos()
                withMacos()
            }
        }
    }

    // --- Apple targets (CLAUDE.md §1) ---------------------------------------
    // Static framework binaries with a stable bundle id. `:reachable` layers
    // its XCFramework aggregator over these in its own build script.
    listOf(iosArm64(), iosSimulatorArm64(), macosArm64()).forEach { target ->
        target.binaries.framework {
            baseName = frameworkBaseName
            isStatic = true
            binaryOption("bundleId", moduleNamespace)
        }
    }

    // --- Android target (CLAUDE.md §1, §4) ----------------------------------
    // The new com.android.kotlin.multiplatform.library plugin's android {} block.
    //
    // CLAUDE.md §1: arm64-v8a only. The new KMP Android plugin doesn't wire
    // ABI filters directly; consumers' app modules pin the splits. We test
    // arm64-v8a only; documented in README.
    @OptIn(ExperimentalKotlinGradlePluginApi::class)
    android {
        namespace = moduleNamespace
        compileSdk =
            libs
                .findVersion("android-compile-sdk")
                .get()
                .requiredVersion
                .toInt()
        minSdk =
            libs
                .findVersion("android-min-sdk")
                .get()
                .requiredVersion
                .toInt()

        withHostTestBuilder { /* enables the androidHostTest source set */ }
    }

    // --- Compiler options (CLAUDE.md §2, §3) ---------------------------------
    @OptIn(ExperimentalKotlinGradlePluginApi::class)
    compilerOptions {
        // K2 stable APIs only (CLAUDE.md §3).
        languageVersion.set(KotlinVersion.KOTLIN_2_3)
        apiVersion.set(KotlinVersion.KOTLIN_2_3)
        allWarningsAsErrors.set(true)
    }

    // Per-target JVM toolchain knobs — Android compilation needs JVM target 21
    // (CLAUDE.md §2).
    targets.withType<KotlinJvmTarget>().configureEach {
        compilations.configureEach {
            compileTaskProvider.configure {
                compilerOptions {
                    jvmTarget.set(JvmTarget.JVM_21)
                }
            }
        }
    }
}

skie {
    // SKIE handles the Kotlin → Swift bridge enhancements (CLAUDE.md §8):
    // exhaustive sealed switching, suspend → async/await, Flow → AsyncSequence,
    // default-arg overloads. All feature defaults stay on; tighten only when
    // something bites.
    analytics {
        // Disable opt-in analytics; we'll revisit if useful.
        disableUpload.set(true)
    }
    // Prevent SKIE from copying bundled Swift sources into the klib.
    //
    // Both modules ship hand-written Swift sweeteners (`Reachability+Shared.swift`,
    // `Reachability+Testing.swift`) whose `extension Reachability` is only valid
    // inside the module where the type keeps its short swift_name. If bundled
    // into the klib, SKIE unpacks and recompiles them in downstream modules
    // where the type is module-prefixed (`ReachableReachability`), causing a
    // compile error. With bundling disabled, SKIE still compiles the Swift
    // sources into each framework binary via its own compile task; only the
    // klib copy that would trigger downstream re-compilation is suppressed.
    swiftBundling {
        enabled.set(false)
    }
}
