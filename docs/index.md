---
hide:
  - navigation
---

# Reachable

A small Kotlin Multiplatform reachability library: **is the device on the
public internet right now, and tell me when that changes**.

```kotlin
// Construct once at the platform entrypoint, inject the interface across
// shared code, and call close() when the owning scope tears down.
val reachability: Reachability = Reachability(context) // or Reachability() on Apple

if (reachability.status.value.reachable) {
    // Online: take the network-bound branch.
}

reachability.status.collect { status ->
    // Reactive: every state change lands here, on whatever dispatcher you collect on.
}
```

Both questions reduce to one observable: a `StateFlow<ReachabilityStatus>`.
`status.value` is the synchronous read; `status.collect { }` is the live
listener; `status.first()` is the one-shot suspend.

## What it covers

- **iOS, iPadOS, macOS** via Apple's Network framework `nw_path_monitor`
  (`platform.Network` cinterop).
- **Android** via `ConnectivityManager.NetworkCallback` against
  `NET_CAPABILITY_INTERNET + NET_CAPABILITY_VALIDATED` — captive portals
  register as **not reachable**, which is what consumers actually want.
- ARM-only targets (iosArm64, iosSimulatorArm64, macosArm64, Android arm64-v8a).
- SKIE-bridged for Swift consumers: enums become exhaustive Swift enums,
  StateFlow becomes AsyncSequence, suspend funs become `async throws`.

## Why a library instead of bespoke code per platform

The two platform APIs aren't hard, but the *correctness* details are. On
Android, bare `onAvailable` reports captive-portal Wi-Fi as online — only the
`NET_CAPABILITY_VALIDATED` capability means the device can actually reach a
public endpoint. On Apple, `nw_path_monitor` exposes a "constrained" signal
(Low Data Mode) that has no Android equivalent; getting that into a shared
type without breaking Android consumers takes a moment of API design.

This library bakes those correctness details in once and exposes a single
`ReachabilityStatus` value type that reads identically from Kotlin and Swift.

## Where to next

- **[Getting started](getting-started.md)** — wire it into a project in three steps.
- **[Installation](installation.md)** — Maven coordinates, SPM URL, version pinning.
- **[Concepts → API design](concepts/api-design.md)** — why composition over a sealed hierarchy, and what `Metering.Constrained` means.
- **[Platforms](platforms/ios.md)** — what each platform implementation actually does under the hood.
