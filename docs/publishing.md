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
2. Set `version` to the semver string without the `v` prefix (e.g. `0.2.0`).
3. Leave `dryRun` at its default (`true`) for the first run.
4. **Run workflow.**

The dry run uploads to the Central Portal staging area and stops, so the
artifact set can be reviewed at
<https://central.sonatype.com/publishing/deployments> before anything is
released to the public. Click **Publish** in the Portal to release, or
**Drop** to discard.

Once the staged set looks right, re-run the workflow with `dryRun=false`.
That path is irreversible: it uploads, closes, and releases the deployment
in one atomic step.

After a real publish, the workflow tags the head commit as `vX.Y.Z`,
pushes the tag, and creates a GitHub Release with auto-generated notes
(commits since the previous tag). The shields.io `Release` badge on the
README updates within seconds.

Within ~30 min the release is searchable at
<https://central.sonatype.com/artifact/com.happycodelucky.reachable/reachable>.
Maven Central indexing into <https://repo1.maven.org/maven2/> usually
takes a few minutes longer.

**Maven Central releases are permanent.** Sonatype never deletes
published artifacts. A bad version means cutting a fresh `0.2.1` that
supersedes it; there is no rollback. Use a `-SNAPSHOT` version for any
experimental upload — vanniktech auto-routes snapshots to the Central
Portal snapshots endpoint, which is mutable.

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
