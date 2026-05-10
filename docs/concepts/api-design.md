# API design

Reachable's public surface is two top-level factories, one interface, one
data class, two enums, and an `AutoCloseable.close()` method. This page
explains the shape: why each piece is what it is, what we deliberately left
off, and what tradeoffs the choices imply for Kotlin and Swift consumers.

## The whole surface, in one block

```kotlin
package com.happycodelucky.reachable

public enum class Transport { Wifi, Cellular, Ethernet, Other, None }

public enum class Metering { Unmetered, Metered, Constrained }

public data class ReachabilityStatus(
    val reachable: Boolean,
    val transport: Transport,
    val metering: Metering,
) {
    public companion object { public val Unknown: ReachabilityStatus }
}

public interface Reachability : AutoCloseable {
    public val status: StateFlow<ReachabilityStatus>
    override fun close()
}

// appleMain — iOS, iPadOS, macOS
public fun Reachability(): Reachability

// androidMain
public fun Reachability(context: Context): Reachability
```

That's the whole thing. Everything else — the platform observers, the
shared base class, the pure mapping helpers — is `internal`.

## One observable for both questions

The user's two questions ("what's the current state?" and "tell me when it
changes") collapse into one primitive: `StateFlow<ReachabilityStatus>`.

| Need                  | Call                              |
|-----------------------|-----------------------------------|
| Synchronous read      | `reachability.status.value`       |
| Reactive listener     | `reachability.status.collect { }` |
| One-shot suspend      | `reachability.status.first()`     |
| Swift `AsyncSequence` | `for await s in reachability.status` |

Adding a separate `suspend fun current(): ReachabilityStatus` would bloat
the API and force consumers to choose between two equivalent calls. We
don't.

## Composition over a sealed hierarchy

`ReachabilityStatus` is a `data class` with three independent fields. It's
not a `sealed interface ReachabilityStatus { Online; Offline; Constrained;
… }`. The reasoning:

- The three axes (reachable / transport / metering) are **orthogonal**.
  You can be online over cellular and metered simultaneously. A sealed
  hierarchy would either cross-product the cases (5 transports × 3
  meterings × reachable = 30 cases) or hide axes inside one case's payload,
  losing the exhaustiveness benefit.
- Enum-per-axis gives Swift consumers an exhaustive `switch` on each axis
  individually via SKIE — `switch status.transport { case .wifi: …; case
  .cellular: …; … }` — without forcing pattern matching on the whole status.
- `data class` gives free `equals` / `hashCode` / `copy()` / destructuring,
  none of which a sealed hierarchy provides automatically.

The cost is that the language can't catch "you forgot to check `reachable`
before reading `transport`" — but that's a domain rule, not a type rule, and
mapping enforces `transport == Transport.None` whenever `reachable` is
`false`.

## Metering.Constrained is Apple-only

`Metering` has three cases — `Unmetered`, `Metered`, `Constrained` — but
**Android never emits `Constrained`**. Apple's Network framework reports
"Low Data Mode active" via `nw_path_is_constrained(path)`; Android's
connectivity model has `NET_CAPABILITY_NOT_METERED` and
`NET_CAPABILITY_TEMPORARILY_NOT_METERED` but no first-class equivalent of
Low Data Mode.

The choice was either:

1. **Lowest common denominator** — drop `Constrained` from the enum, lose
   the Apple signal entirely, force consumers who care to write platform-
   specific code anyway.
2. **Best of both** — include `Constrained` as an Apple-only case; Android
   consumers see it as "never happens" and can ignore it; Apple consumers
   get the richer signal for free.

We picked (2). The cost: a Kotlin `when` over `metering` on a shared-code
path needs an `else -> /* Constrained: never on Android */` arm even if you
only care about Android, or you elide the `Constrained` case from your
Android-only branches and trust the documentation.

## Asymmetric factories

`Reachability()` (no args) on Apple, `Reachability(context)` on Android. The
factories are deliberately **not** wrapped in an `expect class
ReachabilityFactory` — that pattern just moves the asymmetry one layer
down (the factory still needs the Context from somewhere).

Per [CLAUDE.md §5](https://github.com/happycodelucky/reachable/blob/main/CLAUDE.md):
the library uses constructor injection. The consumer's app graph (Koin,
Hilt, hand-wired) calls the platform-specific factory at the entrypoint and
binds the resulting `Reachability` interface for shared / multiplatform code
to depend on.

```kotlin
// Common shared-module code — depends only on the interface.
class ConnectivityModel(private val reachability: Reachability)

// Android entrypoint
val r: Reachability = Reachability(application)
val model = ConnectivityModel(r)

// iOS entrypoint
let r: any Reachability = Reachability()
let model = ConnectivityModel(reachability: r)
```

## No `kotlin.Result<T>`, no `Pair`/`Triple` at the boundary

Per [CLAUDE.md §8](https://github.com/happycodelucky/reachable/blob/main/CLAUDE.md):
the public API never returns `kotlin.Result<T>` and never uses `Pair` /
`Triple` in public signatures. Both render as opaque wrappers in Swift
(`KotlinResult`, `KotlinPair`) with no exhaustive `switch` and no value-type
semantics. We use named `data class`es and project-defined `sealed
interface`s for outcomes instead.

Reachable's surface is small enough that no outcome type is needed yet.

## `AutoCloseable.close()`

Reachable owns a platform observer (the `nw_path_monitor` or the
`NetworkCallback`) and a `SupervisorJob`-rooted coroutine scope. Both have
to be torn down explicitly when the owning scope exits — there's no
finaliser path that's reliable across both Apple and Android.

`AutoCloseable.close()` is the universal idiom across Kotlin, Java, and
Swift, and SKIE renders it as `close()` in Swift without any special
mangling. The implementation is idempotent and synchronous; multiple `close()`
calls are no-ops.

See [Concepts → Lifecycle](lifecycle.md) for when to construct and when to
close.
