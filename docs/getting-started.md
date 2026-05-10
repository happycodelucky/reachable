# Getting started

## 1. Add the dependency

=== "Android (Gradle)"

    ```kotlin
    // app/build.gradle.kts
    dependencies {
        implementation("com.happycodelucky.reachable:reachable:0.1.0")
    }
    ```

=== "iOS / macOS (Swift Package Manager)"

    ```
    .package(url: "https://github.com/happycodelucky/reachable.git", from: "0.1.0")
    ```

Full setup including GitHub Packages auth: [Installation](installation.md).

## 2. Construct one Reachability per process

The factory takes a `Context` on Android and no arguments on Apple. Construct
at the platform entrypoint, then inject the `Reachability` interface into
shared code.

=== "Android"

    ```kotlin
    // Application.onCreate
    val reachability: Reachability = Reachability(applicationContext)
    val viewModel = ConnectivityModel(reachability)
    ```

=== "iOS"

    ```swift
    // App.init or your composition root
    let reachability: any Reachability = Reachability()
    let model = ConnectivityModel(reachability: reachability)
    ```

=== "macOS"

    ```swift
    let reachability: any Reachability = Reachability()
    ```

## 3. React to status

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

### Single-axis shortcuts

`isReachable` and `isLowDataMode` read directly off the latest status without
unpacking it. The matching `reachable` and `lowDataMode` StateFlows give you
the same values as a Flow, conflated so you only see real transitions.

```kotlin
if (reachability.isReachable) { /* online */ }
if (reachability.isLowDataMode) { /* defer large transfers */ }

reachability.reachable.collect { online -> /* … */ }
reachability.lowDataMode.collect { isOn -> /* … */ }
```

See [Concepts → API design](concepts/api-design.md#single-axis-shortcuts).

### Branching on the full status

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

- [Concepts → API design](concepts/api-design.md): the public type, the asymmetric factories, why there's no `Result`.
- [Concepts → Lifecycle](concepts/lifecycle.md): when to construct, when to close, threading.
- [Recipes](recipes/swiftui-binding.md): SwiftUI / Compose patterns, captive-portal handling, one-shot reads.
