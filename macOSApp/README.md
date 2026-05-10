# macOSApp — Reachable sample

A single-window SwiftUI app that subscribes to `reachability.status` and
renders the live `ReachabilityStatus`. Toggle Wi-Fi from the menu bar,
plug in or unplug Ethernet, or enable Low Data Mode in System Settings →
Network → Wi-Fi → Details to see live updates.

The implementation is the same as iOSApp; both consume the same
`appleMain` Kotlin source set in `:reachable`. Only the deployment target
(`macOS 15.0`) and a few SwiftUI window-sizing tweaks differ.

## Prerequisites

Same as iOSApp: Xcode 16+ and the mise-managed toolchain. Run
`brew install mise` then `mise install` from the repo root. See
[iOSApp/README.md](../iOSApp/README.md#prerequisites) for the full list.

## First-time setup

```bash
mise run open:macos   # spm:dev + xcodegen + open macOSApp in Xcode
```

In Xcode, pick the macOSApp scheme and Run.

## Iteration loop

After editing Kotlin in `/reachable/src/...`:

```bash
mise run spm:dev
```

Then rebuild the macOSApp target in Xcode. See
[iOSApp/README.md](../iOSApp/README.md) for the local-SPM mechanics.

## Sandbox and entitlements

`macOSApp.entitlements` enables `app-sandbox` (the Mac App
Store-eligible default) and `network.client` so the displayed reachability
state reflects an app that actually uses the network. `nw_path_monitor`
itself doesn't require any specific entitlement; both entries are about
the app, not the library.
