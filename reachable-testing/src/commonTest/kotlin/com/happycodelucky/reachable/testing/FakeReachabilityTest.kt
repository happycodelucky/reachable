/*
 * :reachable-testing — tests for the FakeReachability driver itself.
 *
 * Covers the public surface (constructor seed, emit, the three setters,
 * reset) and the close-observation hooks (closeCallCount / wasClosed).
 * The underlying StateFlow / scope / close-latch behaviour is verified
 * by :reachable's StateFlowReachabilityTest, which now drives the same
 * FakeReachability — so these tests focus on what's *unique* to the fake.
 */
package com.happycodelucky.reachable.testing

import app.cash.turbine.test
import com.happycodelucky.reachable.ReachabilityStatus
import com.happycodelucky.reachable.Transport
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FakeReachabilityTest {
    private val wifi =
        ReachabilityStatus(isReachable = true, transport = Transport.Wifi, isDataMetered = false)
    private val cell =
        ReachabilityStatus(isReachable = true, transport = Transport.Cellular, isDataMetered = true)
    private val offline =
        ReachabilityStatus(isReachable = false, transport = Transport.None, isDataMetered = false)

    @Test
    fun defaultSeed_isUnknown() {
        val fake = FakeReachability()
        assertEquals(ReachabilityStatus.Unknown, fake.status.value)
    }

    @Test
    fun initialSeed_isImmediatelyVisible() {
        val fake = FakeReachability(initial = wifi)
        assertEquals(wifi, fake.status.value)
    }

    @Test
    fun initialSeed_isEmittedOnceToLateCollector() =
        runTest {
            // Late-joining collectors should see exactly the seed, not
            // `Unknown → seed`. StateFlow conflation guarantees this when
            // the seed is published before any subscription.
            val fake = FakeReachability(initial = wifi)
            fake.status.test {
                assertEquals(wifi, awaitItem())
                expectNoEvents()
            }
        }

    @Test
    fun setReachable_preservesTransportAndDataMetered() {
        val fake = FakeReachability(initial = cell)
        fake.setReachable(false)
        assertEquals(
            ReachabilityStatus(
                isReachable = false,
                transport = Transport.Cellular,
                isDataMetered = true,
            ),
            fake.status.value,
        )
    }

    @Test
    fun setTransport_preservesReachableAndDataMetered() {
        val fake = FakeReachability(initial = wifi)
        fake.setTransport(Transport.Cellular)
        assertEquals(
            ReachabilityStatus(
                isReachable = true,
                transport = Transport.Cellular,
                isDataMetered = false,
            ),
            fake.status.value,
        )
    }

    @Test
    fun setDataMetered_preservesReachableAndTransport() {
        val fake = FakeReachability(initial = wifi)
        fake.setDataMetered(true)
        assertEquals(
            ReachabilityStatus(
                isReachable = true,
                transport = Transport.Wifi,
                isDataMetered = true,
            ),
            fake.status.value,
        )
        // The shortcut should reflect the change too.
        assertTrue(fake.isDataMetered)
    }

    @Test
    fun reset_emitsUnknown() {
        val fake = FakeReachability(initial = wifi)
        fake.reset()
        assertEquals(ReachabilityStatus.Unknown, fake.status.value)
    }

    @Test
    fun emit_drivesAllThreeStateFlows() =
        runTest {
            val fake = FakeReachability()
            fake.reachable.test {
                assertEquals(false, awaitItem())
                fake.emit(wifi)
                assertEquals(true, awaitItem())
                fake.emit(offline)
                assertEquals(false, awaitItem())
            }
        }

    // ---- close observation -----------------------------------------------

    @Test
    fun closeCallCount_incrementsPerCall() {
        val fake = FakeReachability()
        assertEquals(0, fake.closeCallCount)
        assertFalse(fake.wasClosed)
        fake.close()
        assertEquals(1, fake.closeCallCount)
        assertTrue(fake.wasClosed)
        fake.close()
        fake.close()
        assertEquals(3, fake.closeCallCount)
    }

    @Test
    fun emit_isNoOpAfterClose() {
        val fake = FakeReachability(initial = wifi)
        fake.close()
        fake.emit(cell)
        // Frozen at close time — base-class contract.
        assertEquals(wifi, fake.status.value)
    }
}
