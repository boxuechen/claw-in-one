# OpenClaw execution environment

**Status:** implemented and verified on the retained Pixel 8 / Android 17
environment; clean-device release acceptance remains open.

ClawInOne installs one fixed OpenClaw execution environment in Android's
official AVF Debian. Phone capabilities are activated separately, and
development stacks are optional profiles. A missing optional profile must never
make Chat, ADB, Android Use, or VScreen unready.

## Layers

```text
Android AVF + official Debian
  -> required OpenClaw environment
       Foundation + general Node + Chromium + ADB + scrcpy asset + OpenClaw
  -> Supervisor + Gateway
  -> built-in phone capabilities
       Device Bridge + VScreen + Android Use
  -> optional development profiles
       Kotlin + NDK + Flutter + Godot + React Native + Web
```

## Managed on-device setup

Users install the Android App and complete a guided setup on the phone. The App
checks live device capabilities, directs only the Android-owned Developer
options and Linux steps, connects the signed Supervisor with one Terminal
command, and lets Supervisor install and verify the pinned environment. Users do
not manually install Node, OpenClaw, Gateway services, ADB, Chromium, or scrcpy.

```text
Install APK -> Check device -> Enable official Linux
  -> Connect Supervisor -> Install and verify OpenClaw -> Configure AI -> Ready
```

The result needs no root, companion PC, or separately deployed OpenClaw host.
AI providers, package downloads, and external services may still require a
network connection. The current flow is guided rather than a claim of silent,
zero-interaction, or fully offline setup.

The release owns the signed dependency graph. Users do not choose packages in
the required environment, and Supervisor never accepts client-provided URLs,
paths, versions, digests, or arbitrary packages.

## Required components

| Component | Responsibility |
| --- | --- |
| `AvfDebianBaseline` | Verify live Debian/AVF prerequisites; no device-model allowlist. |
| `OpenClawExecutionFoundation` | Git/OpenSSH, TLS/download, archives, Python, build/inspection utilities, and general Node. |
| `ChromiumRuntime` | Qualified Linux Chromium, libraries, fonts, and headless health. |
| `AdbRuntime` | ADB client independent from Android SDK/JDK/Gradle/NDK. |
| `VScreenRuntimeAssets` | Pinned, digest-verified scrcpy server; no first-use download. |
| `OpenClawRuntime` | Pinned upstream runtime and immutable ClawInOne Plugin overlay. |

Linux Chromium is OpenClaw's Debian browser. Android Chrome Use would control
the phone's Chrome and remains deferred. The two products must not share a
readiness flag.

ADB installation is not ADB authority. Device Bridge alone owns the private
server, key, exact phone, pairing, reconnect, revocation, and bounded consumer
sessions. The executable may be on PATH, but ordinary Shell access gains no
install, VScreen, Android Use, or browser-data grant.

ClawInOne needs the pinned scrcpy server, not desktop scrcpy or FFmpeg. The
producer pushes/starts the server through Device Bridge and owns its virtual
display and sockets; the Android App decodes H.264 with `MediaCodec`.

## Independent state domains

| Domain | Owner and states |
| --- | --- |
| Component installation | Supervisor: Missing, Installing, Verifying, Ready, Failed. |
| Phone connection | Device Bridge: Unpaired, Disconnected, Pairing, Connecting, Unauthorized, Connected. |
| Built-in activation | Capability owner: Needs setup, Ready, Unavailable, Degraded. |
| Development profile | Supervisor profile graph: Not installed, Installing, Verifying, Ready, Needs repair, Failed. |

Keep named readiness predicates rather than one product-ready bit:

```text
RequiredSetupComplete = fixed required component graph
CoreChatRuntimeReady = OpenClaw + current Gateway + local connection + Provider
PhoneConnected = ADB runtime + verified Device Bridge generation
AndroidUseReady = PhoneConnected + current consent/Accessibility evidence
VScreenReady = scrcpy asset + PhoneConnected + Home/first-frame evidence
DevelopmentProfileReady(id) = exact qualified optional profile
```

After onboarding, Chromium, ADB, or scrcpy damage degrades only its consumers.
Only OpenClaw/Gateway/local-connection/Provider failure blocks Chat through the
global Runtime Gate.

## Product surfaces and recovery

First run checks device compatibility, prepares the fixed environment, connects
the Gateway, and verifies one Provider/model. Wireless debugging, phone pairing,
Android Use, VScreen activation, and optional development profiles stay in
their post-onboarding owner flows.

| Failure | Recovery boundary |
| --- | --- |
| OpenClaw, Gateway, local connection, or Provider | Global Runtime Gate; preserve the product shell. |
| Chromium | Environment repair; only browser work is degraded. |
| ADB artifact | Environment repair; phone consumers are degraded. |
| scrcpy asset or VScreen startup | VScreen repair; no activation-time download. |
| Private ADB server/endpoint | Device Bridge restart or verified reconnect; no reinstall. |
| Android Use permission | Android Use only; no Chat/VScreen impact. |
| Optional toolchain | Its profile only; other Ready profiles remain usable. |

All mutations are serialized and reconcile unknown outcomes through owner
readback. Healthy state stays quiet. Current implementation uses Supervisor
protocol v10, plan v6, and onboarding receipt v9 with no legacy decoder.

Qualified optional stacks and their observed versions are listed in
[Development profiles](development-profiles.md). First-run and recovery details
are maintained in [Onboarding](maintainers/onboarding.md),
[Runtime startup](maintainers/runtime-startup.md), and
[Supervisor](maintainers/supervisor.md).

## Acceptance

Release acceptance needs a clean required setup without Android SDK/JDK/NDK,
working Chromium/ADB/scrcpy assets, first VScreen activation without a download,
optional-profile isolation, verified phone reconnect, and owner-specific
recovery that never replays onboarding. Use focused checks from
[testing](maintainers/testing.md); retained-device facts live in
[validation](validation.md).
