# Android Device Bridge

**Status:** verified on the retained Pixel 8 / Android 17 environment.

> The Bridge consumes the Supervisor-qualified `/usr/bin/adb` runtime defined by
> [OpenClaw execution environment](development-environment.md).
> Supervisor owns ADB artifact installation; the Bridge continues to own the
> private port-5038 server, keys, exact-phone identity, pairing and bounded
> sessions.

Android Developer Bridge is the single built-in integration between OpenClaw's
developer workflow and this phone. It is one installation and process lifecycle,
but it contains three separate owners:

```text
Android App tool -> Android App Delivery --+
                                          +-> Android Device Bridge -> private ADB -> Android
VScreen Foundation <- Android producer ---+
Native onboarding -> Android Device Bridge
```

The product calls the overall capability **Android development** and the
onboarding requirement **Android device connection**. `Workbench` is not a
domain or public protocol name.

## Android Device Bridge

`AndroidDeviceBridge` is the only owner of the dedicated ADB server, private
credentials, exact-phone identity, pairing, reconnect, revocation, connection
generation and serialized device access. Android Settings owns wireless-debug
consent and pairing codes. Pairing secrets and challenge contents are never
persisted; Forget removes the private credential and invalidates the binding
generation.

The real Bridge is admitted only when the Supervisor-managed Plugin config marks
the execution environment as `android-avf`. Loading the product Plugin in a Mac,
Linux or Windows development Gateway without that marker remains inert: it must
not start a host ADB server, enumerate USB devices or interfere with the
developer's default ADB server. The marker is written by the canonical Android
bootstrap rather than inferred from the host operating system.

The Bridge exposes status, pair, connect, verified reconnect and forget only to
the native control plane. Trusted sibling modules may borrow one generation-
bound device session with bounded command, binary-command and process-spawn
ports. That port is internal: it is never an Agent tool or generic ADB/shell API.
The Bridge knows no APK, package, launcher, VScreen workload, scrcpy policy,
Project permission or Android Use consent.

Pairing is a post-onboarding DevKit action. A changed port or Chat does not
require pairing again. Unknown mutations use status readback and are never
repeated blindly.

For same-phone recovery, the Android App uses platform NSD to discover the
current `_adb-tls-connect._tcp` service and accepts only an address assigned to
this phone. That address is an untrusted reconnect hint: the Bridge still owns
`adb connect` and must match the resulting device to its saved exact-phone
identity before becoming Ready. Debian mDNS is not an authority or a recovery
dependency. If platform discovery returns no local endpoint, the existing
manual-address action remains available without discarding pairing.

## Built-in consumers

[Android App Delivery](app-delivery.md) owns inspected artifacts,
installation and durable results. The Android VScreen producer owns scrcpy,
virtual-display/Home readiness, workload placement and first-frame correlation
under the neutral [VScreen](vscreen.md) contract. Both consume the internal
Bridge session; neither may start another ADB server or persist another device
identity.

Android Use is a separate optional Agent authority defined in
[Android Use](android-use.md). It may consume an exact
VScreen target but cannot acquire Bridge commands, installation authority or
direct-human input authority.

## Lifecycle

- Plugin startup starts the Bridge service; it does not activate a Skill or add
  Android instructions to every Chat. Outside the marked Android AVF runtime,
  startup registers no real Bridge process.
- Disconnect makes new operations unavailable without changing saved pairing.
- Forget and process shutdown invalidate borrowed sessions and close producer
  resources before stopping the private ADB server.
- Reconnect creates no Agent work, installation, workload or Android Use lease.
- ADB does not resolve through `ANDROID_SDK_ROOT`; SDK, NDK, QEMU and build tools
  remain optional Supervisor components. Source and builds remain OpenClaw
  Workspace/Shell work.

## Out of scope

No arbitrary ADB or shell, uninstall, permission grants, file transfer, remote
device management, build orchestration, Android Use semantics or Chrome CDP is
part of the Bridge. Future device consumers must use a bounded internal port and
remain separate domains.
