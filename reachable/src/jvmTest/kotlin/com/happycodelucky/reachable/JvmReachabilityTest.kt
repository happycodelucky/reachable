/*
 * Reachable — behavioural tests for the JVM poll loop.
 *
 * The loop runs on the instance's own scope (Dispatchers.Default), so these
 * tests run against short *real* poll intervals and let Turbine suspend until
 * emissions arrive — no virtual-time scheduler reaches that dispatcher, and
 * no Thread.sleep is involved (CLAUDE.md §11).
 *
 * Determinism note: a collector may attach before or after the first poll
 * lands. Every test therefore starts the scripted interface source in a
 * state that maps to ReachabilityStatus.Unknown (equal to the StateFlow
 * seed, so conflation makes the race invisible) and only then steps it.
 */
package com.happycodelucky.reachable

import app.cash.turbine.test
import com.happycodelucky.reachable.internal.JvmNetworkInterface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

class JvmReachabilityTest {
    private val wifi =
        ReachabilityStatus(isReachable = true, transport = Transport.Wifi, isDataMetered = false)
    private val wired =
        ReachabilityStatus(isReachable = true, transport = Transport.Ethernet, isDataMetered = false)

    private fun iface(
        name: String,
        isUp: Boolean = true,
    ) = JvmNetworkInterface(
        name = name,
        displayName = name,
        isUp = isUp,
        isLoopback = false,
        hasRoutableAddress = true,
    )

    /**
     * Scripted stand-in for [systemNetworkInterfaces]. AtomicReference (not a
     * plain `var`) because the poll loop reads from a Dispatchers.Default
     * thread while the test thread writes.
     */
    private class ScriptedInterfaces(
        initial: List<JvmNetworkInterface>,
    ) : () -> List<JvmNetworkInterface> {
        private val current = AtomicReference(initial)

        fun set(next: List<JvmNetworkInterface>) = current.set(next)

        override fun invoke(): List<JvmNetworkInterface> = current.get()
    }

    @Test
    fun pollLoopPublishesInterfaceChanges() =
        runTest {
            val source = ScriptedInterfaces(listOf(iface("wlan0", isUp = false)))
            JvmReachability(pollInterval = 25.milliseconds, interfaceSource = source).use { reachability ->
                reachability.status.test {
                    // Down interface maps to Unknown — identical to the seed,
                    // so exactly one item regardless of poll/collect ordering.
                    assertEquals(ReachabilityStatus.Unknown, awaitItem())

                    source.set(listOf(iface("wlan0")))
                    assertEquals(wifi, awaitItem())

                    source.set(emptyList())
                    assertEquals(ReachabilityStatus.Unknown, awaitItem())
                }
            }
        }

    @Test
    fun singleAxisFlowsTrackThePollLoop() =
        runTest {
            val source = ScriptedInterfaces(emptyList())
            JvmReachability(pollInterval = 25.milliseconds, interfaceSource = source).use { reachability ->
                reachability.reachable.test {
                    assertEquals(false, awaitItem())
                    source.set(listOf(iface("eth0")))
                    assertEquals(true, awaitItem())
                    assertTrue(reachability.isReachable)
                }
            }
        }

    @Test
    fun closeStopsThePollLoop() =
        runTest {
            val source = ScriptedInterfaces(listOf(iface("eth0")))
            val reachability = JvmReachability(pollInterval = 25.milliseconds, interfaceSource = source)
            reachability.status.test {
                // First item is the seed (Unknown) or the first poll result,
                // depending on who wins the startup race — consume until the
                // wired reading arrives so nothing is left buffered.
                if (awaitItem() != wired) {
                    assertEquals(wired, awaitItem())
                }

                reachability.close()
                source.set(emptyList())

                // Real-time wait spanning several would-be poll ticks; the
                // runTest scheduler's virtual time never touches this delay.
                withContext(Dispatchers.Default) { delay(150.milliseconds) }
                expectNoEvents()

                // close() retains the last published value.
                assertEquals(wired, reachability.status.value)
            }
        }

    @Test
    fun publicFactoryConstructsAndClosesCleanly() {
        // Smoke test against the real NetworkInterface table: must construct,
        // read, and close (idempotently) without throwing on any host.
        val reachability = Reachability(pollInterval = 25.milliseconds)
        reachability.use { /* status.value is the seed or a real reading */ }
        reachability.close()
    }
}
