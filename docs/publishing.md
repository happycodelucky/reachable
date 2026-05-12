# Publishing

Maintainer-facing runbook for cutting a Reachable release. Aimed at the
person holding the publish keys; most contributors don't need this page.

Reachable publishes a single Kotlin Multiplatform artifact to Maven
Central via vanniktech's
[`gradle-maven-publish-plugin`](https://github.com/vanniktech/gradle-maven-publish-plugin):
Android AAR, KMP common metadata, per-target klibs
(`iosArm64`, `iosSimulatorArm64`, `macosArm64`), and sources + javadoc
jars. Everything is GPG-signed in-process. A native Swift Package
Manager distribution is on the v0.2 plan; see
[Installation](installation.md#apple-side-spm-roadmap).

## Cutting a release

1. **Actions → Release → Run workflow.**
2. Pick `bumpType` — `patch` (default), `minor`, or `major`.
3. Optionally fill `versionSuffix` for a pre-release (`beta`, `preview`,
   `rc.1`, …). Leave blank for a final release.
4. Leave `dryRun` at its default (`true`) for the first run.
5. **Run workflow.**

The version is computed, not specified. The workflow reads the latest
release tag, applies the chosen bump, sets the patch component to
`GITHUB_RUN_NUMBER` (a monotonic per-workflow counter), and appends the
suffix if one was given. The runner never types a version number.

If the latest release is `v0.3.7` and this is the 42nd run of the release
workflow:

| `bumpType` | `versionSuffix` | Computed version |
|---|---|---|
| `patch` | _(empty)_ | `0.3.42` |
| `minor` | _(empty)_ | `0.4.42` |
| `major` | _(empty)_ | `1.0.42` |
| `patch` | `beta` | `0.3.42-beta` |
| `patch` | `rc.1` | `0.3.42-rc.1` |
| `minor` | `preview` | `0.4.42-preview` |

The computed version appears in the **Release plan** section of the run
summary on the Actions UI before any irreversible step fires. If
something looks wrong (wrong bump type, mistyped suffix, unexpected
base), cancel the run and start over.

`GITHUB_RUN_NUMBER` doesn't reset across dry runs and real publishes, so
patch numbers have visible gaps (a dry run uses one number, the real
publish uses the next). That's intentional: re-running after a failure
gets a fresh patch automatically, avoiding "tag already exists" conflicts.

### Version suffix rules

`versionSuffix` is appended after a dash to produce a SemVer pre-release
version (`MAJOR.MINOR.PATCH-SUFFIX`). The workflow validates the suffix
against the SemVer 2.0 grammar before any publish: dot-separated lowercase
alphanumeric segments, hyphens allowed inside a segment. Valid examples:

- `beta`, `preview`, `alpha`, `dev`
- `rc1`, `rc2`, `rc.1`, `rc.2`
- `alpha.2.fix`, `beta-1`

Invalid examples (workflow fails with a clear error before publish):

- `BETA`, `Beta` — uppercase not allowed.
- `-beta`, `.rc`, `rc.` — segments can't start or end with a separator.
- `beta_1`, `beta+sha` — only alphanumeric and hyphen allowed inside a
  segment.

Pre-release versions sort *before* their corresponding final release in
SemVer: `0.3.42-rc.1` is older than `0.3.42`. So when cutting the final
release after one or more release candidates, just leave `versionSuffix`
blank — the same `patch`/`minor`/`major` bump produces a clean final
version.

When the suffix is also used in the previous release (`v0.3.42-rc.1` is
the latest tag), the workflow strips the suffix before computing the new
base — i.e. a `patch` bump on top of `v0.3.42-rc.1` produces
`0.3.<run>`, not `0.3.42-rc.1.<run>`.

### Dry run, then real publish

The dry run uploads to the Central Portal staging area and stops, so the
artifact set can be reviewed at
<https://central.sonatype.com/publishing/deployments> before anything is
released to the public. Click **Publish** in the Portal to release, or
**Drop** to discard.

The "stops at staging" behaviour depends on `automaticRelease = false`
in the `mavenPublishing { }` block in
[`reachable/build.gradle.kts`](https://github.com/happycodelucky/reachable/blob/main/reachable/build.gradle.kts).
If that flag is ever flipped to `true`, the dry run silently becomes a
real publish — vanniktech treats the post-upload "release" step as
automatic. The flag is load-bearing; do not change it without
understanding the cascade.

Before either publish step fires, the workflow does a
`Verify version not already published` check against
[`maven-metadata.xml`](https://repo1.maven.org/maven2/com/happycodelucky/reachable/reachable/maven-metadata.xml).
A collision fails the run with a clear message instead of letting
Sonatype reject the upload with a confusing "staging failed" error.

Once the staged set looks right, re-run the workflow with `dryRun=false`.
The version will be recomputed with a fresh `GITHUB_RUN_NUMBER`, so the
publish version won't match the dry-run version — that's fine; only the
real publish version is tagged and released.

After a real publish, the workflow tags the head commit `vX.Y.Z`, pushes
the tag, and creates a GitHub Release with auto-generated notes (commits
since the previous tag). The shields.io `Release` badge on the README
updates within seconds.

Within ~30 min the release is searchable at
<https://central.sonatype.com/artifact/com.happycodelucky.reachable/reachable>.
Maven Central indexing into <https://repo1.maven.org/maven2/> usually
takes a few minutes longer.

**Maven Central releases are permanent.** Sonatype never deletes
published artifacts. A bad version means cutting a fresh `patch` bump
that supersedes it; there is no rollback. Use a `-SNAPSHOT` version for
any experimental upload — vanniktech auto-routes snapshots to the
Central Portal snapshots endpoint, which is mutable. (Snapshots aren't
generated by this workflow; they're a `./gradlew publishToMavenCentral
-Pversion=X.Y.Z-SNAPSHOT` from a developer machine.)

## One-time setup

These steps were done once when Maven Central publishing was first wired up.
Re-doing any of them is only needed for key / credential rotation or if the
namespace is ever moved.

### 1. Claim the namespace

1. Log in to <https://central.sonatype.com>.
2. **View Namespaces** → **Add Namespace** → enter `com.happycodelucky`.
3. Copy the **Verification Key** Sonatype shows.
4. Add a DNS TXT record at the apex of `happycodelucky.com`:
    - Name: `@` (or leave blank, depending on the registrar's UI)
    - Value: the verification key, verbatim
5. Wait for DNS propagation, then click **Verify Namespace** in the Portal.

### 2. Generate a Sonatype user token

The Central Portal login password is **not** what Gradle uploads with.

1. Portal → top-right avatar → **View Account** → **Generate User Token**.
2. Save both fields. Sonatype only shows the password once.
   - The username is short (~12 chars).
   - The password is longer (~24 chars).
3. These map to the `MAVEN_CENTRAL_USERNAME` and `MAVEN_CENTRAL_PASSWORD`
   GitHub secrets below.

### 3. Generate a GPG signing key

Central requires every artifact to be GPG-signed.

```bash
# 4096-bit RSA. Use a real email and a strong passphrase.
gpg --full-generate-key

# Grab the long key ID (16 hex chars after the rsa4096/ marker).
gpg --list-secret-keys --keyid-format=long

# Publish the public half so Central can verify signatures. Use hkps://
# (port 443 / HTTPS) — the legacy hkp:// port 11371 is firewalled on
# many networks. Belt-and-braces: push to all three.
gpg --keyserver hkps://keyserver.ubuntu.com --send-keys <LONG_KEY_ID>
gpg --keyserver hkps://keys.openpgp.org    --send-keys <LONG_KEY_ID>
gpg --keyserver hkps://pgp.mit.edu         --send-keys <LONG_KEY_ID>

# Export the SECRET key. This is the blob that goes into the GitHub
# secret. Delete the .asc file from disk once stored in GitHub.
gpg --armor --export-secret-keys <LONG_KEY_ID> > reachable-signing.asc
```

After upload, confirm the key is findable by fingerprint:

```bash
gpg --keyserver hkps://keys.openpgp.org --recv-keys <LONG_KEY_ID>
```

Or in a browser: <https://keys.openpgp.org/search?q=YOUR_KEY_ID> — this is
the same lookup Sonatype performs at upload time.

**Quirk:** `keys.openpgp.org` strips the email address from uploaded keys
until you confirm via a verification link sent to that email. The
cryptographic material is uploaded regardless, so signature verification
works fine — but the key won't show up in email searches until you click
the link. `keyserver.ubuntu.com` and `pgp.mit.edu` don't strip emails.

### 4. Configure GitHub Actions secrets

Repo → **Settings** → **Secrets and variables** → **Actions** → **New
repository secret**. Four secrets:

| Secret name                          | Value                                                   |
|--------------------------------------|---------------------------------------------------------|
| `MAVEN_CENTRAL_USERNAME`             | Sonatype user token username (from step 2)              |
| `MAVEN_CENTRAL_PASSWORD`             | Sonatype user token password (from step 2)              |
| `MAVEN_CENTRAL_SIGNING_KEY`          | Full contents of `reachable-signing.asc` (from step 3)  |
| `MAVEN_CENTRAL_SIGNING_KEY_PASSWORD` | The GPG key passphrase (from step 3)                    |

`MAVEN_CENTRAL_SIGNING_KEY` must be the entire ASCII-armoured block,
including the `-----BEGIN PGP PRIVATE KEY BLOCK-----` and `-----END PGP
PRIVATE KEY BLOCK-----` lines. Paste it verbatim — GitHub's secret editor
preserves newlines.

The key ID itself doesn't need to be stored — the in-memory key blob
carries it.

## Local dry-run

Before cutting a release, you can sanity-check the publication shape
without uploading anything to Central:

```bash
# Builds, signs (if signing creds are present locally), and writes
# everything to ~/.m2/repository/com/happycodelucky/reachable/.
./gradlew :reachable:publishToMavenLocal -Pversion=0.2.0-test

# Inspect what got produced.
ls -lh ~/.m2/repository/com/happycodelucky/reachable/reachable/0.2.0-test/
```

Expect to see: `.aar`, `-sources.jar`, `-javadoc.jar`, `.module`, `.pom`,
and `.asc` next to each.

**vanniktech 0.36.0 fails the build if signing creds are missing.** That's
intentional — it stops you accidentally publishing an unsigned artifact
to Central, which the Portal would reject anyway. To dry-run locally you
have two choices:

1. **Inspect generated POMs only** (no signing required): run any of the
   `generatePomFileFor*Publication` tasks, then read
   `reachable/build/publications/<publication>/pom-default.xml`. This is
   what the project's CI smoke tests do.
2. **Full local publish with signing**: export the four
   `ORG_GRADLE_PROJECT_*` env vars before running Gradle.

```bash
export ORG_GRADLE_PROJECT_signingInMemoryKey="$(cat ~/path/to/reachable-signing.asc)"
export ORG_GRADLE_PROJECT_signingInMemoryKeyPassword="<passphrase>"
./gradlew :reachable:publishToMavenLocal -Pversion=0.2.0-test
```

`publishToMavenLocal` never contacts Central or GitHub Packages — it only
writes to your local `~/.m2`, so it's safe to run any time.

## Rotating credentials

### Sonatype user token

If the token leaks or you want a fresh one, generate a new one in the
Portal (Step 2 above), then update `MAVEN_CENTRAL_USERNAME` and
`MAVEN_CENTRAL_PASSWORD` in GitHub secrets. The old token continues to
work until you revoke it from the Portal.

### GPG signing key

If the signing key leaks: generate a new key (Step 3 above), upload its
public half, then update `MAVEN_CENTRAL_SIGNING_KEY` and
`MAVEN_CENTRAL_SIGNING_KEY_PASSWORD` in GitHub secrets. Past releases
signed with the old key remain valid — keyservers retain the public half
forever, so Central can still verify their signatures. Future releases
will be signed with the new key.

You can also publish a revocation certificate for the old key if you
generated one with `gpg --gen-revoke` — that's a stronger signal than
just leaving the key dormant.
