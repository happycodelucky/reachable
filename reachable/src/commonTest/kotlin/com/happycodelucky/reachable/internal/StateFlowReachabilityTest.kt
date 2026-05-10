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
}
