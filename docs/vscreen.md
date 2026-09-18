# VScreen

**Status:** verified on the retained Pixel 8 / Android 17 environment.

> The pinned scrcpy server is a required, Supervisor-qualified
> `VScreenRuntimeAssets` component under
> [OpenClaw execution environment](development-environment.md). The
> producer continues to own per-session push/start/socket/cleanup, but must not
> download the server during VScreen activation. The native App remains the
> H.264 decoder; a desktop scrcpy client and FFmpeg are not required.

VScreen is one App-global interactive Android virtual screen: an embedded scrcpy
surface that normally shows Android's dedicated secondary-display Home and may
present the current App, Chrome or system workload. It is built in,
non-disableable and independent from Project, Chat and development-stack
ownership.

VScreen adds no framework pack, Agent Tool, Android permission or second ADB
owner. `ref/scrcpy` is reference-only; production uses the pinned matching scrcpy
server. Removed package-bound Preview models and aliases are not compatible.

## Product contract

```text
Drawer VScreen → one global DisplayTarget
  → Android virtual display + secondary Home
  → authenticated video + direct-human input
  → optional workload
  → optional Android Use consumer with separate authority
```

- Opening VScreen ensures one healthy target and raises the same bottom-up
  fullscreen surface. Successful idle state is Android secondary Home, never
  blank content or the phone's primary Home.
- An Agent workload raises a hidden App-global surface into Floating before
  placement so first-frame proof never depends on a hidden presentation. An
  already visible Fullscreen surface remains Fullscreen. Neither path navigates
  the retained Project or Chat route.
- The target starts lazily on first VScreen/workload demand and may be prewarmed
  after onboarding. Onboarding verifies only the persistent runtime asset; it
  never starts a target or performs a frame probe.
- Workloads reuse the healthy display. Foreground changes, App exit/crash, Home,
  task completion, Project/Chat changes and ordinary background/return do not
  retire it.
- Local Surface changes, decoder backpressure and decoder recreation belong to
  the Android viewer. They retain the exact attachment, display and workload;
  recovery resumes from codec configuration and the next H.264 key frame.
- Close retires the exact target, stream, scrcpy producer and workload
  presentation. Minimize keeps them alive in Floating. Reopening after Close
  creates a fresh target at secondary Home.
- `Open app` revokes matching Agent control and launches the durable result on
  display 0 without retiring VScreen.
- Direct VScreen interaction is human input and grants no Agent, Android Use,
  Accessibility, APK, Project or Chat authority.
- ClawInOne itself belongs to the phone's default display. Launching it from
  VScreen returns its existing task to display 0 and reveals the prior workload
  or secondary Home; the App never renders itself as a VScreen workload.
- Installation publishes its durable Chat result before and independently of
  VScreen. Presentation failure cannot relabel install as failed or block Open.

## State and identity

Runtime, presentation and Android foreground are independent:

```text
runtime:      Starting | Ready | Closing | Reconnecting | Unavailable
presentation: Hidden | Fullscreen | Floating | EdgeMinimized
foreground:   Home | Workload | SystemUi | Unknown
```

Hidden is an App-shell presentation state, not a retained target state after an
explicit Close.

| Identity | Meaning |
| --- | --- |
| `DisplayTargetRef` | Opaque live display generation. |
| `DisplayAttachmentId` | Authenticated stream attachment; presentation changes preserve it. |
| `DisplaySourceHandle` | Producer-private video/control source generation. |
| `WorkloadRequestId` / `WorkloadOrigin` | One placement mutation and canonical Project/Session/run/call provenance. |
| `DisplayForeground` | Producer observation, never ownership. |
| `VScreenInputSequence` | Monotonic attachment-scoped human-input order; no reusable grant. |

Display, source/attachment and workload generations remain distinct. Stale
workloads cannot replace, stop or write to a newer target and are never replayed.

## Owners

| Owner | Responsibility |
| --- | --- |
| Supervisor environment | Persist and verify the pinned scrcpy server artifact; no phone session or VScreen lifecycle. |
| VScreen foundation | One-slot target admission, authenticated attachment/stream, neutral input order and exact hard cleanup. No Android semantics. |
| Android producer | Device Bridge session, virtual display, Home, scrcpy sockets, workload launch/observation and producer cleanup. No ADB identity or UI. |
| Native VScreen | Drawer state, presentation, decoder/render mapping and local pointer lifecycle. No protocol or authority. |
| App Delivery | Durable installed result and `Open app`; no display ownership. |
| Android Use | Optional exact target/foreground control lease; no target creation or presentation ownership. |
| Workspace/Chat | Mutation provenance only. |

Dependency direction is producer/consumer adapters toward the neutral VScreen
contract. Foundation never imports Device Bridge, Android Use or Workspace.

## Presentation

- VScreen is a fixed drawer destination. Selecting it closes the drawer and
  presents above the retained route rather than pushing a Project/Chat route.
- Fullscreen chrome is transparent. One compact top-right group renders Minimize
  then Close as white glyphs with transparent 48 dp semantic targets and no
  border, background, scrim or app bar.
- System Back while fullscreen means Minimize and is not forwarded to Android.
  Content swipes belong to the remote screen; only local chrome consumes them.
- Floating is App-global inside ClawInOne, draggable and edge-minimizable. Tapping
  expands through the same fullscreen transition. Its borderless 28 dp white
  Close glyph has a transparent 48 dp target and retires the exact VScreen.
- Only fullscreen video forwards pointer input. Local chrome, floating controls
  and errors consume their own touches.
- The producer keeps secondary-display system decorations and resolves Android's
  `SECONDARY_HOME`; it does not launch the phone's primary Home or own a launcher.
  Fullscreen hides host system bars to avoid duplication; Floating does not.
  Do not crop frames or add a ClawInOne launcher/navigation bar.

## Target and workload lifecycle

The short-lived onboarding probe only proves a presented video frame and then
releases all resources. Runtime target creation verifies the retained exact phone,
creates the virtual display and scrcpy source, resolves Home, observes a native
frame, then publishes `Ready + Home`. Black/blank is not success.

An authorized app assignment reuses/creates that display. The Android producer
resolves the exact package's Launcher itself, launches it with `am start
--display`, observes the expected package once and one presented frame, then
releases mutation ownership. The model never supplies an arbitrary component.
App Delivery's `place_vscreen_workload` and Android Use's explicit `vscreen`
choice both reuse this same generic assignment boundary; neither creates an
installed result or makes VScreen development-only.

Only one workload mutation runs at once; competitors receive busy and are not
queued. A failed launch stays attached to its origin, returns VScreen to a fresh
Home target and leaves the floating presentation visible for diagnosis. A native
workload first-frame deadline performs the same Home recovery before App Delivery's
readiness deadline. Later foreground changes update observation only.

Retire for explicit Close, unrecoverable producer/stream failure, verified phone
loss, App/Gateway ownership loss or atomic target replacement. Close carries the
exact attachment, target and source generations so a stale surface cannot retire
a replacement. Recovery creates a new generation. Minimize, task completion,
Home and foreground/package changes are never retirement signals.

## Direct-human input

Native emits normalized pointer events for the current authenticated attachment:

```text
{ attachmentId, sequence, pointerId, phase, normalizedX, normalizedY, pressure }
```

It maps the rendered rectangle—including letterbox, rotation and Android system
decorations—to source coordinates and rejects touches outside it. Foundation
admits only current `pointer-v1` events; the producer validates target/source and
encodes the pinned scrcpy 4.1 control message. UI never handles raw scrcpy bytes.

Down/up/cancel are not dropped; moves may coalesce to video cadence. Leaving
fullscreen or losing/replacing the attachment cancels the local sequence without
retiring the display. The first valid human down revokes any matching Android Use
lease before forwarding. Android Use Stop revokes control only; hard target
retirement revokes only its matching consumer.

## Focused acceptance

Verify one global target; Home idle; Fullscreen/Floating/EdgeMinimized; borderless
local controls; terminal exact Close; retaining Minimize/Back; non-terminal task
completion and foreground changes; successive workload reuse; exact stale/busy
rejection; direct Home/workload input without Accessibility; human-vs-Agent
preemption; Open on display 0; and hard generation cleanup. Use
[testing.md](maintainers/testing.md) rather than a full gate.

Deferred: keyboard/IME/text, Android navigation controls, clipboard/file drop,
wheel/multi-touch/gamepad, multi-target/recording/screenshots, OS overlay, remote
devices, persistence across process/Gateway loss, Chrome/CDP and new producers.
