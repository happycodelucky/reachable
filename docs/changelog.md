# Changelog

Versions follow [SemVer](https://semver.org/). Each release is published
to Maven Central and tagged on GitHub. Pin to a tag, never to
`branch: "main"`.

## Unreleased

### Public API

- **`Reachability.shared`** — process-lifetime singleton on the
  `Reachability` interface's `companion object`. Callable from anywhere
  at any time, with no Context plumbing required. Replaces the pattern of
  constructing `Reachability(context)` in `Application.onCreate` and
  threading it through your DI graph.
    - `close()` on `Reachability.shared` is an intentional **no-op** —
      the singleton lives for the process; the OS reaps the platform
      observer at process exit.
    - From Swift: `Reachability.shared` via an in-framework Swift
      extension (`src/appleMain/swift/Reachability+Shared.swift`); the
      raw `ReachabilityCompanion` call is hidden.
- `interface Reachability : AutoCloseable` exposing
  `val status: StateFlow<ReachabilityStatus>` and `fun close()`.
- Single-axis shortcuts on `Reachability`:
    - `val isReachable: Boolean` — synchronous online check.
    - `val isLowDataMode: Boolean` — synchronous Low Data Mode check
      (always `false` on Android).
    - `val reachable: StateFlow<Boolean>` — reactive variant of
      `isReachable`, conflated so transport and metering changes don't emit.
    - `val lowDataMode: StateFlow<Boolean>` — reactive variant of
      `isLowDataMode`.
- `data class ReachabilityStatus(reachable, transport, metering)`.
- `enum class Transport { Wifi, Cellular, Ethernet, Other, None }`.
- `enum class Metering { Unmetered, Metered, Constrained }`. `Constrained`
  is Apple-only (Low Data Mode) and is never emitted on Android.
- Top-level factories: `Reachability()` on Apple, `Reachability(context)` on
  Android. Still available for explicit-lifecycle use (tests, per-feature
  observers). For general use, prefer `Reachability.shared`.

### Implementation

- **`androidx.startup` integration (Android)**: `ReachabilityInitializer`
  (an `androidx.startup.Initializer`) is registered in the library's
  `AndroidManifest.xml` under `InitializationProvider` with
  `tools:node="merge"`. It attaches `Reachability.shared` to the
  application `Context` during the ContentProvider startup pass — before
  `Application.onCreate`. Consumers get auto-init for free; no manifest
  changes required. `androidx.startup:startup-runtime:1.2.0` is a new
  `implementation` dependency of the library.
- **Two-phase Android construction**: `AndroidReachability` now has a
  zero-arg primary constructor (no OS calls, no Context) and an
  `internal fun attach(context)` that is idempotent (first-call-wins via
  CAS). The existing `Reachability(context)` factory still calls
  `attach()` immediately. The singleton path defers `attach()` to the
  `ReachabilityInitializer`.
- Apple: `nw_path_monitor` via `platform.Network` cinterop, on a
  per-instance serial dispatch queue.
- Android: `ConnectivityManager.NetworkCallback` against
  `NET_CAPABILITY_INTERNET + NET_CAPABILITY_VALIDATED`. `status.value` is
  seeded synchronously from `connectivityManager.activeNetwork` so it's
  meaningful immediately after construction.
- Shared base class owns a `SupervisorJob`-rooted scope and an atomic close
  latch. `close()` is idempotent.

### Distribution

- Published to Maven Central as
  `com.happycodelucky.reachable:reachable` via
  [vanniktech maven-publish](https://github.com/vanniktech/gradle-maven-publish-plugin).
  Includes the Android AAR plus the `kotlinMultiplatform` metadata and
  the `iosArm64`, `iosSimulatorArm64`, `macosArm64` klibs, all signed.
- A native Swift Package Manager artifact is **not** published in v0.1;
  see [Installation](installation.md#apple-side-spm-roadmap).

### Known limitations

- Wired Ethernet on macOS surfaces as `Transport.Other`.
  Kotlin/Native's `platform.Network` cinterop doesn't currently expose
  `nw_interface_type_wired_ethernet`. See
  [Concepts → Validated vs available](concepts/validated-vs-available.md).
- VPN-over-Wi-Fi resolves to `Transport.Wifi` (the underlying physical
  transport). No separate `VPN` value.
- No captive-portal detection callback. Apple and Android both handle
  captive-portal flow at the OS level; the library surfaces the resolved
  `reachable` boolean only.
- No `androidHostTest` for `AndroidReachability`'s deferred-attach path.
  Blocked on adding Robolectric or a `ConnectivityManager` wrapper interface.
  End-to-end coverage currently provided by the Android sample app.
