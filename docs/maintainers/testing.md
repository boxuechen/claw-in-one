# Verification workflow

ClawInOne is a pre-release personal showcase. Validate the changed behavior and
its direct owner boundary; normal work does not recertify the entire system.

## Levels

| Level | Required evidence |
| --- | --- |
| Daily change | Relevant format/compile/tests plus one inspection of changed behavior. Authority, mutation or recovery work also gets one adjacent adverse case. |
| Release candidate | Complete repository gate once, then one real Project create/build/install/VScreen/Open journey. Include Android Use only when changed or demonstrated. |

Clean-device onboarding, Provider matrices, production signing and product-wide
visual/accessibility coverage are release work unless directly changed.

## Smallest sufficient daily check

| Change | Check |
| --- | --- |
| Documentation | Docs scope and diff whitespace. |
| Shared Compose foundation | Focused component/state tests, compile, and representative light/dark or size inspection. |
| Drawer/navigation | Route/reducer tests plus direct inspection of open, destination, Project/Chat selection and Back/Close. |
| Chat/starter/naming | Focused state tests plus empty, streaming, durable result and duplicate-name states as applicable. |
| DevKit/onboarding | Focused owner projection/controller tests and affected state inspection; install only if device behavior changed. |
| Project, Skill or Tool admission | Owner tests, one accepted current-Project case and one stale/unrelated rejection. |
| Capability plan/profile | Graph/profile/protocol tests, failed recovery and proof unrelated Ready state survives. |
| APK build/delivery/Open | Builder and App Delivery tests plus one exact success/readback or unknown-outcome recovery. |
| VScreen lifecycle/input | Focused controller/protocol tests plus the changed retained-phone path. |
| Android Use authority | Focused tests, one real task and one matching revocation/owner case. |
| Device Bridge/networking | Owner tests and directly affected retained-phone integration; a desktop Gateway must leave host `adb devices` unchanged. Re-pair only if identity changed. |
| Web build/server/Chrome handoff | Web scope plus one retained-phone build/serve/open/update/refresh/Stop path; no CDP. |
| Broad shared integration | Affected module gates; complete gate only when the risk cannot be isolated. |

Fixtures do not prove real-device or real-Agent acceptance. Documentation-only
or pure file-location changes require neither.

## Capability-pack validation template

Run a batch once after its owner boundary changes, then reuse the accepted evidence.

| Batch | Required | Omit by default |
| --- | --- | --- |
| Contract/plan | Stable IDs, closure/graph, serialized mutation, idempotency, stale/replay rejection, unknown-outcome readback and failure isolation. | Downloads, Compose, phone, full gate. |
| Profile/activation | Digests/cache reuse, fixed output, atomic activation, explicit/relevant Skill or Tool admission and zero unrelated contribution. | Other frameworks and broad matrices. |
| Product surface | Onboarding if changed, DevKit owner states/actions, Ready starter/navigation. | Clean onboarding. |
| Integrated path | One Project/Chat, real output, bounded delivery, small same-Project update and one related failure/recovery. | Second Project, other framework, Android Use and full gate. |

For Web, the integrated path is build/serve/reverse/Open in Chrome/update/refresh/
Stop instead of APK/VScreen. Android Chrome Use/CDP is paused and has no current
validation matrix.

## Reuse and completion

Reuse the [accepted baseline](../validation.md#accepted-baseline), existing Projects,
profiles, caches, Apps, Provider, pairing, and installed results until their
owner or pinned version changes.

- Decide assertions first and stop once they pass.
- Prefer test filters and incremental builds; do not clean caches, reset App data,
  reauthenticate or rebuild historical demos without a concrete reason.
- Install ClawInOne only to observe changed native behavior. Run a real Agent task
  only when Agent/tool integration changed.
- VScreen degradation does not invalidate a verified APK or Open app result.
- Report unavailable checks as `BLOCKED` or `NOT RUN`; recovery does not erase the
  original failure.

## Execution rules

```bash
bash scripts/check.sh --scope docs
bash scripts/check.sh --scope web
bash scripts/check.sh --list
bash scripts/check.sh --scope <scope> --list
bash scripts/check.sh # release candidate or justified broad integration only
```

For Android, use the smallest Gradle tasks and `--tests` filters. Add
`:app:assembleThirdPartyDebug` only for an installable native build; do not run
`clean` by default.

Routine evidence belongs in the task or PR. Add only reusable accepted device
behavior or open release gaps to [validation.md](../validation.md); keep raw logs,
screenshots, APKs and transcripts under ignored `.verification/` output.
