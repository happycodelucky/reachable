# Reachable

Reachable is a Kotlin Multiplatform library that tells you whether the
device is on the internet, and lets you observe changes as they happen. It
targets iOS, iPadOS, macOS (ARM only), and Android (arm64-v8a, minSdk 30),
and presents the same API to Kotlin and Swift consumers.

The Apple side wraps `nw_path_monitor`. The Android side wraps
`ConnectivityManager.NetworkCallback` registered against
`NET_CAPABILITY_INTERNET + NET_CAPABILITY_VALIDATED`, so captive portals
register as not reachable.

## Public API

```kotlin
public data class ReachabilityStatus(
    val isReachable: Boolean,
    val transport: Transport,    // Wifi, Cellular, Ethernet, Other, None
    val isDataMetered: Boolean,
)

public interface Reachability : AutoCloseable {
    public val status: StateFlow<ReachabilityStatus>

    // Convenience shortcuts.
    public val isReachable: Boolean
    public val isDataMetered: Boolean
    public val reachable: StateFlow<Boolean>
    public val dataMetered: StateFlow<Boolean>

    override fun close()

    public companion object {
        // Process-lifetime singleton. close() is a no-op on this instance.
        public val shared: Reachability
    }
}

// appleMain — iOS, iPadOS, macOS
public fun Reachability(): Reachability

// androidMain
public fun Reachability(context: Context): Reachability
```

`isDataMetered` is `true` when the platform reports the active path as
metered — cellular, hotspot, or Apple's Low Data Mode on iOS / macOS.
Treat it as the "consider deferring large transfers" signal. The
invariant `!isReachable ⇒ !isDataMetered` holds on both platforms, so a
caller may read `isDataMetered` without first checking `isReachable`.

## Usage

### Kotlin (shared, Android)

`Reachability.shared` is the recommended entry point — a process-lifetime
singleton, no `Context` plumbing required:

```kotlin
// Singleton — works from anywhere, including before Application.onCreate.
val reachability: Reachability = Reachability.shared

if (reachability.isReachable) { /* online */ }

lifecycleScope.launch {
    reachability.status.collect { status ->
        updateBanner(status)
    }
}
// close() on .shared is a no-op; the OS reaps the observer at process exit.
```

For explicit-lifecycle control (tests, per-feature observers):

```kotlin
val reachability: Reachability = Reachability(context) // or Reachability() on Apple
// ...
reachability.close() // honours close() normally
```

### SwiftUI (iOS, macOS)

SKIE bridges `StateFlow` as `AsyncSequence`. Use `Reachability.shared` as
the primary entry point from Swift too:

```swift
import Reachable

@MainActor
final class ConnectivityModel: ObservableObject {
    @Published var status: ReachabilityStatus = ReachabilityStatus.companion.Unknown
    private var task: Task<Void, Never>?

    init() {
        // Reachability.shared — process-lifetime singleton, no close needed.
        let reachability: any Reachability = Reachability.shared
        task = Task { [weak self] in
            for await s in reachability.status {
                self?.status = s
            }
        }
    }
}
```

`Transport` arrives as a native Swift enum; exhaustive `switch` works
without a `default` arm. `isReachable` and `isDataMetered` are plain
`Bool` on the Swift side.

### Jetpack Compose

```kotlin
@Composable
fun ConnectivityBanner() {
    // No Context, no remember — Reachability.shared is auto-attached by
    // the library's androidx.startup initializer before Application.onCreate.
    val status by Reachability.shared.status.collectAsStateWithLifecycle()
    if (!status.isReachable) {
        Text("You're offline")
    }
}
```

## Construction

`Reachability.shared` is the zero-setup singleton: call it from anywhere.

The explicit-lifecycle factories are intentionally asymmetric — the Apple
factory takes nothing, the Android factory takes a `Context`. Use them
when you need per-instance teardown or a fresh observer (tests,
per-feature observers). Your DI graph wires the platform-specific factory
at the app entrypoint and binds the `Reachability` interface for shared
code.

## Permissions

The library declares `android.permission.ACCESS_NETWORK_STATE` in its own
`AndroidManifest.xml` and merges into your app at build time. It's a
normal-protection permission, so no runtime grant is needed.

Apple platforms require no permission for `nw_path_monitor`. Network
access itself still goes through the usual Info.plist and entitlement
model.

## Behaviour notes

- **Captive portals.** Android: detected via `NET_CAPABILITY_VALIDATED`;
  `isReachable` is `false` until Android's connectivity probe confirms
  public internet. Apple: `nw_path_status_satisfied` defers to its own
  probing.
- **VPN.** Both platforms report the underlying transport (Wifi, Cellular,
  Ethernet) when a VPN is layered on top. The library doesn't currently
  expose a "via VPN" flag.
- **Wired Ethernet on macOS.** Kotlin/Native's `platform.Network` cinterop
  doesn't expose `nw_interface_type_wired_ethernet` at this Kotlin
  version. Wired Ethernet on macOS surfaces as `Transport.Other` (still
  reachable, just unlabelled). Android's `TRANSPORT_ETHERNET` is
  unaffected.
- **Threading.** Apple callbacks fire on a per-instance serial dispatch
  queue. Android callbacks fire on a binder thread. In both cases the
  callback body is a single `MutableStateFlow.value` write, which is
  thread-safe; collectors observe on whatever dispatcher they were
  started on.
- **Close semantics.** On per-instance factories (`Reachability(context)` /
  `Reachability()`), `close()` cancels the platform observer first, then
  the internal coroutine scope. After close, `status.value` continues to
  expose the last observed value but never emits again. `close()` is
  idempotent. On `Reachability.shared`, `close()` is an intentional no-op
  — the singleton lives for the process.

## Testing this library locally

Toolchain bootstrap once per machine — `brew install mise && mise install`
from the repo root. See [docs/contributing.md](../docs/contributing.md) for
the full prerequisite list (Xcode, Android SDK).

```bash
mise run check          # ktlint + all unit tests
mise run build:ios      # iOS device + Apple Silicon simulator debug frameworks
mise run build:macos    # macOS desktop debug framework
mise run build          # release Reachable.xcframework (sample-app local SPM)
mise run build:android  # Android AAR
```

Each of these is a thin wrapper around the equivalent `./gradlew` invocation;
see [`/mise.toml`](../mise.toml) for the exact mapping.

Manual end-to-end verification: build a tiny SwiftUI or Compose app, bind
to `reachability.status`, and toggle airplane mode, switch between Wi-Fi
and cellular, or enable Low Data Mode (iOS Settings → Cellular → Cellular
Data Options). Transitions land within about a second of the network
change.
