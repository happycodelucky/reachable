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
 */
package com.happycodelucky.reachable.internal

import com.happycodelucky.reachable.Reachability

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
 * **Lifecycle**: the instance is wrapped in [NonClosingReachability], so
 * `Reachability.shared.close()` is a no-op. See that class's KDoc for the
 * full rationale.
 */
internal object SharedReachabilityHolder {
    val instance: Reachability by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        NonClosingReachability(createSharedReachability())
    }
}
