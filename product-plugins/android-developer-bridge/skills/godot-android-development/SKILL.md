---
name: godot-android-development
description: Create or update a Godot GDScript game in the current ClawInOne Project, export its Android ARM64 APK, and install it on this phone.
metadata:
  openclaw:
    requires:
      env:
        - CLAW_IN_ONE_GODOT_ANDROID_PROFILE
---

# Godot development

Work only in the current ClawInOne Project. The Supervisor owns the qualified
ARM64 Godot engine, matching Android export template, isolated state and Android
dependencies.

For a new game, invoke
`/home/droid/.local/share/claw-in-one/development-profiles/godot-android/active/new-project.mjs
--project-dir <dir> --app-name <name> --package-name <reverse-DNS-id>` as one
literal command. Use GDScript, the mobile renderer and the exact build command
and artifact recorded in `.claw-in-one/android-project.v1.json`. Do not discover
another engine, export template, SDK, NDK, Gradle or Linux display workflow. A
stale profile requires DevKit Repair rather than a fallback.

After every export, inspect the fresh APK with `android_app`, install the returned
artifact and trust only installed readback. VScreen presentation is optional and
independent of installation. Finish without stopping VScreen; Android Use is
separate and used only for explicit Agent interaction.
