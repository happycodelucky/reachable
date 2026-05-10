# SwiftUI binding

Bridge `reachability.status` (a Kotlin `StateFlow`) into a SwiftUI view via
an `@MainActor` `ObservableObject` view-model. SKIE handles the actual
bridging — it exposes `StateFlow<T>` as an `AsyncSequence<T>` so a
`for await` loop is the entire integration.

## The pattern

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
        // Only call close() if this view-model owns the Reachability — usually
        // it doesn't (the composition root does). Drop this line if you're
        // injecting an externally-owned instance.
        reachability.close()
    }
}

struct ConnectivityBanner: View {
    @StateObject var model: ConnectivityModel

    var body: some View {
        if !model.status.reachable {
            Label("Offline", systemImage: "wifi.slash")
                .foregroundStyle(.red)
        }
    }
}
```

Use it from a parent view:

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
  view-model alive across re-renders. `@ObservedObject` drops it when the
  parent re-evaluates, which would tear down and re-create the
  `observationTask` constantly. Use `@StateObject` for any view-model that
  owns long-lived state.

- **Forgetting `[weak self]`** in the `Task` closure. The task captures
  `self` strongly otherwise, leaking the view-model. The library's StateFlow
  is hot, so the task never naturally completes — without `[weak self]` the
  reference cycle is permanent.

- **`Transport.none` vs `Optional.none`.** Swift's `Optional.none` shadows
  the enum case in `switch` arms when the type can't be inferred. Use
  fully-qualified `case Transport.none:` if you hit it, or write the switch
  on a local of type `Transport`.

  ```swift
  switch model.status.transport {
  case .wifi, .ethernet:    badge.color = .green
  case .cellular:           badge.color = .yellow
  case .other:              badge.color = .gray
  case .none:               badge.color = .red   // works here — type inferred
  }
  ```

- **Branching on `metering` and forgetting `.constrained`.** Swift switches
  are exhaustive. If you write three arms expecting `unmetered`/`metered`
  and forget that the Apple-only `constrained` case exists, the compiler
  will tell you. Add `case .constrained:` and treat it as a stricter form
  of `metered`.

## Reading the current value without subscribing

If you just need a one-off reading:

```swift
let now = reachability.status.value!
if now.reachable {
    // …
}
```

`StateFlow.value` is bridged as a non-optional in Kotlin, but SKIE renders
it as Swift `Any?` — the `!` is needed to unwrap the bridged value to
`ReachabilityStatus`. SKIE may relax this in a future version.

For a one-shot suspending read (e.g. in an `async` function):

```swift
let now = try await reachability.status.first()
```
