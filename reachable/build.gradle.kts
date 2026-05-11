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

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
    alias(libs.plugins.skie)
    alias(libs.plugins.dokka)
    // Maven Publish underpins KMMBridge — KMMBridge contributes the XCFramework
    // zip to the publication that maven-publish then uploads to GitHub Packages.
    `maven-publish`
    // KMMBridge's GitHub variant is a superset of the core plugin in 1.2.x —
    // applying both produces a duplicate-extension error. The `.github` artifact
    // includes the core extension plus the `addGithubPackagesRepository()`
    // helper that registers GitHub Packages as a Maven repo.
    alias(libs.plugins.kmmbridge.github)
    // Vanniktech Maven Publish (CLAUDE.md §9) — publishes signed artifacts to
    // the Sonatype Central Portal. Applies `maven-publish` and `signing`
    // transitively (so the bare `maven-publish` above is harmless duplication
    // but kept for readability and to anchor the KMMBridge dependency). The
    // `mavenPublishing { }` block below configures the Central Portal target,
    // POM metadata, and in-memory GPG signing.
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
    // KMMBridge auto-creates the XCFramework("Reachable") aggregator at config
    // time when it sees `frameworkName.set("Reachable")` in the kmmbridge { }
    // block below; declaring our own here would conflict on the
    // `assembleReachableReleaseXCFramework` task name. We just register each
    // framework binary with the right baseName / isStatic / bundleId and let
    // KMMBridge do the aggregation.
    listOf(iosArm64(), iosSimulatorArm64(), macosArm64()).forEach { target ->
        target.binaries.framework {
            baseName = "Reachable"
            isStatic = true
            // Pin the bundle id so SKIE doesn't fall back to the framework name.
            binaryOption("bundleId", "com.happycodelucky.reachable")
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

// --- KMMBridge: Maven-hosted XCFramework + SPM Package.swift (CLAUDE.md §9) ---
//
// Distribution pipeline:
//   1. `:reachable:assembleReachableXCFramework` builds Reachable.xcframework
//      with all three slices (iosArm64, iosSimulatorArm64, macosArm64).
//   2. KMMBridge zips the XCFramework and publishes the zip as a Maven
//      artifact to GitHub Packages (`com.happycodelucky.reachable:reachable`).
//   3. KMMBridge writes a `Package.swift` at the repo root pointing SPM at
//      that Maven-hosted zip via URL + sha256 checksum. The release workflow
//      commits and pushes that Package.swift along with a `vX.Y.Z` tag.
//   4. iOS / macOS consumers add `https://github.com/happycodelucky/reachable.git`
//      as an SPM dependency and pin to a tag.
//
// Publishing is CI-only and gated by two inputs that .github/workflows/
// release.yml provides — both must be present or `kmmBridgePublish` either
// won't exist or won't have a target repo:
//
//   * Gradle property `ENABLE_PUBLISHING=true` — KMMBridge 1.2.x only
//     registers the `kmmBridgePublish` umbrella task when this is set.
//     Without it, `./gradlew :reachable:tasks --all` will not list it.
//     Kept off by default so local builds skip the publish wiring entirely.
//   * `GITHUB_REPO` (env var or -P) — `addGithubPackagesRepository()` below
//     reads this exact name (NOT the standard `GITHUB_REPOSITORY` GHA sets)
//     to build `https://maven.pkg.github.com/<owner>/<repo>`. If it's
//     missing, the helper silently early-returns and no Maven repo is
//     registered, which later surfaces as "Artifact repository not found,
//     please, specify maven repository" during publish.
//
// `addGithubPackagesRepository()` (from co.touchlab.kmmbridge.github) also
// reads `GITHUB_PUBLISH_USER` (default `cirunner`) and `GITHUB_PUBLISH_TOKEN`
// from the environment for auth. The release workflow sets all three.
//
// To reproduce the publish wiring locally for inspection (no real publish):
//   ./gradlew :reachable:tasks --all \
//       -PENABLE_PUBLISHING=true \
//       -PGITHUB_REPO=happycodelucky/reachable \
//       -PGITHUB_PUBLISH_TOKEN=dummy
addGithubPackagesRepository()

kmmbridge {
    // Push the XCFramework zip to the Maven repo registered above.
    mavenPublishArtifacts()

    // Generate Package.swift at the repo root (KMMBridge's default location).
    // The release workflow commits it back to main on each release; SPM
    // consumers depend on `https://github.com/happycodelucky/reachable.git`
    // and pin to a version tag (`from: "0.1.0"`).
    //
    // `swiftToolVersion = "6.0"` is required because the platform constants
    // `.iOS(.v18)` and `.macOS(.v15)` were introduced in PackageDescription
    // 6.0; KMMBridge defaults to 5.3 which can't compile them.
    spm(swiftToolVersion = "6.0") {
        iOS { v("18") }
        macOS { v("15") }
    }

    // The XCFramework's Swift module name. Must match the `baseName` set on
    // each `binaries.framework { }` above, or the generated Package.swift
    // will reference a binary that doesn't exist.
    frameworkName.set("Reachable")

    // Versioning: KMMBridge reads `project.version` directly. The root
    // build.gradle.kts wires `version = providers.gradleProperty("version")
    // .getOrElse("0.1.0-SNAPSHOT")`, so `-Pversion=0.1.0` from the release
    // workflow drives both the Maven coordinate version, the SwiftPackage
    // version, and the git tag in one step.
}

// --- Maven Central publishing (CLAUDE.md §9) ---------------------------------
//
// Two distribution channels run in parallel from this module:
//
//   1. GitHub Packages (KMMBridge, above) — hosts the XCFramework zip. SPM
//      consumers fetch it via the generated Package.swift. The zip is
//      attached as an additional artifact to the KMP `kotlinMultiplatform`
//      publication; KMMBridge's `kmmBridgePublish` task uploads it.
//
//   2. Maven Central (this block) — hosts the Android AAR, KMP common
//      metadata, per-target klibs (iosArm64, iosSimulatorArm64, macosArm64,
//      android), and their sources/javadoc jars. Android/JVM/KMP consumers
//      add `mavenCentral()` and resolve normally; no credentials required on
//      the consumer side.
//
// The two channels don't conflict: each plugin registers its own Maven
// repository (`GitHubPackages` vs. `mavenCentral`) and exposes its own
// umbrella publish task. The release workflow calls them sequentially.
//
// Reading credentials: vanniktech reads `mavenCentralUsername`,
// `mavenCentralPassword`, `signingInMemoryKey`, and
// `signingInMemoryKeyPassword` as Gradle properties, which Gradle
// auto-populates from `ORG_GRADLE_PROJECT_*` env vars in CI. The release
// workflow wires the four GitHub Actions secrets to those env names. Locally,
// these properties are unset and signing is silently skipped (which is fine
// for `publishToMavenLocal` dry-runs).
mavenPublishing {
    // SonatypeHost.CENTRAL_PORTAL targets the new central.sonatype.com
    // endpoint. Do NOT use SonatypeHost.DEFAULT — that's the legacy
    // s01.oss.sonatype.org OSSRH endpoint, which Sonatype is decommissioning.
    // `automaticRelease = true` makes `publishAndReleaseToMavenCentral` a
    // single-shot build + sign + upload + close + release task. Without it,
    // artifacts land in a "validated" state on the Portal and require a
    // manual "Publish" click in the web UI.
    publishToMavenCentral(automaticRelease = true)

    // Required by Central — every artifact (jar, aar, klib, module, pom)
    // must carry a detached GPG signature next to it. signAllPublications()
    // applies the signing plugin across every publication the project
    // exposes, including KMMBridge's XCFramework-zip artifact on the
    // kotlinMultiplatform publication. Central rejects unsigned uploads.
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
