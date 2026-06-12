/*
 * Reachable — unit tests for the pure JVM interface→status mapping.
 *
 * Exercises the name/displayName classification heuristics, the host-only
 * bridge exclusions, and the Wifi > Ethernet > Cellular > Other transport
 * priority — all without touching java.net.NetworkInterface.
 */
package com.happycodelucky.reachable.internal

import com.happycodelucky.reachable.ReachabilityStatus
import com.happycodelucky.reachable.Transport
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class JvmMappingTest {
    private fun iface(
        name: String,
        displayName: String = name,
        isUp: Boolean = true,
        isLoopback: Boolean = false,
        hasRoutableAddress: Boolean = true,
    ) = JvmNetworkInterface(
        name = name,
        displayName = displayName,
        isUp = isUp,
        isLoopback = isLoopback,
        hasRoutableAddress = hasRoutableAddress,
    )

    @Test
    fun noInterfacesMapsToUnknown() {
        assertEquals(ReachabilityStatus.Unknown, mapJvmInterfaces(emptyList()))
    }

    @Test
    fun loopbackOnlyIsNotReachable() {
        val status = mapJvmInterfaces(listOf(iface("lo", isLoopback = true)))
        assertFalse(status.isReachable)
        assertEquals(Transport.None, status.transport)
    }

    @Test
    fun downInterfaceIsNotReachable() {
        val status = mapJvmInterfaces(listOf(iface("eth0", isUp = false)))
        assertFalse(status.isReachable)
        assertEquals(Transport.None, status.transport)
    }

    @Test
    fun linkLocalOnlyInterfaceIsNotReachable() {
        // DHCP-failed adapter on 169.254.x.x, or Apple AWDL with only fe80::.
        val status = mapJvmInterfaces(listOf(iface("awdl0", hasRoutableAddress = false)))
        assertFalse(status.isReachable)
    }

    @Test
    fun linuxWirelessNamesClassifyAsWifi() {
        for (name in listOf("wlan0", "wlp3s0", "wlx001122334455")) {
            val status = mapJvmInterfaces(listOf(iface(name)))
            assertTrue(status.isReachable, name)
            assertEquals(Transport.Wifi, status.transport, name)
        }
    }

    @Test
    fun windowsAdapterDisplayNameClassifiesAsWifi() {
        // The JDK synthesises generic names on Windows; the vendor string
        // lives in displayName.
        val status = mapJvmInterfaces(listOf(iface("net4", displayName = "Intel(R) Wi-Fi 6 AX201 160MHz")))
        assertEquals(Transport.Wifi, status.transport)
    }

    @Test
    fun wiredNamesClassifyAsEthernet() {
        for (name in listOf("eth0", "enp3s0", "eno1", "ens33", "enx0a1b2c3d4e5f", "em1")) {
            val status = mapJvmInterfaces(listOf(iface(name)))
            assertEquals(Transport.Ethernet, status.transport, name)
        }
    }

    @Test
    fun cellularNamesClassifyAsCellular() {
        for (name in listOf("wwan0", "wwp0s20f0u6", "rmnet0", "ppp0")) {
            val status = mapJvmInterfaces(listOf(iface(name)))
            assertEquals(Transport.Cellular, status.transport, name)
        }
    }

    @Test
    fun macOSAmbiguousEnNamesClassifyAsOther() {
        // en0 is Wi-Fi on MacBooks and wired on desktop Macs; the JDK can't
        // tell, so the mapping must not guess.
        val status = mapJvmInterfaces(listOf(iface("en0")))
        assertTrue(status.isReachable)
        assertEquals(Transport.Other, status.transport)
    }

    @Test
    fun vpnTunnelCountsAsReachableOther() {
        val status = mapJvmInterfaces(listOf(iface("utun2")))
        assertTrue(status.isReachable)
        assertEquals(Transport.Other, status.transport)
    }

    @Test
    fun hostOnlyBridgesAreExcluded() {
        for (name in listOf("docker0", "veth1a2b3c", "br-4d5e6f", "virbr0", "vmnet8", "vboxnet0", "bridge100")) {
            val status = mapJvmInterfaces(listOf(iface(name)))
            assertFalse(status.isReachable, name)
            assertEquals(Transport.None, status.transport, name)
        }
    }

    @Test
    fun bridgeExclusionStillCountsRealInterfaces() {
        val status = mapJvmInterfaces(listOf(iface("docker0"), iface("eth0")))
        assertTrue(status.isReachable)
        assertEquals(Transport.Ethernet, status.transport)
    }

    @Test
    fun transportPriorityPrefersWifiThenEthernetThenCellular() {
        val wifiAndEthernet = mapJvmInterfaces(listOf(iface("enp3s0"), iface("wlan0")))
        assertEquals(Transport.Wifi, wifiAndEthernet.transport)

        val ethernetAndCellular = mapJvmInterfaces(listOf(iface("wwan0"), iface("eth0")))
        assertEquals(Transport.Ethernet, ethernetAndCellular.transport)
    }

    @Test
    fun dataMeteredIsAlwaysFalseEvenOnCellular() {
        // The JDK exposes no metering signal — documented contract.
        val status = mapJvmInterfaces(listOf(iface("wwan0")))
        assertEquals(Transport.Cellular, status.transport)
        assertFalse(status.isDataMetered)
    }
}
