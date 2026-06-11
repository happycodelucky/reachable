# API design

The whole public surface:

```kotlin
package com.happycodelucky.reachable

public enum class Transport { Wifi, Cellular, Ethernet, Other, None }

public data class ReachabilityStatus(
    val isReachable: Boolean,
    val transport: Transport,
    val isDataMetered: Boolean,
) {
    public companion object { public val Unknown: ReachabilityStatus }
}

public interface Reachability : AutoCloseable {
    public val status: StateFlow<ReachabilityStatus>

    // Convenience shortcuts — synchronous reads off `status.value`.
    public val isReachable: Boolean
    public val isDataMetered: Boolean

    // Reactive shortcuts — derived StateFlows, conflated, started eagerly.
    public val reachable: StateFlow<Boolean>
    public val dataMetered: StateFlow<Boolean>

    override fun close()

    public companion object {
        // Process-lifetime singleton. close() is a no-op on this instance.
        public val shared: Reachability
    }
}

// appleMain — iOS, iPadOS, macOS
public fun Reachability(): Reachability

// androidMain
public fun Reachability(context: Context): Reachability
```

Everything else — platform observers, the shared base class, the mapping
helpers, the singleton holder, and the `NonClosingReachability` decorator —
is `internal`.

## One observable for both questions

| Need                  | Call                              |
|-----------------------|-----------------------------------|
| Synchronous read      | `reachability.status.value`       |
| Reactive listener     | `reachability.status.collect { }` |
| One-shot suspend      | `reachability.status.first()`     |
| Swift `AsyncSequence` | `for await s in reachability.status` |

A separate `suspend fun current()` would force consumers to choose between
two equivalent calls, so there isn't one.

### Single-axis shortcuts

| Need                          | Call                              |
|-------------------------------|-----------------------------------|
| Sync online check             | `reachability.isReachable`        |
| Sync metered check            | `reachability.isDataMetered`      |
| Reactive online/offline       | `reachability.reachable.collect { }` |
| Reactive metered/unmetered    | `reachability.dataMetered.collect { }` |

The reactive variants are dedicated `MutableStateFlow`s that the shared
base writes synchronously alongside `status` on every status update. Identical
consecutive values are conflated, so transport-only changes don't trigger
emissions on `reachable`, and reachability-only changes don't trigger
emissions on `dataMetered`.

`isDataMetered` is always `false` when the device is not reachable —
unreachable paths always report `isDataMetered = false`.

## Composition over a sealed hierarchy

`ReachabilityStatus` is a `data class`, not a sealed interface with cases
like `Online`, `Offline`, `Metered`, etc.

The three axes — reachable, transport, data metered — are orthogonal. A
device can be online over cellular and metered at the same time. A sealed
hierarchy would either cross-product the cases (5 transports × reachable ×
metered = many cases) or stuff axes inside one case's payload, defeating
exhaustiveness.

Enum-per-axis still gives Swift consumers exhaustive `switch` over transport
individually: `switch status.transport { case .wifi: …; case
.cellular: …; … }`. `data class` brings `equals`, `hashCode`, `copy()`, and
destructuring for free.

The cost: the type system can't catch "you forgot to check `isReachable`
before reading `transport`". The mapping enforces `transport ==
Transport.None` whenever `isReachable` is `false`, and enforces
`isDataMetered == false` whenever `isReachable` is `false`, so this is
rarely a real hazard.

## Data metering across platforms

`isDataMetered` is `true` when the active path is metered — cellular,
hotspot, or (on Apple) Low Data Mode active. On Apple, both
`nw_path_is_expensive` and `nw_path_is_constrained` set this flag; the
distinction between "expensive" and "constrained" is not surfaced in the
public API because no consumer use case required it beyond what
`transport == Transport.Cellular` already conveys. On Android,
`NET_CAPABILITY_NOT_METERED` and `NET_CAPABILITY_TEMPORARILY_NOT_METERED`
drive the flag.

## Singleton vs explicit lifecycle

`Reachability.shared` is the recommended entry point for application code.
The design goal is eliminating "did I construct Reachability early enough?"
ordering bugs that arise when a module tries to read reachability before the
entrypoint has had a chance to run the factory.

```kotlin
// Android, iOS, macOS — one call, callable from anywhere.
val reachability: Reachability = Reachability.shared
```

From Swift: `Reachability.shared` (via a Swift extension compiled into the
framework; no companion prefix needed).

**Why we use a singleton, not a mandatory factory.** On Android, the
`Context` requirement means that anything consuming reachability via
constructor injection has a transitive dependency on Android lifecycle
ordering. In multi-module apps, that ordering is fragile — a module
initialised during `ContentProvider` startup can't safely call
`Reachability(context)` if the DI graph hasn't wired it yet. The
singleton is attached during the `InitializationProvider` ContentProvider
pass (earlier than any `Application.onCreate`), so the `Unknown → live`
transition happens before any consumer can observe it.

**`StateFlow` makes the `Unknown` seed safe.** The singleton's `status`
starts at `ReachabilityStatus.Unknown`. A collector that starts before the
Android attach will see `Unknown` first, then the live value. A collector
that starts after will immediately see the most-recent live value. No race,
no special-casing needed.

**The singleton doesn't preclude injection.** Shared code can still take a
`Reachability` interface parameter and be tested with a mock; tests just
pass a freshly-constructed per-instance factory instead of the singleton.

```kotlin
// Test: inject a fresh instance, close it afterwards.
val r = Reachability(context)
try {
    assertEquals(true, r.isReachable)
} finally {
    r.close()
}
```

## Asymmetric factories

`Reachability()` on Apple, `Reachability(context)` on Android. There's no
`expect class ReachabilityFactory` to paper over the asymmetry — wrapping
it that way only moves the `Context` requirement one layer down.

The consumer's DI container (Koin, Hilt, hand-wired) calls the platform-
specific factory at the entrypoint and binds the `Reachability` interface
for shared code:

```kotlin
// Common shared-module code: depends on the interface only.
class ConnectivityModel(private val reachability: Reachability)

// Android entrypoint (explicit lifecycle)
val r: Reachability = Reachability(application)
val model = ConnectivityModel(r)

// iOS entrypoint (explicit lifecycle)
let r: any Reachability = Reachability()
let model = ConnectivityModel(reachability: r)
```

## No `kotlin.Result<T>`, no `Pair`/`Triple` at the boundary

The public API never returns `kotlin.Result<T>` and never uses `Pair` or
`Triple`. Both render as opaque wrappers in Swift (`KotlinResult`,
`KotlinPair`) with no exhaustive `switch` and no value-type semantics.
Named `data class`es and project-defined `sealed interface`s are the
substitute. Reachable's current surface needs neither.

## `AutoCloseable.close()`

Per-instance handles (built via `Reachability(context)` / `Reachability()`)
own a platform observer (`nw_path_monitor` or `NetworkCallback`) and a
`SupervisorJob`-rooted coroutine scope. Neither has a reliable finaliser
path on both Apple and Android, so cleanup is explicit.

`AutoCloseable.close()` is the universal idiom across Kotlin, Java, and
Swift; it surfaces in Swift as `close()` without any name mangling. The
implementation is idempotent and synchronous.

`Reachability.shared` is an exception: `close()` is an intentional no-op
on the singleton. The singleton's lifetime is the process; there is no
scope owner that will naturally go out of scope and trigger teardown. See
[Concepts → Lifecycle](lifecycle.md#singleton-path--reachabilityshared) for
the full rationale.

See [Concepts → Lifecycle](lifecycle.md) for when to construct and close.
