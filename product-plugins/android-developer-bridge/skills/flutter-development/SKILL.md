---
name: flutter-development
description: Create or update a Flutter Android app in the current ClawInOne Project, build and install its APK, and optionally present it in VScreen.
metadata:
  openclaw:
    requires:
      env:
        - CLAW_IN_ONE_FLUTTER_PROFILE
---

# Flutter development

Work only in the current ClawInOne Project. The Supervisor owns the qualified
ARM64 Flutter, Dart, Android and offline dependency profile.

For a new app, invoke
`/home/droid/.local/share/claw-in-one/development-profiles/flutter/active/new-project.mjs
--project-dir <dir> --app-name <name> --package-name <reverse-DNS-id>` as one
literal command. Preserve an existing canonical `.git`. Do not run `flutter
upgrade`, change channels, discover another Flutter or Android SDK, replace the
template, use the network as a package fallback, or clear qualified caches. Use
the exact build command and artifact recorded in
`.claw-in-one/android-project.v1.json`; preserve project and application identity
on follow-up work. A stale profile requires DevKit Repair.

After every build, inspect the fresh APK with `android_app`, install the returned
artifact and trust only installed readback. VScreen presentation is optional and
independent of installation. Finish without stopping VScreen; Android Use is
separate and used only for explicit Agent interaction.
