# Lifecycle

`Reachability` is a long-lived handle that owns a platform observer and a
coroutine scope. The library offers two lifecycle paths — singleton and
per-instance — with different construction and close contracts.

## Singleton path — `Reachability.shared`

`Reachability.shared` is a process-lifetime singleton. Use it for the
vast majority of app code: it requires no construction, no Context
plumbing, and no teardown.

### When to use it

Whenever you need reachability in a `@Composable`, a ViewModel, a
background service, or any code that doesn't have a natural "this scope
owns reachability" owner.

### Construction (Android)

On Android, the library's bundled `ReachabilityInitializer` (an
`androidx.startup.Initializer`) attaches the singleton to the application
`Context` during the `InitializationProvider` ContentProvider pass —
*before* `Application.onCreate`. This means:

- The singleton is fully functional by the time any `Activity`,
  `ContentProvider`, or `Application.onCreate` runs.
- Collectors started before attach receive `ReachabilityStatus.Unknown`
  first, then live values as soon as the platform reports them. `StateFlow`
  late-joiner semantics make this race-free.

### Construction (Apple)

On Apple (iOS, iPadOS, macOS), first access constructs an
`nw_path_monitor`-backed observer and starts it eagerly. There is no
Context dependency — the monitor is self-contained. Subsequent accesses
return the same instance.

### Close semantics

Calling `close()` on `Reachability.shared` is an intentional **no-op**.
The singleton's lifetime is the process; neither platform has a usable
"natural deinit" path for a long-lived singleton:

- On Android, `ConnectivityManager.registerNetworkCallback` causes the OS
  to hold a strong reference to the callback, rooting it back to the
  `Reachability` instance. The instance is unreclaimable until
  `unregisterNetworkCallback` runs — so a "natural deinit" doesn't exist.
  The kernel reaps the registration at process exit.
- On Apple, `nw_path_monitor_set_update_handler` retains the update
  handler, which captures the instance. ARC won't drop it until
  `nw_path_monitor_cancel`. Same kernel-reaps-at-exit story.

A misplaced `use { }` block, an `AutoCloseable`-aware DI container, or a
test fixture that closes everything in `@AfterEach` must not accidentally
freeze the singleton's `StateFlow` and break every other consumer in the
process. The no-op `close()` is the safeguard.

## Per-instance path — factories

For tests, per-feature observers, or any code that needs explicit
teardown:

```kotlin
// Android
val reachability: Reachability = Reachability(applicationContext)
```

```swift
// Apple
let reachability: any Reachability = Reachability()
```

### When to construct

| Where you are        | Where to construct                                                                                                   |
|----------------------|----------------------------------------------------------------------------------------------------------------------|
| Android              | `Application.onCreate()` — bind to the application context. Or just use `Reachability.shared`.                      |
| iOS                  | App `init` (the `@main` `App` struct) or your composition root. Or just use `Reachability.shared`.                  |
| macOS                | Same as iOS — both Apple platforms share `appleMain`.                                                                |
| Test (`runTest`)     | Per-test, in a `try-with-resources`-style block. Don't share across tests (use the factory, not `Reachability.shared`). |

The Apple factory creates a per-instance serial dispatch queue and starts
an `nw_path_monitor`. The Android factory grabs `applicationContext`'s
`ConnectivityManager` and registers a `NetworkCallback`. Both are cheap
(microseconds) but each instance is a small allocation plus a platform
observer registration.

### When to close

`close()` cancels the platform observer (`nw_path_monitor_cancel` on Apple,
`unregisterNetworkCallback` on Android) and cancels the internal coroutine
scope.

| Where you are     | Where to close                                                                                  |
|-------------------|-------------------------------------------------------------------------------------------------|
| Android           | `Application.onTerminate()`. The OS rarely calls it; in practice the process dies first.        |
| iOS / macOS       | `deinit` on the owning view-model or composition root.                                          |
| Test              | At the end of the test. `runTest` and Turbine leak the scope otherwise.                         |

`close()` is idempotent and synchronous; multiple calls are no-ops. After
close, `status.value` continues to expose its last observed value but never
emits again. Collectors of `status` see the Flow complete on the next
dispatcher tick after the scope cancels.

## What threads things fire on

### Apple

The `nw_path_monitor` update handler fires on the per-instance serial
dispatch queue created at construction time
(`dispatch_queue_create("dev.reachable.monitor", null)`). The handler body
is a single `MutableStateFlow.value` write, which is concurrency-safe.
Collectors observe on whatever dispatcher they collect on.

The first emission lands on the queue typically within tens of
milliseconds of construction. Until then, `status.value` returns
`ReachabilityStatus.Unknown`.

### Android

`NetworkCallback` methods fire on a binder thread. The handler body is a
single `MutableStateFlow.value` write. Collectors observe on whatever
dispatcher they collect on.

**Explicit-lifecycle factory** (`Reachability(context)`): construction
performs a synchronous read of `connectivityManager.activeNetwork` plus
`getNetworkCapabilities(network)` and seeds `status.value` from that — so
`status.value` is meaningful immediately after construction, before any
callback fires. A `LaunchedEffect`-style synchronous check on app start
works without racing.

**Singleton** (`Reachability.shared`): `status.value` starts as
`ReachabilityStatus.Unknown` and transitions to the live value after the
`ReachabilityInitializer` calls `attach()` during the ContentProvider
startup pass (before `Application.onCreate`). Collectors started before
that transition see `Unknown` first, then the live value. The transition
happens early enough that in practice the `Unknown` window is
sub-millisecond by the time any UI is drawn.

## Multiple collectors

Multiple collectors share one underlying platform observer; the library
does not register a new `nw_path_monitor` or `NetworkCallback` per
collector. Collecting in 50 places costs the same as collecting in one.

A late-joining collector immediately receives the most recent value and
then every subsequent change. There's no replay buffer to tune.

## Patterns to avoid

- **Constructing inside a Compose `@Composable` without `remember(...)`**.
  Every recomposition would create a new platform observer; the Android
  `NetworkCallback` registry would fill up. Use `Reachability.shared`
  (preferred), or wrap in `remember(context) { Reachability(context) }`,
  or hoist into a view-model.
- **Constructing inside a SwiftUI `View`'s `body`**. Same problem. Use
  `Reachability.shared` (preferred), or hoist into an `@StateObject`
  view-model whose `init` calls the factory once.
- **Calling `close()` on `Reachability.shared`**. It's a no-op by design —
  but if you see code that explicitly calls it, the intent was likely to
  use a per-instance factory. The no-op prevents accidents; don't rely on
  it as deliberate cleanup.
- **Sharing a `Reachability` instance across processes** (Android multi-
  process apps with `android:process=...` per service). Each process needs
  its own `ConnectivityManager` registration; cross-process sharing
  doesn't work. `Reachability.shared` is per-process — each process that
  hosts a `ContentProvider` gets its own auto-attached singleton.
- **Forgetting to `close()` in test code** when using per-instance
  factories. `runTest` leaves dangling scopes alive between tests; the next
  test sees stale state. Bracket with
  `val r = Reachability(...); try { ... } finally { r.close() }`. Tests
  that use `Reachability.shared` don't need to close it.

## Cost summary

A construction allocates:

- Apple: one dispatch queue (~kB), one `nw_path_monitor_t` (kernel object,
  ~kB).
- Android: one `NetworkCallback` (~kB) plus a binder transaction to register
  it.

A `close()` releases both. The `SupervisorJob` cancels any in-flight
collectors. The `MutableStateFlow` is GC-eligible after collectors complete.
