// Reachable — iOSApp sample.
//
// One screen, one observable: subscribes to `reachability.status` (which SKIE
// bridges as a Swift `AsyncSequence`) and renders the live ReachabilityStatus.
// Toggle airplane mode or switch between Wi-Fi and cellular in Settings to
// see transitions arrive in real time. Enable Low Data Mode in
// Settings → Cellular → Cellular Data Options to see Metering.constrained.
//
// The Reachability handle is owned by an `@MainActor` view-model held as a
// `@StateObject` so it survives view re-creation. The view-model creates one
// Reachability in its initializer and explicitly cancels its observation
// task in `deinit`.

import SwiftUI
import Reachable

@main
struct iOSApp: App {
    var body: some Scene {
        WindowGroup {
            ReachabilityScreen()
        }
    }
}

@MainActor
final class ReachabilityViewModel: ObservableObject {
    // SKIE renders the Kotlin `companion object` as `ReachabilityStatus.companion`
    // (lowercase `c`) and preserves the Kotlin field name `Unknown` verbatim
    // via `@ObjCName(swiftName = "Unknown")` in the generated header.
    @Published var status: ReachabilityStatus = ReachabilityStatus.companion.Unknown

    private let reachability: Reachability
    private var observationTask: Task<Void, Never>?

    init() {
        // SKIE elides the `Kt` suffix on top-level Kotlin functions, so the
        // `Reachability()` factory in `Reachability.apple.kt` is callable
        // directly as a Swift module-level function.
        let reachability = Reachability()
        self.reachability = reachability
        // SKIE bridges StateFlow<T> as AsyncSequence<T> via `for await` —
        // the sequence emits the current value immediately, then every change.
        observationTask = Task { [weak self] in
            for await value in reachability.status {
                self?.status = value
            }
        }
    }

    deinit {
        observationTask?.cancel()
        reachability.close()
    }
}

struct ReachabilityScreen: View {
    @StateObject private var model = ReachabilityViewModel()

    var body: some View {
        VStack(spacing: 16) {
            Text(model.status.reachable ? "Online" : "Offline")
                .font(.largeTitle.bold())
            row("Transport", model.status.transport.label)
            row("Metering", model.status.metering.label)
            Text("Toggle airplane mode or switch networks to see live updates.")
                .font(.footnote)
                .foregroundStyle(.secondary)
                .multilineTextAlignment(.center)
                .padding(.top, 8)
            Text("(raw) reachable=\(model.status.reachable) transport=\(model.status.transport.label) metering=\(model.status.metering.label)")
                .font(.system(.caption, design: .monospaced))
                .foregroundStyle(.tertiary)
        }
        .padding(24)
    }

    private func row(_ label: String, _ value: String) -> some View {
        Text("\(label): \(value)")
            .font(.title3)
    }
}

// SKIE renders the Kotlin enums as native Swift enums; small label helpers
// turn them into display strings without committing to a localisation
// strategy in the sample app.
private extension Transport {
    var label: String {
        switch self {
        case .wifi: return "Wi-Fi"
        case .cellular: return "Cellular"
        case .ethernet: return "Ethernet"
        case .other: return "Other"
        case .none: return "None"
        }
    }
}

private extension Metering {
    var label: String {
        switch self {
        case .unmetered: return "Unmetered"
        case .metered: return "Metered"
        case .constrained: return "Constrained (Low Data Mode)"
        }
    }
}
