//
// Reachable — Swift-side ergonomic sweetener for `Reachability.shared`.
//
// SKIE renders Kotlin interface companion-object members as a standalone
// `ReachabilityCompanion` class in Swift (not as a nested `.companion`
// property on the protocol — that pattern only applies to concrete classes
// such as `ReachabilityStatus`). The raw call site would read:
//
//     ReachabilityCompanion.shared.shared
//
// — the first `.shared` accesses the companion-object singleton, the second
// accesses the `Reachability` instance stored on it. That's correct but
// un-Swift. This extension adds a static property on the protocol so the
// public surface for Swift consumers is simply:
//
//     let r = Reachability.shared
//
// matching what they'd write for a hand-authored Swift singleton.
//
// This file lives in `src/appleMain/swift/` which SKIE auto-discovers
// and compiles into the same framework module (`Reachable`) as the
// SKIE-generated Swift wrappers around the Kotlin code. No additional
// Gradle wiring required — see SKIE's documentation on Swift source
// sets.
//
// Why a Swift extension instead of Kotlin-side annotations:
//
//   - SKIE does not (yet) bridge interface-companion `val`s to a
//     static var on the Swift protocol. The closest Kotlin-side fix is
//     a top-level `expect val`, but per CLAUDE.md §4 we keep the
//     expect/actual surface minimal and prefer Swift-side adjustment
//     for pure cosmetic mappings.
//   - The shape mirrors the older presentation-protocol project's
//     `ContentDuration.swift` pattern: extension method delegates to
//     the companion accessor. Same technique, zero runtime cost.
//

import Foundation

extension Reachability {
    /// Process-lifetime singleton handle.
    ///
    /// On iOS / iPadOS / macOS, first access constructs an
    /// `nw_path_monitor`-backed observer and starts it eagerly.
    /// Subsequent accesses return the same instance. Calling
    /// ``Reachability/close()`` on this instance is an intentional
    /// no-op — the singleton's lifetime is the process; the kernel
    /// reaps the platform observer at process exit.
    ///
    /// For code that needs explicit lifecycle (tests, per-feature
    /// observers), use the top-level `Reachability()` initializer
    /// instead. That returns a fresh observer and honours `close()`
    /// normally.
    ///
    /// Bridges to the Kotlin-side `Reachability.Companion.shared`.
    /// `ReachabilityCompanion.shared` is the companion-object singleton;
    /// `.shared` on it is the `Reachability` instance property.
    public static var shared: any Reachability {
        return ReachabilityCompanion.shared.shared
    }
}
