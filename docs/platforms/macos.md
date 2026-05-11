# macOS

The macOS implementation is the same as iOS. Both share the `appleMain`
source set in `:reachable`, which contains a single `AppleReachability`
class wrapping `nw_path_monitor`. See [Platforms → iOS](ios.md) for the
implementation walk-through; this page covers the macOS-specific deltas.

## Singleton entry point — `Reachability.shared`

```swift
import Reachable

let reachability: any Reachability = Reachability.shared
```

`Reachability.shared` is a process-lifetime singleton provided by the
same Swift extension as on iOS (`src/appleMain/swift/Reachability+Shared.swift`,
compiled into the `Reachable` module by SKIE). On first access, constructs
an `nw_path_monitor`-backed observer and starts it eagerly. Calling
`close()` on this instance is a no-op.

## Explicit-lifecycle factory

```swift
import Reachable

let reachability: any Reachability = Reachability()
```

Same factory, same dispatch queue, same update-handler shape, same mapping
rules as iOS. Use for tests or per-feature observers that need explicit
teardown via `close()`.

## Deployment target

macOS 15.0, set in `gradle/libs.versions.toml`. The Network framework is
available on macOS 10.14+, so this is well above the floor.

## App sandbox and entitlements

`nw_path_monitor` does not require any specific entitlement. Mac App
Store-eligible apps run in App Sandbox by default; the sandbox needs
`com.apple.security.network.client` for outgoing network traffic. The
reachability check itself works without it — the monitor only inspects
state, never makes connections — but a real app that uses the network
needs the entitlement anyway.

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

Wired Ethernet on macOS surfaces as `Transport.Other`, not
`Transport.Ethernet`. This is a Kotlin/Native cinterop gap. See
[Concepts → Validated vs available](../concepts/validated-vs-available.md#wired-ethernet-on-macos-known-limitation).

## What's different from iOS

The library code is identical. The differences live in the surrounding
app:

- macOS apps are typically multi-window. Wire `Reachability` once at app
  launch and inject it into each window's view-model.
- macOS supports both `WindowGroup` (lifecycle SwiftUI) and
  `NSApplicationDelegateAdaptor` for AppKit-bridged apps. Both work; the
  construction point varies.
- macOS Low Data Mode is set per-Wi-Fi network in System Settings → Network
  → Wi-Fi → Details. Toggle it to trigger `Metering.Constrained` emissions
  manually.

Using `Reachability.shared` (recommended — no property needed):

```swift
@main
struct ReachableExampleApp: App {
    var body: some Scene {
        WindowGroup {
            ReachabilityScreen()
                .environmentObject(ConnectivityModel(reachability: Reachability.shared))
        }
        .windowResizability(.contentSize)
    }
}
```

Or with an explicit-lifecycle instance (useful if you want `close()` on
app teardown):

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

- [Platforms → iOS](ios.md): the full implementation walk-through.
- [Concepts → Lifecycle](../concepts/lifecycle.md): when to construct one Reachability vs many.
