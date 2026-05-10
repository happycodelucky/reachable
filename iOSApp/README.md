# iOSApp — Reachable sample

A SwiftUI app that subscribes to `reachability.status` and renders the
live `ReachabilityStatus`. Toggle airplane mode or switch between Wi-Fi
and cellular to see transitions in real time. Enable Low Data Mode in
Settings → Cellular → Cellular Data Options to see `Metering.Constrained`.

## Prerequisites

- Xcode 16+ (the project targets iOS 18.0).
- [`xcodegen`](https://github.com/yonaskolb/XcodeGen) to generate the
  `.xcodeproj` from `project.yml`. Install with `brew install xcodegen`.
- JDK 21 and Gradle at the repo root (the SPM dependency is produced by
  Gradle).

## First-time setup

```bash
cd iOSApp
make all          # rebuilds local SPM artifact, generates xcodeproj, opens Xcode
```

In Xcode, pick the iOSApp scheme and an iOS Simulator destination, then
Run.

## Iteration loop

After editing Kotlin in `/reachable/src/...`:

```bash
cd iOSApp
make spm          # rebuilds the debug XCFramework, updates root Package.swift
```

Then rebuild the app target in Xcode. The `.xcodeproj` only needs
regenerating when `project.yml` changes.

## Files

- `project.yml` — xcodegen spec. Edit and commit.
- `iOSApp.xcodeproj/` — generated. Gitignored.
- `iOSApp/iOSApp.swift` — single-file SwiftUI app and
  `ReachabilityViewModel` bridging the Kotlin `StateFlow` as a Swift
  `AsyncSequence` via SKIE.
- `Makefile` — convenience targets (`xcodeproj`, `spm`, `all`).

## How the Reachable dependency is wired

`project.yml` declares a local Swift Package at `path: ../`. That resolves
to `/Package.swift` at the repo root, which `./gradlew :reachable:spmDevBuild`
generates and points at the locally-built debug `Reachable.xcframework`.
Xcode re-reads the binary on every open, so the edit-build cycle for
Kotlin code is:

1. Edit Kotlin under `/reachable/src/...`
2. `make spm` (or `./gradlew :reachable:spmDevBuild` from the repo root)
3. Rebuild the iOSApp target in Xcode

After a `vX.Y.Z` release, the same `Package.swift` is overwritten with a
remote-binary form pointing at the GitHub Packages-hosted XCFramework zip.
Downstream apps consume the package via
`https://github.com/happycodelucky/reachable.git` pinned to a tag, not the
local path used here.
