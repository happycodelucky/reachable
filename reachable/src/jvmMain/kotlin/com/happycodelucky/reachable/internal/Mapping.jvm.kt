/*
 * Reachable — pure mapping from JVM network-interface snapshots to ReachabilityStatus.
 *
 * The JDK exposes no connectivity-change callback, no validation probe, and no
 * metering signal — `java.net.NetworkInterface` link state, names, and
 * addresses are all there is. `JvmReachability` projects each interface to a
 * [JvmNetworkInterface] snapshot and calls into here, keeping this file pure
 * and unit-testable from jvmTest without touching real sockets (the same
 * split `Mapping.kt` gives the Apple and Android backends).
 */
package com.happycodelucky.reachable.internal

import com.happycodelucky.reachable.ReachabilityStatus
import com.happycodelucky.reachable.Transport

/**
 * Interface-name prefixes for host-only bridges created by container and
 * hypervisor runtimes (Docker, libvirt, VMware, VirtualBox, macOS internet
 * sharing). These sit "up" with a routable private address even when the
 * machine has no WAN link at all, so counting them would report a
 * Docker-running laptop as reachable in airplane mode. Real VPN tunnels
 * (`utun*`, `tun*`, `tailscale*`) are deliberately *not* listed — an active
 * tunnel is genuine connectivity.
 */
private val hostOnlyBridgePrefixes = listOf("docker", "veth", "br-", "virbr", "vmnet", "vboxnet", "bridge")

// Name prefixes are matched against `name`, keywords against both `name` and
// `displayName`, all lowercased. Sources: Linux predictable interface naming
// (wl*/en*/ww*), classic kernel names (eth*/wlan*/ppp*), the JDK's synthetic
// Windows names, and common Windows adapter vendor strings.
private val wifiNamePrefixes = listOf("wlan", "wl", "ath")
private val wifiKeywords = listOf("wi-fi", "wifi", "wireless", "802.11", "airport")
private val cellularNamePrefixes = listOf("wwan", "wwp", "rmnet", "ppp")
private val cellularKeywords = listOf("cellular", "mobile broadband", "wwan")
private val ethernetNamePrefixes = listOf("eth", "enp", "eno", "ens", "enx", "em")
private val ethernetKeywords = listOf("ethernet")

/**
 * Map one poll's interface snapshots to a [ReachabilityStatus].
 *
 * Reachability on the JVM is **best-effort, not validated**: `isReachable` is
 * `true` when at least one interface is up, non-loopback, holds a routable
 * address, and is not a host-only container/hypervisor bridge. There is no
 * JDK equivalent of Android's `NET_CAPABILITY_VALIDATED` probe or Apple's
 * `nw_path_status_satisfied`, so a captive portal or DNS blackhole still
 * reports `true` here. Consumers who need proof of a working path must make
 * a real request and treat its failure as the signal.
 *
 * `isDataMetered` is always `false`: the JDK has no metering signal.
 */
internal fun mapJvmInterfaces(interfaces: List<JvmNetworkInterface>): ReachabilityStatus {
    val usable =
        interfaces.filter { nif ->
            nif.isUp &&
                !nif.isLoopback &&
                nif.hasRoutableAddress &&
                hostOnlyBridgePrefixes.none { prefix -> nif.name.lowercase().startsWith(prefix) }
        }
    val reachable = usable.isNotEmpty()
    val transports = usable.map(::classifyTransport)
    return ReachabilityStatus(
        isReachable = reachable,
        transport =
            pickTransport(
                reachable = reachable,
                wifi = Transport.Wifi in transports,
                ethernet = Transport.Ethernet in transports,
                cellular = Transport.Cellular in transports,
                other = Transport.Other in transports,
            ),
        isDataMetered = false,
    )
}

/**
 * Best-effort transport classification from interface naming. Checked in
 * Wi-Fi → cellular → Ethernet order so a `wlan0` never falls through to a
 * broader Ethernet pattern. Anything unrecognised — notably macOS's `en0`,
 * which is Wi-Fi on laptops and wired on desktops with no way to tell from
 * the JDK — honestly reports [Transport.Other].
 */
private fun classifyTransport(nif: JvmNetworkInterface): Transport {
    val name = nif.name.lowercase()
    val labels = listOf(name, nif.displayName.lowercase())
    return when {
        wifiNamePrefixes.any(name::startsWith) ||
            wifiKeywords.any { keyword -> labels.any { label -> keyword in label } } -> Transport.Wifi

        cellularNamePrefixes.any(name::startsWith) ||
            cellularKeywords.any { keyword -> labels.any { label -> keyword in label } } -> Transport.Cellular

        ethernetNamePrefixes.any(name::startsWith) ||
            ethernetKeywords.any { keyword -> labels.any { label -> keyword in label } } -> Transport.Ethernet

        else -> Transport.Other
    }
}
