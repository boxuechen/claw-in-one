# Device compatibility

**Status:** capability-gated; one retained device has completed the current
validation matrix.

ClawInOne does not use a phone-model allowlist. It probes the capabilities that
the local OpenClaw environment actually needs and refuses to infer readiness
from a model name.

## Required capabilities

A candidate device currently needs:

- Android API 35 or later;
- ARM64 (`arm64-v8a`);
- Android Virtualization Framework (`android.software.virtualization_framework`);
- the official system Linux Terminal (`com.android.virtualization.terminal`);
- a launchable official Debian environment;
- Developer options and Wireless debugging for the same-phone Device Bridge;
- enough free storage and memory for the selected development profiles.

The App re-probes Android version, ABI, AVF, the system Terminal, Developer
options, and Terminal launchability at entry and after returning from Settings.
Missing or contradictory evidence is reported as Unknown instead of Ready.

## Verified

| Device | Android | Coverage |
| --- | --- | --- |
| Pixel 8 (`shiba`) | Android 17 / API 37 | Retained-device runtime, development, delivery, VScreen, Android Use, and recovery baseline. |

Exact component versions and open gaps are recorded in
[Validation](validation.md).

## Expected compatible, not yet verified

Tensor-based Pixel devices that expose the required official Terminal and pass
all runtime probes are expected to work. This includes the Pixel 6 family on
Android 16 or later as a compatibility candidate, not as a verified support
claim.

Passing device eligibility proves only the local runtime prerequisites. A model
is promoted to Verified only after the relevant setup, Gateway, Device Bridge,
delivery, VScreen, and selected development-profile paths have been observed on
that device.

## Unsupported or unknown

ClawInOne cannot run its local environment when Android is below API 35, ARM64
is unavailable, AVF is absent, or the official system Terminal is missing. An
OEM may also ship Android 16 or later without the required Linux environment;
the Android version alone is not a compatibility promise.

Device reports should include the model, Android/API version, eligibility
result, Terminal status, and only the capabilities actually exercised. Never
include pairing codes, device identities, credentials, or raw private logs.
