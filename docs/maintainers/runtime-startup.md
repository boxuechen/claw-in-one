# Runtime startup and recovery

ClawInOne has one application-level runtime gate after first-run onboarding. It
is composed above the app shell and independent from Chat, Settings, DevKit and
Terminal navigation. App-shell state remains alive behind a blocking recovery
screen.

Required-at-setup components are not all permanent Chat gates. Chromium, ADB,
scrcpy, phone capabilities, and optional profiles degrade only their consumers;
OpenClaw, Gateway, local connection, and Provider failures form the blocking
Chat path. See [execution environment](../development-environment.md).

```text
Android RuntimeCoordinator
  ├─ current Android device-eligibility projection
  ├─ authenticated Supervisor snapshot
  ├─ Supervisor-owned Gateway generation and startup stages
  └─ Android-owned local Gateway connection
```

## Boundary

- Supervisor owns the durable component plan and Gateway process supervision.
- The App connects to Supervisor, then explicitly sends idempotent
  `ensure_gateway`; pairing is a separate `request_gateway_pairing` operation.
- OpenClaw remains the only owner of Chat, Provider, Agent, Plugin, Skill and tool
  behavior after the Gateway connects.
- System Terminal is an explicit escape hatch. Its Activity lifecycle is not a
  runtime health signal and ClawInOne cannot control Android's privileged port
  forwarding setting.
- Chat receives neither Supervisor state nor recovery actions. Conversation
  errors with visible history remain Chat-local; runtime-wide failure stays here.

## Presentation state

| Evidence | Presentation |
| --- | --- |
| Device eligibility is still being checked | Centered startup surface without runtime progress. |
| Developer options or Linux environment was disabled after onboarding | Blocking recovery with the matching Android Settings action; preserve shell state. |
| Device evidence is unsupported or unknown | Blocking device-owned recovery outside Chat; never restart onboarding. |
| Current App process has not entered its verified runtime | Full startup screen with explicit Enter Chat, even when the same generation was acknowledged by an earlier process. |
| Same acknowledged generation is healthy after entry | No runtime UI. |
| Short unconfirmed disconnect after entry | Non-blocking transient notice; preserve the current screen and draft. |
| Ten-second local-port outage or a typed terminal failure | Blocking recovery screen with the owning action. |
| An owner-requested planned Gateway restart | Centered owner progress, automatic reconnect/verification and exact-flow resume; no manual Continue. |
| An unowned or mismatched new runtime generation after entry | Blocking recovery screen with explicit Continue. |
| Supervisor missing/unreachable | Check again, inspect system Terminal, or explicitly prepare the signed one-paste repair command. |

The process-local entry acknowledgement is separate from the persisted runtime
generation. Recreating the App process starts a new global gate; backgrounding,
foregrounding or opening system Terminal in the same process does not. The
progress sequence is the semantic enum Supervisor → Gateway preparation → local
Android port → Ready; presentation never infers owner state from a numeric UI
step. Opaque `supervisorBootId`, `gatewayGeneration` and `ensureAttemptId` prevent
a receipt, stale status or Activity transition from claiming readiness. Only
boot/generation acknowledgement is persisted; no Supervisor secret or Gateway
setup code enters runtime UI state.

Startup and blocking recovery use one centered, branded full-screen surface.
The mark, concise owner status, four-stage progress and current decision stay in
one stable content column; recovery actions remain attached to that decision
instead of moving to a detached bottom area. The retained App shell stays alive
behind the surface. Only a post-entry transient disconnect uses the compact top
notice, so an ordinary liveness check never looks like Chat-owned content.

A Provider or Plugin flow may request a planned Gateway restart through the
canonical restart preflight/request protocol. The shared restart coordinator
records the owning operation and old boot/generation, preserves the retained
shell, and resumes only after a distinct healthy generation reconnects and the
owner verifies its result. This expected interruption does not become an
explicit-Continue Runtime Gate. An unrelated exit, mismatched generation,
timeout or failed owner verification falls back to the blocking gate. See
[AI access and models](ai-providers-and-models.md).

## Exception behavior

- App background/foreground with a live generation: refresh the read-only
  Supervisor and device snapshots and remain quiet; do not reset process entry.
- Developer options or Linux environment changes after onboarding: keep Project,
  Chat, draft and receipt intact; show the device-owned recovery action until a
  fresh probe is Ready.
- System Terminal Activity closes while Linux stays alive: no state change.
- Gateway child exits: Supervisor reports startup stages, creates a new Gateway
  generation, applies bounded restart policy and makes the restored generation
  explicit before Chat resumes.
- Gateway worker exhausts restart policy: show `gateway_failed` and offer
  `ensure_gateway` only after the active command settles.
- Linux/Supervisor exits: show Supervisor unavailable without claiming that the
  whole Linux VM stopped. Missing or terminal Supervisor state remains visible;
  background observation does not overwrite it with a speculative probe.
- Check again is the explicit read-only transition out of a missing/terminal
  Supervisor state. Repair is a separate explicit Bootstrap handoff.
- Gateway is healthy but `127.0.0.1` remains unreachable for ten seconds: show
  local-port recovery and direct the user to Android's system Terminal only for
  forwarding inspection.
- Pairing authentication is rejected: use structured Gateway problem data and
  the separate pairing operation; never parse localized display text.

## Startup cost

The Gateway script stores a content-addressed stamp after successful
product-owned configuration reconciliation. An unchanged OpenClaw version and
unchanged config file skip the many sequential CLI writes on later boots. Any
user/Gateway config edit, product config revision change or OpenClaw update
invalidates the stamp and runs the non-destructive reconciliation again.

Supervisor keeps Gateway liveness fresh with a five-second authenticated status
heartbeat. The status path projects `readyCapabilities` from the durable settled
plan and never reruns toolchain health checks. This keeps foreground refresh and
Gateway recovery independent from capability installation cost.

Once per Supervisor start, a settled current-format plan may requalify
unselected optional profiles against the exact current release graph. Fully
verified closures are atomically adopted into a new current plan before status
projection; missing or damaged profiles are not. This recovers existing current
installations without reading an obsolete plan format, while every later
heartbeat remains a cheap projection.

## Pinned runtime upgrade semantics

An OpenClaw upgrade is one release transition, not a multi-version runtime mode.
The Android release manifest, generated Gateway protocol, Bootstrap runtime and
all five product Plugin compatibility declarations move to the same exact
version.

- Supervisor installs the verified npm artifact and immutable product Plugin
  overlay in a staging directory. Only a release that passes the pinned CLI,
  package identity, required SDK seam and overlay checks may replace `current`.
- Activation is one-way. After `current` points at the new release there is no
  old-runtime fallback, protocol adapter or automatic downgrade path. A failure
  keeps the global Runtime Gate visible and retry repairs the same pinned
  release.
- The OpenClaw version invalidates the product config stamp. Reconciliation
  first lets the pinned OpenClaw Doctor perform its one-way operator-state and
  schema migration. It then preserves user Plugin paths and allow entries, adds
  every bundled Plugin from the pinned release, and enables the immutable
  ClawInOne Plugins. User deny entries remain authoritative.
- A durable capability plan preserves only its product capability choices across
  a release change. Supervisor resolves a fresh exact component graph, clears
  old completion state and verifies every 9.4 component before reporting Ready.
- OpenClaw owns its config and state migrations. ClawInOne neither rewrites
  upstream state formats nor maintains a parallel migration layer.

An older release directory may remain as inert installation data until normal
cleanup, but it is never a runnable compatibility branch.
