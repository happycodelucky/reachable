# React to changes

Two common patterns for "do something when reachability changes" outside
of UI bindings: trigger a one-time effect on the next recovery, or stream
transitions to a side channel (logging, analytics, retries).

## Trigger an effect on the next recovery

Use `Flow.first { … }` to suspend until the predicate matches:

=== "Kotlin"

    ```kotlin
    suspend fun waitForOnline(reachability: Reachability) {
        reachability.status.first { it.reachable }
    }

    // …somewhere
    coroutineScope.launch {
        waitForOnline(reachability)
        retryFailedUploads()
    }
    ```

=== "Swift"

    ```swift
    func waitForOnline(_ reachability: any Reachability) async throws {
        for try await status in reachability.status {
            if status.reachable { return }
        }
    }

    Task {
        try await waitForOnline(reachability)
        await retryFailedUploads()
    }
    ```

If the device is already online when you call this, the function returns
immediately because StateFlow emits the current value to a new collector.
If it's offline, the call suspends until the next emission with
`reachable = true`.

## Distinct-only transitions

`StateFlow` already conflates identical consecutive values, so naive
`collect { }` only sees real changes. If you want to react only when the
**reachable** axis flips (ignoring transport / metering churn), filter
explicitly:

```kotlin
reachability.status
    .map { it.reachable }
    .distinctUntilChanged()
    .collect { isReachable ->
        if (isReachable) onlineTransitionLogger.log("back online")
        else onlineTransitionLogger.log("went offline")
    }
```

The `.map { it.reachable }.distinctUntilChanged()` collapses every
`ReachabilityStatus(true, Wifi, Unmetered)` → `ReachabilityStatus(true,
Cellular, Metered)` transition into a single emission for the unchanged
`reachable=true`.

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

`drop(1)` ensures you don't fire an event for the initial reading on app
start — otherwise every cold start would log a spurious "transport
changed" event.

## Cancel a long-running task on going offline

```kotlin
val job = launch {
    runLongUploadOrSync()
}

reachability.status
    .first { !it.reachable }
    .also {
        job.cancel(CancellationException("Lost connectivity"))
    }
```

Or, more idiomatically with structured concurrency:

```kotlin
coroutineScope {
    val uploadJob = launch { runLongUploadOrSync() }
    launch {
        reachability.status.first { !it.reachable }
        uploadJob.cancel("Lost connectivity")
    }
}
```

## Throttling reactions

If you're sending an analytics event or showing a toast on each transition,
debounce them — flaky networks can produce dozens of state changes a second:

```kotlin
reachability.status
    .map { it.reachable }
    .distinctUntilChanged()
    .debounce(500.milliseconds)
    .collect { isReachable -> showToast(if (isReachable) "Online" else "Offline") }
```

`debounce` waits for the value to be stable for 500ms before emitting, so
a rapid offline→online→offline burst collapses to a single emission.
