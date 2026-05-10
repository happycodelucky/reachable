/*
 * Reachable — shared base class for the per-platform implementations.
 *
 * Owns the `StateFlow`, the `SupervisorJob`-rooted coroutine scope, and the
 * close latch. Per-platform classes (`AppleReachability`, `AndroidReachability`)
 * subclass this and feed `emit()` from their callbacks; their only other
 * responsibility is `onClose()` to tear down the platform observer.
 */
package com.happycodelucky.reachable.internal

import com.happycodelucky.reachable.Reachability
import com.happycodelucky.reachable.ReachabilityStatus
import kotlinx.atomicfu.atomic
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Shared `Reachability` implementation hosting the [MutableStateFlow] and the
 * `SupervisorJob`-rooted [scope]. Platform subclasses register their observer
 * in their own constructor and call [emit] from the callback; [close] cancels
 * the scope and dispatches to [onClose] for platform-specific teardown.
 *
 * CLAUDE.md §3: scope has a clear owner and a defined cancellation lifecycle
 * (the [Reachability.close] call). The atomic close latch is the
 * single-flag-state pattern called out in §3.
 */
internal abstract class StateFlowReachability : Reachability {
    private val supervisor = SupervisorJob()

    /**
     * Default-dispatched scope rooted on a [SupervisorJob]. Platform code may
     * use this for any work it needs to do beyond updating the StateFlow
     * (none of the v1 implementations do).
     */
    protected val scope: CoroutineScope = CoroutineScope(supervisor + Dispatchers.Default)

    private val _status = MutableStateFlow(ReachabilityStatus.Unknown)
    final override val status: StateFlow<ReachabilityStatus> = _status.asStateFlow()

    private val closed = atomic(false)

    /**
     * Push a new status to all collectors of [status]. Safe to call from any
     * thread — `MutableStateFlow.value` writes are concurrency-safe and
     * collapse adjacent identical values automatically.
     *
     * No-op after [close] has been called. (Subclass callbacks may still fire
     * briefly between `unregister` and the OS actually severing them.)
     */
    protected fun emit(next: ReachabilityStatus) {
        if (closed.value) return
        _status.value = next
    }

    final override fun close() {
        if (!closed.compareAndSet(expect = false, update = true)) return
        // Tear down the platform observer first so no further callbacks land,
        // then cancel the scope. Order matters: a callback racing the scope
        // cancellation would otherwise risk a "scope is cancelled" stack
        // trace from `emit`'s coroutines (we don't currently use scope from
        // `emit`, but a future change might).
        runCatching { onClose() }
            .onFailure { /* swallow — close() must not throw */ }
        supervisor.cancel()
    }

    /**
     * Platform-specific teardown. Called exactly once, the first time [close]
     * is invoked. Implementations should release the platform observer
     * synchronously (e.g. `nw_path_monitor_cancel`,
     * `connectivityManager.unregisterNetworkCallback`).
     */
    protected abstract fun onClose()
}
