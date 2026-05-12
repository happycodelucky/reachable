@file:Suppress("UnstableApiUsage")

/*
 * Reachable — :reachable module.
 *
 * Headless KMP module: business logic only, no UI dependencies (CLAUDE.md §1, §7).
 * Targets are ARM-only per CLAUDE.md §1: iosArm64, iosSimulatorArm64, Android
 * arm64-v8a (via the new com.android.kotlin.multiplatform.library plugin), and
 * macosArm64. No x86, no Catalyst, no watchOS / tvOS / Linux / Windows.
 */

import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.plugin.mpp.apple.XCFramework

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
    alias(libs.plugins.skie)
    alias(libs.plugins.dokka)
    // Vanniktech Maven Publish (CLAUDE.md §9) — publishes signed artifacts to
    // the Sonatype Central Portal. Applies `maven-publish` and `signing`
    // transitively, so we don't apply `maven-publish` separately. The
    // `mavenPublishing { }` block below configures the Central Portal target,
    // POM metadata, and in-memory GPG signing.
    //
    // KMMBridge / GitHub Packages distribution was previously wired here for
    // SPM consumers but is currently disabled — Maven Central is the only
    // active distribution channel. See git history for the previous wiring;
    // re-introduce when an SPM-side delivery story is needed again.
    alias(libs.plugins.maven.publish)
}

kotlin {
    // CLAUDE.md §4: applyDefaultHierarchyTemplate. Don't hand-roll source set wiring.
    @OptIn(ExperimentalKotlinGradlePluginApi::class)
    applyDefaultHierarchyTemplate {
        // Coalesce iosMain + macosMain into a shared "appleMain" intermediate.
        // Both Apple platforms share the `platform.Network.*` cinterop bindings
        // 1:1, so a single AppleReachability over `nw_path_monitor` lives here.
        common {
            group("apple") {
                withIos()
                withMacos()
            }
        }
    }

    // --- Apple targets (CLAUDE.md §1) ---------------------------------------
    // Each Apple target gets a static framework binary with a stable bundle id
    // so SKIE doesn't fall back to the framework name. The XCFramework
    // aggregator bundles all three slices (iosArm64 device, iosSimulatorArm64,
    // macosArm64) into a single `Reachable.xcframework` directory at
    // `build/XCFrameworks/{debug,release}/`. The sample apps under
    // /iOSApp and /macOSApp consume that XCFramework via the root
    // `Package.swift` and `.binaryTarget(path: …)` — see iOSApp/README.md.
    //
    // Maven Central distribution doesn't use the XCFramework — it publishes
    // the per-target klibs and `kotlinMultiplatform` metadata; KMP consumers
    // resolve those automatically. The aggregator exists purely for the
    // local-dev path that the sample apps depend on.
    val xcf = XCFramework("Reachable")
    listOf(iosArm64(), iosSimulatorArm64(), macosArm64()).forEach { target ->
        target.binaries.framework {
            baseName = "Reachable"
            isStatic = true
            // Pin the bundle id so SKIE doesn't fall back to the framework name.
            binaryOption("bundleId", "com.happycodelucky.reachable")
            xcf.add(this)
        }
    }

    // --- Android target (CLAUDE.md §1, §4) ----------------------------------
    // Use the new com.android.kotlin.multiplatform.library plugin's android {} block.
    @OptIn(ExperimentalKotlinGradlePluginApi::class)
    android {
        namespace = "com.happycodelucky.reachable"
        compileSdk =
            libs.versions.android.compile.sdk
                .get()
                .toInt()
        minSdk =
            libs.versions.android.min.sdk
                .get()
                .toInt()

        // CLAUDE.md §1: arm64-v8a only. The new KMP Android plugin doesn't wire
        // ABI filters directly; consumers' app modules pin the splits. The
        // shared library itself produces all ABIs the build asks for. We test
        // arm64-v8a only; documented in README.

        withHostTestBuilder { /* enables androidUnitTest */ }
    }

    // --- JVM toolchain (CLAUDE.md §2: JVM target 21) ------------------------
    @OptIn(ExperimentalKotlinGradlePluginApi::class)
    compilerOptions {
        // K2 stable APIs only (CLAUDE.md §3).
        languageVersion.set(org.jetbrains.kotlin.gradle.dsl.KotlinVersion.KOTLIN_2_3)
        apiVersion.set(org.jetbrains.kotlin.gradle.dsl.KotlinVersion.KOTLIN_2_3)
        // Fail builds on stable-API misuse, not just experimental.
        allWarningsAsErrors.set(false) // bump to true once codebase settles.
    }

    // Per-target JVM toolchain knobs — Android compilation needs JVM target 21.
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
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.atomicfu)
            implementation(libs.kermit)
        }

        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.turbine)
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

        // androidUnitTest source set is created by withHostTestBuilder above.
        // Configure its deps here.
        getByName("androidHostTest").dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.turbine)
        }
    }
}

skie {
    // SKIE handles the Kotlin → Swift bridge enhancements (CLAUDE.md §8):
    // exhaustive sealed switching, suspend → async/await, Flow → AsyncSequence,
    // default-arg overloads. We keep all defaults on; tighten only when something
    // bites.
    features {
        group {
            // Keep SKIE-generated Swift names visible in stack traces.
            // (Default behaviour, listed for clarity.)
        }
    }
    analytics {
        // Disable opt-in analytics; we'll revisit if useful.
        disableUpload.set(true)
    }
    build {
        // Xcode 26 requires .swiftinterface files in every framework slice before
        // xcodebuild -create-xcframework will accept them (exit 70 otherwise).
        // produceDistributableFramework() enables Swift library evolution so SKIE
        // emits .swiftinterface alongside .swiftmodule, satisfying the requirement
        // for both debug and release XCFramework builds.
        produceDistributableFramework()
    }
}

// KMMBridge / GitHub Packages distribution previously lived here. It has
// been removed in favour of Maven-Central-only distribution; see the
// `mavenPublishing { }` block below. The previous wiring is in git
// history — restore from there when an SPM-side delivery story is needed
// for non-KMP iOS / macOS consumers again.

// --- Maven Central publishing (CLAUDE.md §9) ---------------------------------
//
// Single distribution channel: Maven Central via vanniktech's `maven-publish`
// plugin. One Gradle invocation publishes:
//
//   * The Android AAR.
//   * The `kotlinMultiplatform` metadata module (`reachable-0.2.0.module`)
//     that ties every target together so KMP consumers can write
//     `implementation("com.happycodelucky.reachable:reachable:X.Y.Z")` from
//     `commonMain` and have Gradle resolve the right per-target klib.
//   * Per-target klibs: `reachable-iosarm64`, `reachable-iossimulatorarm64`,
//     `reachable-macosarm64`, `reachable-android` — one Maven artifact per
//     publication the KMP plugin registers automatically.
//   * Sources / javadoc jars next to each, with detached GPG signatures.
//
// Consumers in another KMP project just add `mavenCentral()` to their
// repositories and depend on the coordinate; no extra setup needed on the
// consumer side.
//
// Credentials: vanniktech reads `mavenCentralUsername`, `mavenCentralPassword`,
// `signingInMemoryKey`, and `signingInMemoryKeyPassword` as Gradle properties.
// Gradle auto-populates those from `ORG_GRADLE_PROJECT_*` env vars in CI. The
// release workflow wires the four `MAVEN_CENTRAL_*` GitHub Actions secrets to
// those env names. Locally these properties are unset and signing is silently
// skipped — fine for `publishToMavenLocal` dry-runs.
mavenPublishing {
    // SonatypeHost.CENTRAL_PORTAL targets the new central.sonatype.com
    // endpoint. Do NOT use SonatypeHost.DEFAULT — that's the legacy
    // s01.oss.sonatype.org OSSRH endpoint, which Sonatype is decommissioning.
    //
    // `automaticRelease = false` is intentional and load-bearing. It controls
    // what `./gradlew :reachable:publishToMavenCentral` does:
    //   * `false` — uploads to the Central Portal staging area and stops.
    //     The deployment sits in "validated" state until someone clicks
    //     Publish (or Drop) in the Portal web UI. This is what makes the
    //     release workflow's `dryRun=true` branch an actual dry run.
    //   * `true` — uploads *and* auto-releases on success. Every "dry run"
    //     becomes an irreversible public publish. Do NOT flip this without
    //     understanding the cascade in `.github/workflows/release.yml`.
    //
    // The `publishAndReleaseToMavenCentral` task is unaffected by this flag —
    // it always closes & releases the deployment regardless, and the
    // release workflow uses it on the `dryRun=false` branch.
    publishToMavenCentral(automaticRelease = false)

    // Required by Central — every artifact (jar, aar, klib, module, pom)
    // must carry a detached GPG signature next to it. signAllPublications()
    // applies the signing plugin across every publication the KMP plugin
    // registered (`kotlinMultiplatform`, `android`, `iosArm64`,
    // `iosSimulatorArm64`, `macosArm64`). Central rejects unsigned uploads.
    signAllPublications()

    // The coordinate triple. groupId here intentionally matches the
    // namespace claimed on the Central Portal (`com.happycodelucky`); the
    // root build.gradle.kts wires `group = "com.happycodelucky.reachable"`
    // and we mirror that here for the artifact suffix.
    coordinates(
        groupId = "com.happycodelucky.reachable",
        artifactId = "reachable",
        version = project.version.toString(),
    )

    pom {
        name.set("Reachable")
        description.set(
            "Kotlin Multiplatform reachability and network-path monitoring " +
                "for iOS, macOS, and Android.",
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
