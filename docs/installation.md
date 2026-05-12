# Installation

Reachable publishes to Maven Central as a Kotlin Multiplatform artifact. The
Android AAR is consumed directly from Gradle. Apple consumers come in via the
same artifact's `iosArm64`, `iosSimulatorArm64`, and `macosArm64` klibs —
i.e. by depending on `:reachable` from a KMP project.

A native Swift Package Manager distribution is planned for a later release
(see [Apple-side SPM](#apple-side-spm-roadmap) below).

## Platform floors

| Platform     | Floor      |
|--------------|------------|
| iOS / iPadOS | iOS 18     |
| macOS        | macOS 15   |
| Android      | API 30 (Android 11), `arm64-v8a` only |
| Kotlin       | 2.3.x (K2). The upper bound tracks SKIE — see [SKIE releases](https://github.com/touchlab/SKIE/releases). |

## Gradle (Android, JVM, KMP)

Maven Central is on the default repository list, so no `repositories { }`
block changes are needed.

=== "Android module"

    ```kotlin
    // app/build.gradle.kts
    dependencies {
        implementation("com.happycodelucky.reachable:reachable:{{ version }}")
    }
    ```

=== "Kotlin Multiplatform module"

    ```kotlin
    // shared/build.gradle.kts
    kotlin {
        sourceSets {
            commonMain.dependencies {
                implementation("com.happycodelucky.reachable:reachable:{{ version }}")
            }
        }
    }
    ```

`android.permission.ACCESS_NETWORK_STATE` is declared in the library's own
manifest and merged in at build time. It's a normal-protection permission,
so no runtime grant is needed.

## Apple-side SPM (roadmap)

There's no standalone `.xcframework` or `Package.swift` published in v0.1.
Pure-Swift apps that don't already use Kotlin Multiplatform can't consume
the library at the moment. A native SPM distribution — either as a
KMMBridge-style XCFramework hosted on GitHub Packages or as a flat
SwiftPackage backed by the published klibs — is on the v0.2 plan.

If you're working from a KMP project today, the iOS / macOS targets are
consumed transparently via the `kotlinMultiplatform` metadata published
alongside the Android AAR. SKIE bridging happens at your project's
framework build time, not the library's.

## Local development override

When working on the library itself, publish to your local Maven repository
and consume from there:

```bash
./gradlew :reachable:publishToMavenLocal
```

A consuming Gradle project then adds `mavenLocal()` to its repository list:

```kotlin
repositories {
    mavenLocal()
    mavenCentral()
}
```

…and pins the dependency to the snapshot version (e.g.
`com.happycodelucky.reachable:reachable:0.1.0-SNAPSHOT`). Re-running
`mise run publish:local` overwrites the cached artifact; the consumer picks
up the change on the next Gradle sync.

See [Publishing](publishing.md) for the full local + Maven Central pipeline.

## Verification

=== "Kotlin"

    ```kotlin
    // Singleton path — no setup required.
    val r = Reachability.shared
    println(r.status.value)   // ReachabilityStatus(reachable=…, transport=…, metering=…)
    // r.close() is a no-op on .shared; omit it.
    ```

=== "Swift"

    ```swift
    // Singleton path — no setup required.
    let r = Reachability.shared
    print(r.status.value!)    // ReachabilityStatus(reachable: …, transport: …, metering: …)
    // r.close() is a no-op on .shared; omit it.
    ```
