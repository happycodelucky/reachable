/*
 * :reachable-testing — public, scriptable fake for the Reachability interface.
 *
 * Subclasses the (now-public, @TestingOnly-gated) `StateFlowReachability` so
 * the fake reuses the production StateFlow / scope / close-latch plumbing.
 * Tests get exactly the conflation, idempotent close, and "no emission after
 * close" semantics the production code provides — by construction, not by
 * duplication.
 */
@file:OptIn(TestingOnly::class)

package com.happycodelucky.reachable.testing

import com.happycodelucky.reachable.Reachability
import com.happycodelucky.reachable.ReachabilityStatus
import com.happycodelucky.reachable.TestingOnly
import com.happycodelucky.reachable.Transport
import com.happycodelucky.reachable.internal.StateFlowReachability
import kotlinx.atomicfu.atomic
import kotlin.experimental.ExperimentalObjCName
import kotlin.native.ObjCName

/**
 * Scriptable fake [Reachability] for tests.
 *
 * Drives [status] / [reachable] / [dataMetered] through the same
 * [StateFlowReachability] base class as the production Apple / Android
 * implementations, so tests exercise the same conflation and
 * close-after-emit semantics consumers see in production.
 *
 * ### Driving state
 *
 * ```kotlin
 * val fake = FakeReachability(
 *     initial = ReachabilityStatus(
 *         isReachable = true,
 *         transport = Transport.Wifi,
 *         isDataMetered = false,
 *     ),
 * )
 * fake.emit(ReachabilityStatus.Unknown)
 * fake.setReachable(false)
 * fake.setTransport(Transport.Cellular)
 * fake.setDataMetered(true)
 * ```
 *
 * The convenience setters compose with the current value —
 * `setReachable(false)` leaves `transport` and `isDataMetered` alone. They
 * publish via the same internal `publish` path so StateFlow conflation
 * rules apply.
 *
 * ### Observing close
 *
 * Unlike the production singleton (which is wrapped in
 * `NonClosingReachability` and no-ops `close()`), this fake **honours**
 * `close()` and counts every call. Tests can assert
 * `assertEquals(0, fake.closeCallCount)` to verify the unit under test
 * does *not* try to close `Reachability.shared`, or assert a positive
 * count to verify a `use { }` block did teardown properly.
 *
 * ### Installing as `Reachability.shared`
 *
 * Use [withFakeReachability] — it constructs a fake, installs it as the
 * shared singleton via `Reachability.installForTesting`, runs your test
 * block, then uninstalls and closes the fake in `finally`.
 *
 * Renames cleanly across the SKIE bridge: in Swift the class reads as
 * `FakeReachability`, with `emit(status:)`, `setReachable(_:)`,
 * `setTransport(_:)`, `setDataMetered(_:)`, `reset()`, `closeCallCount`,
 * `wasClosed`.
 *
 * @param initial Seed [ReachabilityStatus]. Defaults to
 * [ReachabilityStatus.Unknown] to match the production StateFlow seed —
 * supplying any other value publishes it during construction so that
 * late-joining collectors immediately observe it instead of seeing
 * `Unknown → initial`.
 */
@OptIn(ExperimentalObjCName::class)
@ObjCName(name = "ReachableFakeReachability", swiftName = "FakeReachability")
public class FakeReachability(
    initial: ReachabilityStatus = ReachabilityStatus.Unknown,
) : StateFlowReachability() {
    private val _closeCallCount = atomic(0)

    /**
     * Total number of times [close] has been invoked on this fake,
     * including redundant ones. The base class collapses repeated closes
     * into a single `onClose()` (idempotent close-once contract), but the
     * counter is the *call* count — useful for asserting that callers
     * tried to close at all.
     */
    public val closeCallCount: Int
        get() = _closeCallCount.value

    /** `true` once [close] has been called at least once. */
    public val wasClosed: Boolean
        get() = _closeCallCount.value > 0

    init {
        // Skip the publish for `Unknown`: the base StateFlow is already
        // seeded with `Unknown`, so a redundant write here would still
        // be conflated, but late-joining collectors interacting with
        // Turbine's `awaitItem()` benefit from the absence of a no-op
        // publish — fewer "phantom" items in test logs.
        if (initial != ReachabilityStatus.Unknown) publish(initial)
    }

    /**
     * Publish [status] to all collectors of [Reachability.status]. Public
     * counterpart to the protected base-class `publish`; the rename on
     * the base class (`emit` → `publish`) is what lets the fake expose
     * the natural `emit(...)` driver name without a visibility-widening
     * collision.
     *
     * Safe to call from any thread (delegates to
     * `MutableStateFlow.value =`). No-op after [close] (inherited from
     * the base class).
     */
    @OptIn(ExperimentalObjCName::class)
    @ObjCName(swiftName = "emit")
    public fun emit(status: ReachabilityStatus): Unit = publish(status)

    /**
     * Flip the reachable boolean; keep current transport and metering.
     * Equivalent to `emit(status.value.copy(isReachable = isReachable))`.
     */
    @OptIn(ExperimentalObjCName::class)
    @ObjCName("setReachable")
    public fun setReachable(isReachable: Boolean) {
        emit(status.value.copy(isReachable = isReachable))
    }

    /**
     * Swap transport; keep reachable and metering. Equivalent to
     * `emit(status.value.copy(transport = transport))`.
     */
    @OptIn(ExperimentalObjCName::class)
    @ObjCName("setTransport")
    public fun setTransport(transport: Transport) {
        emit(status.value.copy(transport = transport))
    }

    /**
     * Flip the data-metered boolean; keep reachable and transport.
     * Equivalent to `emit(status.value.copy(isDataMetered = isDataMetered))`.
     */
    @OptIn(ExperimentalObjCName::class)
    @ObjCName("setDataMetered")
    public fun setDataMetered(isDataMetered: Boolean) {
        emit(status.value.copy(isDataMetered = isDataMetered))
    }

    /** Reset to [ReachabilityStatus.Unknown]. Useful between assertion blocks. */
    public fun reset(): Unit = emit(ReachabilityStatus.Unknown)

    override fun onCloseInvoked() {
        _closeCallCount.incrementAndGet()
    }

    override fun onClose() {
        // No platform observer to tear down — FakeReachability is a pure-Kotlin
        // StateFlow driver. The base class CAS latch already stops `publish`
        // from accepting further emissions after close().
    }
}
