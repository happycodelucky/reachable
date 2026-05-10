# Captive portals

`reachable` already handles captive portals correctly — it's `false` for
any network where the device hasn't reached a public endpoint. This page
covers how to build on that, and what not to build on top of it.

## Default behaviour: captive portals look offline

When the device is on a captive-portal Wi-Fi where the user hasn't
authenticated:

- Android: `NET_CAPABILITY_VALIDATED` is absent, so `reachable = false`.
- Apple: `nw_path_get_status(path)` returns `nw_path_status_unsatisfied` or
  `nw_path_status_requires_connection`, so `reachable = false`.

Treat that the same as offline:

```kotlin
when {
    !status.reachable -> showOfflineState()
    status.metering == Metering.Constrained -> showLowDataState()
    else -> showFullExperience()
}
```

Don't show "captive portal detected, please authenticate" UI from your own
app. Both Apple and Android handle the auth flow at the OS level — the
captive-portal sheet pops up automatically. Second-guessing that produces
a worse experience than trusting the platform.

## When the user authenticates

After the user completes captive-portal auth, both platforms re-probe and
emit a fresh status with `reachable = true`. The app sees a normal state
transition; no special handling needed.

```kotlin
reachability.status
    .map { it.reachable }
    .distinctUntilChanged()
    .collect { isReachable ->
        if (isReachable) syncQueue.flush()
    }
```

## Edge case: "online" but DNS is failing

If a network passes `INTERNET + VALIDATED` but its DNS is broken for your
domain specifically, Reachable still reports `reachable = true`. That's
correct: Reachable answers "can the device reach the public internet?",
not "can your app's endpoints resolve and respond?".

For an app-level "is my backend reachable?" check, layer your own probe on
top:

```kotlin
class BackendReachability(
    private val reachability: Reachability,
    private val httpClient: HttpClient,
) {
    val available: Flow<Boolean> = reachability.status
        .map { it.reachable }
        .distinctUntilChanged()
        .map { osReachable ->
            if (!osReachable) false
            else runCatching { httpClient.get("https://api.example.com/healthz").status.isSuccess() }
                .getOrDefault(false)
        }
}
```

The OS-level status acts as a gate; the probe only fires when the network
is up, so it doesn't burn battery during genuine offline periods.

## What Reachable deliberately doesn't expose

- A `Captive` enum case on `Transport`. The OS handles captive-portal UX;
  surfacing "you're on a captive portal" in the library would invite apps
  to build worse versions of that flow.
- A `NET_CAPABILITY_CAPTIVE_PORTAL` reading on Android. The default of
  "captive portal == not reachable" handles the common case. Open an issue
  if you have a specific use case for the raw signal.
- An HTTP-level reachability probe. Both platforms probe internally;
  layering another probe slows the first emission and duplicates work the
  OS is already doing.

## See also

- [Concepts → Validated vs available](../concepts/validated-vs-available.md): the `INTERNET + VALIDATED` requirement.
- [Recipes → React to changes](react-to-changes.md): patterns for reacting to the `false → true` transition.
