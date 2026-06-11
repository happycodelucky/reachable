/*
 * Reachable — Apple implementation (iOS / macOS).
 *
 * Wraps Apple's Network framework `nw_path_monitor` C API. Lives in
 * `appleMain` because iOS and macOS share `platform.Network.*` cinterop
 * bindings 1:1 (the framework is identical on both platforms; only the
 * deployment target differs).
 *
 * Reference signatures verified for Kotlin 2.3.20 / kotlinx-coroutines 1.10.2:
 *   - nw_path_monitor_create() -> nw_path_monitor_t? (nullable; nil on alloc failure)
 *   - nw_path_monitor_set_queue(monitor, dispatch_queue_t)
 *   - nw_path_monitor_set_update_handler(monitor) { path: nw_path_t? -> Unit }
 *   - nw_path_monitor_start(monitor)
 *   - nw_path_monitor_cancel(monitor)
 *   - nw_path_get_status(path) -> UInt   (compared against nw_path_status_satisfied etc.)
 *   - nw_path_uses_interface_type(path, nw_interface_type_*) -> Boolean
 *   - nw_path_is_expensive(path) / nw_path_is_constrained(path) -> Boolean
 * Memory: nw_* objects are ARC-managed via the K/N Objective-C runtime
 * integration; no explicit release is required.
 */

// `StateFlowReachability` is `@TestingOnly` to keep its constructor out of
// production consumer reach — but `:reachable`'s own platform subclasses are
// the original consumers. Opt in at the file level so the inheritance is
// allowed; the opt-in does not leak (`AppleReachability` itself is `internal`).
@file:OptIn(ExperimentalForeignApi::class, TestingOnly::class)

package com.happycodelucky.reachable

import com.happycodelucky.reachable.internal.StateFlowReachability
import com.happycodelucky.reachable.internal.mapApplePath
import kotlinx.cinterop.ExperimentalForeignApi
import platform.Network.nw_interface_type_cellular
import platform.Network.nw_interface_type_other
import platform.Network.nw_interface_type_wifi
import platform.Network.nw_interface_type_wired
import platform.Network.nw_path_get_status
import platform.Network.nw_path_is_constrained
import platform.Network.nw_path_is_expensive
import platform.Network.nw_path_monitor_cancel
import platform.Network.nw_path_monitor_create
import platform.Network.nw_path_monitor_set_queue
import platform.Network.nw_path_monitor_set_update_handler
import platform.Network.nw_path_monitor_start
import platform.Network.nw_path_status_satisfied
import platform.Network.nw_path_uses_interface_type
import platform.darwin.dispatch_queue_create

/**
 * Apple-side `Reachability` over `nw_path_monitor`.
 *
 * Constructor side effects: creates a serial dispatch queue, creates the
 * monitor, registers an update handler that maps each `nw_path_t` to a
 * [ReachabilityStatus] via [mapApplePath] and pushes it to the [status]
 * StateFlow, then starts the monitor.
 *
 * The first emission lands on the dispatch queue shortly after construction
 * — typically within tens of milliseconds. Until then, `status.value`
 * returns [ReachabilityStatus.Unknown] from the base class.
 */
internal class AppleReachability : StateFlowReachability() {
    /**
     * Per-instance serial dispatch queue. Serial (the `null` second argument
     * to `dispatch_queue_create`) so update callbacks arrive in causal
     * order and `MutableStateFlow.value` writes don't race.
     */
    private val queue = dispatch_queue_create("dev.reachable.monitor", null)

    /**
     * The underlying `nw_path_monitor_t`. Nullable because
     * `nw_path_monitor_create()` returns nil on allocation failure (rare,
     * but documented). When null, [emit] is never called and `status.value`
     * stays at [ReachabilityStatus.Unknown] — a degraded but safe outcome.
     */
    private val monitor = nw_path_monitor_create()

    init {
        monitor?.let { m ->
            nw_path_monitor_set_queue(m, queue)
            nw_path_monitor_set_update_handler(m) { path ->
                // path is `nw_path_t?` — Apple guarantees a non-null path in
                // practice, but the cinterop signature is nullable. Skip the
                // (never-observed) null case rather than reach for `!!`
                // (CLAUDE.md §13: "No `!!`").
                path?.let { p -> publish(toStatus(p)) }
            }
            nw_path_monitor_start(m)
        }
    }

    override fun onClose() {
        monitor?.let { nw_path_monitor_cancel(it) }
    }

    /**
     * Project an `nw_path_t` to primitive booleans and delegate to the pure
     * mapping helper. Keeping the cinterop calls in one short function
     * makes the rest of the class platform-agnostic and the mapping
     * itself unit-testable from `commonTest`.
     */
    private fun toStatus(path: platform.Network.nw_path_t): ReachabilityStatus {
        val satisfied = nw_path_get_status(path) == nw_path_status_satisfied
        // Wired Ethernet is `nw_interface_type_wired` in Apple's headers (and
        // the K/N cinterop binding) — there is no `…_wired_ethernet` constant.
        return mapApplePath(
            satisfied = satisfied,
            wifi = nw_path_uses_interface_type(path, nw_interface_type_wifi),
            ethernet = nw_path_uses_interface_type(path, nw_interface_type_wired),
            cellular = nw_path_uses_interface_type(path, nw_interface_type_cellular),
            other = nw_path_uses_interface_type(path, nw_interface_type_other),
            expensive = nw_path_is_expensive(path),
            constrained = nw_path_is_constrained(path),
        )
    }
}
