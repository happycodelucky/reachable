/*
 * Reachable — Apple-side public factory.
 *
 * Top-level function (not a companion-object factory) per CLAUDE.md §8:
 * "No companion-object factories for Swift-facing entry points. Top-level
 * functions or constructors." SKIE renders this as a Swift module-level
 * `Reachability()` constructor-style call.
 */
package com.happycodelucky.reachable

/**
 * Construct a [Reachability] backed by Apple's Network framework
 * `nw_path_monitor`. Begins observing immediately; call [Reachability.close]
 * to release the monitor when you're done with it.
 *
 * Available on iOS, iPadOS, and macOS (the `appleMain` source set covers all
 * Apple targets in this module). Android consumers use the `Reachability`
 * factory in `androidMain`, which takes a `Context`; JVM consumers use the
 * `Reachability(pollInterval:)` factory in `jvmMain`.
 */
@Suppress("FunctionName")
public fun Reachability(): Reachability = AppleReachability()
