# Getting started

## 1. Add the dependency

From Maven Central. Apple consumers come in via Kotlin Multiplatform's
iOS / macOS klibs (no separate Swift Package Manager artifact in v0.1;
see [Installation](installation.md#apple-side-spm-roadmap)).

=== "Android (Gradle)"

    ```kotlin
    // app/build.gradle.kts
    dependencies {
        implementation("com.happycodelucky.reachable:reachable:{{ version }}")
    }
    ```

=== "Kotlin Multiplatform"

    ```kotlin
    // shared/build.gradle.kts
    kotlin {
        sourceSets {
            commonMain.dependencies {
                implementation("com.happycodelucky.reachable:reachable:{{ version }}")
            }
        }
    }
    ```

## 2. Get a Reachability handle

`Reachability.shared` is the recommended entry point for most consumers.
It is a process-lifetime singleton: callable from anywhere, at any time,
with no construction or Context plumbing required.

=== "Android"

    ```kotlin
    // From any Composable, ViewModel, or Application — no setup needed.
    val reachability: Reachability = Reachability.shared
    ```

    On Android, the library's bundled `androidx.startup` initializer
    attaches the singleton to the application `Context` during the
    `InitializationProvider` ContentProvider pass — before
    `Application.onCreate`. Collectors started before that point receive
    `ReachabilityStatus.Unknown` first and then live values; `StateFlow`
    late-joiner semantics make this race-free.

=== "iOS"

    ```swift
    // From any SwiftUI view-model, @main App, or anywhere else.
    let reachability: any Reachability = Reachability.shared
    ```

    On first access, constructs an `nw_path_monitor`-backed observer and
    starts it eagerly. Subsequent accesses return the same instance.

=== "macOS"

    ```swift
    let reachability: any Reachability = Reachability.shared
    ```

### Explicit-lifecycle alternative

For tests or any code that needs a fresh observer with explicit teardown,
use the platform factories instead:

=== "Android"

    ```kotlin
    val reachability: Reachability = Reachability(applicationContext)
    // ...
    reachability.close()
    ```

=== "iOS"

    ```swift
    let reachability: any Reachability = Reachability()
    // ...
    reachability.close()
    ```

=== "macOS"

    ```swift
    let reachability: any Reachability = Reachability()
    ```

Calling `close()` on `Reachability.shared` is intentionally a no-op — the
singleton's lifetime is the process. Use the factories above when you need
`close()` to actually tear down the observer.

## 3. React to status

=== "Compose"

    ```kotlin
    @Composable
    fun ConnectivityBanner() {
        // Use Reachability.shared directly — no parameter plumbing needed.
        val status by Reachability.shared.status.collectAsStateWithLifecycle()
        if (!status.isReachable) {
            Text("You're offline")
        }
    }
    ```

=== "SwiftUI"

    ```swift
    @MainActor
    final class ConnectivityModel: ObservableObject {
        @Published var status: ReachabilityStatus = ReachabilityStatus.companion.Unknown
        private var task: Task<Void, Never>?

        init() {
            // Reachability.shared — process-lifetime singleton, no close needed.
            let reachability: any Reachability = Reachability.shared
            task = Task { [weak self] in
                for await s in reachability.status { self?.status = s }
            }
        }

        deinit { task?.cancel() }
    }
    ```

### Single-axis shortcuts

`isReachable` and `isDataMetered` read directly off the latest status without
unpacking it. The matching `reachable` and `dataMetered` StateFlows give you
the same values as a Flow, conflated so you only see real transitions.

```kotlin
if (reachability.isReachable) { /* online */ }
if (reachability.isDataMetered) { /* defer large transfers */ }

reachability.reachable.collect { online -> /* … */ }
reachability.dataMetered.collect { isMetered -> /* … */ }
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

if (status.isDataMetered) {
    // defer large transfers — cellular, hotspot, or Low Data Mode
} else {
    // prefetch, autoplay, etc.
}
```

## Next steps

- [Concepts → API design](concepts/api-design.md): the public type, the asymmetric factories, why there's no `Result`.
- [Concepts → Lifecycle](concepts/lifecycle.md): when to construct, when to close, threading.
- [Recipes](recipes/swiftui-binding.md): SwiftUI / Compose patterns, captive-portal handling, one-shot reads.
