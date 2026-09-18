# Development profiles

**Status:** the profiles below are qualified on the retained Pixel 8 / Android
17 environment. Other supported devices still require device-specific evidence.

Development profiles are optional, pinned toolchain graphs installed and
verified by Supervisor. They are not required for Chat, the local Gateway,
Device Bridge, Android Use, or VScreen.

| Profile | Qualified baseline | Same-device result |
| --- | --- | --- |
| Kotlin | JDK 21, Android SDK 37, pinned Gradle/Compose inputs | Build, inspect, install, launch, and update an ARM64 Android app. |
| Android Native | NDK 29, CMake 3.22, ARM64 Vulkan NativeActivity | Build and deliver a native Android APK. |
| Flutter | Flutter 3.47.4, Dart 3.13.3, NDK 28.2 | Produce and deliver an offline ARM64 APK. |
| Godot | Godot 4.7.2 ARM64 with matching export template | Export a headless GDScript Android game and run it on the phone. |
| React Native | React Native 0.87.1, New Architecture, Hermes, isolated Node toolchain | Build and deliver an ARM64 Android app. |
| Web | React 19.3, TypeScript 7.0, Vite 8.3 | Build, serve, reverse the exact port, and open/update the result in Android Chrome. |

## Product contract

- The required OpenClaw environment and each optional profile have independent
  readiness. One failed profile cannot invalidate Chat or another Ready profile.
- Exact components and caches deduplicate where compatible; incompatible pinned
  versions may coexist.
- Profiles never resolve `latest`, clear shared caches, or turn Supervisor into
  a general build service.
- A Project build remains OpenClaw Workspace work. ClawInOne contributes the
  qualified environment, bounded workflow Skill, and exact delivery path.
- Installing a profile grants no App Delivery, ADB, VScreen, Android Use, or
  filesystem authority by itself.

## Delivery boundaries

Android profiles use [App Delivery](app-delivery.md) and the verified
[Device Bridge](device-bridge.md). VScreen presentation is optional and never
invalidates an otherwise successful install.

The Web profile opens the real Android Chrome through a qualified local
build/serve/reverse workflow. It does not provide Android Chrome CDP automation;
that capability remains deferred.

See [Validation](validation.md) for observed evidence and
[DevKit](maintainers/devkit.md) for installation, readiness, and Chat activation
contracts.
