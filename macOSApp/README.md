# macOSApp — Reachable sample

Single-window SwiftUI app that subscribes to `reachability.status` and renders
the live `ReachabilityStatus`. Toggle Wi-Fi from the menu bar, plug in /
unplug Ethernet, or enable Low Data Mode in System Settings → Network →
Wi-Fi → Details to see live updates.

Functionally identical to the iOSApp sample — both consume the same
`appleMain` Kotlin source set in `:reachable`. Only the deployment target
(`macOS 15.0`) and a few SwiftUI window-sizing tweaks differ.

## Prerequisites

Same as iOSApp: Xcode 16+, [`xcodegen`](https://github.com/yonaskolb/XcodeGen)
(`brew install xcodegen`), JDK 21 + Gradle.

## First-time setup

```bash
cd macOSApp
make all
```

Then in Xcode pick the macOSApp scheme and Run.

## Iteration loop

After editing Kotlin in `/reachable/src/...`:

```bash
cd macOSApp
make spm
```

Then rebuild the macOSApp target in Xcode. See [iOSApp/README.md](../iOSApp/README.md)
for a longer description of the local-SPM mechanics.

## Sandbox & entitlements

`macOSApp.entitlements` enables `app-sandbox` (Mac App Store-eligible
default) plus `network.client` so the displayed reachability state reflects
an app that actually uses the network. `nw_path_monitor` itself does not
require any specific entitlement — both these entries are about the *app*
rather than the library.
