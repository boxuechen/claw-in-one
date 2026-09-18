---
name: android-use
description: Observe and operate the currently authorized Android phone for an explicit user-requested task.
---

# Android Use

Use this workflow only when the user explicitly asks the Agent to inspect or
operate an Android app on the current phone. Invoke the bounded `android_use`
tool with the exact installed package and an explicit display choice: use
`main` for the physical phone or `vscreen` when the user wants the app presented
in VScreen. Keep all observation and input inside the current task, target,
display assignment and authorization lease.

The app does not need to come from the current Project or from App Delivery.
Do not substitute generic ADB, shell commands or VScreen pointer input for
Android Use. VScreen is presentation and direct human input, not Agent authority;
choosing it asks the independent display owner to present the exact app first.
If Android Use is unavailable, disabled or loses Accessibility, phone, target or
Stop availability, explain that it must be enabled or repaired in DevKit and end
the current attempt. Never replay input or resume an earlier control lease after
recovery, lock, Stop or permission loss.
