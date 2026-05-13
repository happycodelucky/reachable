/*
 * Reachable — shared base class for the per-platform implementations.
 *
 * Owns the `StateFlow`, the `SupervisorJob`-rooted coroutine scope, and the
 * close latch. Per-platform classes (`AppleReachability`, `AndroidReachability`)
 * subclass this and feed `publish()` from their callbacks; their only other
 * responsibility is `onClose()` to tear down the platform observer.
 *
 * The class is `public abstract`, but its primary constructor is `internal`
 * and the whole declaration is `@TestingOnly`. Production consumers of
 * `:reachable` see exactly the same surface they did before — only test
 * code in `:reachable-testing` (which opts in via `@OptIn(TestingOnly::class)`)
 * can subclass it. The public visibility exists solely so the test module's
 * `FakeReachability` can reuse the StateFlow plumbing without duplicating
 * the conflation / scope / close-latch logic.
 */
package com.happycodelucky.reachable.internal

import com.happycodelucky.reachable.Reachability
import com.happycodelucky.reachable.ReachabilityStatus
import com.happycodelucky.reachable.TestingOnly
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
 * in their own constructor and call [publish] from the callback; [close]
 * cancels the scope and dispatches to [onClose] for platform-specific
 * teardown.
 *
 * CLAUDE.md §3: scope has a clear owner and a defined cancellation lifecycle
 * (the [Reachability.close] call). The atomic close latch is the
 * single-flag-state pattern called out in §3.
 *
 * **Visibility note (CLAUDE.md §3 — "widen visibility only when needed").**
 * The class is `public abstract` with a `protected` primary constructor,
 * and the declaration carries the [TestingOnly] opt-in marker. That
 * combination lets `:reachable-testing` (a separate Gradle module)
 * subclass it to implement `FakeReachability`, while still preventing
 * arbitrary code in either module from instantiating
 * `StateFlowReachability` directly — only subclasses can call a `protected`
 * constructor.
 *
 * A non-test consumer attempting to subclass without
 * `@OptIn(TestingOnly::class)` hits an `ERROR`-level compile failure
 * pointing them at `:reachable-testing`'s `withFakeReachability { … }`
 * helper. `internal constructor()` would have been stricter within
 * `:reachable` but is too strict across modules — Kotlin's `internal` is
 * module-scoped, so it would block `:reachable-testing` from subclassing
 * at all.
 */
@TestingOnly
public abstract class StateFlowReachability protected constructor() : Reachability {
    private val supervisor = SupervisorJob()

    /**
     * Default-dispatched scope rooted on a [SupervisorJob]. Platform code may
     * use this for any work it needs to do beyond updating the StateFlow
     * (none of the v1 implementations do).
     */
    protected val scope: CoroutineScope = CoroutineScope(supervisor + Dispatchers.Default)

    private val _status = MutableStateFlow(ReachabilityStatus.Unknown)
    final override val status: StateFlow<ReachabilityStatus> = _status.asStateFlow()

    // The single-axis StateFlows are owned `MutableStateFlow`s rather than
    // `status.map { … }.stateIn(…)` derivations. The `stateIn`-based shape
    // works in steady state but races with publishes that happen between
    // construction and a collector's first `awaitItem()` — `Eagerly` schedules
    // the upstream collector on the scope's dispatcher, which doesn't always
    // run before the next `value` write under virtual-time test schedulers.
    // Updating these from inside [publish] keeps the writes causally ordered
    // with `_status` and avoids the race.
    private val _reachable = MutableStateFlow(_status.value.isReachable)
    final override val reachable: StateFlow<Boolean> = _reachable.asStateFlow()

    private val _dataMetered = MutableStateFlow(_status.value.isDataMetered)
    final override val dataMetered: StateFlow<Boolean> = _dataMetered.asStateFlow()

    final override val isReachable: Boolean
        get() = _reachable.value

    final override val isDataMetered: Boolean
        get() = _dataMetered.value

    private val closed = atomic(false)

    /**
     * `true` after [close] has been called. Subclasses use this to guard
     * deferred-init paths — e.g. [com.happycodelucky.reachable.AndroidReachability.attach]
     * early-returns if the instance has already been closed, so a late
     * `attach(context)` after teardown can't accidentally re-register a
     * `NetworkCallback` that will never be unregistered.
     */
    protected val isClosed: Boolean
        get() = closed.value

    /**
     * Push a new status to all collectors of [status]. Safe to call from any
     * thread — `MutableStateFlow.value` writes are concurrency-safe and
     * collapse adjacent identical values automatically.
     *
     * No-op after [close] has been called. (Subclass callbacks may still fire
     * briefly between `unregister` and the OS actually severing them.)
     *
     * Named `publish` (rather than `emit`) so subclasses that want to expose
     * a public driver — notably
     * [com.happycodelucky.reachable.testing.FakeReachability] — can use
     * `emit(...)` as their public surface name without colliding with this
     * protected member. Kotlin forbids widening visibility of a `final`
     * member, so the rename is the cleanest path.
     */
    protected fun publish(next: ReachabilityStatus) {
        if (closed.value) return
        _status.value = next
        // The single-axis StateFlows are kept causally consistent with `_status`
        // by writing to them in the same call. `MutableStateFlow.value` writes
        // are conflating, so identical successive values are dropped — a
        // transport-only change publishes to `_status` but neither derived flow
        // observes a new emission.
        _reachable.value = next.isReachable
        _dataMetered.value = next.isDataMetered
    }

    final override fun close() {
        // Called on every invocation (including redundant ones) — lets
        // subclasses count raw close() call volume independently of the
        // CAS-guarded onClose() path. FakeReachability uses this to assert
        // that test subjects don't try to close Reachability.shared.
        onCloseInvoked()
        if (!closed.compareAndSet(expect = false, update = true)) return
        // Tear down the platform observer first so no further callbacks land,
        // then cancel the scope. Order matters: a callback racing the scope
        // cancellation would otherwise risk a "scope is cancelled" stack
        // trace from `publish`'s coroutines (we don't currently use scope
        // from `publish`, but a future change might).
        runCatching { onClose() }
            .onFailure { /* swallow — close() must not throw */ }
        supervisor.cancel()
    }

    /**
     * Called on every invocation of [close], including redundant (post-CAS)
     * calls that are otherwise no-ops. Override in test subclasses to count
     * total `close()` calls independently of the once-only [onClose] callback.
     *
     * Not called by any other `StateFlowReachability` method — only by
     * [close]. The default implementation does nothing.
     *
     * **Timing note:** this hook is called *before* `closed` is set to `true`
     * and before [onClose] runs. Subclasses must not use the captured count to
     * gate emissions or to infer `isClosed == true`; under concurrent
     * observation there is a brief window where an overridden counter reports a
     * positive value while [publish] has not yet seen the CAS effect.
     */
    protected open fun onCloseInvoked() {}

    /**
     * Platform-specific teardown. Called exactly once, the first time [close]
     * is invoked. Implementations should release the platform observer
     * synchronously (e.g. `nw_path_monitor_cancel`,
     * `connectivityManager.unregisterNetworkCallback`).
     */
    protected abstract fun onClose()
}
