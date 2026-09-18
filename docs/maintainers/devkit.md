# DevKit

DevKit is ClawInOne's first-party capability and recovery center. It projects
authoritative owner state and routes the owner's allowed action. It does not own
installation, permissions, Plugin/Skill state, ADB, or Agent execution.

## Catalog

| Group | Capability | Product policy |
| --- | --- | --- |
| Core | OpenClaw environment | Required fixed environment; repair through Supervisor. |
| Core | Android device connection | Built-in exact-phone Device Bridge; pairing is not installation. |
| Core | VScreen | Built in and non-disableable; Open or Repair. |
| Device capability | Android Use | Optional Enable/Disable with Accessibility-backed consent. |
| Development stack | Kotlin, NDK, Flutter, Godot, React Native, Web | Optional qualified profiles; Install, Retry, or Repair. |

Android Chrome Use/CDP is deferred and has no row, starter, permission, or
placeholder. SDK, NDK, JDK, Gradle, CMake, QEMU, framework distributions, and
caches are dependency components, not user-selectable capabilities.

## Ownership and readiness

```text
owner snapshots -> capability catalog -> immutable DevKit state -> Screen
DevKit action -> owning service -> authoritative readback
```

| Owner | Responsibility |
| --- | --- |
| Supervisor | Signed dependency graph, downloads, qualification, and profile generations. |
| Device Bridge | Private ADB server, saved phone identity, pairing, and reconnect. |
| VScreen | Display target, stream, presentation health, and direct human input. |
| Android Use | Saved consent, Accessibility prerequisite, and exact control leases. |
| Gateway | Eligible Skills and Tools. |
| DevKit | Read-only composition and navigation to owner actions. |

Installed, Ready, selected, invoked, and authorized are different states.
Installed files never prove readiness; Skill selection never grants build,
install, ADB, VScreen, Android Use, or filesystem authority. Unknown mutations
are read back before retry, and a failed optional profile cannot invalidate the
required environment or another Ready profile.

Development profiles share one qualified Toolchain Store. Exact components and
caches deduplicate while incompatible versions coexist. General Node belongs to
the required environment and remains separate from OpenClaw's private Node
runtime. Project builds use bounded profile entrypoints; they never resolve
`latest`, clear shared caches, or turn Supervisor into a general build service.

## UI contract

- DevKit is a full-screen drawer destination before Plugins.
- Home begins with Core, Device capabilities, and Development stacks. Healthy
  aggregate state stays quiet; actionable rows identify one next step.
- Rows use product names, owner-derived state, concise detail, and only the
  action supported by that owner. Keep Plugin IDs, paths, protocol methods, and
  dependency topology out of the primary UI.
- Required rows cannot be disabled or removed. VScreen is built in. Android Use
  alone has Enable/Disable. Optional stacks expose Install, Retry, or Repair.
- Detail Back returns to DevKit; DevKit Back returns to the retained Chat while
  preserving its Project, draft, and global VScreen.
- Settings does not duplicate phone, VScreen, Android Use, or development-stack
  management. Internal product Plugins remain hidden from the ecosystem list.

Temporary phone or VScreen degradation does not relabel a qualified framework
profile. A later install updates current readiness and future starter/Skill
eligibility; it never changes onboarding history or existing Projects.

## Chat activation

Chat exposes only the fixed ClawInOne workflows whose owner and Gateway both
report Ready:

| Label | Stable Skill reference | Owner capability |
| --- | --- | --- |
| Android Use | `android-use` | Android Use |
| Kotlin app | `android-development` | Kotlin |
| NDK app | `android-native-development` | NDK |
| Flutter app | `flutter-development` | Flutter |
| Godot game | `godot-android-development` | Godot |
| React Native app | `react-native-development` | React Native |
| Web app | `web-development` | Web |

The composer `+ -> Skills`, `@`, and New Project starters dispatch the same
typed selection with the same stable reference. A starter only stages an
editable prompt and Skill; sending remains explicit. OpenClaw default and
third-party Skills stay outside this product picker, and Chat contains no
installation or management shortcut.

Skills contain only ClawInOne-specific workflow constraints. They do not teach
frameworks, install dependencies, execute work, or grant authority. Gateway
inventory is canonical; Android never synthesizes a missing Skill, classifies
prompts, injects hidden instructions, or adds a second Agent loop.

VScreen is a global presentation capability and needs no activation Skill.
Opening it without a workload shows secondary Home. Android Use is different:
its Skill is visible only while the independently consented capability is Ready,
and every Tool invocation still requires a fresh exact control lease.

## Acceptance

For a changed capability, verify owner-state projection, the allowed recovery
action, eligible Skill/starter behavior, zero contribution to unrelated Chats,
and one execution-side stale/unrelated rejection. Real device work is needed
only when the changed boundary reaches installation, phone control, VScreen, or
delivery. Follow [testing](testing.md) and reuse
[accepted evidence](../validation.md).
