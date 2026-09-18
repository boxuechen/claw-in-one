# Third-party notices

ClawInOne incorporates and adapts the official OpenClaw Android App from
[openclaw/openclaw](https://github.com/openclaw/openclaw), pinned at the source
revision recorded in [UPSTREAM.md](UPSTREAM.md). That code is distributed under
the MIT License, copyright 2026 OpenClaw Foundation.

Android dependency licenses and bundled attribution texts live in
`apps/android/THIRD_PARTY_LICENSES/` and are exposed by the App's Licenses
screen. Fonts and other attributed assets retain their notices there.

The APK does not redistribute the OpenClaw, Node.js, or scrcpy runtime
artifacts. Bootstrap and the Android preview runtime download pinned artifacts
from their official distribution endpoints, verify their exact size and digest,
and install them into the user's Debian environment. Those artifacts remain
subject to their respective licenses. The scrcpy device server is licensed
under Apache License 2.0; its attribution is included in the App's Licenses
screen.
