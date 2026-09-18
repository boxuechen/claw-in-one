# First experience release

The implementation baseline is complete. Current work prepares the first
experience release: prove a clean first run, polish the primary journey, close
release blockers, and avoid capability expansion.

## Release journey

One candidate must complete these paths on a supported phone:

1. Fresh install checks device eligibility and finishes the fixed OpenClaw
   environment plus one verified AI route.
2. The user creates a Project from a starter, explicitly sends the staged prompt,
   and continues in its Workspace Chat.
3. DevKit installs or repairs one selected development profile without affecting
   Chat or unrelated Ready profiles.
4. The Agent builds one real Android or Web result through the qualified path.
5. Android performs exact install/readback, optional VScreen presentation, Open
   on display 0, and one same-Project update; Web performs build/serve/reverse,
   Open in Chrome, update, and exact Stop.
6. App restart and one directly related Supervisor/Gateway/phone interruption
   recover without losing Project, Chat, or draft and without replaying setup.

## Release work

| Priority | Scope | Exit condition |
| --- | --- | --- |
| P0 | Clean-device onboarding | Supported factory-reset path completes from compatibility check to Ready with truthful progress and recovery. |
| P0 | AI access | One subscription sign-in and one API-key route are proven where credentials/region permit; default model verifies. |
| P0 | Primary Project journey | Starter, naming, Chat, build, delivery, Open, and update are coherent and preserve ownership boundaries. |
| P0 | Runtime recovery | Cold start, Terminal/Supervisor loss, Gateway replacement, and local-port recovery preserve the retained shell. |
| P0 | Release packaging | Project-owned signing, versioning, artifact verification, notices, and installation instructions exist. |
| P1 | Product polish | Critical dark/light, large-text, TalkBack, keyboard/inset, and error states are reviewed on the release journey. |
| P1 | DevKit recovery | Phone reconnect, Android Use permission, VScreen startup, and one optional-profile repair are truthful and isolated. |

## Frozen boundaries

- OpenClaw owns Agent, Chat, Provider, model, Tool, Skill, and Plugin truth.
- Supervisor installs signed components and owns Gateway lifecycle; it is not a
  general shell or build service.
- Required environment, phone connection, capability activation, and optional
  development profiles remain separate state domains.
- Device Bridge owns exact-phone ADB; App Delivery owns artifact installation;
  VScreen owns presentation; Android Use owns Agent control.
- Native UI keeps `Route -> immutable state/actions -> Screen`.
- Do not restore global New Chat, Full starter, Preview/Workbench compatibility,
  visible internal Plugins, Android-owned Provider catalogs, or another Agent loop.

## Candidate gate

During normal development, run only the checks required by
[testing](maintainers/testing.md).
For a release candidate:

1. freeze pinned OpenClaw/component identities and generate the candidate APK;
2. run the complete repository gate once;
3. execute the release journey on a clean supported device;
4. run one retained-device update/recovery journey without clearing data;
5. verify notices, signing, artifact digest, install instructions, and known gaps;
6. record only reusable observed results in [validation](validation.md).

## Deferred after the first release

Android Chrome Use/CDP, multiple Gateways/devices/VScreen targets, broad Provider
matrices, general marketplace redesign, arbitrary framework versions, native
automation/agent management, remote devices, and compatibility with replaced
protocols or UI models.
