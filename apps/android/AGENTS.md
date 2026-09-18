# Android development policy

Repository-level instructions remain authoritative. This file adds Android-only
guardrails.

## Verification

Run checks from `apps/android/` with the checked-in Gradle wrapper. Follow
[Verification workflow](../../docs/maintainers/testing.md): select existing test classes and
compile/format targets for the changed owner. A commit or PR does not require
rerunning all Android tests, lint, fixtures, and assembly.

Use the broader App gate below for Android-wide build/shared infrastructure
changes, or when a concrete integration concern is not covered by focused checks:

```bash
./gradlew :app:ktlintCheck :app:testThirdPartyDebugUnitTest :app:lintThirdPartyDebug :app:assembleThirdPartyDebug
```

Inspect changed UI/interaction behavior on the device with the smallest relevant
path. Preview styling or mode changes do not automatically trigger full real-model
smoke. Reuse valid environment setup and unchanged fixtures; test/build the fixture
when its behavior or the selected integration check requires it. Once checks pass,
finish unless a new change, failure, or specific unresolved concern warrants more.

Do not treat commands from the upstream OpenClaw monorepo as available here.
ClawInOne has no Google Play or Fastlane release entry point yet.

## Product source boundary

- The repository-level `bootstrap/` directory owns Bootstrap and Supervisor
  scripts. Gradle only stages those canonical sources into the APK.
- The sibling OpenClaw fork is reference-only. Android builds and tests must not
  depend on its checkout.
- Keep upstream-derived runtime code separate from ClawInOne product policy and
  presentation adapters. Product screens consume explicit immutable state and
  action interfaces instead of reaching into the runtime directly.

## Licenses screen

- Bundled notices live in `THIRD_PARTY_LICENSES/openclaw/licenses/` as UTF-8
  `.txt` files and are discovered at runtime by `AndroidLicenseNotices`.
- Keep rows alphabetical by the filename-derived display title; do not hardcode
  individual rows or add first-party entries.
- Audit notices whenever dependencies change. When notice loading or presentation
  changes, update and run `AndroidLicenseNoticesTest`.
