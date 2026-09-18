# First-run onboarding

Onboarding establishes the smallest complete local OpenClaw product before the
first Project. It does not pair the phone, authorize Android Use/VScreen, or
install a development profile.

```text
device compatibility
  -> enable developer options / Linux when needed
  -> connect Supervisor with one Terminal command
  -> install the fixed OpenClaw environment
  -> configure and verify one AI route
  -> Ready
```

The fixed environment contains Foundation, general Node, Linux Chromium, ADB,
the pinned scrcpy asset, and pinned OpenClaw. Users do not select packages in
this graph. Phone capabilities and Kotlin/NDK/Flutter/Godot/React Native/Web
profiles belong to post-onboarding DevKit flows.

## UI contract

- Device qualification precedes progress. Checking, Blocked, and Unknown use the
  centered compatibility surface. Only user-resolvable developer-options or
  Linux settings continue into onboarding.
- Present four concise phases: Device, Linux, OpenClaw, and AI. Each state has at
  most one primary decision and preserves owner state across background/process
  recreation.
- The complete one-time Bootstrap command is selectable. Copy, Open Terminal,
  and pinned source are separate actions; opening Terminal never changes the
  clipboard.
- Show the fixed environment as progress, not package choices. Required failure
  offers Retry through Supervisor.
- AI setup uses Gateway-owned sign-in/API-key methods, models, and verification;
  Android never stores Provider secrets or installs Provider Plugins.
- Do not create a Project, Chat, phone pairing, capability authorization, or
  optional profile during setup. Completion opens the uncommitted New Project.
- Never infer current readiness from a historical receipt or repeat an unknown
  mutation. Follow the [Android UI guide](../../apps/android/style.md) for insets,
  focus, scrolling, and Back/Close behavior.

## Ownership

| Phase | Owner |
| --- | --- |
| API level, ABI, AVF, official Terminal | Android device-eligibility probe |
| Developer options and Linux enablement | Android Settings |
| Supervisor delivery/pairing | Bootstrap |
| Fixed setup plan and Gateway lifecycle | Supervisor protocol v10 / plan v6 |
| AI access, model catalog, and verification | OpenClaw Gateway |
| Ordering, presentation, and completion | Android first-run coordinator |

Missing device evidence is Unknown. Unsupported API, ABI, AVF, or system
Terminal is Blocked. The App re-probes after returning from Settings.

## Completion receipt

Receipt v9 records only a completed setup milestone: completion identity/time,
exact Supervisor identity, settled plan identity, and observed Supervisor event
sequence. It contains no phone identity, VScreen/Android Use state, development
profile selection, Provider secret, or current Gateway health. Older receipt
shapes are ignored rather than migrated.

After a receipt exists, onboarding never replays. Current OpenClaw/Gateway/local
connection failure belongs to the [Runtime Gate](runtime-startup.md). Chromium,
ADB, scrcpy, phone, VScreen, Android Use, and optional-profile degradation stays
with its owner as defined by the
[execution environment](../development-environment.md).

## Acceptance

Daily work covers the changed resolver/UI state and one adjacent failure or
recovery case. The first release additionally requires one clean supported-device
journey through Ready. Debug review fixtures prove presentation only. Record
observed device results in [validation](../validation.md) using
[testing](testing.md).

### Debug presentation review

Debug builds expose `Settings -> About -> Preview first setup`. The fixture
reuses production screens without mutating Settings, Terminal, Supervisor,
Gateway, onboarding receipt, Projects, or Chats, so it is visual evidence only.

ADB can open a specific scene:

```bash
adb -s <serial> shell am start \
  -n io.github.boxuechen.clawinone.debug/ai.openclaw.app.MainActivity \
  --ez openclaw.onboardingReview true \
  --es openclaw.onboardingReviewScene ai
```

Available scenes are `checking`, `unsupported`, `unknown`,
`developer-options`, `linux-environment`, `local-service`, `environment`,
`installation`, `gateway`, `ai`, and `ready`.
