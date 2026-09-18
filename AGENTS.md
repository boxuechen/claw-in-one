# ClawInOne

Android client + Bootstrap + Rust Supervisor, running the pinned upstream
OpenClaw runtime on the same phone. Do not add a second Agent loop, private Chat
protocol, bundled rootfs, or build-time dependency on the sibling OpenClaw checkout.
That checkout is reference-only. `bootstrap/` owns the canonical staged scripts.

Use `ClawInOne` for the user-visible product name. Keep stable technical identifiers
such as `claw-in-one`, `clawinone`, `claw_in_one`, and `CLAW_IN_ONE` unchanged unless
a task explicitly changes the associated compatibility boundary.

## Read when relevant

- Product boundaries: `docs/architecture.md`; current release work:
  `docs/roadmap.md`; reusable evidence: `docs/validation.md`.
- Android UI: `apps/android/AGENTS.md` and `apps/android/style.md`. Preserve the
  Route → immutable state/actions → Screen boundary.
- Android Use authority: `docs/android-use.md`; device connection:
  `docs/device-bridge.md`; APK delivery: `docs/app-delivery.md`.
  Preserve canonical authorization, Stop/downgrade revocation, and lifecycle rules.
- Bootstrap/Supervisor: `docs/maintainers/supervisor.md`; first-run flow:
  `docs/maintainers/onboarding.md`.
- Upstream imports: `UPSTREAM.md`; observed validation evidence: `docs/validation.md`.

Read the documents needed for the requested change; no full-document sweep is required.

## Skill scope

This product uses native Android UI and Rust/Node tooling. Web animation, browser
game, sprite, voxel, and website-building skills do not apply to those tasks merely
because they involve UI or Plugins. Use them when the user requests that separate
kind of work. For native UI, start with the Android UI guide linked above.

## Completion and validation

For implementation requests, carry the change through relevant checks, fix failures
introduced by it, and inspect the resulting behavior where applicable. Continue
authorized local edits and validation without asking for approval at each step.
Report any remaining blocker or unverified behavior explicitly.

This is a pre-release personal showcase project. Follow
`docs/maintainers/testing.md`:
daily tasks and PRs require checks for the changed behavior, not a full-system
certification. Once selected checks pass, finish; repeat or broaden only for a
new change, failure, or concrete unresolved concern. Run the complete repository
gate for a release candidate or broad integration changes that cannot be covered by
independent scopes, not automatically before every PR.

Changed interaction or device/runtime behavior needs targeted device validation;
a Preview presentation change does not automatically require full Android Use
smoke. Authority/Stop/ownership changes need one real task and one directly related
revocation case. Reuse valid setup and fixtures; immutable candidates and full
matrices are not daily requirements. Unit tests do not establish device acceptance.
Report unavailable checks as BLOCKED/NOT RUN and record only observed device
results in `docs/validation.md`.
