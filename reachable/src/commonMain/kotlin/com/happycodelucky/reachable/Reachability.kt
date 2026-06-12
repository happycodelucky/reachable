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
import kotlin.experimental.ExperimentalObjCName
import kotlin.native.ObjCName

/**
 * Opt-in marker for the small `Reachability.shared` override surface used
 * exclusively by the `:reachable-testing` module
 * ([com.happycodelucky.reachable.testing.FakeReachability] and
 * [com.happycodelucky.reachable.testing.withFakeReachability]).
 *
 * Direct callers of [Reachability.installForTesting], constructors of
 * [com.happycodelucky.reachable.internal.StateFlowReachability], or holders
 * of a [TestingOverrideHandle] must `@OptIn(TestingOnly::class)`. Without
 * the opt-in the call is a compile-time `ERROR` — the strongest gate
 * `@RequiresOptIn` supports — so production code cannot silently start
 * depending on these affordances.
 *
 * Test code reaches them indirectly through `:reachable-testing`'s
 * public helpers, which already file-level-`@OptIn(TestingOnly::class)`.
 * Consumers wiring `testImplementation` on `reachable-testing` never see
 * this annotation themselves.
 */
@RequiresOptIn(
    message =
        "Test-only API. Use :reachable-testing's withFakeReachability { … } " +
            "or FakeReachability instead of touching this directly.",
    level = RequiresOptIn.Level.ERROR,
)
@Retention(AnnotationRetention.BINARY)
@MustBeDocumented
public annotation class TestingOnly

/**
 * Which physical / virtual interface is carrying the active connection.
 *
 * Maps from Apple's `nw_interface_type_*` enum and Android's
 * `NetworkCapabilities.TRANSPORT_*` flags. A path can use multiple interfaces
 * simultaneously (VPN over Wi-Fi, for example); all platforms collapse to
 * the highest-quality transport using the priority
 * `WIFI > ETHERNET > CELLULAR > OTHER`.
 *
 * On the JVM (desktop / server) the OS exposes no transport type, so the
 * value is inferred from interface naming — best-effort. Interfaces the
 * heuristic can't classify (notably macOS's `en0`, which may be Wi-Fi or
 * wired) report [Other].
 *
 * [None] is reported when [ReachabilityStatus.isReachable] is `false`. A
 * usable path with an unrecognised interface type reports [Other].
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
 * A snapshot of network reachability at a single point in time.
 *
 * Composition over a sealed hierarchy: the axes (reachable / transport /
 * data-metered) are independent, and exhaustive matching on the enum axis is
 * cheap via SKIE-generated Swift enums. See [ReachabilityStatus.Unknown] for
 * the pre-observation seed value.
 *
 * @property isReachable Whether the device has a path to the public internet
 * that has been *validated* (Android `NET_CAPABILITY_VALIDATED`) or is reported
 * as `nw_path_status_satisfied` (Apple). Bare "interface up" is **not**
 * sufficient — captive portals and DNS holes will set this to `false`.
 * Exception: the JVM offers no validation probe, so on desktop / server JVMs
 * this *is* best-effort interface-up detection (a non-loopback interface with
 * a routable address) — captive portals cannot be detected there.
 * @property transport Which interface is carrying the active connection. See
 * [Transport].
 * @property isDataMetered `true` when the active path is reported as metered
 * by the platform — the consumer should consider deferring large or
 * non-essential transfers. On Apple this is
 * `nw_path_is_expensive(path) || nw_path_is_constrained(path)`, so the Low
 * Data Mode signal folds into this single bit. On Android it is the negation
 * of `NET_CAPABILITY_NOT_METERED || NET_CAPABILITY_TEMPORARILY_NOT_METERED`.
 * The JVM has no metering signal; there it is always `false`.
 */
public data class ReachabilityStatus(
    val isReachable: Boolean,
    val transport: Transport,
    val isDataMetered: Boolean,
) {
    public companion object {
        /**
         * Pre-observation seed value. Emitted exactly once at the start of
         * [Reachability.status], before either platform has reported a real
         * reading. Equivalent to "we don't know yet, assume offline."
         */
        public val Unknown: ReachabilityStatus =
            ReachabilityStatus(isReachable = false, transport = Transport.None, isDataMetered = false)
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
 *         self.online = status.isReachable
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
 * platform factories: `Reachability()` in `appleMain` (no arguments),
 * `Reachability(context:)` in `androidMain` (takes an Android `Context`),
 * and `Reachability(pollInterval:)` in `jvmMain` (optional poll cadence).
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
     * Convenience shortcut for `status.value.isReachable`. Reads the current
     * reachability boolean synchronously without unpacking the full
     * [ReachabilityStatus]. Equivalent to:
     *
     * ```kotlin
     * reachability.status.value.isReachable
     * ```
     *
     * For a reactive listener, prefer [reachable] (the `StateFlow<Boolean>`
     * variant) so multiple emissions of the same value are conflated.
     */
    public val isReachable: Boolean

    /**
     * Convenience shortcut for `status.value.isDataMetered`. `true` when the
     * platform reports the active path as metered — cellular, hotspot, or
     * Apple Low Data Mode on iOS / macOS. The consumer should consider
     * deferring large or non-essential transfers.
     *
     * For a reactive listener, prefer [dataMetered].
     */
    public val isDataMetered: Boolean

    /**
     * Reactive variant of [isReachable]: emits the current reachability
     * boolean, then a fresh emission whenever it changes. Transport /
     * metering churn that doesn't change the reachable axis is collapsed
     * (StateFlow conflation).
     *
     * Cheaper than wiring your own `status.map { it.isReachable }
     * .distinctUntilChanged()` because the flow is shared across all
     * collectors and kept current from construction time.
     */
    public val reachable: StateFlow<Boolean>

    /**
     * Reactive variant of [isDataMetered]: emits `true` when the active path
     * is metered (cellular, hotspot, or Apple Low Data Mode), `false`
     * otherwise. Like [reachable], same-value emissions are conflated by
     * StateFlow.
     */
    public val dataMetered: StateFlow<Boolean>

    /**
     * Tear down the platform observer (Apple: `nw_path_monitor_cancel`;
     * Android: `unregisterNetworkCallback`; JVM: stop the interface poll
     * loop) and cancel the internal coroutine scope. Idempotent; subsequent
     * calls are no-ops.
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
         * **JVM (desktop / server)**: on first access, constructs an
         * instance that polls the interface table at the default cadence
         * and starts observing eagerly — self-contained, like Apple. See
         * the `Reachability(pollInterval:)` factory in `jvmMain` for the
         * best-effort semantics on this platform.
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
         * the singleton's lifetime is the process, and the platform
         * observer ends with the process on every platform. Code that
         * needs explicit lifecycle should use the `Reachability(context)`
         * / `Reachability()` top-level factories instead, which return a
         * fresh observer per call and honour [close] normally.
         *
         * From Swift, renders as `Reachability.shared` via SKIE's
         * interface-companion bridging.
         */
        public val shared: Reachability
            get() = SharedReachabilityHolder.instance

        /**
         * Install [override] as the value returned by [shared] for the
         * duration of a test, or pass `null` to clear a previously installed
         * override.
         *
         * Returns a [TestingOverrideHandle] that captures the *previous*
         * override value (or `null` when none was installed). Calling
         * [TestingOverrideHandle.uninstall] restores that previous value —
         * which means nested installs are LIFO-safe by construction, with no
         * shared global stack.
         *
         * The production singleton's `by lazy(SYNCHRONIZED)` cell is
         * **never** invalidated by this call. The override is an overlay:
         * `Reachability.shared` returns `override ?: lazyInstance`. After
         * the last override is uninstalled, [shared] returns the same
         * process-lifetime singleton it would have without any tests
         * running.
         *
         * **Not** for production code. Prefer the
         * `withFakeReachability { … }` helper in `:reachable-testing` over
         * calling this directly — that helper wraps the install in
         * exception-safe `try` / `finally` and closes the fake on exit.
         *
         * Note: `@TestingOnly` is a Kotlin-compile-time guard only; it
         * provides no Swift-side enforcement. Swift consumers of the
         * production `Reachable.framework` can call the ObjC-bridged form
         * without any compiler warning. Discipline between the production
         * and testing frameworks is the only Swift-side boundary.
         *
         * Renames to Swift `installForTesting(_:)` via SKIE
         * (companion-object bridging) and the `Reachability+Testing.swift`
         * extension shipped in `:reachable-testing`.
         */
        @TestingOnly
        @OptIn(ExperimentalObjCName::class)
        @ObjCName(swiftName = "installForTesting")
        public fun installForTesting(override: Reachability?): TestingOverrideHandle =
            SharedReachabilityHolder.installForTesting(override)
    }
}

/**
 * Restore handle returned from [Reachability.installForTesting].
 *
 * Uninstalling re-publishes whatever was installed *before* this call —
 * which may be `null` (no override), the production singleton (after a
 * top-level install was already in place), or a different fake. Stack-safe.
 *
 * Constructor is `internal` so only [SharedReachabilityHolder] can mint
 * handles; consumers can hold and call them but cannot fabricate one.
 */
@TestingOnly
public class TestingOverrideHandle internal constructor(
    private val previous: Reachability?,
) {
    /**
     * Restore the previous override (which may be `null` to mean "no
     * override"). Calling `uninstall()` multiple times restores `previous`
     * each time — safe in serial test setups, but a second call after
     * another install has run would overwrite that install's value. Prefer
     * [withFakeReachability] for nested or exception-prone scenarios, as it
     * manages the handle lifecycle automatically.
     */
    public fun uninstall() {
        SharedReachabilityHolder.installForTesting(previous)
    }
}
