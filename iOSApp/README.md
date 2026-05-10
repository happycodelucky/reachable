# iOSApp — Reachable sample

Minimal SwiftUI app that subscribes to `reachability.status` and renders the
live `ReachabilityStatus`. Toggle airplane mode or switch between Wi-Fi and
cellular to see transitions arrive in real time. Enable Low Data Mode in
Settings → Cellular → Cellular Data Options to see `Metering.constrained`.

## Prerequisites

- Xcode 16+ (the project targets iOS 18.0).
- [`xcodegen`](https://github.com/yonaskolb/XcodeGen) to generate the `.xcodeproj`
  from `project.yml`. Install with `brew install xcodegen`.
- A working JDK 21 + Gradle setup at the repo root (the SPM dependency is
  produced by Gradle).

## First-time setup

```bash
cd iOSApp
make all          # rebuilds local SPM artifact + generates xcodeproj + opens Xcode
```

Then in Xcode pick the iOSApp scheme + an iOS Simulator destination and Run.

## Iteration loop

After editing Kotlin in `/reachable/src/...`:

```bash
cd iOSApp
make spm          # rebuilds the debug XCFramework + updates root Package.swift
```

Then back to Xcode and rebuild the app target. **You do not need to regenerate
the .xcodeproj** unless you change `project.yml`.

## Files

- `project.yml` — xcodegen spec. Edit this; commit it.
- `iOSApp.xcodeproj/` — generated. Gitignored.
- `iOSApp/iOSApp.swift` — single-file SwiftUI app + `ReachabilityViewModel`
  bridging the Kotlin `StateFlow` as a Swift `AsyncSequence` via SKIE.
- `Makefile` — convenience targets (`xcodeproj`, `spm`, `all`).

## How the Reachable dependency is wired

`project.yml` declares a local Swift Package at `path: ../`. That points at
`/Package.swift` at the repo root, which is **generated** by
`./gradlew :reachable:spmDevBuild` and references the locally-built debug
`Reachable.xcframework`. Xcode re-reads the binary on every open, so the
edit-build cycle for Kotlin code is:

1. Edit Kotlin under `/reachable/src/...`
2. `make spm` (or `./gradlew :reachable:spmDevBuild` from the repo root)
3. Rebuild the iOSApp target in Xcode

After a `vX.Y.Z` release, the same `Package.swift` is overwritten with a
remote-binary form pointing at the GitHub Packages-hosted XCFramework zip;
real downstream apps consume the package via the
`https://github.com/happycodelucky/reachable.git` URL pinned to a tag rather
than the local path used here.
