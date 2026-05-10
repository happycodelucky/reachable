# Contributing

Thanks for your interest in Reachable. This page covers how to build, test,
and submit changes.

## Development environment

- JDK 21 (Temurin recommended).
- Latest stable Xcode (currently the version SKIE 0.10.x supports — see
  [SKIE release notes](https://github.com/touchlab/SKIE/releases)).
- Android SDK with command-line tools; `local.properties` should set `sdk.dir`.
- Gradle 9.x (use the wrapper — `./gradlew`).
- Optional but recommended: [`xcodegen`](https://github.com/yonaskolb/XcodeGen)
  if you want to regenerate the iOS / macOS sample app projects
  (`brew install xcodegen`).

The repo's [CLAUDE.md](https://github.com/happycodelucky/reachable/blob/main/CLAUDE.md)
is the binding rule set — Kotlin-first deps, ARM-only targets, SKIE,
KMMBridge, the Apple-name casing rule. Read it before opening a PR.

## Reporting a bug

Open an issue with:

1. The platform (iOS / iPadOS / macOS / Android), OS version, device or simulator.
2. The reachability state when the bug reproduces (Wi-Fi up / cellular up /
   captive portal / Low Data Mode / etc.).
3. The library version (or commit SHA) you're using.
4. A short snippet showing what you expected vs what you observed.
   Including the raw `ReachabilityStatus` value (`reachable=… transport=…
   metering=…`) is usually enough.

For Android: VPN-over-Wi-Fi, multi-SIM, and metered-Wi-Fi are corners worth
flagging when you hit them.

## Building locally

```bash
./gradlew :reachable:check                                   # ktlint + all unit tests
./gradlew :reachable:linkDebugFrameworkIosArm64              # iOS device slice
./gradlew :reachable:linkDebugFrameworkIosSimulatorArm64     # Apple Silicon simulator
./gradlew :reachable:linkDebugFrameworkMacosArm64            # macOS desktop slice
./gradlew :reachable:assembleReachableXCFramework            # SPM-consumable artifact
./gradlew :reachable:assemble                                # Android AAR
./gradlew :androidApp:assembleDebug                          # the sample Android app
```

The sample iOS / macOS apps build via `xcodebuild` — see
`iOSApp/README.md` and `macOSApp/README.md` for their iteration loops.

## Building the docs

```bash
python3 -m venv .venv
.venv/bin/pip install -r docs/requirements.txt
.venv/bin/mkdocs serve            # local preview at http://localhost:8000
.venv/bin/mkdocs build --strict   # what CI runs
python3 docs/check.py             # nav coverage + recipes have code blocks
```

`mkdocs build --strict` fails on broken internal links and dead anchors;
`docs/check.py` adds a couple of structural invariants (every page
referenced from `mkdocs.yml`, every recipe has a code block).

## Pull request expectations

Before requesting review:

- `./gradlew :reachable:check` passes locally.
- Any new public API has KDoc and an [API design](concepts/api-design.md)
  rationale — usually a sentence in the PR description is enough.
- New behavior has a test in `commonTest` (the pure-mapping helpers) or in
  the relevant platform test source set.
- `mkdocs build --strict` and `docs/check.py` pass if you touched the docs.
- The PR title and body explain the *why* — the *what* shows up in the diff.

The full CI pipeline (build, test, XCFramework assembly, docs build, docs
validation) runs on every PR.
