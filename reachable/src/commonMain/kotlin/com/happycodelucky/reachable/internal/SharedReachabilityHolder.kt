/*
 * Reachable — lazy process-lifetime singleton holder.
 *
 * The single seam between the common public API (`Reachability.shared`)
 * and the per-platform construction. The holder owns a `by lazy { … }`
 * cell that calls the platform `actual` factory the first time
 * `Reachability.shared` is read.
 *
 * Why this layout (CLAUDE.md §4):
 *
 *  - The `expect` surface is one function. Apple's `actual` is one line
 *    (`AppleReachability()`); Android's is one line (`AndroidReachability()`).
 *    Both well under the §4 "refactor to interface if it grows past
 *    ~20 lines" threshold.
 *  - The singleton wrapping into [NonClosingReachability] happens here,
 *    once, in common code — neither platform `actual` needs to know about
 *    the decorator.
 *  - The companion accessor `Reachability.shared` reads
 *    `SharedReachabilityHolder.instance` directly, so the laziness is
 *    transparent to callers.
 *  - A second, lower-priority slot — [override] — exists for the
 *    `:reachable-testing` module's `installForTesting` hook (see
 *    `Reachability.installForTesting`). The override is an overlay over
 *    the lazy cell, not a replacement: clearing the override returns
 *    `instance` to the same lazy-initialised production singleton it
 *    would otherwise have been.
 */
@file:OptIn(TestingOnly::class)

package com.happycodelucky.reachable.internal

import com.happycodelucky.reachable.Reachability
import com.happycodelucky.reachable.TestingOnly
import com.happycodelucky.reachable.TestingOverrideHandle
import kotlinx.atomicfu.atomic

/**
 * Platform factory for the singleton's underlying instance. On Apple this
 * constructs an `AppleReachability` (begins observing eagerly). On Android
 * this constructs an `AndroidReachability` with no Context — the bundled
 * `ReachabilityInitializer` (or, for consumers who disable
 * `androidx.startup`'s `InitializationProvider`, no one) calls `attach`
 * later.
 */
internal expect fun createSharedReachability(): Reachability

/**
 * Process-lifetime singleton holder for [Reachability.shared].
 *
 * **Threading**: `by lazy(LazyThreadSafetyMode.SYNCHRONIZED)` is the right
 * tool. Per CLAUDE.md §3, *our call sites* of `kotlin.synchronized { … }`,
 * `@Synchronized`, `volatile`, and `java.util.concurrent.locks.*` are
 * banned because they're not portable to K/N. The stdlib's [lazy] delegate
 * is portable — it has K/N implementations that use atomics under the
 * hood. Using `by lazy` here is not a §3 violation. Do not "fix" this
 * by replacing it with an atomic reference + manual double-checked
 * locking: the stdlib already does that, correctly, and with less code.
 *
 * **Lifecycle**: the production instance is wrapped in
 * [NonClosingReachability], so `Reachability.shared.close()` is a no-op.
 * See that class's KDoc for the full rationale.
 *
 * **Test override**: [override] is a separate, atomically-mutable slot
 * that takes precedence over [lazyInstance] when set. It is mutated only
 * via [installForTesting], which is itself gated behind the
 * `@TestingOnly` opt-in marker on the public companion entry point. The
 * lazy cell is never invalidated by an override — clearing the override
 * exposes the same singleton instance that production would have seen
 * without any tests running.
 */
internal object SharedReachabilityHolder {
    /**
     * Production singleton. Lazy because we want construction to happen
     * on first observation, not on class load (Android's `ContentProvider`
     * pass for `androidx.startup` should be the typical trigger; on Apple
     * any `Reachability.shared` read does it).
     */
    private val lazyInstance: Reachability by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        NonClosingReachability(createSharedReachability())
    }

    /**
     * Test-only override. `kotlinx.atomicfu.atomic` so writes are CAS on
     * K/N without `volatile` (CLAUDE.md §3 / §13). Reads are a single
     * volatile load on JVM, an atomic load on K/N.
     */
    private val override = atomic<Reachability?>(null)

    /**
     * Effective value of [Reachability.shared]. Read on every access — there
     * is no caching of the override choice, so an in-flight test's install /
     * uninstall is observed by the *next* read of `Reachability.shared`
     * from the code under test.
     */
    val instance: Reachability
        get() = override.value ?: lazyInstance

    /**
     * Swap the override slot atomically and return a handle that restores
     * the previous value. The handle's `uninstall()` is what
     * `withFakeReachability { … }` in `:reachable-testing` calls in its
     * `finally`.
     */
    @TestingOnly
    internal fun installForTesting(next: Reachability?): TestingOverrideHandle {
        val previous = override.getAndSet(next)
        return TestingOverrideHandle(previous)
    }
}
