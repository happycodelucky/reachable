# iOS

The iOS implementation wraps Apple's Network framework `nw_path_monitor` C
API via Kotlin/Native cinterop. The same code path covers iPadOS and
macOS; both Apple platforms share the `appleMain` source set in
`:reachable`.

## Singleton entry point — `Reachability.shared`

The recommended way to access reachability from Swift on iOS or iPadOS:

```swift
import Reachable

let reachability: any Reachability = Reachability.shared
```

`Reachability.shared` is a process-lifetime singleton. On first access,
it constructs an `nw_path_monitor`-backed observer and starts it eagerly.
Subsequent accesses return the same instance.

The Swift `Reachability.shared` property is provided by a Swift
extension compiled into the `Reachable` module. Consumers don't need any
additional configuration — `Reachability.shared` just works.

Calling `close()` on `Reachability.shared` is an intentional no-op — the
singleton's lifetime is the process.

## Explicit-lifecycle factory

For tests or per-feature observers:

```swift
import Reachable

let reachability: any Reachability = Reachability()
```

`Reachability()` is a top-level Swift function bridged from the
top-level Kotlin factory `fun Reachability(): Reachability`.
Construction:

1. Creates a per-instance serial dispatch queue
   (`dispatch_queue_create("dev.reachable.monitor", null)`).
2. Calls `nw_path_monitor_create()` for the monitor handle.
3. Registers an update handler that maps each `nw_path_t` to a
   `ReachabilityStatus` and pushes it through `MutableStateFlow.value`.
4. Calls `nw_path_monitor_start(monitor)`.

The first emission lands typically within tens of milliseconds. Until
then, `status.value` returns `ReachabilityStatus.Unknown`.

## What gets read

For each `nw_path_t` the update handler receives:

| Reachable field        | Cinterop call                                                       |
|------------------------|---------------------------------------------------------------------|
| `isReachable`          | `nw_path_get_status(path) == nw_path_status_satisfied`              |
| `transport.Wifi`       | `nw_path_uses_interface_type(path, nw_interface_type_wifi)`         |
| `transport.Cellular`   | `nw_path_uses_interface_type(path, nw_interface_type_cellular)`     |
| `transport.Ethernet`   | `nw_path_uses_interface_type(path, nw_interface_type_wired)`        |
| `transport.Other`      | `nw_path_uses_interface_type(path, nw_interface_type_other)`        |
| `isDataMetered`        | `nw_path_is_expensive(path)` OR `nw_path_is_constrained(path)` — cellular, hotspot, or Low Data Mode |

Both `nw_path_is_expensive` and `nw_path_is_constrained` set `isDataMetered`.
See [Concepts → API design](../concepts/api-design.md#data-metering-across-platforms).

## Threading

The update handler fires on the per-instance serial dispatch queue. The
handler body is a single `MutableStateFlow.value` write:

```kotlin
nw_path_monitor_set_update_handler(m) { path ->
    path?.let { p -> emit(toStatus(p)) }
}
```

Collectors observe on whatever dispatcher they collect on. From SwiftUI:

```swift
@MainActor
final class ConnectivityModel: ObservableObject {
    @Published var status: ReachabilityStatus = ReachabilityStatus.companion.Unknown
    private var task: Task<Void, Never>?

    init(reachability: any Reachability) {
        task = Task { [weak self] in
            for await s in reachability.status { self?.status = s }
        }
    }
}
```

The `Task { … }` runs on the global concurrency executor. Assignments to
`@Published` happen on the `@MainActor` because of the class annotation,
so SwiftUI updates always land on the main thread.

## Memory

`nw_path_monitor_t` is reference-counted via the Objective-C runtime
integration; Kotlin/Native's GC manages the handle lifetime. Explicit
`nw_release` is not required. `close()` calls `nw_path_monitor_cancel` to
tear down the monitor synchronously before the GC reclaims it.

The dispatch queue is ARC-managed.

## Deployment target

iOS 18.0, set in `gradle/libs.versions.toml` and baked into the
SwiftPackage manifest. The Network framework itself is available on iOS
12+ and macOS 10.14+, so the floor is well above what Apple requires; the
iOS 18 pin reflects the project's broader baseline rather than any
`nw_path_monitor` constraint.

To support a lower floor, fork and bump the deployment target down. The
implementation will keep working on anything iOS 12+.

## See also

- [Platforms → macOS](macos.md): functionally identical implementation.
- [Concepts → Lifecycle](../concepts/lifecycle.md): construction, close, threading.
- [Recipes → SwiftUI binding](../recipes/swiftui-binding.md): the full `ObservableObject` view-model pattern.
