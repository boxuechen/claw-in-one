---
name: react-native-development
description: Create or update a React Native Android app in the current ClawInOne Project, build its standalone ARM64 APK, and install it on this phone.
metadata:
  openclaw:
    requires:
      env:
        - CLAW_IN_ONE_REACT_NATIVE_PROFILE
---

# React Native development

Work only in the current ClawInOne Project. The Supervisor owns the qualified
React Native, Developer Node, Hermes, Gradle, SDK, NDK, CMake and offline cache
profile.

For a new app, invoke
`/home/droid/.local/share/claw-in-one/development-profiles/react-native/active/new-project.mjs
--project-dir <dir> --app-name <name> --package-name <reverse-DNS-id>` as one
literal command. Preserve an existing canonical `.git`. Do not inspect or source
`capabilities.env`, run `npx init`, use the registry as a fallback, start Metro,
upgrade dependencies, patch the generated Android project or replace qualified
caches. Edit ordinary TypeScript and React source, then call
`android_project_build`; preserve the project metadata and application identity
on follow-up work. A stale profile requires DevKit Repair.

Inspect every fresh APK with `android_app`, install the returned artifact and
trust only installed readback. VScreen presentation is optional and independent
of installation. Android Use and generic ADB are not part of this workflow.
