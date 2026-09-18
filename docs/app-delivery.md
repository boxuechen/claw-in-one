# Android App Delivery

**Status:** verified on the retained Pixel 8 / Android 17 environment.

Android App Delivery is the bounded Agent-facing application domain for moving
one Workspace APK onto the verified phone. It depends on
[Android Device Bridge](device-bridge.md) and does not own ADB pairing,
VScreen lifetime, builds, Projects or Android Use.

## Tool contract

The single model tool is `android_app`. It is available through an eligible
development workflow or relevant Agent choice under the [DevKit activation
contract](maintainers/devkit.md#chat-activation); Plugin service startup contributes no Chat
prompt.

| Operation | Boundary |
| --- | --- |
| `inspect_apk` | Inspect one regular APK below the effective Workspace root and bind its package, version, SDK, signer and digest to the exact phone generation. |
| `install_apk` | Consume this call's fresh receipt and authorization, revalidate bytes and phone, perform one replace install, then read back the installed bytes. |
| `place_vscreen_workload` | Verify the installed artifact, then publish its exact package through the same generic VScreen app-assignment boundary used by other authorized consumers. |
| `await_vscreen_ready` | Correlate the same owner, artifact and workload with native first-frame acknowledgement, installed bytes, launcher, initial foreground and bounded launch logs. |
| `capture_screen` | Return one bounded PNG only while the inspected package is foreground. |
| `read_logs` | Return one bounded, non-clearing package-UID log snapshot. |

`place_vscreen_workload` and `await_vscreen_ready` are application-level
coordination. Artifact validation remains in App Delivery; physical display
placement remains in `AndroidVScreenProducer`; target admission and attachment
remain in VScreen Foundation. A VScreen failure returns a presentation
degradation and never removes a valid installed result.

## Authority and results

Receipts are in-memory and bind canonical Project/Session/run/tool ownership,
artifact bytes and exact device generation. Change, expiry, restart, re-pair,
target mismatch or another inspection invalidates them. Installation needs the
existing Standard review or explicit Full, except for a server-verified active
Project Workspace run with the qualified development profile. No model field can
assert these grants.

A successful or independently verified `install_apk` readback publishes one
durable native Installed App Result. It contains the exact package, version,
digest and originating Chat identity, and exists independently of VScreen. The
native **Open app** action revalidates installed bytes, revokes only a matching
optional Android Use consumer, launches on display 0 and leaves a healthy global
VScreen alive.

Unknown installation outcomes are reconciled by readback and never retried
automatically. Generic package management, arbitrary intents, commands, UIDs or
paths are rejected.
