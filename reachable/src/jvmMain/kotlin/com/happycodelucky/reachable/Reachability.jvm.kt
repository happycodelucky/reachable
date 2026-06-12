/*
 * Reachable — JVM-side public factory.
 *
 * Top-level function (not a companion-object factory) for symmetry with the
 * Apple and Android factories — `Reachability()` reads like a constructor at
 * the call site on every platform.
 */
package com.happycodelucky.reachable

import kotlin.time.Duration

/**
 * Construct a [Reachability] backed by polling `java.net.NetworkInterface`.
 * Begins observing immediately; call [Reachability.close] to stop the poll
 * loop when you're done with it.
 *
 * **Best-effort semantics.** The JVM offers no OS validation probe, so
 * [ReachabilityStatus.isReachable] means "a non-loopback interface is up
 * with a routable address" — weaker than the validated signal on Apple and
 * Android. Captive portals are not detected, transport classification is
 * inferred from interface names (unrecognised ones report
 * [Transport.Other]), and [ReachabilityStatus.isDataMetered] is always
 * `false`.
 *
 * Available on desktop and server JVMs. Apple consumers use the no-argument
 * `Reachability()` factory in `appleMain`; Android consumers use
 * `Reachability(context)` in `androidMain`.
 *
 * @param pollInterval How often to re-read the interface table. Defaults to
 * 5 seconds — each poll is a cheap local syscall with no network traffic.
 * Identical successive readings are conflated, so a shorter interval changes
 * detection latency, not emission volume.
 */
@Suppress("FunctionName")
public fun Reachability(pollInterval: Duration = defaultJvmPollInterval): Reachability =
    JvmReachability(pollInterval = pollInterval)
