/*
 * Reachable — JVM (desktop / server) implementation.
 *
 * The JDK has no connectivity-change callback (nothing like Apple's
 * `nw_path_monitor` or Android's `ConnectivityManager.NetworkCallback`) and
 * no OS validation probe to read, so this backend *polls*
 * `java.net.NetworkInterface` on the base class scope and maps each snapshot
 * via the pure `mapJvmInterfaces`. Two consequences, both documented on the
 * public surface:
 *
 *   - `isReachable` is best-effort "a usable interface is up", not
 *     validated internet. Captive portals are invisible here.
 *   - Changes surface within one poll tick (default 5 seconds), not
 *     within milliseconds.
 *
 * An active probe (HTTP HEAD against a known endpoint) would close the
 * validation gap but ships phone-home traffic in a library by default —
 * deliberately not done. Revisit as an opt-in knob if asked for.
 */

// `StateFlowReachability` is `@TestingOnly` to keep its constructor out of
// production consumer reach — but `:reachable`'s own platform subclasses are
// the original consumers. Opt in at the file level so the inheritance is
// allowed; the opt-in does not leak (`JvmReachability` itself is `internal`).
@file:OptIn(TestingOnly::class)

package com.happycodelucky.reachable

import com.happycodelucky.reachable.internal.JvmNetworkInterface
import com.happycodelucky.reachable.internal.StateFlowReachability
import com.happycodelucky.reachable.internal.mapJvmInterfaces
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.net.NetworkInterface
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Default cadence for the interface poll loop — shared by the public
 * `Reachability(pollInterval:)` factory and the `Reachability.shared`
 * singleton. Enumerating interfaces is a cheap local syscall (no traffic),
 * so 5 seconds buys near-zero overhead while keeping status staleness
 * tolerable for desktop UI.
 */
internal val defaultJvmPollInterval: Duration = 5.seconds

/**
 * JVM-side `Reachability` over polled `java.net.NetworkInterface` snapshots.
 *
 * Constructor side effects: launches the poll loop on the base class [scope].
 * The first poll runs immediately (no initial delay), so the first real
 * emission lands as soon as the dispatcher schedules it — parity with the
 * Apple backend's "within tens of milliseconds". Until then, `status.value`
 * returns [ReachabilityStatus.Unknown] from the base class.
 *
 * [onClose] has nothing platform-side to release — the base class cancels
 * [scope] right after it, which ends the loop at its next suspension point;
 * `publish` is already a no-op by then.
 *
 * @param pollInterval Delay between interface polls. `MutableStateFlow`
 * conflation drops identical successive readings, so a short interval costs
 * syscalls, not emissions.
 * @param interfaceSource Injection seam for tests. Production uses
 * [systemNetworkInterfaces]; jvmTest swaps in a scripted source to drive the
 * loop deterministically.
 */
internal class JvmReachability internal constructor(
    private val pollInterval: Duration = defaultJvmPollInterval,
    private val interfaceSource: () -> List<JvmNetworkInterface> = ::systemNetworkInterfaces,
) : StateFlowReachability() {
    init {
        scope.launch {
            while (isActive) {
                publish(mapJvmInterfaces(interfaceSource()))
                delay(pollInterval)
            }
        }
    }

    override fun onClose() {
        // No platform observer to release: close() cancels the scope right
        // after this hook, which is what stops the poll loop.
    }
}

/**
 * Snapshot every `NetworkInterface` the JDK can see into pure data for
 * [mapJvmInterfaces]. Defensive throughout: enumeration and the per-interface
 * flag reads can throw `SocketException` when an interface disappears
 * mid-poll (dock unplugged, VPN dropped), in which case the affected
 * interface degrades to "not usable" and the poll as a whole to "no
 * interfaces" — i.e. not reachable — rather than crashing the loop.
 */
internal fun systemNetworkInterfaces(): List<JvmNetworkInterface> =
    runCatching {
        NetworkInterface
            .getNetworkInterfaces()
            ?.toList()
            .orEmpty()
            .map { nif -> nif.toSnapshot() }
    }.getOrDefault(emptyList())

private fun NetworkInterface.toSnapshot(): JvmNetworkInterface =
    JvmNetworkInterface(
        name = name.orEmpty(),
        displayName = displayName.orEmpty(),
        isUp = runCatching { isUp }.getOrDefault(false),
        isLoopback = runCatching { isLoopback }.getOrDefault(true),
        hasRoutableAddress =
            inetAddresses
                ?.toList()
                .orEmpty()
                .any { address ->
                    !address.isLoopbackAddress && !address.isLinkLocalAddress && !address.isAnyLocalAddress
                },
    )
