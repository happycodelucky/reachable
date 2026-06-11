# Contributing

## Development environment

### Toolchain (mise)

[`mise`](https://mise.jdx.dev) pins every non-Gradle build dep: JDK, the
Gradle bootstrap binary, Python (for the docs toolchain), `xcodegen`, `gh`,
`swiftlint`, and `swiftformat`. Versions live in
[`/mise.toml`](https://github.com/happycodelucky/reachable/blob/main/mise.toml)
and match what CI runs.

```bash
brew install mise        # one-time, any directory
mise trust               # one-time per checkout, accepts mise.toml
mise install             # installs every tool at the pinned version
```

After that, the `mise run ...` task surface (see "Building locally" below)
is the recommended entry point. Raw `./gradlew`, `mkdocs`, and `xcodegen`
invocations still work; mise just ensures everyone runs the same versions.

### Other prerequisites

- Latest stable Xcode that the pinned SKIE version supports — see
  [SKIE releases](https://github.com/touchlab/SKIE/releases). Xcode is
  not managed by mise; install it yourself.
- Android SDK with command-line tools; `local.properties` should set
  `sdk.dir`.

A few binding repo conventions worth knowing before you open a PR:
Kotlin-first dependencies, ARM-only targets, SKIE for the Swift surface,
Maven Central publishing, and the Apple platform-name casing rule
(`IOSPathMonitor`, not `IosPathMonitor`).

## Reporting a bug

Open an issue with:

1. Platform (iOS / iPadOS / macOS / Android), OS version, device or simulator.
2. The reachability state when the bug reproduces (Wi-Fi, cellular, captive
   portal, Low Data Mode, etc.).
3. The library version or commit SHA.
4. A short snippet showing expected vs observed behavior. Including the raw
   `ReachabilityStatus` value (`isReachable=… transport=… isDataMetered=…`) is
   usually enough.

VPN-over-Wi-Fi, multi-SIM, and metered-Wi-Fi on Android are corners worth
flagging when you hit them.

## Building locally

The mise tasks below wrap `./gradlew` — pick whichever surface you prefer.

```bash
mise run check          # ktlint, detekt, and every unit test in both published
                        # modules (iOS sim, macOS, Android host)
mise run build:ios      # iOS device and Apple Silicon simulator debug frameworks
mise run build:macos    # macOS desktop debug framework
mise run build          # release Reachable.xcframework (SPM-consumable)
mise run build:android  # Android AAR

# Raw Gradle equivalents, for reference:
./gradlew :reachable:check :reachable-testing:check
./gradlew :reachable:linkDebugFrameworkIosArm64
./gradlew :reachable:linkDebugFrameworkIosSimulatorArm64
./gradlew :reachable:linkDebugFrameworkMacosArm64
./gradlew :reachable:assembleReachableXCFramework
./gradlew :reachable:assemble
./gradlew :androidApp:assembleDebug   # project is under apps/android
```

For the iOS and macOS samples, `mise run open:ios` (and `open:macos`) chains
`spm:dev` → `xcodegen` → opens the project in Xcode. See
[apps/ios/README.md](https://github.com/happycodelucky/reachable/blob/main/apps/ios/README.md)
and `apps/macos/README.md` for the iteration loop.

## Building the docs

```bash
mise run docs:install   # install pinned mkdocs toolchain into the active python
mise run docs:serve     # local preview at http://localhost:8000 (auto-runs docs:dokka)
mise run docs:build     # what CI runs; strict mkdocs build
mise run docs:check     # nav coverage, recipes have code blocks
```

`mkdocs build --strict` fails on broken internal links and dead anchors.
`docs/check.py` enforces the structural invariants: every page is referenced
from the site navigation, every recipe has at least one code block.

## Pull request expectations

- `mise run check` passes locally.
- New public API has KDoc and an [API design](docs/concepts/api-design.md)
  rationale (a sentence in the PR description is usually enough).
- New behavior has a test in `commonTest` or the relevant platform test
  source set.
- `mkdocs build --strict` and `docs/check.py` pass if docs changed.
- The PR title and body explain the *why*; the diff shows the *what*.

CI runs the full pipeline (build, test, XCFramework assembly, docs build,
docs validation) on every PR.

## Releasing

Releases publish to Maven Central via vanniktech maven-publish, then tag
the commit and create a GitHub Release with auto-generated notes. The
mechanics, one-time credential setup, and the dry-run / live-publish
toggle live in [`.github/PUBLISHING.md`](.github/PUBLISHING.md).
