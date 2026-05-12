@file:Suppress("UnstableApiUsage")

/*
 * Reachable — :reachable-testing module.
 *
 * Public, scriptable test fakes and helpers for consumers of `:reachable`.
 * Headless KMP module (CLAUDE.md §1, §7), no UI dependencies. Targets the
 * same ARM-only matrix as `:reachable`: iosArm64, iosSimulatorArm64,
 * Android arm64-v8a, macosArm64. Published as a sibling Maven Central
 * artifact: `com.happycodelucky.reachable:reachable-testing`.
 *
 * Consumers wire it via `testImplementation` (or KMP `commonTest` deps);
 * the production `:reachable` artifact does not depend on this module.
 *
 * No XCFramework / no SKIE `produceDistributableFramework()`: test code is
 * consumed as KMP klibs from Maven Central, not via SPM. The Apple targets
 * exist so KMP consumers can resolve this module from their Apple test
 * source sets, but we don't ship a binary framework for it.
 */

import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
    alias(libs.plugins.skie)
    alias(libs.plugins.dokka)
    // Vanniktech Maven Publish — same pipeline as `:reachable`. Publishes
    // signed klibs + Android AAR + `kotlinMultiplatform` metadata module to
    // the Sonatype Central Portal. Credentials come from the same Gradle
    // properties / env vars as the main module; nothing additional to wire.
    alias(libs.plugins.maven.publish)
}

kotlin {
    // Mirror `:reachable`'s hierarchy template (CLAUDE.md §4). The fake is
    // platform-agnostic and lives entirely in commonMain, so the template
    // is technically optional — but matching `:reachable` exactly keeps
    // source-set names consistent across modules for anyone navigating both.
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
    // Static framework binaries are declared so KMP consumers' Apple test
    // source sets can resolve this module. No XCFramework aggregator: the
    // testing artefact is not consumed via SPM, so there is no binary slot
    // to assemble. Bundle IDs match the testing module's namespace.
    listOf(iosArm64(), iosSimulatorArm64(), macosArm64()).forEach { target ->
        target.binaries.framework {
            baseName = "ReachableTesting"
            isStatic = true
            binaryOption("bundleId", "com.happycodelucky.reachable.testing")
        }
    }

    // --- Android target (CLAUDE.md §1, §4) ----------------------------------
    @OptIn(ExperimentalKotlinGradlePluginApi::class)
    android {
        namespace = "com.happycodelucky.reachable.testing"
        compileSdk =
            libs.versions.android.compile.sdk
                .get()
                .toInt()
        minSdk =
            libs.versions.android.min.sdk
                .get()
                .toInt()

        withHostTestBuilder { /* enables androidUnitTest */ }
    }

    // --- JVM toolchain (CLAUDE.md §2: JVM target 21) ------------------------
    @OptIn(ExperimentalKotlinGradlePluginApi::class)
    compilerOptions {
        languageVersion.set(org.jetbrains.kotlin.gradle.dsl.KotlinVersion.KOTLIN_2_3)
        apiVersion.set(org.jetbrains.kotlin.gradle.dsl.KotlinVersion.KOTLIN_2_3)
        allWarningsAsErrors.set(false)
    }

    targets.withType<org.jetbrains.kotlin.gradle.targets.jvm.KotlinJvmTarget>().configureEach {
        compilations.configureEach {
            compileTaskProvider.configure {
                compilerOptions {
                    jvmTarget.set(JvmTarget.JVM_21)
                }
            }
        }
    }

    sourceSets {
        commonMain.dependencies {
            // `api` so consumers writing `testImplementation(reachable-testing)`
            // get `Reachability` / `ReachabilityStatus` / `Transport` /
            // `Metering` transitively — they will assert against those types.
            api(project(":reachable"))

            // StateFlow plumbing and the atomic counter inside FakeReachability.
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.atomicfu)
        }

        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.turbine)
        }

        // androidHostTest source set is created by withHostTestBuilder above.
        getByName("androidHostTest").dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.turbine)
        }
    }
}

skie {
    features {
        group { /* defaults */ }
    }
    analytics {
        disableUpload.set(true)
    }
    // No `produceDistributableFramework()`: the testing module isn't shipped
    // as an XCFramework, so we don't need Swift library evolution / the
    // .swiftinterface generation. SKIE still runs to give Swift-consumable
    // bridge enhancements (exhaustive enums, suspend → async/await) for any
    // platform consumer that happens to link this module from a KMP build.
    //
    // Disable bundled-Swift re-export / unpacking. The `:reachable` dependency
    // contains `Reachability+Shared.swift` whose `extension Reachability` uses
    // the short swift_name that is only valid inside the `Reachable` module.
    // When SKIE unpacks and recompiles that file into `ReachableTesting`, the
    // type is renamed `ReachableReachability` (module-prefixed) and the
    // extension fails to compile. Disabling Swift bundling here prevents SKIE
    // from pulling in upstream bundled Swift sources while keeping all SKIE
    // bridge enhancements (sealed enums, async/await, AsyncSequence) active.
    swiftBundling {
        enabled.set(false)
    }
}

// --- Maven Central publishing (CLAUDE.md §9) ---------------------------------
//
// Sibling artifact to `:reachable`. Same group, same version (inherited from
// `allprojects { version = ... }` in the root build script), same signing /
// release pipeline. POM mirrors `:reachable`'s metadata — same licence,
// developer, SCM — only the artifactId, name, and description differ.
mavenPublishing {
    publishToMavenCentral(automaticRelease = true)
    signAllPublications()

    coordinates(
        groupId = "com.happycodelucky.reachable",
        artifactId = "reachable-testing",
        version = project.version.toString(),
    )

    pom {
        name.set("Reachable Testing")
        description.set(
            "Test fakes and helpers for the Reachable KMP library: " +
                "FakeReachability + withFakeReachability for installing it " +
                "as Reachability.shared for the lifetime of a test.",
        )
        url.set("https://github.com/happycodelucky/reachable")
        inceptionYear.set("2026")

        licenses {
            license {
                name.set("Apache License 2.0")
                url.set("https://www.apache.org/licenses/LICENSE-2.0")
                distribution.set("repo")
            }
        }
        developers {
            developer {
                id.set("happycodelucky")
                name.set("Paul Bates")
                url.set("https://github.com/happycodelucky")
            }
        }
        scm {
            url.set("https://github.com/happycodelucky/reachable")
            connection.set("scm:git:https://github.com/happycodelucky/reachable.git")
            developerConnection.set("scm:git:ssh://git@github.com/happycodelucky/reachable.git")
        }
    }
}
