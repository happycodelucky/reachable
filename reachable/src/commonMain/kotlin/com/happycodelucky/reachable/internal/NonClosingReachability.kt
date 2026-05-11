/*
 * Reachable — `Reachability` decorator that suppresses `close()`.
 *
 * Wraps the process-lifetime singleton instance returned from
 * `Reachability.shared` so accidental `close()` calls don't tear down
 * an observer the rest of the process still depends on. Every other
 * member of the [Reachability] interface delegates straight through.
 */
package com.happycodelucky.reachable.internal

import com.happycodelucky.reachable.Reachability
import com.happycodelucky.reachable.ReachabilityStatus
import kotlinx.coroutines.flow.StateFlow

/**
 * Decorator over a [Reachability] that no-ops `close()` and delegates
 * everything else. Used exclusively by [SharedReachabilityHolder] to wrap
 * the singleton instance returned from `Reachability.shared`.
 *
 * **Why no-op close on the singleton.** The singleton's lifetime is the
 * process, on both supported platforms:
 *
 *  - On Android, `ConnectivityManager.registerNetworkCallback` causes the
 *    OS to hold a strong reference to the callback (which roots back to
 *    the `Reachability` instance). The instance is unreclaimable by the
 *    GC until `unregisterNetworkCallback` runs — so a "natural" deinit
 *    path doesn't exist. The kernel reaps the registration at process
 *    exit.
 *  - On Apple, `nw_path_monitor_set_update_handler` retains the update
 *    handler, which captures `this`. Same retain-cycle shape. ARC won't
 *    drop the instance until `nw_path_monitor_cancel`. The kernel tears
 *    down the monitor at process exit.
 *
 * In both cases there is no point at which the singleton's [close] could
 * be called *correctly* — it has no scope-owner going out of scope. A
 * misplaced `use { }` block, an `AutoCloseable`-aware DI container, or a
 * test fixture that closes everything in `@AfterEach` would all silently
 * freeze the singleton's [StateFlow] and break every other consumer in
 * the process. Swallowing the call is the least-surprise behaviour for
 * the singleton specifically.
 *
 * Non-singleton [Reachability] instances (built via the
 * `Reachability(context)` / `Reachability()` factories) keep the normal
 * idempotent-close contract — they're the right tool for tests and any
 * call site that wants explicit teardown.
 */
internal class NonClosingReachability(
    private val delegate: Reachability,
) : Reachability {
    override val status: StateFlow<ReachabilityStatus>
        get() = delegate.status

    override val isReachable: Boolean
        get() = delegate.isReachable

    override val isLowDataMode: Boolean
        get() = delegate.isLowDataMode

    override val reachable: StateFlow<Boolean>
        get() = delegate.reachable

    override val lowDataMode: StateFlow<Boolean>
        get() = delegate.lowDataMode

    /**
     * Intentional no-op — see the class KDoc for the lifecycle rationale.
     * Calling this on the `Reachability.shared` instance must not freeze
     * the singleton.
     */
    override fun close() {
        // No-op by design.
    }

    /**
     * Internal accessor for platform code that needs the underlying
     * concrete instance (e.g. Android's `ReachabilityInitializer` needs to
     * downcast to `AndroidReachability` to call `attach(context)`). Kept
     * internal so the public API never leaks the wrapper.
     */
    internal fun unwrap(): Reachability = delegate
}
