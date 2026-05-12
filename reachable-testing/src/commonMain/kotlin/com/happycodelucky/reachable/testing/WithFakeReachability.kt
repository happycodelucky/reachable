/*
 * :reachable-testing — exception-safe install / uninstall helper around
 * `Reachability.installForTesting`.
 *
 * Use this helper in preference to calling `installForTesting` directly:
 * it guarantees uninstall on the exception path, closes the fake on exit
 * so leaked collectors freeze rather than drift, and composes cleanly with
 * both non-suspending and suspending tests via `runTest { … }`.
 *
 * There is a single `suspend` overload. Non-suspending tests wrap their
 * assertion body in `runTest { }` — the idiomatic KMP pattern that avoids
 * K2 overload-resolution ambiguity between `(T) -> R` and
 * `suspend (T) -> R` signatures.
 */
@file:OptIn(TestingOnly::class)

package com.happycodelucky.reachable.testing

import com.happycodelucky.reachable.Reachability
import com.happycodelucky.reachable.ReachabilityStatus
import com.happycodelucky.reachable.TestingOnly
import kotlin.experimental.ExperimentalObjCName
import kotlin.native.ObjCName

/**
 * Install a fresh [FakeReachability] as `Reachability.shared` for the
 * duration of [block]; restore the previous override (or `null`) on exit;
 * close the fake. Exception-safe: every step in the `finally` runs even
 * when [block] throws.
 *
 * Nested calls are LIFO-safe by construction — each install captures the
 * *previous* override in its handle, so the inner uninstall restores the
 * outer fake, and the outer uninstall restores whatever was before that
 * (typically `null`, meaning the production singleton).
 *
 * ### Use from a test (non-suspending or suspending)
 *
 * ```kotlin
 * // Non-suspending — wrap in runTest to enter a coroutine context.
 * @Test
 * fun deviceIsOnline() = runTest {
 *     withFakeReachability(
 *         initial = ReachabilityStatus(true, Transport.Wifi, Metering.Unmetered),
 *     ) { fake ->
 *         val vm = MyViewModel()           // reads Reachability.shared
 *         assertTrue(vm.online)
 *
 *         fake.setReachable(false)
 *         assertFalse(vm.online)
 *     }
 * }
 *
 * // Suspending — runTest handles virtual time and suspend assertions.
 * @Test
 * fun reachableFlowReactsToFakeEmissions() = runTest {
 *     withFakeReachability { fake ->
 *         fake.emit(ReachabilityStatus(true, Transport.Wifi, Metering.Unmetered))
 *         Reachability.shared.reachable.test {
 *             assertEquals(true, awaitItem())
 *         }
 *     }
 * }
 * ```
 *
 * @param initial seed [ReachabilityStatus]; defaults to
 * [ReachabilityStatus.Unknown] to match the production StateFlow seed.
 * @param block receives the installed fake; drive state with
 * [FakeReachability.emit] or the convenience setters.
 * @return the value returned from [block].
 */
@OptIn(ExperimentalObjCName::class)
@ObjCName(swiftName = "withFakeReachability")
public suspend fun <R> withFakeReachability(
    initial: ReachabilityStatus = ReachabilityStatus.Unknown,
    block: suspend (FakeReachability) -> R,
): R {
    val fake = FakeReachability(initial)
    val handle = Reachability.installForTesting(fake)
    try {
        return block(fake)
    } finally {
        handle.uninstall()
        fake.close()
    }
}
