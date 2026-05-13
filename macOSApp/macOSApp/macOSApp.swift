// Reachable — macOSApp sample.
//
// Single-window SwiftUI app that subscribes to `reachability.status` and
// renders the live ReachabilityStatus. Toggle Wi-Fi from the menu bar, plug
// in / unplug Ethernet, or enable Low Data Mode in System Settings →
// Network → Wi-Fi → Details to see live updates.
//
// The implementation is functionally identical to iOSApp because both
// platforms share the same `appleMain` Kotlin source set in :reachable —
// the only differences are window sizing and the deployment platform.

import SwiftUI
import Reachable

@main
struct macOSApp: App {
    var body: some Scene {
        WindowGroup {
            ReachabilityScreen()
                .frame(minWidth: 360, minHeight: 280)
        }
        .windowResizability(.contentSize)
    }
}

@MainActor
final class ReachabilityViewModel: ObservableObject {
    @Published var status: ReachabilityStatus = ReachabilityStatus.companion.Unknown

    private let reachability: Reachability
    private var observationTask: Task<Void, Never>?

    init() {
        let reachability = Reachability()
        self.reachability = reachability
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
            Text(model.status.isReachable ? "Online" : "Offline")
                .font(.largeTitle.bold())
            row("Transport", model.status.transport.label)
            row("Data Metered", model.status.isDataMetered ? "Yes" : "No")
            Text("Toggle Wi-Fi or plug in Ethernet to see live updates.")
                .font(.footnote)
                .foregroundStyle(.secondary)
                .multilineTextAlignment(.center)
                .padding(.top, 8)
            Text("(raw) isReachable=\(model.status.isReachable) transport=\(model.status.transport.label) isDataMetered=\(model.status.isDataMetered)")
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

