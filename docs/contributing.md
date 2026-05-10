# Contributing

## Development environment

- JDK 21 (Temurin recommended).
- Latest stable Xcode that the pinned SKIE version supports — see
  [SKIE releases](https://github.com/touchlab/SKIE/releases).
- Android SDK with command-line tools; `local.properties` should set `sdk.dir`.
- Gradle 9.x via the wrapper (`./gradlew`).
- [`xcodegen`](https://github.com/yonaskolb/XcodeGen)
  (`brew install xcodegen`) to regenerate the iOS / macOS sample projects.

[CLAUDE.md](https://github.com/happycodelucky/reachable/blob/main/CLAUDE.md)
is the binding rule set: Kotlin-first dependencies, ARM-only targets, SKIE,
KMMBridge, the Apple-name casing rule. Read it before opening a PR.

## Reporting a bug

Open an issue with:

1. Platform (iOS / iPadOS / macOS / Android), OS version, device or simulator.
2. The reachability state when the bug reproduces (Wi-Fi, cellular, captive
   portal, Low Data Mode, etc.).
3. The library version or commit SHA.
4. A short snippet showing expected vs observed behavior. Including the raw
   `ReachabilityStatus` value (`reachable=… transport=… metering=…`) is
   usually enough.

VPN-over-Wi-Fi, multi-SIM, and metered-Wi-Fi on Android are corners worth
flagging when you hit them.

## Building locally

```bash
./gradlew :reachable:check                                   # ktlint + all unit tests
./gradlew :reachable:linkDebugFrameworkIosArm64              # iOS device slice
./gradlew :reachable:linkDebugFrameworkIosSimulatorArm64     # Apple Silicon simulator
./gradlew :reachable:linkDebugFrameworkMacosArm64            # macOS desktop slice
./gradlew :reachable:assembleReachableXCFramework            # SPM-consumable artifact
./gradlew :reachable:assemble                                # Android AAR
./gradlew :androidApp:assembleDebug                          # sample Android app
```

The iOS and macOS samples build via `xcodebuild`; see `iOSApp/README.md` and
`macOSApp/README.md`.

## Building the docs

```bash
python3 -m venv .venv
.venv/bin/pip install -r docs/requirements.txt
.venv/bin/mkdocs serve            # local preview at http://localhost:8000
.venv/bin/mkdocs build --strict   # what CI runs
python3 docs/check.py             # nav coverage, recipes have code blocks
```

`mkdocs build --strict` fails on broken internal links and dead anchors.
`docs/check.py` enforces the structural invariants: every page is referenced
from `mkdocs.yml`, every recipe has at least one code block.

## Pull request expectations

- `./gradlew :reachable:check` passes locally.
- New public API has KDoc and an [API design](concepts/api-design.md)
  rationale (a sentence in the PR description is usually enough).
- New behavior has a test in `commonTest` or the relevant platform test
  source set.
- `mkdocs build --strict` and `docs/check.py` pass if docs changed.
- The PR title and body explain the *why*; the diff shows the *what*.

CI runs the full pipeline (build, test, XCFramework assembly, docs build,
docs validation) on every PR.
