/*
 * Reachable — Apple `actual` for the singleton holder.
 *
 * Apple has no Context to wait for: `nw_path_monitor` is constructable
 * from anywhere, and the existing `AppleReachability` constructor starts
 * observing eagerly. The first read of `Reachability.shared` therefore
 * produces a fully-functional, already-observing instance.
 */
package com.happycodelucky.reachable.internal

import com.happycodelucky.reachable.AppleReachability
import com.happycodelucky.reachable.Reachability

internal actual fun createSharedReachability(): Reachability = AppleReachability()
