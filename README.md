# Reachable

![iOS 18+](https://img.shields.io/badge/iOS-18%2B-blue.svg?style=for-the-badge&logo=apple)
![macOS 15+](https://img.shields.io/badge/macOS-15%2B-blue.svg?style=for-the-badge&logo=apple)
![Android 11+](https://img.shields.io/badge/Android-11%2B-3DDC84.svg?style=for-the-badge&logo=android&logoColor=white)
![Kotlin 2.3](https://img.shields.io/badge/Kotlin-2.3-7F52FF.svg?style=for-the-badge&logo=kotlin&logoColor=white)
[![CI](https://img.shields.io/github/actions/workflow/status/happycodelucky/reachable/ci.yml?style=for-the-badge&label=ci)](https://github.com/happycodelucky/reachable/actions/workflows/ci.yml)
[![Docs](https://img.shields.io/github/actions/workflow/status/happycodelucky/reachable/docs.yml?style=for-the-badge&label=docs)](https://github.com/happycodelucky/reachable/actions/workflows/docs.yml)
[![Release](https://img.shields.io/github/v/release/happycodelucky/reachable?style=for-the-badge)](https://github.com/happycodelucky/reachable/releases/latest)

A Kotlin Multiplatform library that tells you whether the device is on the
internet and lets you observe changes as they happen, behind one API:

- **iOS 18+, iPadOS 18+, macOS 15+**: Apple's Network framework
  `nw_path_monitor` (via Kotlin/Native cinterop), serving the same code
  path on all three platforms.
- **Android 11+ (API 30)**: `ConnectivityManager.NetworkCallback`
  registered against `NET_CAPABILITY_INTERNET + NET_CAPABILITY_VALIDATED`,
  so captive portals correctly register as not reachable.

UI is out of scope — Reachable is the headless `:reachable` KMP module
(CLAUDE.md §1). Each platform app consumes it natively. The library uses
constructor injection internally and asymmetric platform factories
(Apple takes nothing, Android takes a `Context`), so any DI graph you
already use plugs in cleanly.

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
- [Installation](docs/installation.md): SPM, Gradle, GitHub Packages auth,
  local development override.
- [Concepts → API design](docs/concepts/api-design.md): the public type,
  the asymmetric factories, why no `Result`.
- [Concepts → Lifecycle](docs/concepts/lifecycle.md): when to construct,
  when to close, threading.
- [Concepts → Validated vs available](docs/concepts/validated-vs-available.md):
  why `INTERNET + VALIDATED`, the wired-Ethernet quirk, captive portals.
- Recipes: [SwiftUI binding](docs/recipes/swiftui-binding.md),
  [Compose binding](docs/recipes/compose-binding.md),
  [React to changes](docs/recipes/react-to-changes.md),
  [Captive portals](docs/recipes/captive-portal.md).
- [Contributing](docs/contributing.md): development environment,
  reporting bugs, PR expectations.

---

## Quick example

```kotlin
val reachability: Reachability = Reachability(context) // or Reachability() on Apple
if (reachability.isReachable) {
    // online
}
reachability.status.collect { status ->
    // every state change
}

reachability.close() // tear down on app exit
```

`status.value` for a synchronous read, `status.collect {}` for a reactive
listener, `status.first()` for a one-shot suspend.

---

## Launch sequence — Apple (iOS, iPadOS, macOS)

The same `appleMain` factory covers all three platforms.

```swift
import Reachable

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

@MainActor
@Observable
final class ConnectivityModel {
    var status: ReachabilityStatus = ReachabilityStatus.companion.Unknown

    @ObservationIgnored
    private let reachability: any Reachability
    @ObservationIgnored
    private var task: Task<Void, Never>?

    init(reachability: any Reachability) {
        self.reachability = reachability
        task = Task { [weak self] in
            for await s in reachability.status { self?.status = s }
        }
    }

    deinit { task?.cancel(); reachability.close() }
}
```

`nw_path_monitor` doesn't require any entitlement. The `network.client`
sandbox entitlement on macOS is about the app's own outgoing traffic, not
the reachability check.

---

## Launch sequence — Android

`Application.onCreate` constructs Reachability against the application
context once, and the rest of the app injects the interface:

```kotlin
import com.happycodelucky.reachable.Reachability

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

In Compose:

```kotlin
@Composable
fun ConnectivityBanner(reachability: Reachability) {
    val status by reachability.status.collectAsStateWithLifecycle()
    if (!status.reachable) {
        Text("You're offline")
    }
}
```

---

## What each platform actually surfaces

|                                  | iOS / iPadOS                       | macOS                              | Android                                 |
| -------------------------------- | ---------------------------------- | ---------------------------------- | --------------------------------------- |
| Reachability backend             | `nw_path_monitor` (satisfied)      | `nw_path_monitor` (satisfied)      | `NetworkCallback` (`INTERNET + VALIDATED`) |
| Captive-portal handling          | OS-internal probe                  | OS-internal probe                  | `NET_CAPABILITY_VALIDATED`              |
| `Transport.Wifi` / `Cellular`    | yes                                | yes                                | yes                                     |
| `Transport.Ethernet`             | n/a                                | **falls through to `Other`** (cinterop gap) | yes (`TRANSPORT_ETHERNET`)              |
| `Metering.Metered`               | `nw_path_is_expensive`             | `nw_path_is_expensive`             | `!NET_CAPABILITY_NOT_METERED`           |
| `Metering.Constrained`           | `nw_path_is_constrained` (Low Data Mode) | `nw_path_is_constrained` (Low Data Mode) | **never emitted** (no equivalent capability) |
| Status seeded synchronously      | no (first emission within tens of ms) | no                                 | **yes** (from `activeNetwork`)          |

The Apple-only `Metering.Constrained` and the macOS Ethernet cinterop gap
are documented in
[Concepts → Validated vs available](docs/concepts/validated-vs-available.md).

---

## Single-axis shortcuts

The two most-asked questions get dedicated properties so callers don't
unpack `status.value` for a one-line read:

```kotlin
reachability.isReachable      // sync online check
reachability.isLowDataMode    // sync Low Data Mode check (always false on Android)

reachability.reachable        // StateFlow<Boolean>, conflated, online/offline only
reachability.lowDataMode      // StateFlow<Boolean>, conflated, Low Data Mode only
```

The reactive variants are dedicated `MutableStateFlow`s the library
updates synchronously alongside `status` — transport- or metering-only
changes don't trigger emissions on `reachable`.

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
mise run build          # release Reachable.xcframework (SPM-consumable)
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
- DI is a user choice. The library uses constructor injection internally
  and asymmetric platform factories; no DI container is required.

See [`CLAUDE.md`](./CLAUDE.md) for the full project conventions.
