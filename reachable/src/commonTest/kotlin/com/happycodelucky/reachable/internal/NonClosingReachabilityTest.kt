/*
 * Reachable — NonClosingReachability decorator tests.
 *
 * Verifies the singleton-wrapping contract: every read delegates to the
 * underlying instance, but `close()` is a no-op so accidental tear-down
 * cannot freeze the singleton.
 *
 * Driver instance is the public
 * [com.happycodelucky.reachable.testing.FakeReachability] from
 * `:reachable-testing` — same fake consumers use; deletes the local
 * test-double duplication that previously lived inline.
 */
@file:OptIn(TestingOnly::class)

package com.happycodelucky.reachable.internal

import app.cash.turbine.test
import com.happycodelucky.reachable.Metering
import com.happycodelucky.reachable.ReachabilityStatus
import com.happycodelucky.reachable.TestingOnly
import com.happycodelucky.reachable.Transport
import com.happycodelucky.reachable.testing.FakeReachability
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class NonClosingReachabilityTest {
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
                under.emit(wifi)
                assertEquals(wifi, awaitItem())
                under.emit(cell)
                assertEquals(cell, awaitItem())
            }
        }

    @Test
    fun statusValue_readsUnderlyingValue() {
        val under = FakeReachability()
        val wrapped = NonClosingReachability(under)
        assertEquals(ReachabilityStatus.Unknown, wrapped.status.value)
        under.emit(wifi)
        assertEquals(wifi, wrapped.status.value)
    }

    @Test
    fun isReachable_andIsLowDataMode_delegate() {
        val under = FakeReachability()
        val wrapped = NonClosingReachability(under)

        // Pre-emission: Unknown is not reachable, not low-data.
        assertFalse(wrapped.isReachable)
        assertFalse(wrapped.isLowDataMode)

        under.emit(wifi)
        assertTrue(wrapped.isReachable)
        assertFalse(wrapped.isLowDataMode)

        under.emit(constrainedWifi)
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
                under.emit(wifi)
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
                under.emit(constrainedWifi)
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
        // The wrapper swallows close() entirely — the underlying fake never
        // sees a call, so its closeCallCount stays at zero.
        assertEquals(0, under.closeCallCount)
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
                under.emit(wifi)
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
