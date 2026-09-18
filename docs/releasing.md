# Releasing

ClawInOne currently uses a manual, project-owned GitHub Alpha release process.
Generated APKs, signing material, credentials, and raw device evidence are never
committed.

## 1. Freeze the candidate

1. Start from the exact public commit intended for the release.
2. Update `apps/android/Config/ProductVersion.properties`. Use a prerelease name
   such as `0.1.0-alpha.1` and monotonically increase `CLAW_IN_ONE_VERSION_CODE`.
3. Confirm the pinned OpenClaw identity and third-party notices.
4. Run `bash scripts/check.sh` once from a clean source checkout.
5. Execute the release journey required by [Roadmap](roadmap.md), then record
   only reusable, sanitized observations in [Validation](validation.md).

## 2. Configure signing outside the repository

Create and protect a ClawInOne-owned Android release key. Losing this key prevents
future in-place updates; copying it into the repository permanently compromises
the release identity. Keep encrypted backups in more than one physical location.

Gradle reads these project properties from a local secret source such as
`~/.gradle/gradle.properties` or `ORG_GRADLE_PROJECT_...` environment variables:

```properties
CLAW_IN_ONE_ANDROID_STORE_FILE=/absolute/private/path/clawinone-release.jks
CLAW_IN_ONE_ANDROID_STORE_PASSWORD=<secret>
CLAW_IN_ONE_ANDROID_KEY_ALIAS=clawinone-release
CLAW_IN_ONE_ANDROID_KEY_PASSWORD=<secret>
```

Never reuse OpenClaw's private signing identity.

## 3. Build the signed APK

Release builds require an exact source commit and an ISO-8601 UTC build time:

```bash
release_commit="$(git rev-parse HEAD)"
release_timestamp="$(date -u +%Y-%m-%dT%H:%M:%S.000Z)"

cd apps/android
./gradlew :app:assembleThirdPartyRelease \
  -PclawInOneBuildCommit="$release_commit" \
  -PclawInOneBuildTimestamp="$release_timestamp"
```

The APK is written below `apps/android/app/build/outputs/apk/thirdParty/release/`.

## 4. Verify the artifact

Use the Android SDK `apksigner` from the selected build-tools installation:

```bash
apksigner verify --verbose --print-certs \
  app/build/outputs/apk/thirdParty/release/*-release.apk
```

Confirm the signer certificate digest against the retained release record. Then
generate a checksum beside the exact APK:

```bash
shasum -a 256 app/build/outputs/apk/thirdParty/release/*-release.apk > SHA256SUMS
```

Install that exact signed artifact on the supported release device, complete the
candidate journey, and test one signed update before tagging it.

## 5. Publish

Create an annotated `v<version>` tag only on the verified commit. A GitHub
prerelease contains:

- the signed `thirdParty-release.apk`;
- `SHA256SUMS`;
- verified devices and Android versions;
- the pinned OpenClaw version;
- installation steps and known limitations;
- links to both short demos.

After CI passes for the public commit, publish the prerelease manually or with
`gh release create`. Do not call an unverified artifact stable.

## Repository launch checklist

Before the first public announcement, set the repository topics to `openclaw`,
`android`, `ai-agent`, `on-device`, `android-development`, `avf`, and `scrcpy`;
set `https://clawin.one` as the homepage when it is live; upload a social preview;
and require the `Check` workflow on `main`. Repository docs remain canonical, so
an unused GitHub Wiki should be disabled.
