# ClawInOne Android

This directory contains the standalone Android product. It is derived from the
OpenClaw Android App, but it builds only from files tracked by the ClawInOne
repository.

## Open in Android Studio

Open `apps/android/` as the project root. Use JDK 21 and the Android SDK version
declared by the Gradle build.

## Build and verify

```bash
./gradlew :app:ktlintCheck
./gradlew :app:testThirdPartyDebugUnitTest
./gradlew :app:lintThirdPartyDebug
./gradlew :app:assembleThirdPartyDebug
```

The APK is written below `app/build/outputs/apk/thirdParty/debug/`. Bootstrap
scripts are staged from the repository-level `bootstrap/` directory during the
build; do not edit generated copies under `app/build/`.

Install the debug build on a connected device:

```bash
adb install -r app/build/outputs/apk/thirdParty/debug/*-debug.apk
```

Useful device and performance helpers remain in `scripts/`. They are optional
development tools and are not release entry points.

## Release status

The first experience release is in preparation. Versioning and the manual Alpha
packaging procedure are documented in [Releasing](../../docs/releasing.md). A
public artifact still requires the complete candidate gate, project-owned
signing continuity, artifact verification, and distribution. Never reuse
OpenClaw's private signing or release infrastructure.

## Source boundary

Product changes live here. The sibling OpenClaw fork is used only to inspect and
prepare upstream changes; this build must never read source files from it. See
the repository-level `UPSTREAM.md` for the import boundary.
