# Validated vs available

The most common bug in homegrown reachability code is treating "the
interface is up" as "the device is on the internet." Reachable bakes the
distinction in once, in both platform implementations, so `reachable`
means **the device can actually reach a public endpoint**.

## The trap

On Android, `ConnectivityManager.NetworkCallback.onAvailable(network)`
fires the moment a network interface comes up. That's:

- A captive-portal Wi-Fi where the auth page hasn't been completed.
- A Wi-Fi network whose DNS is a black hole.
- A cellular connection in a building with no signal but the radio still
  technically attached to a tower.

In all three cases `onAvailable()` is `true`, but `https://example.com`
will time out. Apps that key UI off bare `onAvailable()` show a misleading
"Online" indicator.

## What we do instead

### Android

The library's `NetworkRequest` requires **two** capabilities, not one:

```kotlin
NetworkRequest.Builder()
    .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    .addCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    .build()
```

- `NET_CAPABILITY_INTERNET` — the network *claims* to provide internet.
- `NET_CAPABILITY_VALIDATED` — Android's connectivity service has actually
  reached its probe endpoint (Google's `connectivitycheck.gstatic.com` /
  `connectivitycheck.android.com`) over this network and got the expected
  response.

`reachable` is `true` only when both are present. Captive portals fail
`VALIDATED` until the user authenticates; DNS-blackholed networks fail
indefinitely. The result is a signal you can actually trust.

```kotlin
override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
    val hasInternet  = capabilities.hasCapability(NET_CAPABILITY_INTERNET)
    val hasValidated = capabilities.hasCapability(NET_CAPABILITY_VALIDATED)
    val reachable = hasInternet && hasValidated
    // …emit ReachabilityStatus(reachable, transport, metering)
}
```

### Apple

`nw_path_monitor` does its own validation internally. The library checks:

```c
nw_path_get_status(path) == nw_path_status_satisfied
```

`nw_path_status_satisfied` is Apple's "yes, this path can carry traffic to
the public internet" signal. Captive portals and other not-quite-reachable
states resolve to `unsatisfied` or `requires_connection` (typically the
latter for cellular networks waiting for the user to enable data).

The library does **not** add an extra HTTP probe on top of either platform.
Both platforms already do this internally; piling another probe on top
would slow the first emission, drain battery, and give two slightly
disagreeing signals.

## Wired Ethernet on macOS — known limitation

Kotlin/Native's `platform.Network` cinterop currently does not expose
`nw_interface_type_wired_ethernet`. The constant exists in Apple's headers
(`<Network/nw_path.h>`) but the binding generator at this Kotlin version
doesn't surface it.

The library passes `ethernet = false` to the mapping helper on Apple, so a
wired Ethernet path falls through to `nw_interface_type_other` and surfaces
as `Transport.Other` — still **reachable**, just unlabelled. Android's
`TRANSPORT_ETHERNET` is unaffected.

Tracking this for fix when the cinterop catches up. If it bites you, two
workarounds:

- Treat `Transport.Other` on macOS as "probably Ethernet" — by far the
  most likely case, given the laptop+desktop usage profile.
- Use a SwiftUI extension that consults `NWPathMonitor.currentPath` to
  read `usesInterfaceType(.wiredEthernet)` directly when running on macOS,
  layered on top of the library's reading.

## Captive portals from a UX perspective

When the device is on a captive-portal Wi-Fi, `reachable` is `false`. Your
app should treat that the same as offline: defer network calls, show an
"Offline" or "Connect to network" affordance. **Do not auto-launch the
captive portal sheet** — both Apple and Android handle that flow at the OS
level, and trying to second-guess them produces a worse experience than
trusting the platform.

If you want a stronger signal — e.g. "online but captive portal in the
way" — you'd need a separate platform-specific check (Android exposes
`NET_CAPABILITY_CAPTIVE_PORTAL`, Apple has equivalent flags). The library
doesn't surface those today; if you need them, file an issue describing
the use case.
