/*
 * Reachable — pure-data snapshot of one JVM network interface.
 *
 * Sits between the impure projection in `JvmReachability` (which reads
 * `java.net.NetworkInterface`) and the pure mapping in `Mapping.jvm.kt`.
 */
package com.happycodelucky.reachable.internal

/**
 * Pure-data projection of one `java.net.NetworkInterface`, captured at poll
 * time. Holding primitives instead of the live JDK object keeps
 * [mapJvmInterfaces] deterministic — `NetworkInterface.isUp` can throw
 * `SocketException` if the interface vanishes mid-read, so the impure
 * projection happens once, in `JvmReachability`.
 *
 * @property name OS-level interface name (`wlan0`, `en0`, `eth0`, …). On
 * Windows the JDK synthesises Unix-style names (`eth0`, `wlan0`, `ppp0`).
 * @property displayName Human-readable adapter name. Mostly equals [name] on
 * macOS and Linux; on Windows it carries the vendor string
 * (`Intel(R) Wi-Fi 6 AX201 160MHz`), which is why classification checks both.
 * @property isUp `NetworkInterface.isUp` — administratively up and running.
 * @property isLoopback `NetworkInterface.isLoopback`.
 * @property hasRoutableAddress `true` when at least one bound address is not
 * loopback, link-local, or wildcard. Filters out interfaces that are "up" but
 * unusable — a DHCP-failed adapter squatting on 169.254.x.x, or Apple's
 * AWDL links which only ever hold fe80:: addresses.
 */
internal data class JvmNetworkInterface(
    val name: String,
    val displayName: String,
    val isUp: Boolean,
    val isLoopback: Boolean,
    val hasRoutableAddress: Boolean,
)
