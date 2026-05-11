# Android

The Android implementation wraps `ConnectivityManager.NetworkCallback`
registered against a `NetworkRequest` that requires both
`NET_CAPABILITY_INTERNET` and `NET_CAPABILITY_VALIDATED`.

## Singleton entry point — `Reachability.shared`

The recommended way to access reachability from Android code:

```kotlin
import com.happycodelucky.reachable.Reachability

val reachability: Reachability = Reachability.shared
```

`Reachability.shared` returns a process-lifetime singleton. On Android,
the library's bundled `ReachabilityInitializer` (an
`androidx.startup.Initializer`) runs during the
`InitializationProvider` ContentProvider pass — before
`Application.onCreate` — and attaches the singleton to the application
`Context`. After attach, `status` emits live values.

`status.value` is `ReachabilityStatus.Unknown` between first access and
the initializer's first run. Collectors started before attach receive
`Unknown` first and then live values; `StateFlow` late-joiner semantics
make this race-free.

Calling `close()` on `Reachability.shared` is an intentional no-op —
the singleton's lifetime is the process.

### Manifest merger

The library's `AndroidManifest.xml` registers `ReachabilityInitializer`
under `InitializationProvider` with `tools:node="merge"`:

```xml
<provider
    android:name="androidx.startup.InitializationProvider"
    android:authorities="${applicationId}.androidx-startup"
    android:exported="false"
    tools:node="merge">
    <meta-data
        android:name="com.happycodelucky.reachable.ReachabilityInitializer"
        android:value="androidx.startup" />
</provider>
```

`tools:node="merge"` unions the `<meta-data>` children from all libraries
(WorkManager, Profile Installer, etc.) into a single
`InitializationProvider` entry. No extra manifest work is needed in
consumer apps.

### Disabled InitializationProvider

If your app removes `InitializationProvider` entirely
(`tools:node="remove"` on the provider), the auto-attach won't run and
`Reachability.shared.status` will remain `Unknown` indefinitely. In that
case, either re-enable startup and let this initializer run, or stop
using `Reachability.shared` and use the explicit-lifecycle factory
described below.

## Explicit-lifecycle factory

For tests, per-feature observers, or when `InitializationProvider` is
disabled:

```kotlin
import com.happycodelucky.reachable.Reachability

val reachability: Reachability = Reachability(applicationContext)
```

Construction:

1. Calls `context.applicationContext.getSystemService(ConnectivityManager::class.java)`.
   The implementation always upgrades to `applicationContext` to avoid
   leaking activity-scoped contexts.
2. Builds a `NetworkRequest` requiring `INTERNET + VALIDATED`. See
   [Validated vs available](../concepts/validated-vs-available.md) for
   why both.
3. Eagerly seeds `status.value` from `connectivityManager.activeNetwork`
   and `getNetworkCapabilities(network)`. `status.value` is therefore
   meaningful immediately after construction, before any async callback —
   useful for `LaunchedEffect`-style checks on app start.
4. Calls `connectivityManager.registerNetworkCallback(request, callback)`.

## What gets read

`onCapabilitiesChanged(network, capabilities)` is the primary callback.

| Reachable field        | NetworkCapabilities call                                                     |
|------------------------|------------------------------------------------------------------------------|
| `reachable`            | `hasCapability(NET_CAPABILITY_INTERNET) && hasCapability(NET_CAPABILITY_VALIDATED)` |
| `transport.Wifi`       | `hasTransport(TRANSPORT_WIFI)`                                               |
| `transport.Cellular`   | `hasTransport(TRANSPORT_CELLULAR)`                                           |
| `transport.Ethernet`   | `hasTransport(TRANSPORT_ETHERNET)`                                           |
| `metering.Unmetered`   | `hasCapability(NET_CAPABILITY_NOT_METERED)` or `hasCapability(NET_CAPABILITY_TEMPORARILY_NOT_METERED)` |
| `metering.Constrained` | never emitted on Android — no equivalent capability                          |

`onLost(network)` synthesises a "no internet" emission because the
capability stream stops without a final terminator. If a different network
is up at the same time, the next `onCapabilitiesChanged` overwrites it
immediately.

## Permission

`android.permission.ACCESS_NETWORK_STATE` is declared in the library's
`AndroidManifest.xml` and merged into your app at build time. It's a
normal-protection permission, so no runtime grant is needed at any API
level.

```xml
<!-- declared in the library AAR; merges into your app -->
<uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
```

## Threading

`NetworkCallback` methods fire on a binder thread by default. The library
keeps the callback body to a single `MutableStateFlow.value` write:

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

`collectAsStateWithLifecycle()` (from
`androidx.lifecycle:lifecycle-runtime-compose`) auto-pauses Flow collection
when the activity goes to STOPPED, so the StateFlow doesn't keep work
alive in the background.

## Multi-process apps

Android apps with `android:process=":foo"` services run each process
isolated. `ConnectivityManager` registrations don't cross process
boundaries.

`Reachability.shared` is attached per-process: the
`InitializationProvider` runs once in each process that has a
`ContentProvider` declaration. A separate `:foo` process gets its own
`shared` singleton, attached (if the process hosts a
`ContentProvider`) during that process's startup pass.

If the separate process does not host a `ContentProvider` and
`InitializationProvider` doesn't run there, use the explicit-lifecycle
factory `Reachability(context)` instead.

Rare in modern Compose-shaped apps, but worth knowing if you have a
long-running service in a separate process.

## Min-SDK

`minSdk 30` (Android 11), set in `gradle/libs.versions.toml`. The APIs the
library uses (`NetworkCallback`, `NetworkRequest`,
`NET_CAPABILITY_VALIDATED`, `getSystemService(Class)`) are available on
API 23+, so the floor is much higher than what the implementation
requires. It reflects the project's broader baseline.

## ABI

`arm64-v8a` only. Set in `gradle/libs.versions.toml` and reflected in CI.
No `armeabi-v7a`, no `x86_64`, no `x86`. Per
[CLAUDE.md §1](https://github.com/happycodelucky/reachable/blob/main/CLAUDE.md):
ARM only, no exceptions.

## See also

- [Concepts → Validated vs available](../concepts/validated-vs-available.md): why both `INTERNET` and `VALIDATED`.
- [Concepts → Lifecycle](../concepts/lifecycle.md): eager-seed details, threading, idempotent close.
- [Recipes → Compose binding](../recipes/compose-binding.md): full `collectAsStateWithLifecycle()` patterns.
