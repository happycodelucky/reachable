# Lessons Learned — Reachable

Living document. Agents and humans add entries here whenever something is worth
remembering across sessions. Read it before planning non-trivial work and
whenever you get stuck — we may have seen the issue before.

## How to use this file

- **Before planning** a non-trivial change: skim all four sections, then grep for keywords from the task (e.g. `mutex`, `reachability`, `XCFramework`, `SKIE`, `NWPathMonitor`).
- **When stuck** for more than a few minutes: search here before going wider.
- **Add an entry as soon as you learn something** — don't batch. A terse line written now beats a polished paragraph never written.

## How to add an entry

- Pick the right section. If it fits two, pick the one a future reader would search first.
- Allocate the next sequential ID for that section (`B-007`, `D-003`, …). IDs are stable forever — never renumber.
- One to three lines per entry. Cite a file path or commit/PR when useful. No long prose. If you need more than three lines, you're explaining what, not why.
- Code comments may reference an entry by ID (e.g. `// see B-004`).
- If an entry becomes obsolete, mark it `~~B-NNN~~ (superseded by B-NNN)` — do not delete. History matters.

Date format: `YYYY-MM-DD`. Always absolute, never relative ("last week").

---

## Bugs we've hit (B)

### B-001 — `EphemeralRegistry` read-modify-write race — (ported from backgrounder)
**Cause:** `add { snapshot().toMutableSet().add(item); write(...) }` without a lock; `NSUserDefaults` / `SharedPreferences` are write-atomic, not RMW-atomic.
**Fix:** Wrap every RMW in `kotlinx.atomicfu.locks.synchronized` against a `SynchronizedObject`. Single-flag state → `kotlinx.atomicfu.atomic`.

### B-002 — `BGTask` double-completion / wrong-outcome race — (ported from backgrounder)
**Cause:** Multiple paths (success handler + iOS expiration handler) could both call `setTaskCompletedWithSuccess(_:)`. Race → Apple "BGTask completed twice" assertion or wrong outcome reported.
**Fix:** Per-invocation single-fire atomicfu latch; every completion site is `guard.runOnce { ... }`.

### B-003 — `BGTaskScheduler.submitTaskRequest(error = null)` is a hard crash — (ported from backgrounder)
**Cause:** Passing `null` for the `NSError**` out-pointer raises `NSException`; K/N `try/catch` does NOT catch ObjC exceptions.
**Fix:** Always allocate a real `ObjCObjectVar<NSError?>` via `memScoped` and surface failures as a typed result. Never pass `null` to ObjC out-error parameters.

### B-004 — `WorkerRegistry.create()` calls user factory inside a lock — (ported from backgrounder)
**Cause:** Running the factory while holding the registry lock serialises all concurrent dispatches behind arbitrary user code (slow factory stalls everything); a factory that blocks on another thread needing the lock deadlocks.
**Fix:** Capture the factory reference inside the lock, release the lock, then invoke the factory outside it.

### B-005 — `WorkerRegistry.seal()` wrote the sealed flag outside the registry lock — (ported from backgrounder)
**Cause:** `register()` checks `sealed` inside `synchronized(lock)` but `seal()` wrote it lock-free — a register racing `start()` could slip in after the engine began.
**Fix:** `seal()` takes the same lock. One line.

---

## Novel design decisions (D)

### D-001 — Sealed result types over `kotlin.Result<T>` at the Swift boundary — (ported from backgrounder)
`kotlin.Result<T>` doesn't bridge to Swift — SKIE has no special mapping, and K/N erases the payload. Use a project-defined `sealed interface`; SKIE renders it as an exhaustive Swift enum via `onEnum(of:)`. Same rule applies to `Pair<A,B>` / `Triple<…>` at public boundaries.

### D-002 — Apple platform-name casing rule overrides Kotlin acronym convention — (ported from backgrounder)
`iOS`, `macOS`, `tvOS`, `watchOS` are brand names; they stay cased as Apple spells them in all identifiers, file names, and comments we author. JetBrains-supplied identifiers (`iosArm64`, `iosMain`, etc.) are the allowed exception.

### D-003 — `expect`/`actual` cap ~20 lines, otherwise refactor to interface — (ported from backgrounder)
If an `actual` implementation grows past ~20 lines, refactor to a `commonMain` interface and inject platform implementations at the entrypoint. Keeps the `expect`/`actual` surface minimal and testable.

### D-004 — `MonitorEventEmitter` is `tryEmit`-only — (ported from backgrounder)
`SharedFlow` event emitters use `tryEmit` (non-suspending, buffer-or-drop) rather than `emit` (suspending). Keeps the emitter usable from non-suspending callbacks and avoids backpressure deadlocks.

---

## NEVER DO (N)

### N-001 — Never include `CancellationException` in `@Throws` on SKIE-bridged APIs — (ported from backgrounder)
SKIE bridges `suspend fun` as Swift `async throws` and routes cancellation through Swift's `Task.cancel()` / `CancellationError`. Adding `CancellationException::class` pollutes the generated signature and forces callers to write a meaningless `catch is CancellationError` arm.

### N-002 — Never call `suspend` functions inside a `kotlinx.atomicfu.locks.synchronized` block — (ported from backgrounder)
`synchronized` on K/N is a non-reentrant spin-wait; suspending inside it can deadlock the coroutine dispatcher. If the body needs to suspend, use a `Mutex` instead.

### N-003 — Never use `kotlin.synchronized`, `@Synchronized`, `java.util.concurrent.locks.*`, or `volatile` — (ported from backgrounder)
None are portable to K/N or wasm. Use `kotlinx.atomicfu.locks.synchronized` (non-suspending) or `kotlinx.coroutines.sync.Mutex` (suspending).

### N-004 — Never use `kotlin.Result<T>` / `Pair<…>` / `Triple<…>` in public Swift-facing signatures — (ported from backgrounder)
See D-001. Use a named `sealed interface` instead.

### N-005 — Never add CocoaPods, Compose Multiplatform, x86/x86_64, watchOS, or tvOS — (ported from backgrounder)
See CLAUDE.md §13 hard rules.

### N-006 — Never bump Kotlin past SKIE's supported range — (ported from backgrounder)
SKIE lags Kotlin by a few days after each release. If SKIE doesn't yet support the new Kotlin version, wait — don't force-bump.

### N-007 — Never read wall-clock time inside dispatcher / scheduler logic — (ported from backgrounder)
Direct `Clock.System.now()` reads make test virtual time lie. Inject the clock or compute delays from injected timestamps.

### N-008 — Never call ObjC/Foundation scheduling APIs while holding a Kotlin-side lock — (ported from backgrounder)
ObjC/Foundation can invoke callbacks re-entrantly on the same thread; acquiring a Kotlin lock before crossing the ObjC boundary risks deadlock.

---

## Troubleshooting (T)

### T-001 — Dispatcher tests pass but production behaviour drifts — (ported from backgrounder)
Root cause is almost always a wall-clock read inside the dispatcher (see N-007). Verify that all time sources are injected and that tests drive virtual time via `runTest`.

### T-002 — `BGTask` registered but never fires in the simulator — (ported from backgrounder)
`BGTaskScheduler` requires the task identifier to be declared in `Info.plist` under `BGTaskSchedulerPermittedIdentifiers`. Missing entry → task silently never fires; no error at registration time.
