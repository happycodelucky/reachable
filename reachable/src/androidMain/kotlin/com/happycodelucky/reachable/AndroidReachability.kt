/*
 * Reachable — Android implementation.
 *
 * Wraps `ConnectivityManager.NetworkCallback` registered against a
 * NetworkRequest that requires both NET_CAPABILITY_INTERNET and
 * NET_CAPABILITY_VALIDATED. The `VALIDATED` capability is the only
 * trustworthy "real internet reachable" signal — bare `onAvailable()` will
 * fire for captive-portal Wi-Fi and DNS-blackholed networks too.
 *
 * The class supports two construction paths:
 *
 *   1. `AndroidReachability(context)` — the today/legacy path used by the
 *      public `Reachability(context)` factory. The secondary constructor
 *      delegates to the zero-arg primary and immediately calls [attach].
 *
 *   2. `AndroidReachability()` — the deferred path used by
 *      [com.happycodelucky.reachable.Reachability.shared]. Returns an
 *      instance whose `status.value == ReachabilityStatus.Unknown` until
 *      [attach] is called (typically by the bundled
 *      [com.happycodelucky.reachable.ReachabilityInitializer] during the
 *      `androidx.startup` ContentProvider pass, before
 *      `Application.onCreate`). Splitting construction from attach removes
 *      the "did the consumer build Reachability early enough?" footgun.
 *
 * Min-SDK 30 (CLAUDE.md §1, gradle/libs.versions.toml). NetworkCallback,
 * NetworkRequest, NET_CAPABILITY_VALIDATED, and the `getSystemService(Class)`
 * overload are all available on API 23+, so no version checks are needed.
 */

// `StateFlowReachability` is `@TestingOnly` to keep its constructor out of
// production consumer reach — but `:reachable`'s own platform subclasses are
// the original consumers. Opt in at the file level so the inheritance is
// allowed; the opt-in does not leak (`AndroidReachability` itself is `internal`).
@file:OptIn(TestingOnly::class)

package com.happycodelucky.reachable

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import com.happycodelucky.reachable.internal.StateFlowReachability
import com.happycodelucky.reachable.internal.mapAndroidCapabilities
import kotlinx.atomicfu.atomic

/**
 * Android-side `Reachability` over `ConnectivityManager.NetworkCallback`.
 *
 * **Lifecycle in two phases.** Constructor is side-effect-free with respect
 * to the platform: it only builds the [NetworkRequest] and the
 * [ConnectivityManager.NetworkCallback], neither of which touches the OS.
 * The actual registration happens in [attach], which can be called once,
 * later, when a [Context] is available.
 *
 * The `AndroidReachability(context)` secondary constructor preserves the
 * legacy "construct and observe in one step" call path used by the public
 * `Reachability(context)` factory.
 *
 * Permission: requires `android.permission.ACCESS_NETWORK_STATE`. Declared
 * in the library's `AndroidManifest.xml` so it merges into consumer apps;
 * no runtime grant needed (it's a normal-protection permission).
 */
internal class AndroidReachability internal constructor() : StateFlowReachability() {
    /**
     * Single-shot latch. First [attach] sets it to `true` via CAS; subsequent
     * attaches early-return. Uses `kotlinx.atomicfu.atomic` per CLAUDE.md §3
     * (no `volatile`, no `@Synchronized`, no `kotlin.synchronized`).
     */
    private val attached = atomic(false)

    /**
     * Lazily populated by [attach]. Held in atomic references so [onClose]
     * can read them safely even if it races a late [attach]; the references
     * are written before [attached] is observed to flip, and read after
     * the [isClosed] check inside `close()`.
     */
    private val connectivityManagerRef = atomic<ConnectivityManager?>(null)
    private val callbackRef = atomic<ConnectivityManager.NetworkCallback?>(null)

    /**
     * Pure-data; built once at construction. `NetworkRequest.Builder` does
     * not touch the OS, so keeping it Context-free lets [attach] stay tiny.
     */
    private val request: NetworkRequest =
        NetworkRequest
            .Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            .build()

    /**
     * Legacy / explicit-lifecycle path. Behaves identically to the previous
     * `AndroidReachability(context)` constructor: attaches immediately so
     * `status.value` is meaningful as soon as the caller can observe it.
     *
     * @param context Any [Context]. Stored as `applicationContext` to avoid
     * leaking a non-Application context.
     */
    internal constructor(context: Context) : this() {
        attach(context)
    }

    /**
     * Resolve [ConnectivityManager], seed [status] from the active network,
     * and register the [callback]. Idempotent — repeated calls (whether from
     * the secondary constructor, `ReachabilityInitializer`, or both, in any
     * order) are no-ops after the first. No-op if the instance has already
     * been [close]d.
     *
     * Thread-safe via [attached]'s CAS. Single-shot semantics mean no lock
     * is needed.
     *
     * TODO(test): exercise the no-Context construct path and idempotent
     * attach behaviour in an `androidHostTest`. Blocked on adding either
     * Robolectric (heavyweight) or a `ConnectivityManager` wrapper
     * interface so we can mock the system service. The deferred-attach
     * path is currently verified end-to-end by the Android sample app.
     */
    internal fun attach(context: Context) {
        if (isClosed) return
        if (!attached.compareAndSet(expect = false, update = true)) return

        val cm =
            context.applicationContext
                .getSystemService(ConnectivityManager::class.java)
        connectivityManagerRef.value = cm

        val cb = makeCallback()
        callbackRef.value = cb

        seedFromActiveNetwork(cm)
        cm.registerNetworkCallback(request, cb)
    }

    override fun onClose() {
        val cm = connectivityManagerRef.value
        val cb = callbackRef.value
        if (cm != null && cb != null) {
            // Belt-and-braces: `attached` already guarantees we registered at
            // most once, but `unregisterNetworkCallback` documents
            // IllegalArgumentException on a never-registered callback. The
            // base-class contract says `close()` must not throw.
            runCatching { cm.unregisterNetworkCallback(cb) }
        }
    }

    private fun makeCallback(): ConnectivityManager.NetworkCallback =
        object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(
                network: Network,
                capabilities: NetworkCapabilities,
            ) {
                publish(toStatus(capabilities))
            }

            override fun onLost(network: Network) {
                // The capability stream stops without a final "no internet" event,
                // so we synthesise one. If a different network is up, the next
                // `onCapabilitiesChanged` will overwrite this immediately.
                publish(ReachabilityStatus.Unknown)
            }
        }

    /**
     * Synchronous one-shot read of the active network's capabilities, used to
     * seed [status] before the first callback. Falls through to
     * [ReachabilityStatus.Unknown] when there is no active network — the
     * StateFlow's initial value, so no emission is needed in that case.
     */
    private fun seedFromActiveNetwork(cm: ConnectivityManager) {
        val active = cm.activeNetwork ?: return
        val caps = cm.getNetworkCapabilities(active) ?: return
        publish(toStatus(caps))
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
