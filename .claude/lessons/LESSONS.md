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
- Allocate the next sequential ID for that section (`B-002`, `D-004`, …). IDs are stable forever — never renumber.
- One to three lines per entry. Cite a file path or commit/PR when useful. No long prose. If you need more than three lines, you're explaining what, not why.
- Code comments may reference an entry by ID (e.g. `// see B-001`).
- If an entry becomes obsolete, mark it `~~B-NNN~~ (superseded by B-NNN)` — do not delete. History matters.

Date format: `YYYY-MM-DD`. Always absolute, never relative ("last week").

For build/toolchain work specifically, [`toolchain-audit.md`](toolchain-audit.md)
is a repeatable audit procedure — the recurring ways build tooling silently
stops working, each with a detection command, plus registry queries for checking
versions without trusting search.

---

## Bugs we've hit (B)

### B-001 — Renovate's SKIE-bound Kotlin guard was silently dead (2026-07-30)
`matchPackagePrefixes` was removed in Renovate **v38**, so the rule enforcing N-006 survived only via silent config migration. It also over-matched: `org.jetbrains.kotlin` prefix-matches `org.jetbrains.kotlinx`, so it was disabling coroutines/atomicfu updates too — and v38+ auto-migration to `org.jetbrains.kotlin{/,}**` preserves that. Fixed with an anchored regex (`/^org\.jetbrains\.kotlin([.:]|$)/`). Validate config against a **current** Renovate major; older validators still accept the removed key.

---

## Novel design decisions (D)

### D-001 — Sealed result types over `kotlin.Result<T>` at the Swift boundary
`kotlin.Result<T>` doesn't bridge to Swift — SKIE has no special mapping, and K/N erases the payload. Use a project-defined `sealed interface`; SKIE renders it as an exhaustive Swift enum via `onEnum(of:)`. Same rule applies to `Pair<A,B>` / `Triple<…>` at public boundaries.

### D-002 — Apple platform-name casing rule overrides Kotlin acronym convention
`iOS`, `macOS`, `tvOS`, `watchOS` are brand names; they stay cased as Apple spells them in all identifiers, file names, and comments we author. JetBrains-supplied identifiers (`iosArm64`, `iosMain`, etc.) are the allowed exception.

### D-003 — `expect`/`actual` cap ~20 lines, otherwise refactor to interface
If an `actual` implementation grows past ~20 lines, refactor to a `commonMain` interface and inject platform implementations at the entrypoint. Keeps the `expect`/`actual` surface minimal and testable.

---

## NEVER DO (N)

### N-001 — Never include `CancellationException` in `@Throws` on SKIE-bridged APIs
SKIE bridges `suspend fun` as Swift `async throws` and routes cancellation through Swift's `Task.cancel()` / `CancellationError`. Adding `CancellationException::class` pollutes the generated signature and forces callers to write a meaningless `catch is CancellationError` arm.

### N-002 — Never call `suspend` functions inside a `kotlinx.atomicfu.locks.synchronized` block
`synchronized` on K/N is a non-reentrant spin-wait; suspending inside it can deadlock the coroutine dispatcher. If the body needs to suspend, use a `Mutex` instead.

### N-003 — Never use `kotlin.synchronized`, `@Synchronized`, `java.util.concurrent.locks.*`, or `volatile`
None are portable to K/N or wasm. Use `kotlinx.atomicfu.locks.synchronized` (non-suspending) or `kotlinx.coroutines.sync.Mutex` (suspending).

### N-004 — Never use `kotlin.Result<T>` / `Pair<…>` / `Triple<…>` in public Swift-facing signatures
See D-001. Use a named `sealed interface` instead.

### N-005 — Never add CocoaPods, Compose Multiplatform, x86/x86_64, watchOS, or tvOS
See CLAUDE.md §13 hard rules.

### N-006 — Never bump Kotlin past SKIE's supported range
SKIE lags Kotlin by a few days after each release. If SKIE doesn't yet support the new Kotlin version, wait — don't force-bump.

### N-007 — Never read wall-clock time inside dispatcher / scheduler logic
Direct `Clock.System.now()` reads make test virtual time lie. Inject the clock or compute delays from injected timestamps.

### N-008 — Never call ObjC/Foundation APIs while holding a Kotlin-side lock
ObjC/Foundation can invoke callbacks re-entrantly on the same thread; acquiring a Kotlin lock before crossing the ObjC boundary risks deadlock.

---

## Troubleshooting (T)

### T-001 — Tests pass but production timing behaviour drifts
Root cause is almost always a wall-clock read inside production logic (see N-007). Verify that all time sources are injected and that tests drive virtual time via `runTest`.
