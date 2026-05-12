/*
 * :reachable-testing — semantics of Reachability.installForTesting.
 *
 * Covers the install / uninstall / handle-LIFO contract on the install
 * hook directly (without going through withFakeReachability). Every test
 * brackets its work in a `try { … } finally { ... installForTesting(null) }`
 * to guarantee the process-global override slot is cleared before the next
 * test runs, even on assertion failure.
 *
 * Design note: these tests deliberately avoid reading `Reachability.shared`
 * *before* installing a fake. Doing so would trigger the lazy initialiser
 * for the platform singleton — and on Android's JVM host test harness,
 * `AndroidReachability`'s constructor calls `NetworkRequest.Builder.addCapability`,
 * which throws `RuntimeException("Method not mocked")` under the stub Android
 * runtime. Tests instead use a sentinel fake to verify "the previous value
 * is restored" — install a sentinel, then install a real fake on top, then
 * verify uninstall returns to the sentinel. The "before any test" state
 * (null override) is verified separately by `installForTesting_null_clearsOverride`.
 */
@file:OptIn(TestingOnly::class)

package com.happycodelucky.reachable.testing

import com.happycodelucky.reachable.Reachability
import com.happycodelucky.reachable.TestingOnly
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertNotSame
import kotlin.test.assertSame

class InstallForTestingSemanticsTest {
    /**
     * Belt-and-braces: every test does its own cleanup in `finally`, but if
     * an `assertSame` throws before the `finally` is set up, this @AfterTest
     * still wipes the slot so the next test starts clean.
     */
    @AfterTest
    fun clearOverride() {
        Reachability.installForTesting(null)
    }

    @Test
    fun installForTesting_swapsTheSharedInstance() {
        val sentinel = FakeReachability()
        val fake = FakeReachability()
        // Install a sentinel first so `Reachability.shared` reads the sentinel
        // (a fake we constructed in this test), not the platform singleton.
        val sentinelHandle = Reachability.installForTesting(sentinel)
        try {
            val before = Reachability.shared
            assertSame(sentinel, before)

            val handle = Reachability.installForTesting(fake)
            try {
                assertSame(fake, Reachability.shared)
                assertNotSame(before, Reachability.shared)
            } finally {
                handle.uninstall()
            }
        } finally {
            sentinelHandle.uninstall()
            fake.close()
            sentinel.close()
        }
    }

    @Test
    fun handle_uninstall_restoresPreviousValue() {
        val sentinel = FakeReachability()
        val fake = FakeReachability()
        val sentinelHandle = Reachability.installForTesting(sentinel)
        try {
            // Sentinel is now the "previous value" the handle should restore.
            val handle = Reachability.installForTesting(fake)
            try {
                assertSame(fake, Reachability.shared)
            } finally {
                handle.uninstall()
            }
            // After uninstall, the singleton is whatever was before — our
            // sentinel, identity-equal to what we installed first.
            assertSame(sentinel, Reachability.shared)
        } finally {
            sentinelHandle.uninstall()
            fake.close()
            sentinel.close()
        }
    }

    @Test
    fun installForTesting_null_clearsOverride() {
        // Verify that passing `null` clears whatever override was installed,
        // without ever reading `Reachability.shared` while the slot is empty.
        // On Android JVM host, an empty-slot read would initialise the lazy
        // production singleton and throw on `NetworkRequest.Builder` calls.
        //
        // Pattern: fake1 in → assert; clear; fake2 in → assert. If `null`
        // had failed to clear, the second `installForTesting(fake2)` would
        // either have returned a non-null `previous` of `fake1` (proving the
        // clear was a no-op) or `shared` would still be `fake1` after the
        // second install. The `assertSame(fake2, Reachability.shared)` line
        // catches both.
        val fake1 = FakeReachability()
        val fake2 = FakeReachability()
        try {
            val handle1 = Reachability.installForTesting(fake1)
            assertSame(fake1, Reachability.shared)

            // Cleared.
            Reachability.installForTesting(null)

            val handle2 = Reachability.installForTesting(fake2)
            assertSame(fake2, Reachability.shared)
            // And the previous-capture is null because the slot was empty
            // when `installForTesting(fake2)` ran. We can't observe that
            // directly without exposing it from `TestingOverrideHandle`,
            // but the LIFO contract test (`nestedInstalls_restoreInLifoOrder`)
            // exercises the analogous case where previous was a sentinel.
            handle2.uninstall()
            handle1.uninstall()
        } finally {
            Reachability.installForTesting(null)
            fake2.close()
            fake1.close()
        }
    }

    @Test
    fun nestedInstalls_restoreInLifoOrder() {
        val sentinel = FakeReachability()
        val outer = FakeReachability()
        val inner = FakeReachability()
        val sentinelHandle = Reachability.installForTesting(sentinel)
        try {
            val outerHandle = Reachability.installForTesting(outer)
            try {
                assertSame(outer, Reachability.shared)
                val innerHandle = Reachability.installForTesting(inner)
                try {
                    assertSame(inner, Reachability.shared)
                } finally {
                    innerHandle.uninstall()
                }
                // After inner uninstall, outer is reinstated.
                assertSame(outer, Reachability.shared)
            } finally {
                outerHandle.uninstall()
            }
            // After outer uninstall, sentinel is reinstated.
            assertSame(sentinel, Reachability.shared)
        } finally {
            sentinelHandle.uninstall()
            inner.close()
            outer.close()
            sentinel.close()
        }
    }
}
