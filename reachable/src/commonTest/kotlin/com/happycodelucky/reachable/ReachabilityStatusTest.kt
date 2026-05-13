/*
 * Reachable — public type and pure-mapping tests.
 *
 * Lives in commonTest so it runs on every target (iosSimulatorArm64Test,
 * macosArm64Test, testAndroidHostTest). All tests target pure functions —
 * no platform mocking, no coroutines, no Turbine. That coverage lives in
 * StateFlowReachabilityTest.
 */
package com.happycodelucky.reachable

import com.happycodelucky.reachable.internal.mapAndroidCapabilities
import com.happycodelucky.reachable.internal.mapApplePath
import com.happycodelucky.reachable.internal.pickTransport
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals

class ReachabilityStatusTest {
    // ---- Unknown sentinel -------------------------------------------------

    @Test
    fun unknown_isOfflineWithNoneTransport() {
        val u = ReachabilityStatus.Unknown
        assertEquals(false, u.isReachable)
        assertEquals(Transport.None, u.transport)
        assertEquals(false, u.isDataMetered)
    }

    @Test
    fun dataClass_equalityAndCopyWork() {
        val a = ReachabilityStatus(isReachable = true, transport = Transport.Wifi, isDataMetered = false)
        val b = ReachabilityStatus(isReachable = true, transport = Transport.Wifi, isDataMetered = false)
        assertEquals(a, b)
        val c = a.copy(isDataMetered = true)
        assertNotEquals(a, c)
        assertEquals(true, c.isDataMetered)
    }

    // ---- pickTransport priority -----------------------------------------

    @Test
    fun pickTransport_unreachableAlwaysReturnsNone() {
        val t =
            pickTransport(
                reachable = false,
                wifi = true,
                ethernet = true,
                cellular = true,
                other = true,
            )
        assertEquals(Transport.None, t)
    }

    @Test
    fun pickTransport_wifiBeatsEverything() {
        val t =
            pickTransport(
                reachable = true,
                wifi = true,
                ethernet = true,
                cellular = true,
                other = true,
            )
        assertEquals(Transport.Wifi, t)
    }

    @Test
    fun pickTransport_ethernetBeatsCellularAndOther() {
        val t =
            pickTransport(
                reachable = true,
                wifi = false,
                ethernet = true,
                cellular = true,
                other = true,
            )
        assertEquals(Transport.Ethernet, t)
    }

    @Test
    fun pickTransport_cellularBeatsOther() {
        val t =
            pickTransport(
                reachable = true,
                wifi = false,
                ethernet = false,
                cellular = true,
                other = true,
            )
        assertEquals(Transport.Cellular, t)
    }

    @Test
    fun pickTransport_otherWhenNoneOfTheNamedFlags() {
        val t =
            pickTransport(
                reachable = true,
                wifi = false,
                ethernet = false,
                cellular = false,
                other = true,
            )
        assertEquals(Transport.Other, t)
    }

    @Test
    fun pickTransport_reachableButNoFlagsFallsBackToOther() {
        val t =
            pickTransport(
                reachable = true,
                wifi = false,
                ethernet = false,
                cellular = false,
                other = false,
            )
        assertEquals(Transport.Other, t)
    }

    // ---- mapApplePath ----------------------------------------------------

    @Test
    fun applePath_satisfiedWifiUnmetered() {
        val s =
            mapApplePath(
                satisfied = true,
                wifi = true,
                ethernet = false,
                cellular = false,
                other = false,
                expensive = false,
                constrained = false,
            )
        assertEquals(true, s.isReachable)
        assertEquals(Transport.Wifi, s.transport)
        assertEquals(false, s.isDataMetered)
    }

    @Test
    fun applePath_satisfiedCellularExpensiveIsMetered() {
        val s =
            mapApplePath(
                satisfied = true,
                wifi = false,
                ethernet = false,
                cellular = true,
                other = false,
                expensive = true,
                constrained = false,
            )
        assertEquals(Transport.Cellular, s.transport)
        assertEquals(true, s.isDataMetered)
    }

    @Test
    fun applePath_constrainedAloneCountsAsMetered() {
        // Low Data Mode without `expensive` (e.g. user enabled it on Wi-Fi):
        // still surfaces as `isDataMetered = true`.
        val s =
            mapApplePath(
                satisfied = true,
                wifi = true,
                ethernet = false,
                cellular = false,
                other = false,
                expensive = false,
                constrained = true,
            )
        assertEquals(Transport.Wifi, s.transport)
        assertEquals(true, s.isDataMetered)
    }

    @Test
    fun applePath_constrainedAndExpensiveBothMetered() {
        // Low Data Mode active on a cellular path: still one bit of output.
        val s =
            mapApplePath(
                satisfied = true,
                wifi = false,
                ethernet = false,
                cellular = true,
                other = false,
                expensive = true,
                constrained = true,
            )
        assertEquals(true, s.isDataMetered)
    }

    @Test
    fun applePath_unsatisfiedClampsToNoneAndUnmetered() {
        // Even if the platform claims an interface and "expensive" while the
        // path is unsatisfied, we flatten to a clean offline reading. The
        // `!isReachable ⇒ !isDataMetered` invariant is load-bearing for
        // consumers who only check `isDataMetered` without first checking
        // `isReachable`.
        val s =
            mapApplePath(
                satisfied = false,
                wifi = true,
                ethernet = false,
                cellular = false,
                other = false,
                expensive = true,
                constrained = true,
            )
        assertEquals(false, s.isReachable)
        assertEquals(Transport.None, s.transport)
        assertEquals(false, s.isDataMetered)
    }

    // ---- mapAndroidCapabilities ------------------------------------------

    @Test
    fun androidCaps_internetWithoutValidatedIsUnreachable() {
        // Captive portal on Wi-Fi: hasInternet=true but hasValidated=false.
        val s =
            mapAndroidCapabilities(
                hasInternet = true,
                hasValidated = false,
                hasWifi = true,
                hasEthernet = false,
                hasCellular = false,
                notMetered = true,
                temporarilyNotMetered = false,
            )
        assertEquals(false, s.isReachable)
        assertEquals(Transport.None, s.transport)
    }

    @Test
    fun androidCaps_validatedWifiUnmetered() {
        val s =
            mapAndroidCapabilities(
                hasInternet = true,
                hasValidated = true,
                hasWifi = true,
                hasEthernet = false,
                hasCellular = false,
                notMetered = true,
                temporarilyNotMetered = false,
            )
        assertEquals(true, s.isReachable)
        assertEquals(Transport.Wifi, s.transport)
        assertEquals(false, s.isDataMetered)
    }

    @Test
    fun androidCaps_validatedCellularMetered() {
        val s =
            mapAndroidCapabilities(
                hasInternet = true,
                hasValidated = true,
                hasWifi = false,
                hasEthernet = false,
                hasCellular = true,
                notMetered = false,
                temporarilyNotMetered = false,
            )
        assertEquals(Transport.Cellular, s.transport)
        assertEquals(true, s.isDataMetered)
    }

    @Test
    fun androidCaps_temporarilyUnmeteredCountsAsUnmetered() {
        // Carriers can flag a metered network as "temporarily unmetered" (e.g.
        // for app updates). We treat that as not data-metered.
        val s =
            mapAndroidCapabilities(
                hasInternet = true,
                hasValidated = true,
                hasWifi = false,
                hasEthernet = false,
                hasCellular = true,
                notMetered = false,
                temporarilyNotMetered = true,
            )
        assertEquals(false, s.isDataMetered)
    }

    @Test
    fun androidCaps_dataMeteredFalseWheneverUnreachable() {
        // Sweep every combination — Android's mapping must never report
        // `isDataMetered = true` for an unreachable path. The invariant
        // `!isReachable ⇒ !isDataMetered` lets consumers read the metered
        // bit without first checking reachability.
        val combos = listOf(true, false)
        for (hasInternet in combos) {
            for (hasValidated in combos) {
                for (hasWifi in combos) {
                    for (hasEthernet in combos) {
                        for (hasCellular in combos) {
                            for (notMetered in combos) {
                                for (tempUnmetered in combos) {
                                    val s =
                                        mapAndroidCapabilities(
                                            hasInternet = hasInternet,
                                            hasValidated = hasValidated,
                                            hasWifi = hasWifi,
                                            hasEthernet = hasEthernet,
                                            hasCellular = hasCellular,
                                            notMetered = notMetered,
                                            temporarilyNotMetered = tempUnmetered,
                                        )
                                    if (!s.isReachable) {
                                        assertFalse(
                                            s.isDataMetered,
                                            "Unreachable path must not be reported as data-metered",
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
