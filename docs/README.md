# ClawInOne documentation

ClawInOne runs upstream OpenClaw inside Android's official AVF Debian
environment and connects it back to the same phone for development, app
delivery, interactive presentation, and permission-scoped device use.

## Start here

- [Installation](installation.md) — supported-device requirements, APK verification, and guided setup.
- [Building from source](building.md) — prerequisites, debug APK builds, and repository checks.
- [Architecture](architecture.md) — the same-device product model and ownership boundaries.
- [Device compatibility](device-compatibility.md) — runtime requirements and verified devices.
- [Development environment](development-environment.md) — the managed local OpenClaw environment.
- [Development profiles](development-profiles.md) — qualified Kotlin, NDK, Flutter, Godot, React Native, and Web stacks.

## Android capabilities

- [Device Bridge](device-bridge.md) — private, exact-phone ADB ownership.
- [App Delivery](app-delivery.md) — bounded APK inspection, installation, and readback.
- [VScreen](vscreen.md) — the built-in interactive virtual Android display.
- [Android Use](android-use.md) — explicit, permission-scoped Agent operation of Android apps.
- [Terminal](terminal.md) — shell access to the same Debian environment.

## Evidence and direction

- [Validation](validation.md) — observed retained-device evidence and open release gaps.
- [Roadmap](roadmap.md) — first-release work and explicitly deferred scope.

## Maintainer contracts

The [maintainer index](maintainers/README.md) covers first run, runtime recovery,
Supervisor behavior, Projects, DevKit, Plugins and Skills, AI routing,
localization, and verification rules. These are implementation contracts rather
than the primary product introduction.

The manual Alpha packaging and publication procedure is documented in
[Releasing](releasing.md).

## Documentation policy

Versioned documents describe current behavior, accepted decisions, reusable
evidence, or contributor workflows. Session handoffs, temporary implementation
order, unfinished investigations, and raw logs are not product documentation;
promote their accepted conclusions here and keep the transient material out of
the repository. Private verification artifacts belong in ignored
`.verification/` and must never contain committed credentials or device secrets.
