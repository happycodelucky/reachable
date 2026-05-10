# Reachable

A small Kotlin Multiplatform library that answers two questions:

1. **Is the device reachable on the public internet right now?**
2. **Tell me when that changes.**

Both reduce to one observable: a `StateFlow<ReachabilityStatus>`. Read
`status.value` for a synchronous one-shot; collect `status` for live updates;
call `status.first()` for a one-shot suspend.

Targets iOS, iPadOS, macOS (ARM only), and Android (arm64-v8a, minSdk 30). The
Apple side wraps Apple's Network framework `nw_path_monitor`. The Android side
wraps `ConnectivityManager.NetworkCallback` registered against
`NET_CAPABILITY_INTERNET + NET_CAPABILITY_VALIDATED` — so captive portals
register as **not reachable**, which is what consumers actually want.

## Public API

```kotlin
public data class ReachabilityStatus(
    val reachable: Boolean,
    val transport: Transport,    // Wifi, Cellular, Ethernet, Other, None
    val metering: Metering,      // Unmetered, Metered, Constrained
)

public interface Reachability : AutoCloseable {
    public val status: StateFlow<ReachabilityStatus>
    override fun close()
}

// appleMain — iOS, iPadOS, macOS
public fun Reachability(): Reachability

// androidMain
public fun Reachability(context: Context): Reachability
```

`Metering.Constrained` corresponds to Apple's Low Data Mode and is **never
emitted on Android** — Android's connectivity model has no direct equivalent.
Treat it as a stricter form of `Metered` if you need a lowest-common-denominator
read.

## Usage

### From Kotlin (shared / Android)

```kotlin
val reachability: Reachability = Reachability(context) // or Reachability() on Apple

// Synchronous current state.
if (reachability.status.value.reachable) { /* ... */ }

// Live updates.
lifecycleScope.launch {
    reachability.status.collect { status ->
        updateBanner(status)
    }
}

// Tear down on app exit.
reachability.close()
```

### From SwiftUI (iOS / macOS)

SKIE bridges `StateFlow` as an `AsyncSequence`:

```swift
import Reachable

@MainActor
final class ConnectivityModel: ObservableObject {
    @Published var status: ReachabilityStatus = .unknown
    private let reachability = Reachability()

    func observe() async {
        for await s in reachability.status {
            self.status = s
        }
    }

    deinit { reachability.close() }
}
```

Both `Transport` and `Metering` arrive as native Swift enums — exhaustive
`switch` works without a `default` arm.

### From Jetpack Compose

```kotlin
@Composable
fun ConnectivityBanner(reachability: Reachability) {
    val status by reachability.status.collectAsStateWithLifecycle()
    if (!status.reachable) {
        Text("You're offline")
    }
}
```

## Construction

The factories are intentionally asymmetric: the Apple factory takes nothing,
the Android factory takes a `Context`. The library does not centralise
construction — your DI graph (Koin, Hilt, manual) wires the platform-specific
factory at the app entrypoint and binds the `Reachability` interface for
shared code to consume.

## Permissions

The library declares `android.permission.ACCESS_NETWORK_STATE` in its own
`AndroidManifest.xml`. The Android Manifest Merger pulls it into your app at
build time — you don't need to add it yourself, and there's no runtime grant
to handle (it's a normal-protection permission).

Apple platforms require no permission for `nw_path_monitor`. Network access
itself still goes through the usual Info.plist / entitlement model.

## Behaviour notes

- **Captive portals.** Android: detected via `NET_CAPABILITY_VALIDATED` —
  `reachable` is `false` until Android's connectivity probe confirms public
  internet. Apple: `nw_path_status_satisfied` defers to its own probing.
- **VPN.** Both platforms report the underlying transport (Wifi / Cellular /
  Ethernet) when a VPN is layered on top. The library doesn't currently
  expose a "via VPN" flag.
- **Wired Ethernet on macOS.** Kotlin/Native's `platform.Network` cinterop
  doesn't expose `nw_interface_type_wired_ethernet` at this Kotlin version,
  so wired Ethernet on macOS surfaces as `Transport.Other` (still reachable,
  just unlabelled). Android's `TRANSPORT_ETHERNET` is unaffected.
- **Threading.** Apple callbacks fire on a per-instance serial dispatch
  queue. Android callbacks fire on a binder thread. In both cases the
  callback body is just a `MutableStateFlow.value` write, which is
  thread-safe; collectors observe on whatever dispatcher they were started
  on.
- **Close semantics.** `close()` cancels the platform observer first, then
  the internal coroutine scope. After close, `status.value` continues to
  expose the last observed value but no further emissions happen. `close()`
  is idempotent.

## Testing this library locally

```bash
./gradlew :reachable:check                                   # ktlint + all unit tests
./gradlew :reachable:linkDebugFrameworkIosArm64              # iOS device slice
./gradlew :reachable:linkDebugFrameworkIosSimulatorArm64     # Apple Silicon simulator
./gradlew :reachable:linkDebugFrameworkMacosArm64            # macOS desktop slice
./gradlew :reachable:assembleReachableXCFramework            # SPM-consumable artifact
./gradlew :reachable:assemble                                # Android AAR
```

Manual end-to-end verification: build a tiny SwiftUI / Compose app, bind to
`reachability.status`, and toggle airplane mode / switch between Wi-Fi and
cellular / enable Low Data Mode (iOS Settings → Cellular → Cellular Data
Options). Transitions should land within ~1 second of the network change.
