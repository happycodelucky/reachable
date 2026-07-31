# Toolchain audit playbook

A repeatable audit for a Kotlin Multiplatform repo's build tooling. Written to be
copied between repos — nothing here is specific to one project.

Derived from a real audit of this template (2026-07-30). Three of the seven
findings were tooling that had **silently stopped working** while still looking
configured. That's the theme: the expensive bugs aren't wrong versions, they're
things that report success while doing nothing.

---

## 1. The method

Four rules, in priority order. They matter more than the specific findings below,
because findings expire and method doesn't.

### Verify by running, not by reading

Every comment in a build script is a claim about the past. Some were true when
written and are false now. Before trusting a comment that gates behaviour
("silenced because upstream bug X"), re-test the claim — the upstream bug may be
fixed, and the workaround may now be suppressing real signal.

In this audit, a `dependency-analysis` config carried a comment explaining why
three categories were silenced. Re-running with all three un-silenced showed one
category now reported **nothing at all** — the upstream false positive had been
fixed, and the silencer was pure loss.

### Query registries directly; don't web-search versions

Web search returns stale, contradictory, and hallucinated version numbers. Ask
the registry. See §4 for copy-paste commands. Note that Maven's `<release>`
marker is *not* "latest stable" — it's the last non-snapshot deployed, and betas
count. Filter the version list yourself.

### Distinguish "advisory" from "broken"

A tool printing `proceed at your own risk` is not the same as a tool failing.
Decide deliberately, verify empirically, and write down which you chose and why —
otherwise the next person re-litigates it.

### Differential-test config changes

When fixing a config whose semantics you can't observe directly, construct the
old and new forms side by side and run the real validator against both. Proving
"the old one is rejected AND the new one passes" is much stronger than "the new
one passes."

---

## 2. Findings that recur

Each has a **symptom**, a **detection** command, and a **fix**.

### A. Renovate `matchPackagePrefixes` silently dead

**Symptom.** Package rules using `matchPackagePrefixes` — removed in Renovate
**v38**. Renovate auto-migrates, so nothing visibly breaks; the rule just stops
being yours. If that rule encodes a policy ("never auto-bump X"), the policy is
now enforced by a migration heuristic instead of by you.

**Second, older bug in the same place.** Prefix matching has no word boundary, so
`org.jetbrains.kotlin` also matches `org.jetbrains.kotlinx` — silently disabling
coroutines/serialization/atomicfu updates. **The v38+ auto-migration preserves
this**, rewriting to `org.jetbrains.kotlin{/,}**`, which still over-matches.

**Detection.**

```bash
grep -rn 'matchPackagePrefixes\|matchPackagePatterns' renovate.json* .github/renovate.json* 2>/dev/null
```

**Fix.** Use an anchored regex, not a glob, when a boundary matters:

```json5
// Matches org.jetbrains.kotlin and org.jetbrains.kotlin.* / org.jetbrains.kotlin:*
// but NOT org.jetbrains.kotlinx
matchPackageNames: ['/^org\\.jetbrains\\.kotlin([.:]|$)/'],
```

**Verify (differential).**

```bash
mkdir -p /tmp/rn-old /tmp/rn-new
# put the old form in /tmp/rn-old/renovate.json5, the new form in /tmp/rn-new/
(cd /tmp/rn-old && npx --yes --package renovate@latest -- renovate-config-validator)
(cd /tmp/rn-new && npx --yes --package renovate@latest -- renovate-config-validator)
```

Expect `WARN: Config migration necessary` on the old and a clean pass on the new.
Run the validator **from the directory** — passing a path makes it validate as
*global* config, which checks different rules.

> Pin the validator major. `npx --package renovate` may resolve to an old major
> whose schema still accepts the removed key — which would "validate" a config
> that current Renovate rewrites.

### B. Gated-off tooling that became permanently inert

**Symptom.** A tool was disabled behind a flag to work around an upstream bug,
with a comment saying "re-enable when fixed." Nobody re-checks. The task still
exists, CI may still name it, and docs still describe it as working — but it
does nothing. This is worse than not having the tool, because the docs lie.

**Detection.** Grep for gates and commented-out CI steps:

```bash
grep -rn 'enable[A-Z][A-Za-z]*\s*==\|providers.gradleProperty(' build.gradle.kts */build.gradle.kts
grep -rn '^\s*#\s*\(mise\|\./gradlew\|npm\|yarn\)' .github/workflows/
```

Then, for each gate, **check whether the upstream issue is closed** and test with
the gate flipped on.

**Fix.** Remove the gate, restore the CI step, and re-derive any tuning the
workaround implied.

### C. Plugin/package id migrations that only warn

**Symptom.** A build prints `X is deprecated; use Y instead` on every
configuration. Because it's a warning, it survives indefinitely.

**Detection.** These only appear on a real build, so read the log:

```bash
./gradlew help 2>&1 | grep -iE 'deprecat|renamed|moved|instead|at your own risk'
```

**Fix.** Adopt the new coordinate. Check whether the *Java package* also changed —
usually it hasn't, so imports are untouched and the change is one line.

### D. Comment/doc drift from reality

**Symptom.** A CI job comment lists steps the job doesn't run. A contributor guide
describes a task that's inert. An agent guide (`CLAUDE.md`/`AGENTS.md`) hardcodes
versions that also live in the version catalog, creating two sources of truth.

**Detection.** For any comment claiming a task runs, prove it from the task graph
rather than from the script:

```bash
./gradlew <module>:check --dry-run | grep -iE 'abi|detekt|lint|Test$' | sort -u
```

**Fix.** Correct the comment, or delete the duplicated fact and point at the one
source of truth. Prose that restates a version number will drift; prose that says
"read the catalog" cannot.

### E. Provenance leaks in a distilled template

**Symptom.** A repo distilled from an earlier project still carries the old
project's name in headers, and — worse in a *template* — names identifiers that
won't exist after rendering.

**Detection.**

```bash
grep -rniE 'old-project-name' . --exclude-dir=.git --exclude-dir=build
# and audit which files the render script actually rewrites
```

**Fix.** Replace with the template's token so the render script substitutes it.
Then **test-render into a scratch copy** and assert nothing leaked:

```bash
mkdir -p /tmp/render-test && git archive HEAD | tar -x -C /tmp/render-test
(cd /tmp/render-test && sh scripts/init.sh testname --group com.acme --org acme)
grep -rlE '__[A-Z_]+__|old-project-name|old-org' /tmp/render-test || echo "clean"
```

`git archive HEAD` gives a clean tree with no build output — much faster and more
accurate than `cp -r`.

### F. Dead code that looks load-bearing

**Symptom.** A safety check whose conditions can never be satisfied, usually with
a long comment describing the protection it provides.

Real example — a "stable versions only" filter:

```kotlin
val stableVersion = "^[0-9][0-9.]*$".toRegex()
val preReleaseQualifier = "(?i)[.\\-]?(alpha|beta|rc|...)".toRegex()

rejectVersionIf {
    !stableVersion.matches(candidate.version) ||
        preReleaseQualifier.containsMatchIn(candidate.version)   // unreachable
}
```

If a string matches `^[0-9][0-9.]*$` it contains only digits and dots, so it can
never contain an alphabetic qualifier. The second clause never fires.

**Why it matters.** The risk isn't the wasted cycles — it's that someone later
loosens the first regex believing the second is a backstop.

**Fix.** Delete the unreachable branch. State in the comment which single check is
load-bearing, and what you'd have to restore if you relax it.

### G. Diagnostics that can't observe anything

**Symptom.** A "run the health check" task wired to `--dry-run`, `--version`, or
some other no-op. Build-health plugins (Gradle Doctor and friends) instrument
*task execution* — timings, cache hit/miss, download throughput. With nothing
executing they measure nothing and report nothing, which is indistinguishable
from a clean bill of health.

**Detection.** Read the task body and ask: does this actually execute work?

**Fix.** Run a real build. Document that such tools can only diagnose work that
happened, so an up-to-date tree yields little — run after a clean or a real
change.

---

## 3. Ordering and commit discipline

Sequence a toolchain refresh so a bisect lands on one cause:

1. **Zero-build-risk fixes first** — config, comments, docs. Cheap to review,
   nothing to revert.
2. **Un-gate / re-enable tooling** — behaviour change, no version movement.
3. **Bundle low-risk version bumps** — everything not version-locked to something
   else.
4. **Isolate the constrained bump** — the one bounded by another tool's supported
   range gets its own commit, so it reverts alone.
5. **Cleanups last.**

**Never bundle the language/compiler bump with anything else** when the build sets
`allWarningsAsErrors`. A compiler bump can introduce new warnings, which become
build failures; if it's bundled you can't tell which change broke the build.

Commit messages should record what you *verified*, with timings, not just what you
changed — that's what makes the claim auditable later.

---

## 4. Version-check commands

Registry queries beat search. Substitute the group path and artifact.

```bash
# Maven Central — recent versions (filter pre-releases yourself)
latest() {
  curl -sf "https://repo1.maven.org/maven2/$1/$2/maven-metadata.xml" \
    | grep -o '<version>[^<]*' | sed 's/<version>//' | tail -8
}
latest org/jetbrains/kotlin kotlin-gradle-plugin
latest org/jetbrains/kotlinx kotlinx-coroutines-core

# Gradle Plugin Portal — plugin marker artifacts
curl -sf "https://plugins.gradle.org/m2/<id-as-path>/<id>.gradle.plugin/maven-metadata.xml" \
  | grep -o '<version>[^<]*' | sed 's/<version>//' | tail -5

# Google Maven (AGP, androidx) — NOT on Maven Central
curl -sf "https://dl.google.com/dl/android/maven2/com/android/tools/build/gradle/maven-metadata.xml" \
  | grep -o '<version>[^<]*' | sed 's/<version>//' | grep -vE 'alpha|beta|rc' | tail -8

# Gradle itself
curl -sf https://services.gradle.org/versions/current
```

Cross-check with the repo's own reporter once bumped — it sees the whole graph
including transitive plugin internals:

```bash
mise run dependencies:outdated   # or: ./gradlew dependencyUpdates
```

**Bump Gradle via the wrapper task**, never by hand-editing the properties file —
it keeps the properties, scripts, and jar consistent:

```bash
./gradlew wrapper --gradle-version X.Y.Z --distribution-type all
```

Then update any *other* pin of Gradle (e.g. a `mise.toml` / `.tool-versions`
entry) in the same commit, and group them in Renovate so they can't drift.

---

## 5. KMP-specific notes

- **SKIE bounds Kotlin.** Never bump Kotlin past SKIE's supported range; bump
  SKIE first and confirm from its changelog that it *names* the target Kotlin
  version. SKIE's per-Kotlin runtime artifact naming is not a reliable support
  signal — read the changelog.
- **`dependency-analysis` on hierarchical source sets** is structurally noisy: a
  dependency declared once in `commonMain` is visible in every leaf set, so the
  plugin advises re-declaring it per leaf. Expect to silence
  `usedTransitiveDependencies`. Re-test the other categories periodically —
  they get fixed.
- **Exclude only what the tool structurally cannot see** — deliberate `api`
  re-exports, convention-plugin-injected baselines. Do *not* exclude genuinely
  unused scaffolding; that advice is correct and clears itself.
- **DAGP's `AGP_MAX` lags the AGP release train**, producing an advisory warning
  on current AGP. Verify equivalent output and document the choice.
- **A JVM-only test run is not a gate.** Native compile and lint failures hide
  there. The gate is the full multi-target `check`.
- **Prove the ABI check actually runs** rather than assuming `check` depends on
  it — see §2.D's `--dry-run` graph inspection.
- **After a compiler/SKIE bump, build the XCFramework too.** `check` doesn't
  cover framework linking, and Swift-facing output (e.g. `.swiftinterface`
  emission required by recent Xcode) can regress independently.

---

## 6. Audit checklist

```
[ ] Every gate/flag disabling a tool re-checked against its upstream issue
[ ] Every commented-out CI step re-evaluated
[ ] Build log read for deprecation / renamed-id / "at your own risk" warnings
[ ] Renovate (or equivalent) config validated against a CURRENT major
[ ] Matchers checked for prefix over-match across sibling namespaces
[ ] Versions confirmed from registries, not from memory or search
[ ] Compiler/language bump isolated in its own commit
[ ] Full multi-target check run cold, not from cache — record the timing
[ ] Framework/packaging artifact built after any compiler or interop bump
[ ] Public API/ABI dump diffed (no unintended surface change)
[ ] Template repos: test-rendered into a scratch copy, asserted no token leaks
[ ] Comments that claim a task runs proved against the actual task graph
[ ] Docs/agent guides carry no duplicated version facts
```
