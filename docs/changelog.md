# Changelog

All notable changes ship here. Versions follow [SemVer](https://semver.org/);
KMMBridge stamps the released version onto the published Maven artifact and
the same-tag SwiftPackage. Pin to a tag, never to `branch: "main"`.

## Unreleased

The v1 surface is feature-complete. Concepts, platforms, and recipes are all
documented.

### Public API

- `interface Reachability : AutoCloseable` with one observable
  `val status: StateFlow<ReachabilityStatus>` and one teardown `fun close()`.
- Single-axis shortcuts on `Reachability`:
  - `val isReachable: Boolean` — synchronous "is it online right now?".
  - `val isLowDataMode: Boolean` — synchronous "Apple Low Data Mode active?"
    (always `false` on Android).
  - `val reachable: StateFlow<Boolean>` — reactive variant of `isReachable`,
    shares its upstream observer with `status` and conflates identical
    consecutive values so transport / metering churn is dropped.
  - `val lowDataMode: StateFlow<Boolean>` — reactive variant of `isLowDataMode`.
- `data class ReachabilityStatus(reachable, transport, metering)`.
- `enum class Transport { Wifi, Cellular, Ethernet, Other, None }`.
- `enum class Metering { Unmetered, Metered, Constrained }` —
  `Constrained` is Apple-only (Low Data Mode); never emitted on Android.
- Top-level factories: `Reachability()` on Apple, `Reachability(context)` on
  Android. Asymmetric on purpose; the consumer's DI graph wires construction.

### Implementation

- Apple: `nw_path_monitor` from `platform.Network` cinterop, on a per-instance
  serial dispatch queue.
- Android: `ConnectivityManager.NetworkCallback` against
  `NET_CAPABILITY_INTERNET + NET_CAPABILITY_VALIDATED`; eager-seeds
  `status.value` from `connectivityManager.activeNetwork` so `.value` is
  meaningful immediately after construction.
- Shared base class owns a `SupervisorJob`-rooted scope, atomic close latch,
  idempotent `close()`.

### Distribution

- KMMBridge wired with `co.touchlab.kmmbridge.github`; XCFramework published
  to GitHub Packages, `Package.swift` committed at the repo root by the
  release workflow.
- AAR published under `com.happycodelucky.reachable:reachable`.

### Known limitations (and what we're explicitly not shipping)

- Wired Ethernet on macOS surfaces as `Transport.Other` because Kotlin/Native's
  `platform.Network` cinterop doesn't currently expose
  `nw_interface_type_wired_ethernet`. Documented in
  [Concepts → Validated vs available](concepts/validated-vs-available.md).
- VPN-over-Wi-Fi resolves to `Transport.Wifi` (the underlying physical
  transport), not a separate `VPN` value — by design.
- No "captive portal detection" callback. Apple and Android both internalise
  this; the library surfaces only the resolved `reachable` boolean.
