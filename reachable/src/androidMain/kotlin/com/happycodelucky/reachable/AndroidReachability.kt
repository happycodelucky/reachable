/*
 * Reachable — Android implementation.
 *
 * Wraps `ConnectivityManager.NetworkCallback` registered against a
 * NetworkRequest that requires both NET_CAPABILITY_INTERNET and
 * NET_CAPABILITY_VALIDATED. The `VALIDATED` capability is the only
 * trustworthy "real internet reachable" signal — bare `onAvailable()` will
 * fire for captive-portal Wi-Fi and DNS-blackholed networks too.
 *
 * Min-SDK 30 (CLAUDE.md §1, gradle/libs.versions.toml). NetworkCallback,
 * NetworkRequest, NET_CAPABILITY_VALIDATED, and the `getSystemService(Class)`
 * overload are all available on API 23+, so no version checks are needed.
 */
package com.happycodelucky.reachable

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import com.happycodelucky.reachable.internal.StateFlowReachability
import com.happycodelucky.reachable.internal.mapAndroidCapabilities

/**
 * Android-side `Reachability` over `ConnectivityManager.NetworkCallback`.
 *
 * Constructor side effects: resolves the system [ConnectivityManager],
 * eagerly reads the current network state so [status.value] is meaningful
 * immediately, then registers a [NetworkCallback]. Without the eager read,
 * callers inspecting `status.value` right after construction would see
 * [ReachabilityStatus.Unknown] until the first capability-changed callback
 * fired — which may never happen if the device's connectivity is stable.
 *
 * Permission: requires `android.permission.ACCESS_NETWORK_STATE`. Declared
 * in the library's `AndroidManifest.xml` so it merges into consumer apps;
 * no runtime grant needed (it's a normal-protection permission).
 *
 * @param context Any [Context]. Stored as `applicationContext` to avoid
 * leaking a non-Application context.
 */
internal class AndroidReachability(
    context: Context,
) : StateFlowReachability() {
    private val connectivityManager: ConnectivityManager =
        context.applicationContext.getSystemService(ConnectivityManager::class.java)

    private val request: NetworkRequest =
        NetworkRequest
            .Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            .build()

    private val callback =
        object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(
                network: Network,
                capabilities: NetworkCapabilities,
            ) {
                emit(toStatus(capabilities))
            }

            override fun onLost(network: Network) {
                // The capability stream stops without a final "no internet" event,
                // so we synthesise one. If a different network is up, the next
                // `onCapabilitiesChanged` will overwrite this immediately.
                emit(ReachabilityStatus.Unknown)
            }
        }

    init {
        seedFromActiveNetwork()
        connectivityManager.registerNetworkCallback(request, callback)
    }

    override fun onClose() {
        connectivityManager.unregisterNetworkCallback(callback)
    }

    /**
     * Synchronous one-shot read of the active network's capabilities, used to
     * seed [status] before the first callback. Falls through to
     * [ReachabilityStatus.Unknown] when there is no active network — the
     * StateFlow's initial value, so no emission is needed in that case.
     */
    private fun seedFromActiveNetwork() {
        val active = connectivityManager.activeNetwork ?: return
        val caps = connectivityManager.getNetworkCapabilities(active) ?: return
        emit(toStatus(caps))
    }

    /**
     * Project [NetworkCapabilities] to primitive booleans and delegate to the
     * pure mapping helper. See [mapAndroidCapabilities] for the rules.
     */
    private fun toStatus(caps: NetworkCapabilities): ReachabilityStatus {
        val temporarilyNotMetered =
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_TEMPORARILY_NOT_METERED)
        return mapAndroidCapabilities(
            hasInternet = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET),
            hasValidated = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED),
            hasWifi = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI),
            hasEthernet = caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET),
            hasCellular = caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR),
            notMetered = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED),
            temporarilyNotMetered = temporarilyNotMetered,
        )
    }
}
