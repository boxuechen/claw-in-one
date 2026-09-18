---
name: android-native-development
description: Create or update an ARM64 Android Native app with the qualified NDK, CMake and Vulkan profile, then build and install its APK.
metadata:
  openclaw:
    requires:
      env:
        - CLAW_IN_ONE_ANDROID_NATIVE_PROFILE
---

# Android Native development

Work only in the current ClawInOne Project. The Supervisor owns the qualified
Android SDK, NDK, CMake, QEMU, Gradle cache and ARM64 Vulkan profile.

For a new app, invoke
`/home/droid/.local/share/claw-in-one/development-profiles/android-native/active/new-project.mjs
--project-dir <dir> --app-name <name> --package-name <reverse-DNS-id>` as one
literal command. Do not discover or install another SDK, NDK, CMake, Gradle,
template or ABI. Use the exact build command and artifact recorded in
`.claw-in-one/android-project.v1.json`; preserve that metadata and application
identity on follow-up work. If the profile is stale, direct the user to DevKit
Repair instead of improvising a fallback.

After every build, inspect the fresh APK with `android_app`, install the returned
artifact and trust only installed readback. VScreen presentation is optional and
independent of installation. Finish without stopping VScreen; Android Use is not
required unless the user explicitly asks the Agent to operate the app.
