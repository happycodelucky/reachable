# Compose binding

Bind `reachability.status` to a `@Composable` via
`collectAsStateWithLifecycle()` (from
`androidx.lifecycle:lifecycle-runtime-compose`). The lifecycle-aware
variant auto-pauses Flow collection when the activity goes to STOPPED,
which prevents the underlying `NetworkCallback` from doing useless work in
the background.

## The pattern

```kotlin
@Composable
fun ConnectivityBanner(reachability: Reachability) {
    val status by reachability.status.collectAsStateWithLifecycle()
    if (!status.reachable) {
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

Call it from your top-level scaffold:

```kotlin
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Application-scoped Reachability — survives configuration changes.
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
lifecycle and gives you somewhere to put `viewModelScope.launch { … }` for
side effects:

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
    if (!status.reachable) Text("You're offline")
}
```

`SharingStarted.WhileSubscribed(5_000)` keeps the upstream collection alive
for 5 seconds after the last subscriber unsubscribes — useful so a quick
config change doesn't drop and re-establish the subscription.

## What can go wrong

- **Constructing `Reachability` inside `@Composable`** without `remember`
  re-creates the platform observer on every recomposition. Either hoist
  into the Application class (as above) or wrap in
  `remember(context) { Reachability(context) }` if the lifetime really is
  composable-scoped.

- **Calling `collectAsState()` instead of `collectAsStateWithLifecycle()`**
  keeps collecting in the background even when the activity is STOPPED.
  The library's StateFlow is cheap to keep alive, but it's still wasteful.
  Prefer the lifecycle-aware variant in any production app.

- **Branching on `metering` and forgetting `Constrained`.** Same trap as
  Swift — Kotlin's `when` is exhaustive on enums. The compiler will catch
  it. Treat `Constrained` as never-emitted on Android (so you can collapse
  it into the Metered branch).

  ```kotlin
  when (status.metering) {
      Metering.Unmetered    -> showHighDataPrompts()
      Metering.Metered,
      Metering.Constrained  -> hideHighDataPrompts()
  }
  ```

## Synchronous read

For a one-off, no recomposition involved:

```kotlin
val now: ReachabilityStatus = reachability.status.value
if (now.reachable) {
    // …
}
```

For a one-shot suspending read (e.g. in `viewModelScope.launch`):

```kotlin
viewModelScope.launch {
    val now = reachability.status.first()
    // …
}
```
