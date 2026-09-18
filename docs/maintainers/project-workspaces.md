# Project-first UI and workspace domain

After onboarding, ClawInOne opens a lazy New Project draft. A starter tap stages
an editable prompt and Skill in the composer without sending. The user's explicit
non-empty send opens one naming dialog. Confirm atomically creates one canonical
Git Project, its first Workspace Chat and the initial message. Cancel creates
nothing and preserves the draft. There is no global New Chat; one Project may
contain multiple Chats.

## Invariants

- OpenClaw owns Project identity, root, Session, tools and runs. Android owns only
  drafts/read models and never creates Debian directories or a second catalog.
- Project Chats start with Workspace permission, which grants no Full, build,
  APK install, Android Use, VScreen or browser-data authority. A materialized
  Chat may later change its canonical Gateway permission without changing its
  Project binding.
- One Project has at most one active mutating run initially. Other Chats remain
  browsable and cannot steal, queue behind or silently stop the owner.
- VScreen is App-global; Device Bridge owns phone identity; App Delivery owns
  artifact/install/result; optional control and browser data remain separate.
- Every mutation uses stable intent/idempotency identity and reconciles unknown
  Project, Session, message, install and launch outcomes before retry.
- Android UI preserves `Route → immutable state/actions → Screen` and performs no
  I/O, protocol parsing, path construction or authority decisions.

## Domain

| Type | Owner and meaning |
| --- | --- |
| `ProjectDraft` / `ProjectNameDraft` | Android-local uncommitted request, name origin/revision and inline validation. |
| `ProjectCreationAttempt` | Stable coordinator identity for name reservation, Project, first Session and initial send. |
| `ProjectRecord` | Gateway ID, display name, opaque canonical root, revision and availability. |
| `ProjectSessionBinding` | Gateway Project/Session/key/root with its current confirmed permission mode. |
| `ProjectChatDraft` | One pristine local composer per Project; first send creates its Session. |
| `ProjectRunLease` | Exact Project/Session/run/generation; the only active writer. |
| `ProjectArtifactReference` | Reference to App Delivery truth, never a copied install claim. |

Agent-writable Project metadata may describe build/package/output hints but never
establishes identity, readiness, permission or an install receipt.

## Atomic creation and naming

Gateway validates/reserves the name, allocates a safe root below the configured
developer directory, initializes Git with an initial commit, registers the Project
and reads it back. Android and App Delivery cannot emulate this operation.

Names trim surrounding whitespace, normalize Unicode and compare by a
locale-independent case-insensitive key. Display name and stable root are separate;
future rename must not move the directory.

- Default origin allocates `New Project`, `New Project 2`, `New Project 3`, …
  atomically. Android may prefill; Gateway resolves races.
- Custom collision returns typed `name_conflict` before filesystem mutation and
  never auto-suffixes.
- Empty, control-character, path-separator and over-limit names fail inline.
- Repeating one revision reuses the attempt; editing creates a new revision/key.

## UI contract

### Drawer and entry

The Project area is always visible below the fixed global destinations. Projects
sort by latest Chat activity and remain visible as outlined folder rows. Tapping
a Project only expands/collapses its nested Chats; it does not select a Chat.
Each row owns one compact New Chat action for that exact Project. Search retains
Project grouping and temporarily reveals matches without changing saved collapse
state. Initial scope has no overview, rename/remove, import, manual reorder,
branch or worktree UI.

The drawer footer owns New Project and Settings. There is no creation action in
the product header and no global New Chat.

New Project is a quiet bootstrap canvas with the fixed Ready-derived 2×2 Kotlin,
Flutter, Godot and Web starters plus the composer. It is not a Chat until the
atomic creation flow confirms the Project, first Workspace Session and initial
message. It asks for no path, SDK or permission mode. A starter only prefills the
composer; explicit Send preserves that draft while naming. After Project commit,
failure opens that Project with Finish setup rather than creating another.

### Name dialog

Use one modal with Project name, Cancel and Create project. Prefill the current
default without focusing or opening IME; tapping selects it for replacement.

A custom conflict keeps the dialog open, preserves/focuses the value, shows
`Name already exists. Choose another.`, warning styling and one semantic
announcement. Shake once horizontally by at most 4 dp unless reduced motion is
enabled. Clear on edit; never rely on color or show a second error dialog.

### Project Chat

Header shows only the Chat Session name. Project context, model, conditional
account, thinking, context, naming and per-Chat access live in its options sheet.
New Chat remains a drawer action. Manual rename sets the Gateway Session `label`;
Use automatic name clears that label and reveals `displayName` or `derivedTitle`.
Android may stage `sessions.title.prepare` while the first prompt is composed, but only
`sessions.create.displayName` persists that prepared result. First send in a
Project Chat draft creates the bound Workspace Session; follow-ups remain there.
Every empty Chat in an existing Project is a conversation-only canvas with its
composer; it never displays New Project starters, regardless of Chat order.

After materialization, the access summary opens a bounded picker for Default,
Read-only, Standard, Workspace and Full; it does not expand Chat options. The UI
sends choices only through the Session permission owner, waits for canonical
readback and never reconstructs the Project root. Applying, unavailable and
unconfirmed states expose their owner-routed recovery action. If another Chat
owns the lease, the timeline shows `This project is active in another Chat` and
Return to owner.

## Lifecycle and recovery

```text
LocalDraft → Naming → ProjectConfirmed → SessionConfirmed
  → SubmittingInitialMessage → ActiveProjectChat
```

Each step reads back unknown outcomes and reuses reserved identities.

- First onboarding-complete entry opens New Project; ordinary resume opens the last
  concrete Project/Chat.
- Explicit New Project does not replace the retained concrete route until commit.
- Completion releases the run lease and retains VScreen. Stop releases only the
  exact run/lease and matching control.
- VScreen failure keeps Chat, build, installed result and Open usable. Project
  failure preserves its identity/Chats and routes recovery to its owner.
- Open app return resolves the exact owning Project/Chat and replays no workload
  or control action.

## Install admission

The smooth Workspace path still requires current immutable profile readiness,
the exact active Project run, qualified build and App Delivery receipt bound to
Project/Session/run/artifact/phone. Model text, arbitrary paths/commands, shell
output, stale receipts and Project metadata grant nothing.

## Focused acceptance

Verify default and custom naming (including atomic collision), cancellation,
duplicate activation/idempotency, one Project with multiple Chats, one-writer
contention/return, readback recovery, resume routing, starter-to-Skill equivalence
and exact install admission. Use [testing.md](testing.md).

Deferred: clone/import, rename/remove, overview/editor/build history, branches/
worktrees, concurrent writers, global Chats, moving Chats and multiple VScreen
targets.
