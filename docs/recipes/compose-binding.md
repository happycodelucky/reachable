# Compose binding

Use `collectAsStateWithLifecycle()` (from
`androidx.lifecycle:lifecycle-runtime-compose`) to bind
`reachability.status` to a `@Composable`. The lifecycle-aware variant
auto-pauses Flow collection when the activity goes to STOPPED, so the
underlying `NetworkCallback` doesn't keep working in the background.

## With `Reachability.shared` (recommended)

The library's bundled `androidx.startup` initializer attaches the
singleton before `Application.onCreate`, so you can call
`Reachability.shared` directly from any `@Composable` with no `remember`
wrapping needed:

```kotlin
@Composable
fun ConnectivityBanner() {
    // Reachability.shared is the process-lifetime singleton — no remember,
    // no context, no Application subclass required.
    val status by Reachability.shared.status.collectAsStateWithLifecycle()
    if (!status.isReachable) {
        Surface(
            color = MaterialTheme.colorScheme.errorContainer,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                "You're offline",
                modifier = Modifier.padding(8.dp),
            )
        }
    }
}
```

```kotlin
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Column {
                    ConnectivityBanner()
                    AppContent()
                }
            }
        }
    }
}
```

## With an injected instance (explicit lifecycle)

If you need an explicit-lifecycle instance (tests, per-feature observers),
pass it as a parameter:

```kotlin
@Composable
fun ConnectivityBanner(reachability: Reachability) {
    val status by reachability.status.collectAsStateWithLifecycle()
    if (!status.isReachable) {
        Surface(
            color = MaterialTheme.colorScheme.errorContainer,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                "You're offline",
                modifier = Modifier.padding(8.dp),
            )
        }
    }
}
```

From a top-level scaffold with an `Application`-owned instance:

```kotlin
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Application-scoped Reachability survives configuration changes.
        val reachability = (application as ReachableApplication).reachability
        setContent {
            MaterialTheme {
                Column {
                    ConnectivityBanner(reachability)
                    AppContent()
                }
            }
        }
    }
}

class ReachableApplication : Application() {
    val reachability: Reachability by lazy { Reachability(applicationContext) }
}
```

## Lifting into a ViewModel

For anything beyond a banner, lift the subscription into an
`androidx.lifecycle.ViewModel`. This decouples the UI from the platform
lifecycle and gives you `viewModelScope.launch { … }` for side effects:

```kotlin
class ConnectivityViewModel(reachability: Reachability) : ViewModel() {
    val status: StateFlow<ReachabilityStatus> =
        reachability.status
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5_000),
                initialValue = reachability.status.value,
            )
}

@Composable
fun ConnectivityBanner(viewModel: ConnectivityViewModel = viewModel()) {
    val status by viewModel.status.collectAsStateWithLifecycle()
    if (!status.isReachable) Text("You're offline")
}
```

`SharingStarted.WhileSubscribed(5_000)` keeps the upstream collection
alive for five seconds after the last subscriber unsubscribes, so a quick
configuration change doesn't drop and re-establish the subscription.

## What can go wrong

- **Constructing `Reachability(context)` inside `@Composable` without `remember`**
  re-creates the platform observer on every recomposition. Use
  `Reachability.shared` (preferred — already a stable singleton), hoist
  into the `Application` class (as above), or wrap in
  `remember(context) { Reachability(context) }` if the lifetime really is
  composable-scoped.
- **Calling `collectAsState()` instead of `collectAsStateWithLifecycle()`**
  keeps collecting even when the activity is STOPPED. The library's
  StateFlow is cheap to keep alive, but it's still wasteful. Prefer the
  lifecycle-aware variant.
- **Reading metered state.** Use the boolean `status.isDataMetered` directly —
  no enum branching needed:

  ```kotlin
  if (status.isDataMetered) hideHighDataPrompts() else showHighDataPrompts()
  ```

## Synchronous read

For a one-off without recomposition:

```kotlin
val now: ReachabilityStatus = reachability.status.value
if (now.isReachable) {
    // …
}
```

For a one-shot suspending read in `viewModelScope.launch`:

```kotlin
viewModelScope.launch {
    val now = reachability.status.first()
    // …
}
```
