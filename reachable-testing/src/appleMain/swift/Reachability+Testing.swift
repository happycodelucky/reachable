//
// Reachable — Swift-side ergonomic sweetener for `Reachability.installForTesting`.
//
// Mirrors the `Reachability+Shared.swift` pattern in `:reachable`'s
// appleMain Swift source set: SKIE renders Kotlin interface companion-object
// members as a standalone `ReachabilityCompanion` class on the Swift side,
// so the raw call site reads:
//
//     _ = ReachabilityCompanion.shared.installForTesting(override: fake)
//
// — the first `.shared` accesses the companion-object singleton, the
// trailing label `override:` is required because Swift can't omit the
// first parameter label on a companion-method bridge. The extension below
// adds a static method on the protocol so Swift test code reads simply:
//
//     let handle = Reachability.installForTesting(fake)
//
// matching the Kotlin shape (`Reachability.installForTesting(fake)`) and
// pairing naturally with the bundled `withFakeReachability(initial:_:)`
// top-level helper, which SKIE already renders without an extension.
//
// This file lives in `:reachable-testing/src/appleMain/swift/` — separate
// from `:reachable`'s Swift sources — so the symbol only ships when a
// consumer links the testing module. No accidental "installForTesting"
// surface on the production framework.
//
// Note on SKIE Swift bundling: `:reachable`'s build script sets
// `skie.swiftBundling.enabled = false` to keep its `Reachability+Shared.swift`
// from being re-extracted and recompiled inside downstream modules where
// `Reachability` is renamed `ReachableReachability` (module-prefixed). The
// same setting is wired on `:reachable-testing` for the same reason — this
// extension is compiled into `ReachableTesting.framework` directly and not
// bundled into the klib for downstream re-extraction.
//

import Foundation
import Reachable

extension Reachability {
    /// Install `override` as the value returned from
    /// ``Reachability/shared`` for the duration of a test, or pass `nil`
    /// to clear a previously installed override.
    ///
    /// Returns a ``TestingOverrideHandle`` whose
    /// ``TestingOverrideHandle/uninstall()`` restores the previous
    /// override value (which may be the production singleton, a
    /// different fake, or `nil`). Nested installs are LIFO-safe.
    ///
    /// Prefer the free function ``withFakeReachability(initial:_:)``
    /// over calling this directly — it wraps the install / uninstall in
    /// exception-safe `try` / `finally` and closes the fake on exit.
    ///
    /// Bridges to the Kotlin-side
    /// `Reachability.Companion.installForTesting(_:)`.
    public static func installForTesting(
        _ override: (any Reachability)?
    ) -> TestingOverrideHandle {
        return ReachabilityCompanion.shared.installForTesting(override: override)
    }
}
