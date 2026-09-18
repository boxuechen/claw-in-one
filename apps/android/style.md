# Android UI guide

This is the native Compose design contract. ChatGPT on Pixel 8 is the reference
for calm hierarchy and density; ClawInOne's product boundaries remain canonical.

## Principles

- Conversation first: product chrome recedes behind the task.
- Quiet when healthy; show status only when it changes a decision.
- One obvious primary action per state; progressively disclose technical detail.
- Preserve Project, Chat, draft, scroll and focus through navigation and failure.
- Use shared tokens/components and standard Material icons for semantic actions.
  A capability that names one external development stack may use its unmodified
  official brand mark through the shared DevKit visual registry; composite and
  ClawInOne-owned capabilities keep semantic product icons.
- Motion explains spatial change, stays brief/interruptible and respects system scale.

## Architecture and foundations

- Preserve `Route → immutable state/actions → Screen`. Routes bind owners and
  navigation; Screens render and emit typed actions. Compose never parses protocol
  JSON, stores credentials or decides authority/readiness.
- Use `ClawTheme` semantic colors, typography, spacing, radii and sizes. Do not
  introduce literal feature colors when a semantic role exists.
- Manrope is the product face; monospace is limited to code and terminal content.
- Prefer whitespace and dividers over nested cards. Long content scrolls while
  navigation and the current decision remain reachable.
- Respect status/navigation/gesture/IME insets. Touch targets are at least 48dp.
- Define loading, empty, active, waiting, success, failure, offline, unknown and
  recovery states before polishing the happy path. Never rely on color alone.

## Shell and drawer

- The drawer is 80% width. Its fixed header contains the product mark,
  `ClawInOne` and Project/Chat search; it has no creation action.
- Fixed global destinations precede the Project area in this order: DevKit,
  Plugins, VScreen and Terminal.
- The Project area is always present and scrolls independently. Projects sort by
  latest Chat activity; each Project row stays visible while its nested Chats may
  independently collapse. Search reveals matching children without overwriting
  the saved collapse choice.
- Project rows use 26dp outlined open/closed folder glyphs. New Chat exists only
  on its Project row as a borderless 20dp compose glyph inside a transparent 48dp
  semantic target. Tapping the Project row toggles its Chats and changes no route.
- The fixed footer keeps the blue `+ Project` action at left and Settings at
  right. Do not expose Workspace, Node, Gateway, Agent or removed pages.
- Selecting a destination or detail never silently changes the selected Chat.
- Full-screen destinations use predictable Back/Close behavior. Entering them
  dismisses composer focus; returning preserves the draft without reopening IME.

## Chat

- The uncommitted New Project entry contains a New Project header, centered
  ClawInOne mark, concise prompt, fixed 2×2 Ready starter grid (Kotlin, Flutter,
  Godot, Web) and composer. Each starter is one quiet tile with its official
  stack mark (or the semantic Web mark) and short name; capability explanations
  stay out of the launcher. Unready starters are absent. Empty Chats in an
  existing Project contain only the Chat header and composer; they never repeat
  Project bootstrap content.
- A starter only stages its prompt and Skill in the composer; it never sends or
  creates a Project. The user's explicit Send opens the shared naming dialog.
  Default-name collisions receive atomic numeric suffixes; a custom duplicate
  stays in the dialog with restrained shake/orange feedback and no second error dialog.
- User messages align right in a raised neutral bubble. Assistant content is
  unboxed in the reading column. Code, tools, approvals, questions and results use
  purpose-specific blocks.
- The header shows only the current Chat Session title; Project identity remains
  in the drawer and the read-only Project row in Chat options. New Chat exists
  only on the owning Project row in the drawer. A borderless More action opens
  one sheet for Model, Thinking, Context, Chat details and
  the current per-Chat access summary. Chat name opens a bounded rename dialog;
  clearing its manual label returns to the Gateway-provided automatic name.
  Model and Access rows open their own bounded picker sheets. Gateway resolves
  model access automatically; auth-profile selection is not exposed. Refresh
  appears in Chat options only when recovery is relevant.
- One inset composer pill owns options, text, Send and Stop and grows upward to
  four lines. Its `+` menu contains Photos, Files and Skills. The Skill picker
  contains only Ready DevKit workflows; it exposes neither Plugin ownership nor
  a management shortcut. `+`, `@Skill`, a starter and DevKit's Use in Chat action
  converge on the same typed Skill selection and stable reference. The composer stays pinned while
  the timeline scrolls or Working details expand. Ordinary
  background/return preserves the draft but does not restore focus or reopen the
  keyboard.
- Project Chats start with Workspace access. The Access row opens a bounded picker
  that can switch a materialized Chat among Default, Read-only, Standard,
  Workspace and Full through the canonical Gateway permission owner; it never
  expands or resizes Chat options. A local unsent draft remains fixed to its
  creation policy. Applying, unavailable and unconfirmed states disable choices
  and expose owner-routed recovery. Stop/recovery never becomes a second fixed
  panel.
- While a run is active, the timeline contains exactly one compact, truthful
  Working row. It may expand to show current plan steps, tools, diff counts,
  bounded Gateway commentary, elapsed time and tokens. Commentary/preamble
  projections are ephemeral run activity: they never become transcript rows or
  cache entries. A completed run removes this row; there is no durable
  task-list/progress card. Approval, failure and unknown outcomes remain visible
  and actionable in the timeline.
- Only a canonical assistant answer receives Copy, Share and More actions. A live
  answer draft is action-free until canonical history replaces it; Working,
  tool-mirror and commentary projections never inherit answer actions.
- Native result rows are lightweight and durable: Android shows app label/Open
  app after exact install readback; Web shows app label/Open in Chrome only while
  the exact server/reverse generation is current. Optional VScreen presentation
  failure attaches to the Android result instead of becoming a global Chat banner.
  Do not add an artifact dashboard.
- Open app launches on display 0 and revokes only matching Agent control. It does
  not destroy a healthy global VScreen. Web Stop removes only the matching result,
  server and reverse and never closes Chrome.

## DevKit

- DevKit is a full-screen drawer destination, not a Plugin store or installer.
- Home uses a fixed Back + `DevKit` navigation title and begins directly with
  Core, Device capabilities and Development stacks. Healthy aggregate status is
  hidden; a needs-attention summary appears only when user action is required.
  Healthy rows recede; actionable rows offer one next step.
- Rows display product names, owner-derived state and allowed actions. Hide Plugin
  IDs, paths, protocol methods and dependency topology.
- Required items cannot Disable/Remove. VScreen is built in. Android Use alone is
  independently enabled/disabled; optional stacks Install/Retry/Repair.
- Detail Back returns to DevKit, then to the retained Chat. Device and Android Use
  management are not duplicated in Settings.

See [DevKit](../../docs/maintainers/devkit.md) for its state/owner contract.

## Onboarding, Settings, Plugins and Terminal

- Device compatibility is resolved before onboarding. Checking, Blocked and
  Unknown use one centered branded surface without onboarding progress; only
  developer-options and Linux-enablement actions enter the first-run sequence.
- Onboarding is linear, concise and owner-specific. One primary action advances
  the current phase. A short semantic stage label and segmented progress replace
  generic step copy; raw logs and implementation topology stay hidden. Setup
  screens use a compact ClawInOne mark, with a restrained Dev badge only in debug.
- The local-service phase shows the complete one-time command in a selectable
  code surface. Copy, Open Terminal and pinned GitHub source are separate actions;
  opening Terminal never changes the clipboard. The following environment phase
  installs the fixed OpenClaw, Chromium, ADB and VScreen runtime graph without
  package choices. USB and Wireless debugging, phone pairing, VScreen activation
  and optional development stacks stay out of onboarding and remain with their
  DevKit owners.
- After onboarding, each App process establishes its local runtime through the
  application-level Runtime Gate before exposing the shell. Startup and durable
  recovery use one centered full-screen surface with the ClawInOne mark, concise
  owner status, semantic Supervisor/OpenClaw/App/Ready progress and actions next
  to the current decision. Only a short post-entry disconnect uses a compact top
  notice. Chat never renders or owns either presentation.
- Settings is a raised near-full-screen sheet. Its home owns the fixed Close;
  every detail level owns one fixed Back and no duplicate Close. The sheet top
  inset is applied once so detail headers stay compact. Home uses an identity
  header, section labels and grouped rows. Permanent sections are AI and models,
  Appearance, Local environment and About/Licenses. AI access, verified default
  models and per-Chat selection follow
  [the AI providers and models contract](../../docs/maintainers/ai-providers-and-models.md);
  Settings exposes only AI access and Default model; do not restore My accounts,
  Add Provider or the removed API-key Provider page.
- Debug builds may expose the read-only first-setup review from About. The shell
  owns its full-screen presentation; fixtures reuse production Screens, never
  mutate onboarding/runtime owners, and are absent from release builds.
- Local environment exposes only focused health/recovery. DevKit owns capability
  presentation. Healthy checks run silently.
- Plugins, Skills and Connections remain distinct ecosystem concepts. Internal
  product Plugins stay hidden; their user-facing capabilities live in DevKit.
- Terminal is a full-screen drawer destination with fixed Back and environment
  controls. Native code owns IME/clipboard/recovery; upstream owns PTY rendering.
  Preserve the terminal session and never replay input after disconnect.

## VScreen and Android Use

- VScreen is one App-global Android display with direct human input. It is not
  tied to a Project/Chat and does not grant Agent authority.
- Open from the drawer as a bottom-up full-screen surface. Fullscreen places
  borderless Minimize then Close controls at top-right; Floating uses a borderless
  Close at top-right. Every icon retains a transparent 48dp semantic target.
- Close retires the exact display, stream and scrcpy producer, then changes
  presentation to Hidden. Minimize and system Back change it to Floating and
  keep the target and workload alive. Reopening after Close creates a fresh
  secondary Home target.
- Floating keeps a 9:19.5 portrait shell around 30% of window width (108–136dp),
  remembers edge/vertical position and supports edge snap but no manual resize.
- Fullscreen hides host system bars to avoid duplicate chrome; Floating leaves
  them unchanged. Rotated content letterboxes rather than stretches.
- Backgrounding hides presentation while valid work may continue. Return restores
  only a still-current target and never the keyboard. Task completion releases
  task authority but does not hide or stop VScreen.
- Producer, stream or Android display loss retires the exact target. Local
  Surface/decoder lag recovers the viewer against the same attachment and never
  retires the workload. Reconnect, unlock or view recreation cannot resurrect a
  genuinely retired generation.
- Android Use is an optional exact-target Agent consumer with separate saved
  consent and Accessibility. Its Stop/disable/lock revokes matching Agent control,
  not VScreen or development.

See [VScreen](../../docs/vscreen.md), [Android Use](../../docs/android-use.md)
and [Device Bridge](../../docs/device-bridge.md) for lifecycle authority.

## Accessibility, motion and verification

- Maintain semantic name/role/state, logical traversal, readable contrast,
  non-color cues and useful announcements. Keep actions reachable with keyboard,
  large text and IME visible.
- Avoid motion that implies authority, completion or owner change before readback.
  Reduced motion replaces breathing/repeated motion with stable emphasis.
- For daily work, run focused format/compile/tests and inspect only affected states
  on the retained Pixel, including one relevant adverse state. Dark/light/system,
  text scale, reduced motion and TalkBack are design expectations, not a mandatory
  combined matrix for every change. See
  [testing](../../docs/maintainers/testing.md).

Sources of truth:

- tokens/components: `app/src/main/java/ai/openclaw/app/ui/design/`
- shell: `app/src/main/java/ai/openclaw/app/ui/shell/`
- feature UI: `app/src/main/java/ai/openclaw/app/ui/`

When implementation and this guide diverge, update both in the same change and
record only observed device evidence in `docs/validation.md`.
