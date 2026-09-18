# Building from source

Use source builds for development and review. Public users should prefer a
signed APK from [GitHub Releases](https://github.com/boxuechen/claw-in-one/releases)
once one is available.

## Prerequisites

- Git and Bash;
- JDK 21;
- Android SDK with platform and build tools 37;
- Node.js 22;
- Rust 1.95 with the `aarch64-unknown-linux-musl` target.

Install the Rust target with:

```bash
rustup target add aarch64-unknown-linux-musl
```

Set `ANDROID_HOME` or place `sdk.dir` in the ignored
`apps/android/local.properties`. Do not commit machine-local SDK paths.

## Build a debug APK

```bash
git clone https://github.com/boxuechen/claw-in-one.git
cd claw-in-one/apps/android
./gradlew :app:assembleThirdPartyDebug
```

The build compiles the static ARM64 Supervisor and stages the canonical
repository-level Bootstrap, product Plugins, and product Skills into the APK.
It never reads from a sibling OpenClaw checkout.

The APK is written below:

```text
apps/android/app/build/outputs/apk/thirdParty/debug/
```

The debug application ID is `io.github.boxuechen.clawinone.debug`; it can coexist
with a signed release build.

## Install a debug APK

With one explicitly selected device visible to the Android SDK `adb`:

```bash
adb install -r app/build/outputs/apk/thirdParty/debug/*-debug.apk
```

This desktop command is only a contributor convenience. ClawInOne's user-facing
installation and same-phone development flow do not require a PC.

## Verify the repository

From the repository root, run the complete release-candidate gate:

```bash
bash scripts/check.sh
```

Focused development may select an existing scope, for example:

```bash
bash scripts/check.sh --scope docs
bash scripts/check.sh --scope android
bash scripts/check.sh --scope android-use
```

Scope selection and device evidence rules are documented in
[Verification workflow](maintainers/testing.md). Release builds require a
project-owned signing key and explicit build identity; maintainers should follow
[Releasing](releasing.md) rather than weakening those checks.
