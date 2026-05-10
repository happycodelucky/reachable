# Validated vs available

A common bug in homegrown reachability code is treating "the interface is
up" as "the device is on the internet." Reachable's `reachable` is the
stronger signal: the device can actually reach a public endpoint.

## The trap

On Android, `ConnectivityManager.NetworkCallback.onAvailable(network)`
fires the moment a network interface comes up. That includes:

- A captive-portal Wi-Fi where the auth page hasn't been completed.
- A Wi-Fi network whose DNS is a black hole.
- A cellular connection where the radio is technically attached to a tower
  but there's no signal.

In all three cases `onAvailable()` is `true`, but `https://example.com`
times out. Apps that key UI off bare `onAvailable()` show a misleading
"Online" indicator.

## What Reachable does instead

### Android

The library's `NetworkRequest` requires two capabilities:

```kotlin
NetworkRequest.Builder()
    .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    .addCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    .build()
```

- `NET_CAPABILITY_INTERNET` — the network claims to provide internet.
- `NET_CAPABILITY_VALIDATED` — Android's connectivity service has reached
  its probe endpoint (`connectivitycheck.gstatic.com` /
  `connectivitycheck.android.com`) over this network and got the expected
  response.

`reachable` is `true` only when both are present. Captive portals fail
`VALIDATED` until the user authenticates; DNS-blackholed networks fail
indefinitely.

```kotlin
override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
    val hasInternet  = capabilities.hasCapability(NET_CAPABILITY_INTERNET)
    val hasValidated = capabilities.hasCapability(NET_CAPABILITY_VALIDATED)
    val reachable = hasInternet && hasValidated
    // …emit ReachabilityStatus(reachable, transport, metering)
}
```

### Apple

`nw_path_monitor` does its own validation. The library checks:

```c
nw_path_get_status(path) == nw_path_status_satisfied
```

`nw_path_status_satisfied` is Apple's "this path can carry traffic to the
public internet" signal. Captive portals and other not-quite-reachable
states resolve to `unsatisfied` or `requires_connection` (the latter for
cellular networks waiting for the user to enable data).

The library does not add an HTTP probe on top of either platform. Both
platforms already probe internally; layering another probe slows the first
emission, drains battery, and produces two slightly disagreeing signals.

## Wired Ethernet on macOS — known limitation

Kotlin/Native's `platform.Network` cinterop does not currently expose
`nw_interface_type_wired_ethernet`. The constant exists in Apple's headers
(`<Network/nw_path.h>`) but the binding generator at this Kotlin version
doesn't surface it.

The library passes `ethernet = false` to the mapping helper on Apple, so
a wired Ethernet path falls through to `nw_interface_type_other` and
surfaces as `Transport.Other` — still reachable, just unlabelled. Android's
`TRANSPORT_ETHERNET` is unaffected.

Workarounds:

- Treat `Transport.Other` on macOS as "probably Ethernet" — by far the
  most likely case given the laptop and desktop usage profile.
- Layer a SwiftUI extension on top of the library's reading: consult
  `NWPathMonitor.currentPath` and call `usesInterfaceType(.wiredEthernet)`
  directly on macOS.

## Captive portals from a UX perspective

When the device is on a captive-portal Wi-Fi, `reachable` is `false`. Treat
that the same as offline: defer network calls, show an "Offline" or
"Connect to network" affordance.

Don't auto-launch the captive-portal sheet from your own code. Both Apple
and Android handle that flow at the OS level; second-guessing them
produces a worse experience than trusting the platform.

For a stronger signal ("online but captive portal in the way"), use a
platform-specific check: Android exposes `NET_CAPABILITY_CAPTIVE_PORTAL`,
Apple has equivalent flags. The library doesn't surface these. Open an
issue if you have a use case for them.
