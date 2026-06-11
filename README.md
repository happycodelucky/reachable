# Reachable

![iOS 18+](https://img.shields.io/badge/iOS-18%2B-blue.svg?style=for-the-badge&logo=apple)
![macOS 15+](https://img.shields.io/badge/macOS-15%2B-blue.svg?style=for-the-badge&logo=apple)
![Android 11+](https://img.shields.io/badge/Android-11%2B-3DDC84.svg?style=for-the-badge&logo=android&logoColor=white)
![Kotlin 2.3](https://img.shields.io/badge/Kotlin-2.3-7F52FF.svg?style=for-the-badge&logo=kotlin&logoColor=white)
[![CI](https://img.shields.io/github/actions/workflow/status/happycodelucky/reachable/ci.yml?style=for-the-badge&label=ci)](https://github.com/happycodelucky/reachable/actions/workflows/ci.yml)
[![Docs](https://img.shields.io/github/actions/workflow/status/happycodelucky/reachable/docs.yml?style=for-the-badge&label=docs)](https://github.com/happycodelucky/reachable/actions/workflows/docs.yml)
[![Release](https://img.shields.io/github/v/release/happycodelucky/reachable?style=for-the-badge)](https://github.com/happycodelucky/reachable/releases/latest)
[![License: Apache 2.0](https://img.shields.io/badge/License-Apache_2.0-blue.svg?style=for-the-badge)](./LICENSE)

A Kotlin Multiplatform library that tells you whether the device is on the
internet and lets you observe changes as they happen, behind one API:

- **iOS 18+, iPadOS 18+, macOS 15+**: Apple's Network framework
  `nw_path_monitor` (via Kotlin/Native cinterop), serving the same code
  path on all three platforms.
- **Android 11+ (API 30)**: `ConnectivityManager.NetworkCallback`
  registered against `NET_CAPABILITY_INTERNET + NET_CAPABILITY_VALIDATED`,
  so captive portals correctly register as not reachable.

UI is out of scope — Reachable is the headless `:reachable` KMP module
(CLAUDE.md §1). Each platform app consumes it natively.

`Reachability.shared` is the recommended entry point: a process-lifetime
singleton with no Context plumbing. On Android, the library's bundled
`androidx.startup` initializer attaches it before `Application.onCreate`.
On Apple, first access constructs the `nw_path_monitor` eagerly and starts
observing immediately. The explicit-lifecycle factories
(`Reachability(context)` / `Reachability()`) remain available for tests
and any code that wants per-instance teardown.

ARM-only targets: `iosArm64`, `iosSimulatorArm64`, `macosArm64`, Android
`arm64-v8a`. SKIE bridges the Swift surface — enums become exhaustive
Swift enums, `StateFlow<T>` becomes `AsyncSequence<T>`, `suspend fun`
becomes `async throws`.

---

## Documentation

The full mkdocs site is published to
[happycodelucky.github.io/reachable](https://happycodelucky.github.io/reachable/).
Highlights:

- [Getting started](docs/getting-started.md): three steps from install to
  a UI-bound `Reachability`.
- [Installation](docs/installation.md): Maven Central, KMP-side Apple
  consumption, local development override.
- [Concepts → API design](docs/concepts/api-design.md): the public type,
  the asymmetric factories, why no `Result`.
- [Concepts → Lifecycle](docs/concepts/lifecycle.md): when to construct,
  when to close, threading.
- [Concepts → Validated vs available](docs/concepts/validated-vs-available.md):
  why `INTERNET + VALIDATED`, wired-Ethernet mapping, captive portals.
- Recipes: [SwiftUI binding](docs/recipes/swiftui-binding.md),
  [Compose binding](docs/recipes/compose-binding.md),
  [React to changes](docs/recipes/react-to-changes.md),
  [Captive portals](docs/recipes/captive-portal.md).
- [Contributing](docs/contributing.md): development environment,
  reporting bugs, PR expectations.

---

## Installation

Reachable publishes to Maven Central. From a Kotlin Multiplatform project,
depend on `:reachable` from `commonMain` — KMP resolves the right per-target
slice (Android AAR, `iosArm64`, `iosSimulatorArm64`, `macosArm64`) for you:

```kotlin
// shared/build.gradle.kts
kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation("com.happycodelucky.reachable:reachable:0.12.11")
        }
    }
}
```

Android-only consumers depend on the artifact directly:

```kotlin
// app/build.gradle.kts
dependencies {
    implementation("com.happycodelucky.reachable:reachable:0.12.11")
}
```

Pure-Swift apps (no Kotlin toolchain) consume Reachable via Swift Package
Manager instead — a prebuilt, SKIE-enhanced `Reachable.xcframework` attached
to each GitHub Release:

```swift
// Package.swift — or Xcode: File → Add Package Dependencies…
dependencies: [
    .package(url: "https://github.com/happycodelucky/reachable.git", from: "0.12.11"),
]
```

A companion `reachable-testing` artifact ships `FakeReachability` and the
`withFakeReachability { }` helper — add it as a test dependency at the same
version (Maven Central only; no SPM distribution). See the
[Installation guide](https://happycodelucky.github.io/reachable/installation/)
for platform floors, the testing artifact, and the local-development
override.

---

## Quick example

```kotlin
// Singleton path — no Context plumbing, no construction boilerplate.
val reachability: Reachability = Reachability.shared
if (reachability.isReachable) {
    // online
}
reachability.status.collect { status ->
    // every state change
}
// close() on .shared is a no-op — the singleton lives for the process.
```

From Swift:

```swift
let reachability: any Reachability = Reachability.shared
```

For explicit-lifecycle needs (tests, per-feature observers):

```kotlin
// Android
val r: Reachability = Reachability(applicationContext)
// Apple
val r: any Reachability = Reachability()
r.close() // honours close() normally
```

`status.value` for a synchronous read, `status.collect {}` for a reactive
listener, `status.first()` for a one-shot suspend.

---

## Launch sequence — Apple (iOS, iPadOS, macOS)

The same `appleMain` factory covers all three platforms. Use
`Reachability.shared` for zero-setup access:

```swift
import Reachable

@MainActor
@Observable
final class ConnectivityModel {
    var status: ReachabilityStatus = ReachabilityStatus.companion.Unknown

    @ObservationIgnored
    private var task: Task<Void, Never>?

    init() {
        // Reachability.shared — process-lifetime singleton, no close needed.
        let reachability: any Reachability = Reachability.shared
        task = Task { [weak self] in
            for await s in reachability.status { self?.status = s }
        }
    }

    deinit { task?.cancel() }
}
```

For explicit-lifecycle code (tests or per-feature observers), use the
top-level factory and call `close()` on teardown:

```swift
@main
struct MyApp: App {
    private let reachability: any Reachability = Reachability()

    var body: some Scene {
        WindowGroup {
            ContentView()
                .environmentObject(ConnectivityModel(reachability: reachability))
        }
    }
}
```

`nw_path_monitor` doesn't require any entitlement. The `network.client`
sandbox entitlement on macOS is about the app's own outgoing traffic, not
the reachability check.

---

## Launch sequence — Android

The library bundles an `androidx.startup` initializer that attaches
`Reachability.shared` to the application `Context` automatically during
the `InitializationProvider` ContentProvider pass — before
`Application.onCreate`. No setup required:

```kotlin
import com.happycodelucky.reachable.Reachability

// No Application subclass, no Context plumbing needed.
@Composable
fun ConnectivityBanner() {
    val status by Reachability.shared.status.collectAsStateWithLifecycle()
    if (!status.isReachable) {
        Text("You're offline")
    }
}
```

If you disable `InitializationProvider` entirely (rare), or need explicit
lifecycle control, use the factory instead:

```kotlin
class MyApp : Application() {
    lateinit var reachability: Reachability

    override fun onCreate() {
        super.onCreate()
        reachability = Reachability(applicationContext)
    }

    override fun onTerminate() {
        reachability.close()
        super.onTerminate()
    }
}
```

`android.permission.ACCESS_NETWORK_STATE` is declared in the library's own
`AndroidManifest.xml` and merged into your app at build time. It's a
normal-protection permission, so no runtime grant is needed.

---

## What each platform actually surfaces

|                                  | iOS / iPadOS                       | macOS                              | Android                                 |
| -------------------------------- | ---------------------------------- | ---------------------------------- | --------------------------------------- |
| Reachability backend             | `nw_path_monitor` (satisfied)      | `nw_path_monitor` (satisfied)      | `NetworkCallback` (`INTERNET + VALIDATED`) |
| Captive-portal handling          | OS-internal probe                  | OS-internal probe                  | `NET_CAPABILITY_VALIDATED`              |
| `Transport.Wifi` / `Cellular`    | yes                                | yes                                | yes                                     |
| `Transport.Ethernet`             | yes (USB-C / dock adapters)        | yes (`nw_interface_type_wired`)    | yes (`TRANSPORT_ETHERNET`)              |
| `isDataMetered = true`           | `nw_path_is_expensive \|\| nw_path_is_constrained` | `nw_path_is_expensive \|\| nw_path_is_constrained` | `!(NET_CAPABILITY_NOT_METERED \|\| TEMPORARILY_NOT_METERED)` |
| Status seeded synchronously      | no (first emission within tens of ms) | no                                 | **yes** (from `activeNetwork`)          |

Apple's Low Data Mode signal (`nw_path_is_constrained`) folds into
`isDataMetered` alongside `nw_path_is_expensive`; there's no separate
"Low Data Mode" axis in the public API.

---

## Single-axis shortcuts

The two most-asked questions get dedicated properties so callers don't
unpack `status.value` for a one-line read:

```kotlin
reachability.isReachable      // sync online check
reachability.isDataMetered    // sync metered-path check

reachability.reachable        // StateFlow<Boolean>, conflated, online/offline only
reachability.dataMetered      // StateFlow<Boolean>, conflated, metered/unmetered only
```

The reactive variants are dedicated `MutableStateFlow`s the library
updates synchronously alongside `status` — transport-only changes don't
trigger emissions on `reachable` or `dataMetered`.

---

## Testing support

A companion artifact ships test fakes so consumers can drive
`Reachability.shared` in unit tests without a live platform observer:

```kotlin
// shared/build.gradle.kts
kotlin {
    sourceSets {
        commonTest.dependencies {
            implementation("com.happycodelucky.reachable:reachable-testing:VERSION")
        }
    }
}
```

Then from any test:

```kotlin
@Test
fun deviceIsOnline() = runTest {
    withFakeReachability(
        initial = ReachabilityStatus(
            isReachable = true,
            transport = Transport.Wifi,
            isDataMetered = false,
        ),
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
when the block throws. See [Installation](docs/installation.md#testing-support)
for the full dependency snippet and the Gradle coordinate.

---

## Build and test

[`mise`](https://mise.jdx.dev) pins JDK, Gradle, Python, `xcodegen`, `gh`,
and the Swift tooling. Bootstrap once per machine:

```bash
brew install mise
mise trust        # accept mise.toml in this checkout
mise install      # provision every tool at the pinned version
```

Then the task surface:

```bash
mise run check          # ktlint + all unit tests (iOS sim, macOS, Android host)
mise run build:ios      # iOS device + simulator debug frameworks
mise run build:macos    # macOS desktop debug framework
mise run build          # release Reachable.xcframework (sample-app local SPM)
mise run build:android  # Android AAR
mise run open:ios       # spm:dev + xcodegen + open iOSApp in Xcode
mise run open:macos     # spm:dev + xcodegen + open macOSApp in Xcode
```

Each task is a thin wrapper over `./gradlew` (or `xcodegen` for the
`open:*` tasks); see [`mise.toml`](./mise.toml) for the exact mapping, or
run `mise tasks` to list everything. Raw `./gradlew` invocations still
work — mise just ensures everyone (and CI) runs the same versions.

For the iOS and macOS sample apps see [`iOSApp/README.md`](./iOSApp/README.md)
and [`macOSApp/README.md`](./macOSApp/README.md).

---

## Repository conventions

- **Versions** (`gradle/libs.versions.toml`) are the single source of
  truth. Web-search before bumping any dependency (CLAUDE.md §2). Kotlin
  is pinned at the highest version SKIE supports — currently 2.3.20 with
  SKIE 0.10.11.
- Every public method on the Swift-facing surface follows
  CLAUDE.md §8 conventions: `@ObjCName` where the default Swift name is
  awkward, `@Throws` on every `suspend fun` that can throw across the
  boundary.
- `internal` by default; widen visibility only when needed (CLAUDE.md §3).
- DI is a user choice. The primary entry point is `Reachability.shared`
  (zero-setup singleton). For tests or explicit lifecycle, the asymmetric
  platform factories (`Reachability(context)` / `Reachability()`) are also
  public. No DI container is required by the library.

See [`CLAUDE.md`](./CLAUDE.md) for the full project conventions.

## License

Reachable is released under the [Apache License 2.0](./LICENSE).

```
Copyright 2026 Paul Bates

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

    https://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
```
