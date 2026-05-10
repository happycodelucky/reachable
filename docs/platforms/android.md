# Android

Reachable's Android implementation wraps `ConnectivityManager.NetworkCallback`
registered against a `NetworkRequest` that requires both
`NET_CAPABILITY_INTERNET` and `NET_CAPABILITY_VALIDATED`.

## Construction

```kotlin
import com.happycodelucky.reachable.Reachability

val reachability: Reachability = Reachability(applicationContext)
```

The construction:

1. Calls `context.applicationContext.getSystemService(ConnectivityManager::class.java)`
   — the implementation always upgrades to `applicationContext` to avoid
   leaking activity-scoped contexts.
2. Builds a `NetworkRequest` requiring `INTERNET + VALIDATED` (see
   [Validated vs available](../concepts/validated-vs-available.md) for why both).
3. **Eagerly seeds** `status.value` from
   `connectivityManager.activeNetwork` plus `getNetworkCapabilities(network)`.
   This means `status.value` is meaningful **immediately** after construction,
   before the first async callback fires — useful for `LaunchedEffect`-style
   checks on app start.
4. Calls `connectivityManager.registerNetworkCallback(request, callback)`.

## What gets read

`onCapabilitiesChanged(network, capabilities)` is the primary callback —
the library checks each capability and transport in turn:

| Reachable field        | NetworkCapabilities call                                                     |
|------------------------|------------------------------------------------------------------------------|
| `reachable`            | `hasCapability(NET_CAPABILITY_INTERNET) && hasCapability(NET_CAPABILITY_VALIDATED)` |
| `transport.Wifi`       | `hasTransport(TRANSPORT_WIFI)`                                               |
| `transport.Cellular`   | `hasTransport(TRANSPORT_CELLULAR)`                                           |
| `transport.Ethernet`   | `hasTransport(TRANSPORT_ETHERNET)`                                           |
| `metering.Unmetered`   | `hasCapability(NET_CAPABILITY_NOT_METERED)` or `hasCapability(NET_CAPABILITY_TEMPORARILY_NOT_METERED)` |
| `metering.Constrained` | **Never emitted on Android** — no equivalent capability                       |

`onLost(network)` synthesises a "no internet" emission because the
capability stream stops without a final terminator. If a different network
is up at the same time, the next `onCapabilitiesChanged` overwrites it
immediately.

## Permission

The library declares `android.permission.ACCESS_NETWORK_STATE` in its own
`AndroidManifest.xml`. The Android Manifest Merger pulls it into your app
at build time — you don't need to add it yourself.

It's a normal-protection permission, so no runtime grant is needed at any
API level.

```xml
<!-- declared in the library AAR; merges into your app -->
<uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
```

## Threading

`NetworkCallback` methods fire on a **binder thread** by default. The
library keeps the callback body to a single `MutableStateFlow.value` write,
which is concurrency-safe:

```kotlin
private val callback = object : ConnectivityManager.NetworkCallback() {
    override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
        emit(toStatus(capabilities))
    }
    override fun onLost(network: Network) {
        emit(ReachabilityStatus.Unknown)
    }
}
```

Collectors observe on whatever dispatcher they collect on. From Compose:

```kotlin
@Composable
fun ConnectivityBanner(reachability: Reachability) {
    val status by reachability.status.collectAsStateWithLifecycle()
    if (!status.reachable) Text("You're offline")
}
```

`collectAsStateWithLifecycle()` (from `androidx.lifecycle:lifecycle-runtime-compose`)
is the right primitive — it auto-pauses Flow collection when the activity
goes to STOPPED, preventing the StateFlow from doing useless work in the
background.

## Multi-process apps

Android apps with `android:process=":foo"` services run each process
isolated. Each process must construct its own `Reachability` —
`ConnectivityManager` registrations don't cross process boundaries.

This is rare in modern Compose-shaped apps but worth knowing if you have
a long-running service in a separate process.

## Min-SDK

`minSdk 30` (Android 11). Set in `gradle/libs.versions.toml`. The APIs the
library uses (`NetworkCallback`, `NetworkRequest`, `NET_CAPABILITY_VALIDATED`,
`getSystemService(Class)`) are all available on API 23+, so the floor is
much higher than what the implementation requires — it reflects the
project's broader baseline rather than an Android API constraint.

## ABI

`arm64-v8a` only. Set in `gradle/libs.versions.toml` and reflected in CI.
No `armeabi-v7a`, no `x86_64`, no `x86`. Per
[CLAUDE.md §1](https://github.com/happycodelucky/reachable/blob/main/CLAUDE.md):
ARM only, no exceptions.

## See also

- [Concepts → Validated vs available](../concepts/validated-vs-available.md) — why we require both `INTERNET + VALIDATED`.
- [Concepts → Lifecycle](../concepts/lifecycle.md) — eager-seed details, threading, idempotent close.
- [Recipes → Compose binding](../recipes/compose-binding.md) — full `collectAsStateWithLifecycle()` patterns.
