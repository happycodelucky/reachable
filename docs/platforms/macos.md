# macOS

The macOS implementation is **functionally identical to iOS** — both share
the `appleMain` source set in `:reachable`, which contains a single
`AppleReachability` class that wraps `nw_path_monitor`. Read [Platforms → iOS](ios.md)
for the full implementation details. This page covers the macOS-specific
deltas.

## Construction

Same factory:

```swift
import Reachable

let reachability: any Reachability = Reachability()
```

The same `nw_path_monitor`-based observer registration runs on macOS as on
iOS. Same dispatch queue, same update-handler shape, same mapping rules.

## Deployment target

`macOS 15.0` — set in `gradle/libs.versions.toml`. Network framework is
available on macOS 10.14+, so this is well above the floor.

## App sandbox & entitlements

`nw_path_monitor` itself does **not** require any specific entitlement.
However, Mac App Store-eligible apps run in App Sandbox by default, and the
sandbox needs `com.apple.security.network.client` for outgoing network
traffic. The reachability check itself works without it (the monitor only
inspects state, doesn't make connections), but a real app that uses the
network needs the entitlement anyway.

The sample `macOSApp` declares both:

```xml
<dict>
    <key>com.apple.security.app-sandbox</key>
    <true/>
    <key>com.apple.security.network.client</key>
    <true/>
</dict>
```

## Wired Ethernet — known limitation

Wired Ethernet on macOS surfaces as `Transport.Other`, not `Transport.Ethernet`.
This is a Kotlin/Native cinterop gap, not a macOS issue — see
[Concepts → Validated vs available](../concepts/validated-vs-available.md#wired-ethernet-on-macos-known-limitation)
for the details and workarounds.

## What's different from iOS

For this library, essentially nothing:

- Same code path (`AppleReachability` in `appleMain`).
- Same C API (`nw_path_monitor_*`).
- Same mapping rules.
- Same threading model.

The differences live in the **app surrounding the library**:

- macOS apps are typically multi-window — wire `Reachability` once at app
  launch and inject it into each window's view-model.
- macOS supports both `WindowGroup` (lifecycle SwiftUI) and `NSApplicationDelegateAdaptor`
  for AppKit-bridged apps. Both work fine; the construction point varies.
- macOS Low Data Mode is set per-Wi-Fi-network in System Settings → Network →
  Wi-Fi → Details. Toggle it to manually trigger `Metering.Constrained`
  emissions.

```swift
@main
struct ReachableExampleApp: App {
    private let reachability: any Reachability = Reachability()

    var body: some Scene {
        WindowGroup {
            ReachabilityScreen()
                .environmentObject(ConnectivityModel(reachability: reachability))
        }
        .windowResizability(.contentSize)
    }
}
```

## See also

- [Platforms → iOS](ios.md) — the full implementation walk-through.
- [Concepts → Lifecycle](../concepts/lifecycle.md) — when to construct one Reachability vs many.
