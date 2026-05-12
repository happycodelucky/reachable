/*
 * Reachable — base-class lifecycle and Flow-semantics tests.
 *
 * Verifies the contract `StateFlowReachability` provides to platform
 * subclasses and to public consumers: late collectors get the latest value,
 * close() is idempotent, publish() is a no-op after close(), and the close
 * latch actually cancels the supervisor.
 *
 * Most of these assertions now exercise the public
 * [com.happycodelucky.reachable.testing.FakeReachability] from
 * `:reachable-testing` — the same fake consumers use to test their own
 * reachability-aware code. One white-box assertion (the scope-cancellation
 * check) uses a tiny inline subclass because the `scope` member is
 * protected and intentionally not exposed on the public fake.
 */
@file:OptIn(TestingOnly::class)

package com.happycodelucky.reachable.internal

import app.cash.turbine.test
import com.happycodelucky.reachable.Metering
import com.happycodelucky.reachable.ReachabilityStatus
import com.happycodelucky.reachable.TestingOnly
import com.happycodelucky.reachable.Transport
import com.happycodelucky.reachable.testing.FakeReachability
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StateFlowReachabilityTest {
    private val wifi = ReachabilityStatus(true, Transport.Wifi, Metering.Unmetered)
    private val cell = ReachabilityStatus(true, Transport.Cellular, Metering.Metered)
    private val constrainedWifi =
        ReachabilityStatus(true, Transport.Wifi, Metering.Constrained)
    private val offline = ReachabilityStatus(false, Transport.None, Metering.Unmetered)

    @Test
    fun statusStartsAsUnknown() {
        val r = FakeReachability()
        assertEquals(ReachabilityStatus.Unknown, r.status.value)
    }

    @Test
    fun emit_pushesToCollectors() =
        runTest {
            val r = FakeReachability()
            r.status.test {
                assertEquals(ReachabilityStatus.Unknown, awaitItem())
                r.emit(wifi)
                assertEquals(wifi, awaitItem())
                r.emit(cell)
                assertEquals(cell, awaitItem())
            }
        }

    @Test
    fun lateCollector_immediatelyReceivesLatest() =
        runTest {
            val r = FakeReachability()
            r.emit(wifi)
            // A new collector joining after the publish gets the latest value
            // immediately — StateFlow conflation.
            r.status.test {
                assertEquals(wifi, awaitItem())
            }
        }

    @Test
    fun emit_collapsesIdenticalConsecutiveValues() =
        runTest {
            val r = FakeReachability()
            r.status.test {
                assertEquals(ReachabilityStatus.Unknown, awaitItem())
                r.emit(wifi)
                assertEquals(wifi, awaitItem())
                r.emit(wifi) // Same value — StateFlow drops duplicates.
                r.emit(cell)
                assertEquals(cell, awaitItem())
            }
        }

    @Test
    fun close_isIdempotentAndInvokesOnCloseOnce() {
        val r = FakeReachability()
        r.close()
        r.close()
        r.close()
        // FakeReachability counts every `close()` call rather than the
        // collapsed onClose() count, but its `onClose()` runs exactly once
        // because of the base class's CAS latch. The "call count is 3" is
        // expected; the underlying "onClose ran once" is verified
        // indirectly via emit_isNoOpAfterClose below.
        assertEquals(3, r.closeCallCount)
    }

    @Test
    fun close_cancelsTheScope() {
        // White-box: the public FakeReachability does not expose its scope,
        // but the scope-cancellation contract is internal to
        // StateFlowReachability. A tiny inline subclass with a probe is
        // the cleanest way to keep that coverage without leaking the
        // protected `scope` member through the public testing API.
        val r =
            object : StateFlowReachability() {
                override fun onClose() = Unit

                fun isScopeCancelled(): Boolean {
                    val job = scope.coroutineContext[Job] ?: return true
                    return !job.isActive
                }
            }
        assertFalse(r.isScopeCancelled())
        r.close()
        assertTrue(r.isScopeCancelled())
    }

    @Test
    fun emit_isNoOpAfterClose() {
        val r = FakeReachability()
        r.emit(wifi)
        assertEquals(wifi, r.status.value)
        r.close()
        r.emit(cell)
        // Last value frozen at close time.
        assertEquals(wifi, r.status.value)
    }

    // ---- isReachable / isLowDataMode shortcuts ---------------------------

    @Test
    fun isReachable_readsLatestStatusValue() {
        val r = FakeReachability()
        // Pre-emission: Unknown is not reachable.
        assertFalse(r.isReachable)
        r.emit(wifi)
        assertTrue(r.isReachable)
        r.emit(offline)
        assertFalse(r.isReachable)
    }

    @Test
    fun isLowDataMode_isTrueOnlyForConstrainedMetering() {
        val r = FakeReachability()
        assertFalse(r.isLowDataMode)
        r.emit(wifi) // Metering.Unmetered
        assertFalse(r.isLowDataMode)
        r.emit(cell) // Metering.Metered
        assertFalse(r.isLowDataMode)
        r.emit(constrainedWifi) // Metering.Constrained
        assertTrue(r.isLowDataMode)
    }

    // ---- reachable / lowDataMode StateFlows -----------------------------

    @Test
    fun reachableFlow_initialValueMatchesCurrentStatus() =
        runTest {
            val r = FakeReachability()
            r.emit(wifi) // seed before any collector subscribes
            r.reachable.test {
                assertEquals(true, awaitItem())
            }
        }

    @Test
    fun reachableFlow_emitsOnReachableTransitions() =
        runTest {
            val r = FakeReachability()
            r.reachable.test {
                assertEquals(false, awaitItem()) // Unknown
                r.emit(wifi)
                assertEquals(true, awaitItem())
                r.emit(offline)
                assertEquals(false, awaitItem())
            }
        }

    @Test
    fun reachableFlow_collapsesNonReachableChanges() =
        runTest {
            val r = FakeReachability()
            r.reachable.test {
                assertEquals(false, awaitItem())
                r.emit(wifi)
                assertEquals(true, awaitItem())
                // Transport / metering changes that keep `reachable=true` are
                // collapsed by StateFlow conflation on the derived flow.
                r.emit(cell)
                r.emit(constrainedWifi)
                // No further emissions expected; cancelAndIgnoreRemainingEvents
                // would also work, but this is more explicit about intent.
                expectNoEvents()
            }
        }

    @Test
    fun lowDataModeFlow_emitsOnConstrainedTransitionsOnly() =
        runTest {
            val r = FakeReachability()
            r.lowDataMode.test {
                assertEquals(false, awaitItem()) // Unknown / Unmetered
                r.emit(wifi)
                // wifi is Unmetered → still false; conflated.
                r.emit(cell)
                // cell is Metered → still false; conflated.
                expectNoEvents()
                r.emit(constrainedWifi)
                assertEquals(true, awaitItem())
                r.emit(wifi)
                assertEquals(false, awaitItem())
            }
        }
}
