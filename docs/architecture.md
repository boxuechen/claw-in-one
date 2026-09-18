# Architecture

**Status:** implemented; the retained core path is verified on Pixel 8 / Android 17.

ClawInOne makes one Android phone both the computer running OpenClaw and the
Android device it builds for and operates. Upstream OpenClaw remains the only
Agent runtime. ClawInOne supplies the same-device environment around it.

![ClawInOne same-device architecture](assets/architecture.svg)

The outer boundary is the product: every component shown above runs on the same
physical Android phone. No root, companion PC, or separately deployed OpenClaw
host is required.

## Three product surfaces

| Surface | Responsibility |
| --- | --- |
| AVF Debian VM | Runs the pinned upstream OpenClaw Gateway, Supervisor, required command-line environment, and optional development profiles. |
| ClawInOne Android App | Provides Chat, guided setup and recovery, Terminal, VScreen, Android Use consent, and one local Gateway connection. |
| Android runtime and apps | Runs both software built by OpenClaw and existing installed apps on the same phone. |

ClawInOne is derived from the upstream OpenClaw Android App, but it does not add
a second Agent loop or private Chat protocol. The App connects to the local
Gateway using upstream OpenClaw contracts.

## Same-device flows

### Develop and deliver

```text
Prompt -> Code -> Build -> Inspect -> Install -> Run -> Observe -> Improve
```

The Agent works inside its canonical Project Workspace. A qualified development
profile builds an artifact, [App Delivery](app-delivery.md) binds it to the
current Project/run and exact phone, and the [Device Bridge](device-bridge.md)
performs bounded installation and readback.

### Present and operate

[VScreen](vscreen.md) provides one App-global interactive Android virtual
display for the user. [Android Use](android-use.md) is a separate, optional
Agent authority that can observe and operate an exact app on the main display or
a matching VScreen target after explicit consent.

### Set up and recover

The Android App checks live device capabilities, guides the user through the
official Linux environment, and connects a signed Rust Supervisor. Supervisor
installs and verifies the pinned OpenClaw environment and owns Gateway lifecycle.
The App presents owner-specific recovery instead of replaying unknown mutations.

## Ownership boundaries

| Owner | Authority |
| --- | --- |
| OpenClaw | Agent loop, Projects, Sessions, Chat, Providers, models, Tools, Skills, and Plugins. |
| Supervisor | Signed setup plans, qualified components and profiles, and Gateway lifecycle. |
| Android App | Native product UI, local grants, runtime coordination, and the Gateway connection. |
| Device Bridge | Private ADB server, exact-phone identity, pairing, reconnect, and bounded device sessions. |
| App Delivery | Receipt-bound APK inspection, install/readback, and installed-app results. |
| VScreen | Virtual display, authenticated stream/input, workload presentation, and direct human control. |
| Android Use | Explicit saved consent and exact Agent control leases. |

Canonical Project, Session, run, artifact, phone, display, and owner generations
establish identity and authority. Model text, foreground UI, Workspace access,
Skill selection, or an installed Plugin cannot substitute for those grants.

## Deliberate boundaries

- No bundled root filesystem, root requirement, or custom Android build.
- No general Agent-facing ADB or arbitrary package-management tool.
- No hidden installation, Accessibility consent, or Android Use activation.
- No dependency on a sibling OpenClaw checkout at build or runtime.
- No Android Chrome CDP automation in the current release.
- No claim of universal Android-device compatibility.

Detailed implementation contracts live in the
[maintainer documentation](maintainers/README.md). Upstream provenance is
recorded in [UPSTREAM.md](../UPSTREAM.md), and observed device evidence is in
[Validation](validation.md).
