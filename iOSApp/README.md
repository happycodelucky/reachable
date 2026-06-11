# iOSApp — Reachable sample

A SwiftUI app that subscribes to `reachability.status` and renders the
live `ReachabilityStatus`. Toggle airplane mode or switch between Wi-Fi
and cellular to see transitions in real time. Enable Low Data Mode in
Settings → Cellular → Cellular Data Options to flip `isDataMetered` on
Wi-Fi (Low Data Mode folds into the metered signal).

## Prerequisites

- Xcode 16+ (the project targets iOS 18.0). Xcode itself is not managed by
  mise; install the latest stable Xcode that the current SKIE release
  supports (see [docs/contributing.md](../docs/contributing.md)).
- [`mise`](https://mise.jdx.dev) for everything else. From the repo root:
  ```bash
  brew install mise
  mise trust
  mise install
  ```
  That installs the JDK, the Gradle bootstrap binary, `xcodegen`, `gh`,
  Python, `swiftlint`, and `swiftformat` at the versions pinned in
  [`/mise.toml`](../mise.toml).

## First-time setup

```bash
mise run open:ios   # rebuilds local SPM artifact, generates xcodeproj, opens Xcode
```

In Xcode, pick the iOSApp scheme and an iOS Simulator destination, then
Run.

## Iteration loop

After editing Kotlin in `/reachable/src/...`:

```bash
mise run spm:dev    # rebuilds the debug XCFramework, updates root Package.swift
```

Then rebuild the app target in Xcode. The `.xcodeproj` only needs
regenerating when `project.yml` changes.

## Files

- `project.yml` — xcodegen spec. Edit and commit.
- `iOSApp.xcodeproj/` — generated. Gitignored.
- `iOSApp/iOSApp.swift` — single-file SwiftUI app and
  `ReachabilityViewModel` bridging the Kotlin `StateFlow` as a Swift
  `AsyncSequence` via SKIE.
- `.swiftlint.yml`, `.swiftformat` — Swift lint and format configs
  consumed by `mise run lint:swift` and `mise run format:swift`.

## How the Reachable dependency is wired

`project.yml` declares a local Swift Package at `path: ../`. That resolves
to `/Package.swift` at the repo root. KMMBridge's
`./gradlew :reachable:spmDevBuild` rewrites it to point at the
locally-built debug `Reachable.xcframework`. Xcode re-reads the binary on
every open, so the edit-build cycle for Kotlin code is:

1. Edit Kotlin under `/reachable/src/...`
2. `mise run spm:dev` (or `./gradlew :reachable:spmDevBuild` from the repo root)
3. Rebuild the iOSApp target in Xcode

`/Package.swift` is committed, and the committed form is the *released*
one: a remote `.binaryTarget(url:checksum:)` referencing the
`Reachable.xcframework.zip` asset on the GitHub Release for the latest
version tag — that's what SPM consumers resolve when they add this repo
as a package (see
[docs/installation.md](../docs/installation.md#swift-package-manager)).
`spm:dev` flips it to a local `.binaryTarget(path:)` for iteration; that
rewrite is working-tree-only — don't commit it. `mise run spm:restore`
puts the committed version back.
