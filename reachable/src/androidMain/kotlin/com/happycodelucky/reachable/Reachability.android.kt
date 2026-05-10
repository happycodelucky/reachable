/*
 * Reachable — Android-side public factory.
 *
 * The Android factory is asymmetric with the Apple one: it requires a
 * `Context`. Per CLAUDE.md §5, the library uses constructor injection — the
 * consumer's app graph (Application.onCreate, Koin module, Hilt, manual)
 * supplies the Context once and binds the resulting `Reachability`
 * interface for shared / multiplatform code to depend on.
 */
package com.happycodelucky.reachable

import android.content.Context

/**
 * Construct a [Reachability] backed by Android's [android.net.ConnectivityManager]
 * `NetworkCallback`. Begins observing immediately; call [Reachability.close]
 * to unregister the callback when you're done.
 *
 * Requires `android.permission.ACCESS_NETWORK_STATE`. The library declares
 * it in its own manifest so it merges into the consuming app — consumers do
 * not need to add it themselves, and no runtime permission grant is required
 * (it's a normal-protection permission).
 *
 * @param context Any [Context]; the implementation upgrades it to
 * `applicationContext` to avoid leaking activity-scoped contexts.
 */
@Suppress("FunctionName")
public fun Reachability(context: Context): Reachability = AndroidReachability(context)
