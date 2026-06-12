/*
 * Reachable — JVM `actual` for the singleton holder.
 *
 * Like Apple, the JVM has no Context to wait for: `JvmReachability` is
 * constructable from anywhere and starts its poll loop eagerly. The first
 * read of `Reachability.shared` therefore produces a fully-functional,
 * already-observing instance whose first real reading lands on the first
 * poll tick.
 */
package com.happycodelucky.reachable.internal

import com.happycodelucky.reachable.JvmReachability
import com.happycodelucky.reachable.Reachability

internal actual fun createSharedReachability(): Reachability = JvmReachability()
