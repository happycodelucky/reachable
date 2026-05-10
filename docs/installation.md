# Installation

Reachable ships as an XCFramework on Swift Package Manager (iOS, macOS) and
as an Android AAR on Maven (Android, JVM-bound consumers). Both artifacts are
hosted on GitHub Packages.

## Platform floors

| Platform     | Floor      |
|--------------|------------|
| iOS / iPadOS | iOS 18     |
| macOS        | macOS 15   |
| Android      | API 30 (Android 11), `arm64-v8a` only |
| Kotlin       | 2.3.x (K2). The upper bound tracks SKIE — see [SKIE releases](https://github.com/touchlab/SKIE/releases). |

## Swift Package Manager (iOS, macOS)

In Xcode: **File → Add Package Dependencies…** with
`https://github.com/happycodelucky/reachable.git`.

In a `Package.swift` manifest:

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

Pin to a tag (`from:`, `exact:`, or a half-open range), never to
`branch: "main"`. Each release rewrites `Package.swift` at the repo root with
a fresh binary URL and checksum, so a `branch: "main"` consumer would see
work in progress.

The Swift module is `Reachable`:

```swift
import Reachable

let reachability: any Reachability = Reachability()
```

### GitHub Packages authentication

The XCFramework binary lives in GitHub Packages, which requires
authentication even for public repositories. Add a `~/.netrc` entry on every
machine that resolves the package:

```
machine maven.pkg.github.com
login your-github-username
password ghp_your_personal_access_token   # needs `read:packages` scope
```

CI uses the workflow's `GITHUB_TOKEN` automatically.

## Gradle (Android, JVM)

Add GitHub Packages to `settings.gradle.kts`:

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

Then declare the dependency:

```kotlin
dependencies {
    implementation("com.happycodelucky.reachable:reachable:0.1.0")
}
```

`android.permission.ACCESS_NETWORK_STATE` is declared in the library's own
manifest and merged in at build time. It's a normal-protection permission,
so no runtime grant is needed.

## Local development override

When working on the library itself, build the XCFramework locally and point
your Xcode app at it:

```bash
./gradlew :reachable:spmDevBuild
```

The task rebuilds `reachable/build/XCFrameworks/debug/Reachable.xcframework`
and rewrites the root `Package.swift` to reference the local file. Any Xcode
app declaring `.package(path: "../reachable")` picks up your changes after a
rebuild, with no Gradle inside Xcode.

The sample apps under `/iOSApp` and `/macOSApp` use this pattern; see
[iOSApp/README.md](https://github.com/happycodelucky/reachable/blob/main/iOSApp/README.md)
for the iteration loop.

## Verification

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
