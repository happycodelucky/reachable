---
hide:
  - navigation
---

# Reachable

Reachable is a Kotlin Multiplatform library that tells you whether the
device is on the internet, and lets you observe changes as they happen.
It targets iOS, iPadOS, macOS, Android, and the JVM (desktop / server),
and presents the same API to Kotlin and Swift consumers.

```kotlin
// Singleton — no Context plumbing, callable from anywhere.
val reachability: Reachability = Reachability.shared

if (reachability.isReachable) {
    // online
}

reachability.status.collect { status ->
    // every state change
}
```

From Swift:

```swift
let reachability: any Reachability = Reachability.shared
```

`status.value` for a synchronous read, `status.collect {}` for a reactive
listener, `status.first()` for a one-shot suspend. `isReachable` and
`isDataMetered` are shortcuts for the two most common axes; see
[Concepts → API design](concepts/api-design.md).

For explicit lifecycle (tests, per-feature observers), use the platform
factories `Reachability(context)` / `Reachability()` instead.

## Targets

- iOS 18, iPadOS 18, macOS 15. ARM only (`iosArm64`, `iosSimulatorArm64`,
  `macosArm64`).
- Android 11 (API 30). `arm64-v8a` only.
- JVM 21 (desktop / server). Architecture-neutral bytecode — any OS with a
  JVM 21 runtime.

## Implementation

Apple uses the Network framework's `nw_path_monitor` C API via
Kotlin/Native cinterop. Android uses `ConnectivityManager.NetworkCallback`
against a `NetworkRequest` requiring `NET_CAPABILITY_INTERNET` *and*
`NET_CAPABILITY_VALIDATED`, which is the only capability pair that
distinguishes a working network from a captive portal.

Apple's `nw_path_is_expensive` and `nw_path_is_constrained` (cellular,
hotspot, and Low Data Mode) both fold into `isDataMetered`. Android uses
`NET_CAPABILITY_NOT_METERED` for the same signal.

The JVM has neither a connectivity callback nor a validation probe, so
the desktop / server backend polls the interface table and reports
best-effort reachability — see [Platforms → JVM](platforms/jvm.md) for
the exact semantics.

The Swift surface is idiomatic: Kotlin enums arrive as exhaustive Swift
enums, `StateFlow<T>` is consumed as an `AsyncSequence<T>`, and
`suspend fun` becomes `async throws`.

## Install

From Maven Central (Android, JVM, Kotlin Multiplatform):

```kotlin
implementation("com.happycodelucky.reachable:reachable:{{ version }}")
```

Kotlin Multiplatform projects get the iOS / macOS klibs published
alongside the Android AAR. Pure-Swift apps add the repo as a Swift
package instead — a prebuilt XCFramework, no Kotlin toolchain:

```swift
.package(url: "https://github.com/happycodelucky/reachable.git", from: "{{ version }}")
```

See [Installation](installation.md) for both channels.
