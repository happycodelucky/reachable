# Getting started

Three steps from zero to a live `Reachability` instance bound to a UI.

## 1. Add the dependency

See [Installation](installation.md) for the full Maven / SPM details. The
short version:

=== "Android (Gradle)"

    ```kotlin
    // app/build.gradle.kts
    dependencies {
        implementation("com.happycodelucky.reachable:reachable:0.1.0")
    }
    ```

=== "iOS / macOS (Swift Package Manager)"

    Add `https://github.com/happycodelucky/reachable.git` as an SPM
    dependency in Xcode and pin to the latest tag:

    ```
    .package(url: "https://github.com/happycodelucky/reachable.git", from: "0.1.0")
    ```

## 2. Construct one Reachability per process

The factory is asymmetric on purpose: Android needs a `Context`, Apple
doesn't. Construct at the platform entrypoint and inject the `Reachability`
interface across shared code (CLAUDE.md §5: library uses constructor injection).

=== "Android"

    ```kotlin
    // Application.onCreate
    val reachability: Reachability = Reachability(applicationContext)

    // ...later, somewhere your DI graph wires together…
    val viewModel = ConnectivityModel(reachability)
    ```

=== "iOS"

    ```swift
    // Wherever your composition root lives — App.init or a SceneDelegate.
    let reachability: any Reachability = Reachability()

    // Inject across SwiftUI views via @Environment, an ObservableObject, or
    // your DI container of choice.
    let model = ConnectivityModel(reachability: reachability)
    ```

=== "macOS"

    ```swift
    // Identical to iOS — same `appleMain` source set in :reachable.
    let reachability: any Reachability = Reachability()
    ```

## 3. React to status

`reachability.status` is a `StateFlow<ReachabilityStatus>` (Swift consumers
see it as an `AsyncSequence`). It always exposes a current value and emits
every change.

=== "Compose"

    ```kotlin
    @Composable
    fun ConnectivityBanner(reachability: Reachability) {
        val status by reachability.status.collectAsStateWithLifecycle()
        if (!status.reachable) {
            Text("You're offline")
        }
    }
    ```

=== "SwiftUI"

    ```swift
    @MainActor
    final class ConnectivityModel: ObservableObject {
        @Published var status: ReachabilityStatus = ReachabilityStatus.companion.Unknown
        private let reachability: any Reachability
        private var task: Task<Void, Never>?

        init(reachability: any Reachability) {
            self.reachability = reachability
            task = Task { [weak self] in
                for await s in reachability.status { self?.status = s }
            }
        }

        deinit { task?.cancel(); reachability.close() }
    }
    ```

That's the full surface. Three additional axes you might branch on:

```kotlin
when (status.transport) {
    Transport.Wifi      -> { /* unmetered, fast */ }
    Transport.Cellular  -> { /* may be expensive */ }
    Transport.Ethernet  -> { /* desktop, plug-in */ }
    Transport.Other     -> { /* loopback, virtual, unknown */ }
    Transport.None      -> { /* not reachable */ }
}

when (status.metering) {
    Metering.Unmetered    -> { /* prefetch, autoplay, etc. */ }
    Metering.Metered      -> { /* defer large transfers */ }
    Metering.Constrained  -> { /* Apple-only — Low Data Mode active */ }
}
```

## Next steps

- **[Concepts → API design](concepts/api-design.md)** — what shapes the public type, why `Metering.Constrained` is Apple-only, why we don't expose a `Result`.
- **[Concepts → Lifecycle](concepts/lifecycle.md)** — when to construct, when to close, and what threads things fire on.
- **[Recipes](recipes/swiftui-binding.md)** — copy-pasteable patterns for the UI bindings shown above, plus retry / captive-portal / one-shot patterns.
