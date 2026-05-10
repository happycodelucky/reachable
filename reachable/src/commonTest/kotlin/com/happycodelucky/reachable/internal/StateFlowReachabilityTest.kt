/*
 * Reachable — base-class lifecycle and Flow-semantics tests.
 *
 * Verifies the contract `StateFlowReachability` provides to platform
 * subclasses and to public consumers: late collectors get the latest value,
 * close() is idempotent, emit() is a no-op after close(), and the close
 * latch actually cancels the supervisor.
 */
package com.happycodelucky.reachable.internal

import app.cash.turbine.test
import com.happycodelucky.reachable.Metering
import com.happycodelucky.reachable.ReachabilityStatus
import com.happycodelucky.reachable.Transport
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StateFlowReachabilityTest {
    /**
     * Minimal subclass that exposes [emit] and tracks `onClose` invocations.
     * Lets the tests drive the base class as if it were a platform impl.
     */
    private class FakeReachability : StateFlowReachability() {
        var onCloseCount = 0
            private set

        fun publish(s: ReachabilityStatus) = emit(s)

        fun isScopeCancelled(): Boolean {
            val job = scope.coroutineContext[Job] ?: return true
            return !job.isActive
        }

        override fun onClose() {
            onCloseCount++
        }
    }

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
                r.publish(wifi)
                assertEquals(wifi, awaitItem())
                r.publish(cell)
                assertEquals(cell, awaitItem())
            }
        }

    @Test
    fun lateCollector_immediatelyReceivesLatest() =
        runTest {
            val r = FakeReachability()
            r.publish(wifi)
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
                r.publish(wifi)
                assertEquals(wifi, awaitItem())
                r.publish(wifi) // Same value — StateFlow drops duplicates.
                r.publish(cell)
                assertEquals(cell, awaitItem())
            }
        }

    @Test
    fun close_isIdempotentAndInvokesOnCloseOnce() {
        val r = FakeReachability()
        r.close()
        r.close()
        r.close()
        assertEquals(1, r.onCloseCount)
    }

    @Test
    fun close_cancelsTheScope() {
        val r = FakeReachability()
        assertFalse(r.isScopeCancelled())
        r.close()
        assertTrue(r.isScopeCancelled())
    }

    @Test
    fun emit_isNoOpAfterClose() {
        val r = FakeReachability()
        r.publish(wifi)
        assertEquals(wifi, r.status.value)
        r.close()
        r.publish(cell)
        // Last value frozen at close time.
        assertEquals(wifi, r.status.value)
    }

    // ---- isReachable / isLowDataMode shortcuts ---------------------------

    @Test
    fun isReachable_readsLatestStatusValue() {
        val r = FakeReachability()
        // Pre-emission: Unknown is not reachable.
        assertFalse(r.isReachable)
        r.publish(wifi)
        assertTrue(r.isReachable)
        r.publish(offline)
        assertFalse(r.isReachable)
    }

    @Test
    fun isLowDataMode_isTrueOnlyForConstrainedMetering() {
        val r = FakeReachability()
        assertFalse(r.isLowDataMode)
        r.publish(wifi) // Metering.Unmetered
        assertFalse(r.isLowDataMode)
        r.publish(cell) // Metering.Metered
        assertFalse(r.isLowDataMode)
        r.publish(constrainedWifi) // Metering.Constrained
        assertTrue(r.isLowDataMode)
    }

    // ---- reachable / lowDataMode StateFlows -----------------------------

    @Test
    fun reachableFlow_initialValueMatchesCurrentStatus() =
        runTest {
            val r = FakeReachability()
            r.publish(wifi) // seed before any collector subscribes
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
                r.publish(wifi)
                assertEquals(true, awaitItem())
                r.publish(offline)
                assertEquals(false, awaitItem())
            }
        }

    @Test
    fun reachableFlow_collapsesNonReachableChanges() =
        runTest {
            val r = FakeReachability()
            r.reachable.test {
                assertEquals(false, awaitItem())
                r.publish(wifi)
                assertEquals(true, awaitItem())
                // Transport / metering changes that keep `reachable=true` are
                // collapsed by StateFlow conflation on the derived flow.
                r.publish(cell)
                r.publish(constrainedWifi)
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
                r.publish(wifi)
                // wifi is Unmetered → still false; conflated.
                r.publish(cell)
                // cell is Metered → still false; conflated.
                expectNoEvents()
                r.publish(constrainedWifi)
                assertEquals(true, awaitItem())
                r.publish(wifi)
                assertEquals(false, awaitItem())
            }
        }
}
