# Changelog

Versions follow [SemVer](https://semver.org/). KMMBridge stamps the released
version onto the published Maven artifact and the matching SwiftPackage tag.
Pin to a tag, never to `branch: "main"`.

## Unreleased

### Public API

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
  Android. The asymmetry is intentional — consumers wire construction in
  their own DI graph.

### Implementation

- Apple: `nw_path_monitor` via `platform.Network` cinterop, on a
  per-instance serial dispatch queue.
- Android: `ConnectivityManager.NetworkCallback` against
  `NET_CAPABILITY_INTERNET + NET_CAPABILITY_VALIDATED`. `status.value` is
  seeded synchronously from `connectivityManager.activeNetwork` so it's
  meaningful immediately after construction.
- Shared base class owns a `SupervisorJob`-rooted scope and an atomic close
  latch. `close()` is idempotent.

### Distribution

- XCFramework published to GitHub Packages via
  `co.touchlab.kmmbridge.github`; `Package.swift` committed at the repo root
  by the release workflow.
- AAR published under `com.happycodelucky.reachable:reachable`.

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
