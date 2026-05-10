# Installation

Reachable is published as:

- **An XCFramework** zipped and uploaded to GitHub Packages on each release,
  with a `Package.swift` at the repo root pointing SPM at it.
- **An Android AAR** published to GitHub Packages under
  `com.happycodelucky.reachable:reachable`.

## Platform floors

| Platform     | Floor      | Why                                                              |
|--------------|------------|------------------------------------------------------------------|
| iOS / iPadOS | iOS 18.0   | Aligns the deployment target across the ARM-only KMP slices.     |
| macOS        | macOS 15.0 | Same — Network framework is available much earlier (10.14+).     |
| Android      | API 30     | arm64-v8a only; minSdk pinned to 30 (Android 11).                |
| Kotlin       | 2.3.x      | K2 only. SKIE caps the upper bound; track the [SKIE release notes](https://github.com/touchlab/SKIE/releases). |

## iOS / macOS — Swift Package Manager

Add the package to Xcode (**File → Add Package Dependencies…**) using
`https://github.com/happycodelucky/reachable.git`, or declare it in a
`Package.swift` manifest:

```swift
// swift-tools-version:6.0
import PackageDescription

let package = Package(
    name: "MyApp",
    platforms: [.iOS(.v18), .macOS(.v15)],
    dependencies: [
        .package(url: "https://github.com/happycodelucky/reachable.git", from: "0.1.0"),
    ],
    targets: [
        .target(
            name: "MyApp",
            dependencies: [
                .product(name: "Reachable", package: "reachable"),
            ]
        ),
    ]
)
```

Pin to a tag (`from: "0.1.0"`, `exact: "0.1.0"`, or
`"0.1.0"..<"0.2.0"`) — never to `branch: "main"`. The `Package.swift` at
the root of the source repo is updated by every release with a fresh
binary URL + checksum, so a `branch: "main"` consumer would see in-progress
work.

The Swift module is named `Reachable`:

```swift
import Reachable

let reachability: any Reachability = Reachability()
```

### Authenticating to GitHub Packages

The XCFramework binary that the SPM manifest references lives in GitHub
Packages, which requires authentication even for public repos. Add a
`~/.netrc` entry on every machine that resolves the package:

```
machine maven.pkg.github.com
login your-github-username
password ghp_your_personal_access_token   # needs `read:packages` scope
```

CI environments use `GITHUB_TOKEN` automatically.

## Android — Gradle

Add GitHub Packages to your repositories (settings.gradle.kts):

```kotlin
dependencyResolutionManagement {
    repositories {
        maven {
            url = uri("https://maven.pkg.github.com/happycodelucky/reachable")
            credentials {
                username = providers.gradleProperty("gpr.user").orNull
                    ?: System.getenv("GITHUB_USERNAME")
                password = providers.gradleProperty("gpr.key").orNull
                    ?: System.getenv("GITHUB_TOKEN")
            }
        }
    }
}
```

Then in your app module's `build.gradle.kts`:

```kotlin
dependencies {
    implementation("com.happycodelucky.reachable:reachable:0.1.0")
}
```

The library declares `android.permission.ACCESS_NETWORK_STATE` in its own
manifest; the merger pulls it into your app at build time. No runtime
permission grant needed (it's a normal-protection permission).

## Local development override

If you're working on the library itself, point Xcode at the locally-built
debug XCFramework instead of the published one:

```bash
./gradlew :reachable:spmDevBuild
```

This rebuilds the debug XCFramework and rewrites `Package.swift` at the
repo root to reference the local file (`./reachable/build/XCFrameworks/debug/Reachable.xcframework`).
Any Xcode app declaring `.package(path: "../reachable")` (or similar
relative path to the repo root) automatically picks up your changes after a
rebuild — no Gradle inside Xcode.

The sample apps under `/iOSApp` and `/macOSApp` use exactly this pattern;
see [iOSApp/README.md](https://github.com/happycodelucky/reachable/blob/main/iOSApp/README.md)
for the iteration loop.

## Verification

After installing, the smallest verification step:

=== "Kotlin"

    ```kotlin
    val r = Reachability(applicationContext)
    println(r.status.value)   // ReachabilityStatus(reachable=…, transport=…, metering=…)
    r.close()
    ```

=== "Swift"

    ```swift
    let r = Reachability()
    print(r.status.value!)    // ReachabilityStatus(reachable: …, transport: …, metering: …)
    r.close()
    ```

If those print a sensible reading on a connected device, you're good.
