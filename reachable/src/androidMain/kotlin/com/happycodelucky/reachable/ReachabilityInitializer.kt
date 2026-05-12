/*
 * Reachable — `androidx.startup.Initializer` for the shared singleton.
 *
 * Wired by the library's `AndroidManifest.xml` into the merged manifest's
 * `androidx.startup.InitializationProvider` ContentProvider. The provider
 * runs early in process startup — before `Application.onCreate` — and
 * invokes [create] with the application Context. We attach the singleton
 * here so collectors anywhere in the app (including ones registered
 * before `Application.onCreate`, e.g. from another library's
 * `ContentProvider`) see live values without any startup ordering
 * dependency.
 *
 * Consumers who disable `InitializationProvider` entirely will not see
 * auto-attach. They must either re-enable startup and let this initializer
 * run, or stop using [Reachability.shared] and build a per-app instance
 * via the public `Reachability(context)` factory.
 */
package com.happycodelucky.reachable

import android.content.Context
import androidx.startup.Initializer
import com.happycodelucky.reachable.internal.NonClosingReachability
import com.happycodelucky.reachable.internal.SharedReachabilityHolder

/**
 * `androidx.startup.Initializer` that attaches [Reachability.shared] to the
 * application [Context]. Registered in the library's manifest under
 * `androidx.startup.InitializationProvider`; the manifest merger combines
 * this entry with any consumer-side `InitializationProvider`.
 *
 * Returns the singleton itself as the initializer's component value. The
 * `androidx.startup` framework caches that return by class, so a later
 * `AppInitializer.getInstance(context).initializeComponent(ReachabilityInitializer::class.java)`
 * resolves to the same handle as [Reachability.shared].
 */
internal class ReachabilityInitializer : Initializer<Reachability> {
    override fun create(context: Context): Reachability {
        val shared = Reachability.shared
        // `shared` is normally `NonClosingReachability` wrapping `AndroidReachability`.
        // Guard both downcasts: if a test override was installed before this
        // initializer ran (possible — the ContentProvider pass happens before
        // Application.onCreate, but tests may install an override earlier in
        // the process), `shared` won't be `NonClosingReachability`. In that case
        // skip attach and return the override as-is; tests own the fake's lifecycle.
        val nonClosing = shared as? NonClosingReachability ?: return shared
        val underlying = nonClosing.unwrap() as? AndroidReachability ?: return shared
        underlying.attach(context.applicationContext)
        return shared
    }

    override fun dependencies(): List<Class<out Initializer<*>>> = emptyList()
}
