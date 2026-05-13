# React to changes

Patterns for reacting to reachability changes outside UI bindings: triggering
a one-time effect on the next recovery, streaming transitions to a side
channel, throttling reactions on flaky networks.

## Trigger an effect on the next recovery

`Flow.first { … }` suspends until the predicate matches:

=== "Kotlin"

    ```kotlin
    suspend fun waitForOnline(reachability: Reachability) {
        reachability.status.first { it.isReachable }
    }

    coroutineScope.launch {
        waitForOnline(reachability)
        retryFailedUploads()
    }
    ```

=== "Swift"

    ```swift
    func waitForOnline(_ reachability: any Reachability) async throws {
        for try await status in reachability.status {
            if status.isReachable { return }
        }
    }

    Task {
        try await waitForOnline(reachability)
        await retryFailedUploads()
    }
    ```

If the device is already online, the function returns immediately because
StateFlow emits the current value to a new collector. If offline, the call
suspends until the next emission with `reachable = true`.

## Distinct-only transitions

`StateFlow` already conflates identical consecutive values, so `collect { }`
only sees real changes. To react only when the `reachable` axis flips,
ignoring transport or metered-state churn, use the
[single-axis shortcut](../concepts/api-design.md#single-axis-shortcuts):

```kotlin
reachability.reachable.collect { isReachable ->
    if (isReachable) onlineTransitionLogger.log("back online")
    else onlineTransitionLogger.log("went offline")
}
```

The shortcut is a dedicated `MutableStateFlow` that the library updates
synchronously alongside `status`. It conflates identical consecutive values
and a late-joining collector immediately sees the current value. Don't
re-implement it manually with `.map { … }.distinctUntilChanged()` on top of
`status`.

The same pattern applies to the metered signal:

```kotlin
reachability.dataMetered.collect { isMetered ->
    if (isMetered) deferLargeTransfers()
}
```

## Detect transport changes for analytics

```kotlin
reachability.status
    .map { it.transport }
    .distinctUntilChanged()
    .drop(1)   // skip the initial value; only react to changes
    .onEach { newTransport ->
        analytics.track("transport_changed", mapOf("to" to newTransport.name))
    }
    .launchIn(applicationScope)
```

`drop(1)` skips the initial reading on app start; otherwise every cold
start would log a spurious "transport changed" event.

## Cancel a long-running task on going offline

```kotlin
val job = launch {
    runLongUploadOrSync()
}

reachability.status
    .first { !it.isReachable }
    .also {
        job.cancel(CancellationException("Lost connectivity"))
    }
```

With structured concurrency:

```kotlin
coroutineScope {
    val uploadJob = launch { runLongUploadOrSync() }
    launch {
        reachability.status.first { !it.isReachable }
        uploadJob.cancel("Lost connectivity")
    }
}
```

## Throttling reactions

Flaky networks can produce dozens of state changes a second. For analytics
events or toasts, debounce:

```kotlin
reachability.status
    .map { it.isReachable }
    .distinctUntilChanged()
    .debounce(500.milliseconds)
    .collect { isReachable -> showToast(if (isReachable) "Online" else "Offline") }
```

`debounce` waits for the value to stay stable for 500ms before emitting,
so a rapid offline → online → offline burst collapses to one emission.
