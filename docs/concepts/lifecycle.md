# Lifecycle

`Reachability` is a long-lived handle that owns a platform observer and a
coroutine scope. Construct **once per process**, keep it alive for the
process lifetime (or for the scope you're observing on), and `close()` it
when that scope tears down.

## When to construct

| Where you are        | Where to construct                                                              |
|----------------------|---------------------------------------------------------------------------------|
| Android              | `Application.onCreate()` — bind to the application context.                     |
| iOS                  | App `init` (the `@main` `App` struct) or your composition root.                 |
| macOS                | Same as iOS — both Apple platforms share `appleMain`.                           |
| Test (`runTest`)     | Per-test, in a `try-with-resources`-style block. Don't share across tests.      |

The Apple-side `Reachability()` factory creates a per-instance serial
dispatch queue and starts an `nw_path_monitor`; the Android-side
`Reachability(context)` factory grabs `applicationContext`'s
`ConnectivityManager` and registers a `NetworkCallback`. Both are cheap (~µs
to construct) but **not free** — each instance is a small allocation plus a
platform observer registration. Constructing more than one per process is
wasteful, not wrong.

## When to close

`close()` cancels the platform observer (`nw_path_monitor_cancel` /
`unregisterNetworkCallback`) and cancels the internal coroutine scope.

| Where you are     | Where to close                                                                                  |
|-------------------|-------------------------------------------------------------------------------------------------|
| Android           | `Application.onTerminate()` — but note the OS rarely calls this; in practice the process dies first. |
| iOS / macOS       | A `deinit` on the owning view-model or composition root.                                        |
| Test              | At the end of the test — Turbine and `runTest` will leak the scope otherwise.                   |

`close()` is **idempotent** and **synchronous**. Multiple calls are no-ops.
After close, `status.value` continues to expose its last observed value, but
no further emissions happen. Collectors of `status` see the Flow complete on
the next dispatcher tick after the scope cancels.

## What threads things fire on

### Apple

The `nw_path_monitor` update handler fires on the per-instance serial
dispatch queue created at construction time
(`dispatch_queue_create("dev.reachable.monitor", null)`). The handler body
just calls `MutableStateFlow.value = ...`, which is concurrency-safe.
Collectors observe on whatever dispatcher they collect on.

Construction creates the queue and registers the observer eagerly. The
first emission lands on the queue typically within tens of milliseconds of
construction. Until then, `status.value` returns `ReachabilityStatus.Unknown`
from the base class.

### Android

`NetworkCallback` methods fire on a binder thread by default. The library
keeps the callback body to a single `MutableStateFlow.value` write, which
is thread-safe. Collectors observe on whatever dispatcher they collect on.

Construction also performs a **synchronous read** of
`connectivityManager.activeNetwork` plus `getNetworkCapabilities(network)`
and seeds `status.value` from that — so unlike the Apple side, `status.value`
is meaningful **immediately** after construction, before any callback fires.
This matters: a `LaunchedEffect`-style `if (reachability.status.value.reachable) ...`
check on app start works without racing.

## Multiple collectors

`StateFlow` semantics: multiple collectors share **one** underlying
platform observer. The library does not register a new
`nw_path_monitor` / `NetworkCallback` per collector. Collecting in 50
places is identical in cost to collecting in 1 place.

A late-joining collector immediately receives the most recent value
(StateFlow conflation), then every subsequent change. There is no replay-
buffer worth tuning; `StateFlow` is the right primitive here.

## Patterns to avoid

- **Constructing inside a Compose `@Composable` without `remember(...)`**.
  Every recomposition would create a new platform observer; the Android
  `NetworkCallback` registry would fill up. Always wrap in
  `remember(context) { Reachability(context) }`, or hoist into a view-model.

- **Constructing inside a SwiftUI `View`'s `body`**. Same problem — every
  view re-evaluation reconstructs. Hoist into an `@StateObject` view-model
  whose `init` calls the factory once.

- **Sharing a `Reachability` instance across processes** (Android multi-
  process apps with `android:process=...` per service). Each process needs
  its own `ConnectivityManager` registration; cross-process sharing
  doesn't work.

- **Forgetting to `close()` in test code**. `runTest` leaves dangling
  scopes alive between tests; the next test sees stale state. Always
  bracket: `val r = Reachability(...); try { ... } finally { r.close() }`.

## Cost summary

A construction allocates:

- Apple: one dispatch queue (~kB), one `nw_path_monitor_t` (kernel object,
  ~kB).
- Android: one `NetworkCallback` (~kB) plus a binder transaction to register
  it.

A `close()` releases both. The scope's `SupervisorJob` cancels any
in-flight collectors. The `MutableStateFlow` is GC-eligible after collectors
complete.
