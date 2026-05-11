/*
 * Reachable — Android `actual` for the singleton holder.
 *
 * Returns an unattached [AndroidReachability]. Its `status.value` is
 * [com.happycodelucky.reachable.ReachabilityStatus.Unknown] until either
 * the bundled [com.happycodelucky.reachable.ReachabilityInitializer]
 * runs (the default; happens during the `androidx.startup`
 * `InitializationProvider` ContentProvider pass, before
 * `Application.onCreate`) or the consumer calls `attach` through their
 * own initialization path.
 */
package com.happycodelucky.reachable.internal

import com.happycodelucky.reachable.AndroidReachability
import com.happycodelucky.reachable.Reachability

internal actual fun createSharedReachability(): Reachability = AndroidReachability()
