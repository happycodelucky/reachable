/*
 * Reachable — public API surface (CLAUDE.md §8).
 *
 * Designed for Swift consumers as much as Kotlin ones: the enums become
 * exhaustive Swift enums via SKIE, `data class` becomes a Swift struct-like
 * value type, and `AutoCloseable.close()` reads natively in both languages.
 */
package com.happycodelucky.reachable

import com.happycodelucky.reachable.internal.SharedReachabilityHolder
import kotlinx.coroutines.flow.StateFlow

/**
 * Which physical / virtual interface is carrying the active connection.
 *
 * Maps from Apple's `nw_interface_type_*` enum and Android's
 * `NetworkCapabilities.TRANSPORT_*` flags. A path can use multiple interfaces
 * simultaneously (VPN over Wi-Fi, for example); both platforms collapse to
 * the highest-quality transport using the priority
 * `WIFI > ETHERNET > CELLULAR > OTHER`.
 *
 * [NONE] is reported when [ReachabilityStatus.reachable] is `false`, or when
 * the platform reports a usable path with an unrecognised interface type.
 */
public enum class Transport {
    /** 802.11 Wi-Fi. */
    Wifi,

    /** Cellular (3G / 4G / 5G), or a personal hotspot tethered over cellular. */
    Cellular,

    /** Wired Ethernet, including USB/Thunderbolt Ethernet adapters on Apple. */
    Ethernet,

    /** Anything reachable but not Wi-Fi / cellular / Ethernet (loopback, virtual, unknown). */
    Other,

    /** No usable transport — paired with `reachable = false`. */
    None,
}

/**
 * Whether the active path is metered, and how aggressively the user wants to
 * limit data usage on it.
 *
 * Apple's Network framework distinguishes "expensive" (cellular / hotspot) from
 * "constrained" (the user has Low Data Mode active for this interface). Android
 * distinguishes only metered / unmetered via
 * `NET_CAPABILITY_NOT_METERED` and `NET_CAPABILITY_TEMPORARILY_NOT_METERED`,
 * with no first-class equivalent of Low Data Mode.
 *
 * Consequence: on Android, [CONSTRAINED] is **never** emitted. Treating it as
 * a stricter form of [METERED] is the right interpretation on Apple.
 */
public enum class Metering {
    /** Unlimited data; prefetching, background sync, autoplay are all fine. */
    Unmetered,

    /** Cellular or hotspot; consider deferring large transfers to Wi-Fi. */
    Metered,

    /**
     * Apple-only: user has enabled Low Data Mode for this interface. Apps
     * should aggressively defer non-essential traffic. Never emitted on Android.
     */
    Constrained, ;

    /**
     * Does the metering represent a low data mode recommendation
     */
    val lowDataMode: Boolean
        get() = this == Metered || this == Constrained
}

/**
 * A snapshot of network reachability at a single point in time.
 *
 * Composition over a sealed hierarchy: the three axes (reachable / transport /
 * metering) are independent, and exhaustive matching on each axis is cheap via
 * SKIE-generated Swift enums. See [ReachabilityStatus.Unknown] for the
 * pre-observation seed value.
 *
 * @property reachable Whether the device has a path to the public internet that
 * has been *validated* (Android `NET_CAPABILITY_VALIDATED`) or is reported as
 * `nw_path_status_satisfied` (Apple). Bare "interface up" is **not** sufficient
 * — captive portals and DNS holes will set this to `false`.
 * @property transport Which interface is carrying the active connection. See
 * [Transport].
 * @property metering Whether the active path is metered. See [Metering].
 */
public data class ReachabilityStatus(
    val reachable: Boolean,
    val transport: Transport,
    val metering: Metering,
) {
    public companion object {
        /**
         * Pre-observation seed value. Emitted exactly once at the start of
         * [Reachability.status], before either platform has reported a real
         * reading. Equivalent to "we don't know yet, assume offline."
         */
        public val Unknown: ReachabilityStatus =
            ReachabilityStatus(reachable = false, transport = Transport.None, metering = Metering.Unmetered)
    }
}

/**
 * Observable reachability handle — the only public type a consumer interacts
 * with.
 *
 * ### Reading the current value (synchronous)
 *
 * ```kotlin
 * val now: ReachabilityStatus = reachability.status.value
 * ```
 *
 * From Swift, the same field reads through the SKIE-generated `StateFlow`
 * wrapper:
 *
 * ```swift
 * let now = reachability.status.value
 * ```
 *
 * ### Reacting to changes (Flow / AsyncSequence)
 *
 * From Kotlin (Android, shared code):
 *
 * ```kotlin
 * reachability.status.collect { status -> /* ... */ }
 * ```
 *
 * From SwiftUI:
 *
 * ```swift
 * .task {
 *     for await status in reachability.status {
 *         self.online = status.reachable
 *     }
 * }
 * ```
 *
 * ### Lifecycle
 *
 * The recommended entry point is [Reachability.shared] — a process-lifetime
 * singleton that requires no construction or Context plumbing. See
 * [companion object][Reachability.Companion.shared] for semantics.
 *
 * For explicit-lifecycle use (tests, per-feature observers), use the
 * platform factories: `Reachability()` in `appleMain` (no arguments) and
 * `Reachability(context:)` in `androidMain` (takes an Android `Context`).
 * Call [close] when the owning scope tears down. [close] is idempotent.
 */
public interface Reachability : AutoCloseable {
    /**
     * The always-current reachability state. Emits [ReachabilityStatus.Unknown]
     * on first collection, then the live state as soon as the platform
     * reports it (typically within a few hundred milliseconds of construction).
     *
     * `StateFlow` semantics mean multiple collectors share one underlying
     * platform observer, and a late-joining collector immediately receives
     * the most recent value.
     */
    public val status: StateFlow<ReachabilityStatus>

    /**
     * Convenience shortcut for `status.value.reachable`. Reads the current
     * reachability boolean synchronously without unpacking the full
     * [ReachabilityStatus]. Equivalent to:
     *
     * ```kotlin
     * reachability.status.value.reachable
     * ```
     *
     * For a reactive listener, prefer [reachable] (the `StateFlow<Boolean>`
     * variant) so multiple emissions of the same value are conflated.
     */
    public val isReachable: Boolean

    /**
     * Convenience shortcut for `status.value.metering == Metering.Constrained`.
     * On Apple, `true` means the user has Low Data Mode active for the
     * current path; on Android this is **always** `false` (the platform has
     * no equivalent capability — see [Metering] for the rationale).
     *
     * For a reactive listener, prefer [lowDataMode].
     */
    public val isLowDataMode: Boolean

    /**
     * Reactive variant of [isReachable]: emits the current reachability
     * boolean, then a fresh emission whenever it changes. Built off [status]
     * via `.map { it.reachable }`, so transport / metering churn that
     * doesn't change the reachable axis is collapsed (StateFlow conflation).
     *
     * Cheaper than wiring your own `status.map { it.reachable }
     * .distinctUntilChanged()` because the flow is shared across all
     * collectors and started eagerly at construction time.
     */
    public val reachable: StateFlow<Boolean>

    /**
     * Reactive variant of [isLowDataMode]: emits `true` when the path is
     * constrained (Apple Low Data Mode), `false` otherwise. **Always**
     * `false` on Android — the platform has no equivalent of Low Data Mode.
     */
    public val lowDataMode: StateFlow<Boolean>

    /**
     * Tear down the platform observer (Apple: `nw_path_monitor_cancel`;
     * Android: `unregisterNetworkCallback`) and cancel the internal coroutine
     * scope. Idempotent; subsequent calls are no-ops.
     *
     * After [close], [status] continues to expose its last value but will
     * never emit again.
     *
     * Renames to `close()` in Swift via SKIE without an explicit `@ObjCName`
     * — the method name already requires no mangling.
     */
    override fun close()

    public companion object {
        /**
         * Process-lifetime singleton handle. The recommended entry point
         * for most consumers — eliminates "did I construct Reachability
         * early enough?" bugs by being callable from anywhere at any
         * time, including before `Application.onCreate` on Android.
         *
         * **Apple (iOS / macOS)**: on first access, constructs an
         * [Reachability] backed by `nw_path_monitor` and starts observing
         * eagerly. Subsequent accesses return the same instance. There is
         * no Context dependency on Apple — the monitor is self-contained.
         *
         * **Android**: on first access, returns an unattached instance
         * whose [status] is [ReachabilityStatus.Unknown]. The library's
         * bundled `androidx.startup` initializer attaches it to the
         * application Context during the `InitializationProvider`
         * ContentProvider pass — *before* `Application.onCreate`. Once
         * attached, the instance begins emitting real
         * [ReachabilityStatus] values.
         *
         * Collectors started before attach receive [ReachabilityStatus.Unknown]
         * as their first value and then the live state as soon as the
         * platform reports it. `StateFlow` semantics guarantee a
         * late-joining collector immediately receives the most recent
         * value, so there is no race between subscribing and the platform
         * coming online.
         *
         * Calling [close] on this instance is an intentional **no-op** —
         * the singleton's lifetime is the process, and the OS reaps the
         * platform observer at process exit on both platforms. Code that
         * needs explicit lifecycle should use the `Reachability(context)`
         * / `Reachability()` top-level factories instead, which return a
         * fresh observer per call and honour [close] normally.
         *
         * From Swift, renders as `Reachability.shared` via SKIE's
         * interface-companion bridging.
         */
        public val shared: Reachability
            get() = SharedReachabilityHolder.instance
    }
}
