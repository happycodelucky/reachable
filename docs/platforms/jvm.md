# JVM

The JVM target covers desktop apps (Compose Desktop, Swing, JavaFX) and
server-side services on JVM 21+. It presents the same `Reachability` API as
every other platform, with one honest difference: the JVM has no
connectivity-change callback and no OS validation probe, so this backend
**polls the interface table** and reports **best-effort** reachability.

## Singleton entry point — `Reachability.shared`

```kotlin
import com.happycodelucky.reachable.Reachability

val reachability: Reachability = Reachability.shared
```

No Context, no initializer: the first access starts observing eagerly,
exactly like Apple. Calling `close()` on this instance is a no-op.

## Explicit-lifecycle factory

```kotlin
import com.happycodelucky.reachable.Reachability
import kotlin.time.Duration.Companion.seconds

val reachability: Reachability = Reachability(pollInterval = 10.seconds)
// ...
reachability.close() // stops the poll loop
```

`pollInterval` defaults to 5 seconds. Each poll reads the local interface
table — a cheap syscall, **no network traffic** — and identical consecutive
readings are conflated, so a shorter interval changes detection latency,
not emission volume. Status changes surface within one poll tick rather
than within milliseconds.

## What `isReachable` means here

On Apple and Android, `isReachable` is a *validated* signal — the OS has
probed a public endpoint. The JVM has no such probe, so here it means:

> At least one network interface is up, is not a loopback, and holds a
> routable (non-link-local) address.

Consequences:

- **Captive portals are not detected.** A hotel Wi-Fi with an
  unauthenticated portal reads as reachable. If you need proof of a
  working path, make a real request and treat its failure as the signal —
  see [Concepts → Validated vs available](../concepts/validated-vs-available.md#jvm).
- Host-only bridges created by container and hypervisor runtimes
  (`docker0`, `veth*`, `br-*`, `virbr*`, `vmnet*`, `vboxnet*`, `bridge*`)
  are ignored — a laptop running Docker in airplane mode correctly reads
  as offline.
- An active VPN tunnel (`utun*`, `tun*`) counts as reachable.
- A DHCP-failed adapter squatting on `169.254.x.x` does not count.

## Transport classification

The JDK exposes no transport type, so `Transport` is inferred from
interface naming — best-effort by design:

| Interface | Reported transport |
|---|---|
| `wlan0`, `wlp3s0`, `wlx…` (Linux), Windows adapters whose name contains "Wi-Fi" / "Wireless" | `Transport.Wifi` |
| `eth0`, `enp3s0`, `eno1`, `ens33`, `enx…`, `em1`, Windows "Ethernet" adapters | `Transport.Ethernet` |
| `wwan0`, `wwp…`, `rmnet0`, `ppp0`, "Mobile Broadband" adapters | `Transport.Cellular` |
| Anything unrecognised — notably macOS's `en0`, which may be Wi-Fi or wired | `Transport.Other` |

When several interfaces are active at once, the highest-priority transport
wins: `Wifi > Ethernet > Cellular > Other` — the same rule as every other
platform.

```kotlin
when (reachability.status.value.transport) {
    Transport.Wifi, Transport.Ethernet, Transport.Cellular -> { /* classified */ }
    Transport.Other -> { /* up and routable, type unknown — common on macOS JVMs */ }
    Transport.None -> { /* not reachable */ }
}
```

## Metering

`isDataMetered` is always `false` on the JVM — the JDK has no metering
signal. Don't branch download policy on it for desktop/server builds.

## Platform floor

JVM 21. The published jar is architecture-neutral bytecode, so any OS and
CPU with a JVM 21 runtime works — including the Linux and Windows hosts
that have no native Reachable target.

## See also

- [Concepts → Validated vs available](../concepts/validated-vs-available.md): why validated matters, and the JVM gap.
- [Concepts → Lifecycle](../concepts/lifecycle.md): when to construct one Reachability vs many.
