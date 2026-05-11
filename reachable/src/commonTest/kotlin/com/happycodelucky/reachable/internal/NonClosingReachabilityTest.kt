/*
 * Reachable — NonClosingReachability decorator tests.
 *
 * Verifies the singleton-wrapping contract: every read delegates to the
 * underlying instance, but `close()` is a no-op so accidental tear-down
 * cannot freeze the singleton.
 */
package com.happycodelucky.reachable.internal

import app.cash.turbine.test
import com.happycodelucky.reachable.Metering
import com.happycodelucky.reachable.ReachabilityStatus
import com.happycodelucky.reachable.Transport
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class NonClosingReachabilityTest {
    /**
     * Same minimal subclass shape used in [StateFlowReachabilityTest], copied
     * to keep this file self-contained. Exposes `emit` and tracks `onClose`.
     */
    private class FakeReachability : StateFlowReachability() {
        var onCloseCount = 0
            private set

        fun publish(s: ReachabilityStatus) = emit(s)

        override fun onClose() {
            onCloseCount++
        }
    }

    private val wifi = ReachabilityStatus(true, Transport.Wifi, Metering.Unmetered)
    private val cell = ReachabilityStatus(true, Transport.Cellular, Metering.Metered)
    private val constrainedWifi =
        ReachabilityStatus(true, Transport.Wifi, Metering.Constrained)

    // ---- delegation ------------------------------------------------------

    @Test
    fun status_delegatesToUnderlying() =
        runTest {
            val under = FakeReachability()
            val wrapped = NonClosingReachability(under)
            wrapped.status.test {
                assertEquals(ReachabilityStatus.Unknown, awaitItem())
                under.publish(wifi)
                assertEquals(wifi, awaitItem())
                under.publish(cell)
                assertEquals(cell, awaitItem())
            }
        }

    @Test
    fun statusValue_readsUnderlyingValue() {
        val under = FakeReachability()
        val wrapped = NonClosingReachability(under)
        assertEquals(ReachabilityStatus.Unknown, wrapped.status.value)
        under.publish(wifi)
        assertEquals(wifi, wrapped.status.value)
    }

    @Test
    fun isReachable_andIsLowDataMode_delegate() {
        val under = FakeReachability()
        val wrapped = NonClosingReachability(under)

        // Pre-emission: Unknown is not reachable, not low-data.
        assertFalse(wrapped.isReachable)
        assertFalse(wrapped.isLowDataMode)

        under.publish(wifi)
        assertTrue(wrapped.isReachable)
        assertFalse(wrapped.isLowDataMode)

        under.publish(constrainedWifi)
        assertTrue(wrapped.isReachable)
        assertTrue(wrapped.isLowDataMode)
    }

    @Test
    fun reachableFlow_delegates() =
        runTest {
            val under = FakeReachability()
            val wrapped = NonClosingReachability(under)
            wrapped.reachable.test {
                assertEquals(false, awaitItem()) // Unknown
                under.publish(wifi)
                assertEquals(true, awaitItem())
            }
        }

    @Test
    fun lowDataModeFlow_delegates() =
        runTest {
            val under = FakeReachability()
            val wrapped = NonClosingReachability(under)
            wrapped.lowDataMode.test {
                assertEquals(false, awaitItem())
                under.publish(constrainedWifi)
                assertEquals(true, awaitItem())
            }
        }

    // ---- the central contract: close() is a no-op -----------------------

    @Test
    fun close_isANoOp_doesNotInvokeUnderlyingOnClose() {
        val under = FakeReachability()
        val wrapped = NonClosingReachability(under)
        wrapped.close()
        wrapped.close()
        wrapped.close()
        assertEquals(0, under.onCloseCount)
    }

    @Test
    fun close_doesNotFreezeTheUnderlyingFlow() =
        runTest {
            val under = FakeReachability()
            val wrapped = NonClosingReachability(under)
            wrapped.close()
            // Underlying is still live — publishing must still propagate.
            wrapped.status.test {
                assertEquals(ReachabilityStatus.Unknown, awaitItem())
                under.publish(wifi)
                assertEquals(wifi, awaitItem())
            }
        }

    // ---- unwrap() exposes the underlying instance -----------------------

    @Test
    fun unwrap_returnsTheDelegate() {
        val under = FakeReachability()
        val wrapped = NonClosingReachability(under)
        assertSame(under, wrapped.unwrap())
    }
}
