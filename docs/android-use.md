# Android Use

**Status:** implemented and verified with a real retained-device task and Stop
revocation case. Activation remains optional and explicit.

## Status and role

Android Use is an implemented built-in DevKit device capability with optional,
explicit activation for Agent observation and input on the verified phone. It
is not an installable pack and is not required for Android app development, App
Delivery, direct user interaction in VScreen or Open app. Its target
installation/activation boundary is defined by
[OpenClaw execution environment](development-environment.md).

It ships as a built-in integration but starts Disabled. Enabling it requires an
explicit global disclosure and Android Accessibility setup. Saved consent covers
current and future supported Chats until disabled; every task still passes native
owner, target and lifecycle admission.

## Boundary

```text
User enables Android Use
  → native global consent
  → Accessibility and Device Bridge prerequisites
  → capability Ready

Explicit/relevant Agent request
  → the optional `android-use` Skill selects the bounded workflow
  → canonical Chat/session/run lease
  → exact package plus explicit main/VScreen selection
  → optional generic VScreen app assignment
  → bounded observation/action
  → completion or revocation releases that lease
```

The built-in Plugin exposes bounded observation/action operations. Native code
owns consent, Accessibility, current task admission and Stop. Device Bridge owns
the phone connection. VScreen owns presentation and direct human input. None may
infer Android Use authority from another owner's Ready state.

The thin `android-use` Skill is an independent product Skill registered through
OpenClaw's standard Skill loading configuration; it is not owned by the Android
Use Plugin. Chat may expose it only while both Gateway eligibility and the
Android Use owner report Ready. The Skill supplies routing instructions only:
selecting it cannot enable Android Use, grant consent, satisfy Accessibility or
create a control lease.

## DevKit experience

The Android Use row appears under Device capabilities:

- `Disabled` offers Enable and explains that Agent actions require Accessibility.
- Enable first records explicit native consent, then guides only missing system
  prerequisites and verifies the actual service connection.
- `Ready` permits future supported tasks without repeated per-Chat approval.
- `Needs permission` or `Needs repair` identifies the missing owner and offers
  its focused recovery action.
- Disable immediately closes new admission and revokes current control while
  preserving the independently healthy VScreen and phone connection.

No Android Use setup appears during normal developer onboarding or merely because
VScreen opens. A relevant Agent request may direct the user to DevKit when the
capability is Disabled.

## Task and lifecycle rules

| Event | Required behavior |
| --- | --- |
| Task starts | Acquire one canonical lease after consent, Accessibility, phone and target checks. |
| Tool activity/navigation | Preserve the same valid task; do not create a second lease or window. |
| Task succeeds/fails | Release Agent authority; do not hide, minimize or stop healthy VScreen. |
| Native Stop | Revoke the exact current lease immediately; keep saved global consent. |
| Android Use disabled | Revoke current control and reject future admission until explicitly re-enabled. |
| App background/return | Continue only while the same task and native Stop surface remain reachable; never replay input. |
| Main display off, device locked or Accessibility lost | Revoke current control. Later recovery permits fresh work but cannot revive it. |
| Device/Gateway/process/target loss | Revoke matching generations and reconcile before new work. |
| Open app | Revoke only control matching that installed result before physical-display handoff; retain VScreen. |

An Android virtual display can keep global wakefulness true while the physical
screen is off, so Stop availability requires the main display to be ON and the
device unlocked. Screen/keyguard broadcasts alone are insufficient.

## Authorization invariants

- Native consent is the only product-level Android Use grant. Full, Workspace
  permission, Plugin installation, Accessibility alone or model text cannot
  replace it.
- Authority binds canonical Chat/session/run, exact phone/connection generation,
  package, display assignment and operation. Guessed, stale or mismatched values
  fail closed.
- Stop/disable/downgrade revocation is immediate and generation-specific. A late
  completion cannot affect replacement work.
- Observation and input stay bounded to supported methods; no generic ADB shell,
  app install, VScreen lifetime or browser-data authority leaks through.
- Logs and UI never expose reusable credentials, authorization tokens or raw
  accessibility content beyond the requested task surface.

## VScreen relationship

VScreen is always available as an independent presentation tool when its own
owners are healthy. Direct taps and swipes in VScreen are human input through
scrcpy and need no Accessibility. Android Use can target any exact installed,
launchable app on either `main` or `vscreen`; the latter publishes one generic
package assignment and then binds its lease to that exact assignment generation.
It does not require a Project, build or App Delivery receipt, and it does not
retain, stop or authorize the global screen.

## Validation

When this boundary changes, run focused authorization/target tests plus one real
task and one directly related Stop, disable, permission-loss or owner-replacement
case. Reuse existing phone/VScreen state and do not rebuild an app or rerun the
complete matrix unless that behavior also changed. Record observed results in
[validation.md](validation.md).
