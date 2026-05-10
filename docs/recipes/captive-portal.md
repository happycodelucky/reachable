# Captive portals

Reachable's `reachable` field already handles the captive-portal case
correctly: it's `false` for any network where the device hasn't actually
reached a public endpoint. This page covers what you should and shouldn't
build on top of that.

## Default behaviour: captive portals look offline

When the device is on a captive-portal Wi-Fi where the user hasn't
authenticated:

- **Android** — `NET_CAPABILITY_VALIDATED` is absent; `reachable = false`.
- **Apple** — `nw_path_get_status(path)` returns
  `nw_path_status_unsatisfied` or `nw_path_status_requires_connection`;
  `reachable = false`.

Treat that the same as offline:

```kotlin
when {
    !status.reachable -> showOfflineState()
    status.metering == Metering.Constrained -> showLowDataState()
    else -> showFullExperience()
}
```

This is the right default. **Don't** show "captive portal detected, please
authenticate" UI from your own app code — both Apple and Android handle
the auth flow at the OS level (the captive-portal sheet that pops up
automatically). Trying to second-guess that produces a worse experience
than trusting the platform.

## When the user authenticates

Once the user completes the captive portal auth, both platforms re-probe
and emit a fresh status with `reachable = true`. Your app sees a normal
state transition; no special handling needed.

```kotlin
reachability.status
    .map { it.reachable }
    .distinctUntilChanged()
    .collect { isReachable ->
        if (isReachable) syncQueue.flush()
    }
```

## Edge case: "online" but DNS is failing

If a network passes both `INTERNET` and `VALIDATED` checks but its DNS is
broken for *your* domain, Reachable will still report `reachable = true`.
That's correct — Reachable answers "can the device reach the public
internet?", not "can your app's specific endpoints resolve and respond?".

For an app-level "is my backend reachable?" check, layer your own probe
on top:

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

The Reachable status acts as a gate — your probe only fires when the OS
says the network is up, which avoids burning battery probing during
genuine offline periods.

## What we deliberately don't expose

- A `Captive` enum case on `Transport`. The OS handles captive-portal UX;
  surfacing "you're on a captive portal" in the library would invite apps
  to build worse versions of that flow.
- A `NET_CAPABILITY_CAPTIVE_PORTAL` reading on Android. If you have a
  specific use case for it, file an issue describing the use case. The
  default of "captive portal == not reachable" handles 99% of the value.
- An HTTP-level reachability probe. Both platforms do this internally;
  layering another probe on top would slow the first emission and
  duplicate work the OS is already doing.

## See also

- [Concepts → Validated vs available](../concepts/validated-vs-available.md) — full discussion of the `INTERNET + VALIDATED` requirement.
- [Recipes → React to changes](react-to-changes.md) — patterns for reacting to the `false → true` transition when auth completes.
