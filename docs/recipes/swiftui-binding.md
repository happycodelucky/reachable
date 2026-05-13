# SwiftUI binding

SKIE bridges `StateFlow<T>` as `AsyncSequence<T>`, so the integration is a
single `for await` loop. Wrap that in an `@MainActor`
`ObservableObject` view-model and bind from a SwiftUI view.

## With `Reachability.shared` (recommended)

`Reachability.shared` is the process-lifetime singleton — no factory call,
no `deinit` close needed:

```swift
import SwiftUI
import Reachable

@MainActor
final class ConnectivityModel: ObservableObject {
    @Published var status: ReachabilityStatus = ReachabilityStatus.companion.Unknown
    private var observationTask: Task<Void, Never>?

    init() {
        // Reachability.shared starts observing eagerly on first access.
        // No close() needed — the singleton lives for the process.
        let reachability: any Reachability = Reachability.shared
        observationTask = Task { [weak self] in
            for await value in reachability.status {
                self?.status = value
            }
        }
    }

    deinit { observationTask?.cancel() }
}

struct ConnectivityBanner: View {
    @StateObject var model: ConnectivityModel = ConnectivityModel()

    var body: some View {
        if !model.status.isReachable {
            Label("Offline", systemImage: "wifi.slash")
                .foregroundStyle(.red)
        }
    }
}
```

## With an injected instance (explicit lifecycle)

For tests or when you need a fresh observer with explicit teardown:

```swift
import SwiftUI
import Reachable

@MainActor
final class ConnectivityModel: ObservableObject {
    @Published var status: ReachabilityStatus = ReachabilityStatus.companion.Unknown

    private let reachability: any Reachability
    private var observationTask: Task<Void, Never>?

    init(reachability: any Reachability) {
        self.reachability = reachability
        observationTask = Task { [weak self] in
            for await value in reachability.status {
                self?.status = value
            }
        }
    }

    deinit {
        observationTask?.cancel()
        // Only call close() if this view-model owns the Reachability —
        // usually it doesn't (the composition root does).
        reachability.close()
    }
}

struct ConnectivityBanner: View {
    @StateObject var model: ConnectivityModel

    var body: some View {
        if !model.status.isReachable {
            Label("Offline", systemImage: "wifi.slash")
                .foregroundStyle(.red)
        }
    }
}
```

From a parent view using the explicit-lifecycle factory:

```swift
@main
struct ReachableExampleApp: App {
    private let reachability: any Reachability = Reachability()

    var body: some Scene {
        WindowGroup {
            ContentView()
                .overlay(alignment: .top) {
                    ConnectivityBanner(model: ConnectivityModel(reachability: reachability))
                }
        }
    }
}
```

## What can go wrong

- **`@StateObject` vs `@ObservedObject`.** `@StateObject` keeps the
  view-model alive across re-renders. `@ObservedObject` drops it on every
  parent re-evaluation, tearing down and re-creating the
  `observationTask`. Use `@StateObject` for any view-model that owns
  long-lived state.
- **Forgetting `[weak self]`** in the `Task` closure. Without it the task
  captures `self` strongly. The library's StateFlow is hot, so the task
  never naturally completes — the reference cycle is permanent.
- **`Transport.none` vs `Optional.none`.** Swift's `Optional.none` shadows
  the enum case in `switch` arms when the type can't be inferred. Use
  fully-qualified `case Transport.none:` if you hit the conflict, or
  switch on a local of type `Transport`.

  ```swift
  switch model.status.transport {
  case .wifi, .ethernet:    badge.color = .green
  case .cellular:           badge.color = .yellow
  case .other:              badge.color = .gray
  case .none:               badge.color = .red   // works here — type inferred
  }
  ```
- **Reading metered state.** `isDataMetered` is a `Bool` — no `switch`
  needed. Use `if status.isDataMetered { … }` directly.

## Reading the current value without subscribing

For a one-off read:

```swift
let now = reachability.status.value!
if now.isReachable {
    // …
}
```

`StateFlow.value` is non-optional in Kotlin, but SKIE renders it as
Swift `Any?`. The `!` unwraps the bridged value to `ReachabilityStatus`.
SKIE may relax this in a future version.

For a one-shot suspending read in an `async` function:

```swift
let now = try await reachability.status.first()
```
