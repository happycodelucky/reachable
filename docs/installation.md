# Installation

Reachable ships through two channels:

| Channel | For | Artifacts |
|---|---|---|
| **Maven Central** | Gradle — Android, JVM, Kotlin Multiplatform | Android AAR, JVM jar, `kotlinMultiplatform` metadata, per-target klibs (`iosArm64`, `iosSimulatorArm64`, `macosArm64`) |
| **Swift Package Manager** | Pure-Swift iOS / macOS apps, no Kotlin toolchain | Prebuilt `Reachable.xcframework`, hosted as a GitHub Release asset |

Kotlin Multiplatform projects should use the Maven artifact from
`commonMain` — KMP resolves the right per-target slice automatically, and
the Swift surface is produced at *your* project's framework build. The
Swift package is for apps with no Kotlin in them at all — see
[Swift Package Manager](#swift-package-manager) below.

## Platform floors

| Platform     | Floor      |
|--------------|------------|
| iOS / iPadOS | iOS 18     |
| macOS        | macOS 15   |
| Android      | API 30 (Android 11), `arm64-v8a` only |
| JVM          | 21 (desktop / server, any OS) |
| Kotlin       | 2.3.x (K2) |

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

=== "JVM module"

    ```kotlin
    // desktop-app/build.gradle.kts
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
so no runtime grant is needed. The JVM target needs no permissions —
reachability is read from the local interface table, with no network
traffic.

## Testing support

A companion artifact — `com.happycodelucky.reachable:reachable-testing` —
ships `FakeReachability` and the `withFakeReachability { }` helper for
installing it as `Reachability.shared` for the duration of a test. Add it
as a test dependency:

=== "Android module"

    ```kotlin
    // app/build.gradle.kts
    dependencies {
        implementation("com.happycodelucky.reachable:reachable:{{ version }}")
        testImplementation("com.happycodelucky.reachable:reachable-testing:{{ version }}")
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
            commonTest.dependencies {
                implementation("com.happycodelucky.reachable:reachable-testing:{{ version }}")
            }
        }
    }
    ```

The testing artifact does not ship as an XCFramework or a Swift package —
only the main `reachable` artifact has an SPM distribution. `FakeReachability`
is consumed via KMP klibs from Maven Central.

### Basic usage

```kotlin
@Test
fun deviceIsOnline() = runTest {
    withFakeReachability(
        initial = ReachabilityStatus(isReachable = true, transport = Transport.Wifi, isDataMetered = false),
    ) { fake ->
        val vm = MyViewModel()      // reads Reachability.shared
        assertTrue(vm.online)

        fake.setReachable(false)
        assertFalse(vm.online)
    }
}
```

`withFakeReachability` installs the fake as `Reachability.shared`,
runs the block, then uninstalls and closes the fake in `finally` — even
when the block throws.

## Swift Package Manager

Pure-Swift apps consume Reachable as a binary Swift package: a prebuilt
`Reachable.xcframework` with `iosArm64`, `iosSimulatorArm64`,
and `macosArm64` slices. No Kotlin toolchain, no Gradle, no authentication —
the package manifest lives at the root of this repository and the binary is
a public GitHub Release asset, pinned by sha256 checksum in the manifest.

=== "Xcode"

    1. **File → Add Package Dependencies…**
    2. Enter `https://github.com/happycodelucky/reachable.git`.
    3. Keep **Up to Next Major Version** with the suggested version.
    4. Add the **Reachable** product to your app target.

=== "Package.swift"

    ```swift
    dependencies: [
        .package(url: "https://github.com/happycodelucky/reachable.git", from: "{{ version }}"),
    ],
    targets: [
        .target(
            name: "MyApp",
            dependencies: [
                .product(name: "Reachable", package: "reachable"),
            ]
        ),
    ]
    ```

Then `import Reachable`. The Swift bridge is baked into the framework, so
`StateFlow` arrives as a Swift `AsyncSequence`, sealed types `switch`
exhaustively via `onEnum(of:)`, and `suspend` functions are `async throws`.

Each release tag carries a `Package.swift` whose binary target references
that release's `Reachable.xcframework.zip` asset, so `swift package
resolve` downloads a prebuilt framework instead of compiling Kotlin.

If you're working from a KMP project, don't add the Swift package — the
iOS / macOS targets are consumed transparently via the
`kotlinMultiplatform` metadata published alongside the Android AAR, and
the Swift surface is produced at your project's framework build time, not
the library's.

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

See [`.github/PUBLISHING.md`](https://github.com/happycodelucky/reachable/blob/main/.github/PUBLISHING.md) for the full local + Maven Central pipeline.

## Verification

=== "Kotlin"

    ```kotlin
    // Singleton path — no setup required.
    val r = Reachability.shared
    println(r.status.value)   // ReachabilityStatus(isReachable=…, transport=…, isDataMetered=…)
    // r.close() is a no-op on .shared; omit it.
    ```

=== "Swift"

    ```swift
    // Singleton path — no setup required.
    let r = Reachability.shared
    print(r.status.value!)    // ReachabilityStatus(isReachable: …, transport: …, isDataMetered: …)
    // r.close() is a no-op on .shared; omit it.
    ```
