# Installation

ClawInOne is an early Alpha for technical users. It needs no root, companion PC,
or separately deployed OpenClaw server, but it does depend on Android's official
virtualized Linux environment and explicit system permissions.

## Compatibility

The retained release baseline is Pixel 8 (`shiba`) on Android 17 / API 37. A
device must provide all of the following:

- Android API 35 or later on ARM64 (`arm64-v8a`);
- Android Virtualization Framework and the official system Linux Terminal;
- a launchable official Debian environment;
- Developer options and Wireless debugging;
- enough free storage and memory for the selected development profiles.

Tensor-based Pixel devices that pass every in-app probe—including Pixel 6 on
Android 16 or later—are compatibility candidates, not yet verified support.
See [Device compatibility](device-compatibility.md) for the precise policy.

## Install the APK

1. Open the ClawInOne page under
   [GitHub Releases](https://github.com/boxuechen/claw-in-one/releases).
2. Download the signed `thirdParty-release.apk` and `SHA256SUMS` from the same
   release. Do not install APKs redistributed by another source.
3. Verify the APK digest against `SHA256SUMS` when your environment provides a
   SHA-256 tool.
4. Open the APK from Android Files and allow that app to install unknown apps
   when Android asks. This permission can be revoked after installation.

Installing from GitHub does not require ADB. Developers may instead install a
source build as described in [Building from source](building.md).

## Complete the guided setup

ClawInOne checks each prerequisite and opens the relevant Android-owned setup
surface. Follow the app in order:

1. pass the device capability check;
2. enable the official Linux Terminal and start its Debian environment;
3. enable Developer options and Wireless debugging when requested;
4. run the one Supervisor connection command shown by ClawInOne in Terminal;
5. let Supervisor install and verify the pinned OpenClaw environment;
6. configure one supported AI route and verify its default model;
7. optionally enable Android Use Accessibility access or a development profile
   only when you need that capability.

The required environment includes pinned OpenClaw, Node, Chromium, ADB, and the
scrcpy server used by VScreen. Users do not install these components manually.
Network access is still required for AI services and dependency downloads.

## Permissions and safety

- Wireless debugging connects the AVF Debian environment to this exact phone;
  it does not grant a remote server general ADB access.
- Android Use is optional. It requires explicit Accessibility setup and can be
  disabled at any time. Screen contents used for a task may be sent to the AI
  provider configured by the user.
- APK installation, Android Use, VScreen, and development profiles retain
  separate authorization and readiness boundaries.
- ClawInOne never needs root.

## Update or remove

Install a newer APK signed by the same ClawInOne release key to update in place.
Do not uninstall the existing app when testing an update path: uninstalling
removes Android-owned app data. Uninstall ClawInOne normally from Android
Settings when you want to remove it; the official Linux environment and its data
remain Android-owned and may require separate removal.

Current verified behavior and release gaps are listed in
[Validation](validation.md). Report reproducible problems through
[GitHub Issues](https://github.com/boxuechen/claw-in-one/issues) without attaching
API keys, setup codes, device identifiers, or private logs.
