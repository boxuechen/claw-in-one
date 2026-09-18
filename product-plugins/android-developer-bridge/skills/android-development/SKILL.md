---
name: android-development
description: Create or update a Kotlin and Compose Android app in the current ClawInOne Project, build its APK, install it on this phone, and optionally present it in VScreen.
metadata:
  openclaw:
    requires:
      env:
        - CLAW_IN_ONE_ANDROID_PROFILE
---

# Android development

Work only in the current ClawInOne Project. The Supervisor owns the qualified
Kotlin, Compose, Gradle and Android SDK profile; use normal Android development
knowledge only inside that fixed environment.

For a new app, invoke
`/home/droid/.local/share/claw-in-one/development-profiles/android-kotlin/active/new-project.mjs
--project-dir <dir> --app-name <name> --package-name <reverse-DNS-id>` as one
literal command. Do not inspect or source `capabilities.env`, discover another
SDK or Gradle, run `gradle init`, resolve `latest`, replace the template, or clear
the qualified caches. The materializer records the exact build command, artifact
and stable identity in `.claw-in-one/android-project.v1.json`; use that metadata
and preserve it on follow-up work.

After every build, use `android_app` to inspect the fresh APK and install the
returned artifact. Treat only installed readback as success. For presentation,
place that receipt in VScreen and wait for VScreen ready; presentation failure
does not invalidate installation. Finish without stopping VScreen. Use Android
Use only when the user explicitly requests Agent inspection or interaction.
