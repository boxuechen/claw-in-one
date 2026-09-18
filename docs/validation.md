# Validation

This file contains reusable observed evidence and release gaps. Raw logs,
screenshots, IDs, APKs, and transcripts belong in ignored `.verification/`.

## Reference environment

Retained-device evidence uses Pixel 8 (`shiba`), Android 17/API 37, official AVF
Debian 13.7 ARM64, OpenClaw 2026.9.4, JDK 21, Android SDK 37, and scrcpy server
4.1. It proves only the listed paths on this device.

## Accepted baseline

| Area | Observed result |
| --- | --- |
| Runtime | The signed 2026.9.4 npm runtime and five exact-version product Plugins passed qualification. Supervisor/Gateway restart and Terminal/local-port loss recovered through the global Runtime Gate while preserving Chat. |
| Environment | Fixed setup verified Foundation, general Node, Chromium, ADB, scrcpy asset, and OpenClaw without requiring a development profile. A later cold start skipped onboarding. |
| Desktop isolation | Debian Device Bridge used its private port-5038 ADB while macOS port 5037 continued to see the Pixel over USB; the Android AVF marker prevents a desktop Gateway from starting the product Bridge. |
| Device reconnect | After the private ADB server lost its endpoint, native Reconnect discovered the new same-phone TLS endpoint, reused saved identity, and returned Ready without re-pairing. |
| Projects/Chat | New Project starters stage an editable prompt/Skill without sending. Explicit creation produces a canonical Git Project and Workspace Chat; Projects own multiple Chats and one active writer. |
| Chat UI | Canonical assistant answers own one action set; run activity is one compact row; header/drawer use canonical Gateway Session naming and manual labels. |
| DevKit/Skills | Six development profiles were adopted as Ready without reinstall. `+ -> Skills` and `@` showed only matching Ready product workflows; Android Use appeared only after its permission returned. |
| Android delivery | Kotlin, NDK, Flutter, Godot, and React Native produced exact ARM64 APKs and completed retained-device install/update paths under their qualified profiles. |
| Web delivery | React/TypeScript/Vite production build/serve, exact reverse, Chrome open/update, and exact Stop completed without CDP. |
| VScreen | Secondary Home and workload input worked. Close from fullscreen/Floating removed the scrcpy virtual display; reopening created a fresh Home target. System Back minimized and preserved the live display. |
| Android Use | A real Chat opened Calculator on VScreen, observed/operated it, and Stop released only the matching control lease while VScreen remained. |
| AI UI | Settings exposes AI access and verified default model only; Chat options expose model/thinking without account selection or Provider installation. Dynamic thinking metadata and secret-lifetime policies passed focused tests. |

Qualified profile baseline:

| Profile | Accepted identity/scope |
| --- | --- |
| Kotlin | Shared Android Build Core; exact APK delivery/update. |
| NDK | NDK 29, CMake 3.22, ARM64 Vulkan NativeActivity. |
| Flutter | Flutter 3.47.4 / Dart 3.13.3 / NDK 28.2, offline ARM64 APK. |
| Godot | Godot 4.7.2 ARM64, matching export template, headless GDScript export. |
| React Native | RN 0.87.1, New Architecture/Hermes, isolated Node/toolchain. |
| Web | React 19.3, TypeScript 7.0, Vite 8.3 production profile; no CDP. |

## Accepted safety boundaries

- Canonical generations—not model text—control Project, run, artifact, phone,
  display, and owner identity.
- Workspace/Full/Skill selection never bypasses independent build, install,
  Android Use, Accessibility, VScreen, or browser-data authority.
- Unknown mutations reconcile before retry; stale or unrelated generations fail
  closed.
- Stop, Disable, disconnect, screen-off, and owner replacement revoke only their
  matching authority and never replay captured work.
- Optional-profile failure cannot replace the required environment or another
  Ready profile.
- VScreen Close destroys the exact target; Minimize, task completion, and
  Android Use Stop preserve it and grant no Agent authority.

## AI acceptance

A real API-key route and live model-backed Project and Android Use runs were
observed on the retained Pixel during demo capture. No credential value, raw
Provider response, or private transcript is retained in this repository.

The ChatGPT subscription device flow reached the Gateway-advertised pairing
operation, but AVF Debian received HTTP 403
`unsupported_country_region_territory` before a code was issued; the phone
browser and Debian had different egress. Completed subscription pairing, live
default-model mutation, and credential recovery remain NOT RUN.

## Open release gaps

- Clean supported-device/factory-reset onboarding is not yet certified.
- Subscription sign-in, live model mutation, and credential recovery need
  acceptance where Provider access permits.
- A project-owned Alpha signing path and manual release procedure exist, but
  off-machine key backup, public distribution, and signed install/update
  acceptance are not complete.
- Product-wide accessibility, large-text, light/dark, and error-state review is
  not complete.
- Lock-screen VScreen and broader Android Use matrices are not claimed.
- Android Chrome Use/CDP remains deferred.

Use [testing](maintainers/testing.md) to select checks. Reuse this evidence only while its
owner and pinned versions are unchanged; record new facts or gaps, not a debug
timeline.
