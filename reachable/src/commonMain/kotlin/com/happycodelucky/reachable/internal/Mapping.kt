/*
 * Reachable — pure mapping helpers from platform primitives to ReachabilityStatus.
 *
 * Living in commonMain (not appleMain / androidMain) so they're unit-testable
 * without a simulator or Robolectric. The platform classes flatten their
 * native types (`nw_path_t`, `NetworkCapabilities`) to primitive booleans
 * and call into here.
 */
package com.happycodelucky.reachable.internal

import com.happycodelucky.reachable.Metering
import com.happycodelucky.reachable.ReachabilityStatus
import com.happycodelucky.reachable.Transport

/**
 * Pick the canonical [Transport] from a set of "is this interface in use"
 * booleans. Apple paths and Android `NetworkCapabilities` can both report
 * multiple transports active at once (notably VPN-over-Wi-Fi); the priority
 * `Wifi > Ethernet > Cellular > Other` matches what most consumer-facing UIs
 * want to display.
 *
 * If [reachable] is `false`, returns [Transport.None] regardless of the
 * interface flags — having an interface light up while the path is
 * unsatisfied indicates a transient state we treat as "no connection."
 */
internal fun pickTransport(
    reachable: Boolean,
    wifi: Boolean,
    ethernet: Boolean,
    cellular: Boolean,
    other: Boolean,
): Transport =
    when {
        !reachable -> Transport.None
        wifi -> Transport.Wifi
        ethernet -> Transport.Ethernet
        cellular -> Transport.Cellular
        other -> Transport.Other
        else -> Transport.Other
    }

/**
 * Map an Apple `nw_path_t`, projected to primitive booleans, to a
 * [ReachabilityStatus]. The caller has already invoked
 * `nw_path_get_status`, `nw_path_uses_interface_type`,
 * `nw_path_is_constrained`, and `nw_path_is_expensive`.
 *
 * @param satisfied `nw_path_get_status(path) == nw_path_status_satisfied`
 * @param wifi `nw_path_uses_interface_type(path, nw_interface_type_wifi)`
 * @param ethernet `nw_path_uses_interface_type(path, nw_interface_type_wired_ethernet)`
 * @param cellular `nw_path_uses_interface_type(path, nw_interface_type_cellular)`
 * @param other `nw_path_uses_interface_type(path, nw_interface_type_other)`
 * @param expensive `nw_path_is_expensive(path)` — cellular or hotspot
 * @param constrained `nw_path_is_constrained(path)` — Low Data Mode active
 */
internal fun mapApplePath(
    satisfied: Boolean,
    wifi: Boolean,
    ethernet: Boolean,
    cellular: Boolean,
    other: Boolean,
    expensive: Boolean,
    constrained: Boolean,
): ReachabilityStatus {
    val transport =
        pickTransport(
            reachable = satisfied,
            wifi = wifi,
            ethernet = ethernet,
            cellular = cellular,
            other = other,
        )
    val metering =
        when {
            !satisfied -> Metering.Unmetered
            constrained -> Metering.Constrained
            expensive -> Metering.Metered
            else -> Metering.Unmetered
        }
    return ReachabilityStatus(reachable = satisfied, transport = transport, metering = metering)
}

/**
 * Map Android `NetworkCapabilities` flags to a [ReachabilityStatus]. The
 * caller has already invoked `hasCapability(...)` and `hasTransport(...)`.
 *
 * Notably, [reachable] is `true` only when **both** `NET_CAPABILITY_INTERNET`
 * and `NET_CAPABILITY_VALIDATED` are present. The bare `INTERNET` capability
 * means "the network claims to provide internet"; only `VALIDATED` means
 * Android's connectivity probe has actually reached a public endpoint.
 *
 * [Metering.Constrained] is never emitted on Android; the platform has no
 * direct equivalent of Apple's Low Data Mode signal.
 *
 * @param hasInternet `caps.hasCapability(NET_CAPABILITY_INTERNET)`
 * @param hasValidated `caps.hasCapability(NET_CAPABILITY_VALIDATED)`
 * @param hasWifi `caps.hasTransport(TRANSPORT_WIFI)`
 * @param hasEthernet `caps.hasTransport(TRANSPORT_ETHERNET)`
 * @param hasCellular `caps.hasTransport(TRANSPORT_CELLULAR)`
 * @param notMetered `caps.hasCapability(NET_CAPABILITY_NOT_METERED)`
 * @param temporarilyNotMetered `caps.hasCapability(NET_CAPABILITY_TEMPORARILY_NOT_METERED)`
 */
internal fun mapAndroidCapabilities(
    hasInternet: Boolean,
    hasValidated: Boolean,
    hasWifi: Boolean,
    hasEthernet: Boolean,
    hasCellular: Boolean,
    notMetered: Boolean,
    temporarilyNotMetered: Boolean,
): ReachabilityStatus {
    val reachable = hasInternet && hasValidated
    // Android exposes other transports (VPN, USB, etc.) but we collapse them
    // into Transport.Other only when none of the named transports are active.
    // VPN-over-Wi-Fi resolves to Wifi here, which is what consumers want.
    val transport =
        pickTransport(
            reachable = reachable,
            wifi = hasWifi,
            ethernet = hasEthernet,
            cellular = hasCellular,
            other = false,
        )
    val metering =
        if (notMetered || temporarilyNotMetered) Metering.Unmetered else Metering.Metered
    return ReachabilityStatus(reachable = reachable, transport = transport, metering = metering)
}
