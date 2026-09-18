# Rust Supervisor

Protocol v10 and plan v6 accept an empty development-extension selection. The
physical required graph follows
[OpenClaw execution environment](../development-environment.md): Linux
Chromium, ADB and the pinned VScreen runtime asset belong to the required
environment; Android development is optional, and installation is separate
from phone connection and capability activation. There is no legacy decoder.

Supervisor is the independent Debian control plane for verified component setup and Gateway lifecycle. It is not a shell, Terminal, Chat transport, Provider proxy, or Agent runtime.

```text
Android onboarding ↔ authenticated commands/status ↔ Rust Supervisor
  ├─ persist and execute one immutable setup plan
  ├─ install and verify fixed components
  └─ own Gateway process lifecycle
```

Bootstrap only verifies, installs, activates, and starts the versioned Supervisor. Android owns presentation; Gateway remains the only Chat/Provider/Plugin/Skill/Agent protocol.

## Protocol v10

Both sides reject other versions, malformed/oversized fields, stale timestamps, replayed sequences, unknown components, and invalid HMACs. There is no fallback.

```text
command: 10|supervisorId|sequence|timestamp|operation|argument|hmac
status:  10|supervisorId|supervisorBootId|eventSequence|commandSequence|timestamp|
         stage|gatewayGeneration|ensureAttemptId|exitCode|planId|
         selectedCapabilities|resolvedComponents|readyCapabilities|
         currentComponent|completedBytes|totalBytes|setupCode|hmac
```

HMAC-SHA256 signs the newline-joined preceding fields. Accepted operations are
`probe`, `apply_capabilities`, `retry_capabilities`, `skip_capability`,
`ensure_gateway` and `request_gateway_pairing`. Android selects stable product capabilities; Supervisor resolves
their release-owned versioned components. Every development extension,
including Android Kotlin, is optional; an empty selection still resolves the
fixed required setup roots. Retry references the active plan and skip applies
only to the unique failed selected extension. Byte counters appear only during
downloads and `setupCode` only at `gateway_pairing_ready`.

`probe` is read-only. It reports current capability and Gateway state without
installing or starting anything. An accepted unfinished capability plan is the
only installation work automatically resumed after Supervisor restart.
For a settled plan, `readyCapabilities` is projected from its durable verified
receipts; ordinary status refresh does not rerun Android, Flutter, Godot or Web
toolchain health commands. Those bounded health checks belong to capability
apply/retry, not Gateway liveness.
`ensure_gateway` is idempotent and joins an existing worker; it does not create a
pairing credential. `request_gateway_pairing` first ensures the Gateway, then
publishes the short-lived setup code. `supervisorBootId` changes whenever the
Supervisor process starts. `gatewayGeneration` changes for every new Gateway
process, including a supervised crash restart; `ensureAttemptId` orders those
attempts within one Supervisor boot. While a Gateway worker is active,
Supervisor publishes an authenticated same-stage status heartbeat every five
seconds so Android can distinguish a live generation from a stale snapshot
without issuing heavyweight probes.

## Setup and delivery

- Persist the plan before fixed-order execution; reject a different active plan.
- Install and verify the fixed Debian 13 arm64 environment roots independently:
  execution foundation, general Node, Chromium/CDP, ADB, pinned scrcpy server
  asset and OpenClaw. JDK/QEMU/Android SDK remain behind the optional Android
  build foundation.
- Retry only the failure. Verified components never repeat and only failed optional components may be skipped.
- Pin downloadable size/digest, verify caches, use bounded timeouts, and retry only classified transient failures.
- Failed staging never replaces an active release. Active release identity must be exactly readable.
- Status survives Android loss. Unknown mutation outcomes use readback, never automatic repetition.
- Logs contain no secrets, setup codes, or Provider data.
- The APK embeds one reproducibly built static ARM64 Supervisor and digest; Debian needs no Rust toolchain.
- Bootstrap activates atomically and uses `systemd --user` when available with explicit foreground fallback.
- Bootstrap stages first-party workflow Skills independently from Plugins;
  Gateway startup registers their versioned directory through OpenClaw's
  standard Skill loading configuration.
- The pinned npm package is the OpenClaw platform baseline. Before Gateway
  startup, the active immutable Supervisor release atomically installs only
  ClawInOne-owned product Plugins into their exact host-bundled runtime slots
  and removes stale product load paths. This preserves host trust and leaves
  one active implementation per Plugin id.
- Android Kotlin, Android Native, Flutter and Godot Android publish immutable
  qualified profiles and stable capability environments, not arbitrary
  Supervisor execution.

Project builds belong to OpenClaw file/Shell tools, not Supervisor operations.
Clean-device setup and failed-install containment remain first-release work in
[roadmap](../roadmap.md); automatic runtime rollback is deliberately absent.

## Capability-plan boundary

The current contract is protocol v10, component identity `key@version`, and plan
format v6. Bootstrap, Supervisor, and Android reject replaced shapes and retain
no compatibility decoder. Onboarding owns its separate completion receipt.

- Android may request only release-known product capability IDs. Supervisor
  resolves the signed component/dependency graph and never accepts client URLs,
  paths, versions, digests or arbitrary packages.
- Onboarding and DevKit share apply, status, retry and optional-skip semantics.
  Every mutation is serialized and idempotent by request/plan identity; unknown
  outcomes are read back before another command.
- One Toolchain Store owns the shared SDK, versioned Gradle/NDK/CMake/Flutter/
  Godot components and caches. Exact components deduplicate; NDK 29 and
  Flutter's NDK 28.2 coexist. Standard Godot export does not claim an NDK.
- Verified shared components and active profiles survive a failed optional plan.
  Only a fully qualified staged profile can atomically publish a generation.
- Within one release, plans are additive. When the pinned release changes,
  Supervisor retains the selected product capabilities, resolves a fresh exact
  component graph and clears old completion state so every new component is
  reverified. Removal, in-place framework update and Project build commands
  remain absent from Supervisor.

User-facing capability and Toolchain Store boundaries are defined by
[DevKit](devkit.md#ownership-and-readiness).

The shipped Android Developer Bridge Plugin explicitly enables OpenClaw's
`hooks.allowConversationAccess` at Gateway startup for its exact run-end cleanup
hook. It does not mutate prompts or grant Shell/APK execution or Android Use
consent. Installed development Skills remain available for explicit reference
or relevant Agent choice under the [DevKit activation
contract](devkit.md#chat-activation). Unrelated Plugin hook policies remain
unchanged.
