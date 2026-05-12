/*
 * :reachable-testing — tests for the exception-safe install/uninstall wrapper.
 *
 * `withFakeReachability` is a `suspend fun` — all tests run inside
 * `runTest { }` so they can enter a coroutine context and call it directly.
 * This is the idiomatic KMP pattern that avoids K2 overload-resolution
 * ambiguity between suspending and non-suspending lambda overloads.
 *
 * Design note: tests that need to verify "the previous value is restored
 * after withFakeReachability exits" install a sentinel fake first (via the
 * raw `installForTesting` hook) and then call `withFakeReachability` on top
 * of it. That avoids reading `Reachability.shared` before any override is
 * installed — which on Android's JVM host test harness would trigger
 * `AndroidReachability`'s constructor and throw `RuntimeException("Method
 * NetworkRequest$Builder.addCapability not mocked")`. See the matching note
 * in `InstallForTestingSemanticsTest`.
 */
@file:OptIn(TestingOnly::class)

package com.happycodelucky.reachable.testing

import app.cash.turbine.test
import com.happycodelucky.reachable.Metering
import com.happycodelucky.reachable.Reachability
import com.happycodelucky.reachable.ReachabilityStatus
import com.happycodelucky.reachable.TestingOnly
import com.happycodelucky.reachable.Transport
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue

class WithFakeReachabilityTest {
    private val wifi = ReachabilityStatus(true, Transport.Wifi, Metering.Unmetered)

    @AfterTest
    fun clearOverride() {
        Reachability.installForTesting(null)
    }

    @Test
    fun installsFakeAsShared_andUninstallsOnNormalExit() =
        runTest {
            val sentinel = FakeReachability()
            val sentinelHandle = Reachability.installForTesting(sentinel)
            try {
                var capturedShared: Reachability? = null
                var capturedFake: FakeReachability? = null

                withFakeReachability { fake ->
                    capturedShared = Reachability.shared
                    capturedFake = fake
                }

                // Inside the block, shared was the fake.
                assertSame(capturedFake, capturedShared)
                // After the block, shared is restored to the sentinel.
                assertSame(sentinel, Reachability.shared)
            } finally {
                sentinelHandle.uninstall()
                sentinel.close()
            }
        }

    @Test
    fun blockReceivesAFakeSeededWithInitialStatus() =
        runTest {
            withFakeReachability(initial = wifi) { fake ->
                assertEquals(wifi, fake.status.value)
                assertSame(fake, Reachability.shared)
            }
        }

    @Test
    fun restoresPreviousOnException() =
        runTest {
            val sentinel = FakeReachability()
            val sentinelHandle = Reachability.installForTesting(sentinel)
            try {
                var capturedShared: Reachability? = null

                assertFailsWith<IllegalStateException> {
                    withFakeReachability { _ ->
                        capturedShared = Reachability.shared
                        error("boom")
                    }
                }

                // Override observed inside the block (it was the
                // `withFakeReachability`-internal fake, not the sentinel).
                assertNotSame(sentinel, capturedShared)
                // Restored to the sentinel even though the block threw.
                assertSame(sentinel, Reachability.shared)
            } finally {
                sentinelHandle.uninstall()
                sentinel.close()
            }
        }

    @Test
    fun closesFakeOnExit() =
        runTest {
            var capturedFake: FakeReachability? = null
            withFakeReachability { fake ->
                capturedFake = fake
                assertEquals(0, fake.closeCallCount)
            }
            // `withFakeReachability` closes the fake in its `finally` so leaked
            // collectors freeze rather than drift.
            val fake = capturedFake
            assertTrue(fake != null && fake.wasClosed)
        }

    @Test
    fun closesFakeOnExceptionPath() =
        runTest {
            var capturedFake: FakeReachability? = null
            assertFailsWith<IllegalStateException> {
                withFakeReachability { fake ->
                    capturedFake = fake
                    error("boom")
                }
            }
            val fake = capturedFake
            assertTrue(fake != null && fake.wasClosed)
        }

    @Test
    fun returnsBlockResult() =
        runTest {
            val result =
                withFakeReachability { fake ->
                    fake.emit(wifi)
                    fake.status.value
                }
            assertEquals(wifi, result)
        }

    @Test
    fun nestedWithFakeReachability_restoresOuterFake() =
        runTest {
            val sentinel = FakeReachability()
            val sentinelHandle = Reachability.installForTesting(sentinel)
            try {
                withFakeReachability { outer ->
                    assertSame(outer, Reachability.shared)
                    withFakeReachability { inner ->
                        assertSame(inner, Reachability.shared)
                    }
                    // Inner uninstalls; outer is back.
                    assertSame(outer, Reachability.shared)
                }
                // Outer uninstalls; sentinel is back.
                assertSame(sentinel, Reachability.shared)
            } finally {
                sentinelHandle.uninstall()
                sentinel.close()
            }
        }

    // ---- suspending assertions -----------------------------------------------

    @Test
    fun suspendingAssertions_integratesWithRunTest() =
        runTest {
            withFakeReachability { fake ->
                fake.emit(wifi)
                Reachability.shared.reachable.test {
                    assertEquals(true, awaitItem())
                    fake.emit(ReachabilityStatus.Unknown)
                    assertEquals(false, awaitItem())
                }
            }
        }

    @Test
    fun suspendingAssertions_restoresOnException() =
        runTest {
            val sentinel = FakeReachability()
            val sentinelHandle = Reachability.installForTesting(sentinel)
            try {
                assertFailsWith<IllegalStateException> {
                    withFakeReachability { _ ->
                        error("boom")
                    }
                }
                assertSame(sentinel, Reachability.shared)
            } finally {
                sentinelHandle.uninstall()
                sentinel.close()
            }
        }
}
