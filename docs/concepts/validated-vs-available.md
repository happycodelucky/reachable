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
    // …emit ReachabilityStatus(isReachable, transport, isDataMetered)
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

## Wired Ethernet on Apple

Wired connections surface as `Transport.Ethernet` on Apple platforms via
`nw_interface_type_wired` — Apple's constant for wired interfaces in
`<Network/nw_interface.h>` (Swift spells it `.wiredEthernet`; the C and
Kotlin/Native name is `wired`). On macOS this covers built-in Ethernet and
USB/Thunderbolt adapters; on iOS / iPadOS it covers wired adapters and
docks. Android's `TRANSPORT_ETHERNET` maps to the same `Transport.Ethernet`
value, so the axis reads identically across platforms.

When a path uses several interfaces at once (VPN over Ethernet, for
example) the library reports the highest-priority transport:
`Wifi > Ethernet > Cellular > Other`.

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
