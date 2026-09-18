# Android Use references

Local research checkouts for the [Android Use contract](../docs/android-use.md), not selected implementation dependencies. The product prioritizes the pinned OpenClaw `mobile_ui` path; these projects address demonstrated gaps rather than replace it by default. Source directories are Git-ignored; only this index belongs to ClawInOne. Do not run upstream setup scripts or import code merely because a reference is present.

The existing OpenClaw Fork stays in `../openclaw/` relative to the product repository root; it is not moved or duplicated here. No reference checkout is a submodule or product build input.

Fetched on 2026-09-05. Git references are shallow clones of each upstream's default branch (`main`, except `uiautomator2` and `scrcpy`: `master`); their links below pin the downloaded commits. Core packages are PyPI source snapshots, not Git checkouts. Git LFS payload downloads were skipped; dependencies were not installed and project code was not executed.

## Scope

| Local directory | Upstream | Reference value |
| --- | --- | --- |
| `mobilerun/` | [droidrun/mobilerun](https://github.com/droidrun/mobilerun/tree/7a0aff54064511d4874859ba5d402dceb1039072) | Full mobile Agent architecture; distinguish its planning/model loop from reusable device tools. |
| `mobile-harness/` | [droidrun/mobile-harness](https://github.com/droidrun/mobile-harness/tree/7d7ab48529941ea7ab0c17c46f710d135ba76675) | Skills and workflows that let an existing Agent operate devices. |
| `mobilerun-portal/` | [droidrun/mobilerun-portal](https://github.com/droidrun/mobilerun-portal/tree/d4cb7d6657385488239812e776df584f890e32fd) | Android AccessibilityService, device state, input, and local control interfaces. |
| `gemini-android-computer-use-quickstart/` | [google-gemini/gemini-android-computer-use-quickstart](https://github.com/google-gemini/gemini-android-computer-use-quickstart/tree/52025016586ecc383a8610efdd53a8d76a51c268) | Screenshot/action feedback and coordinate mapping; a Gemini-specific example, not a cross-provider protocol. |
| `languse-android-use/` | [languse-ai/android-use](https://github.com/languse-ai/android-use/tree/4d0e92e37c286b5d182440dc91e026c1bfb3fba5) | UI hierarchy parsing, indexed targets, compact descriptions, and optional vision. |
| `iurysza-android-use/` | [iurysza/android-use](https://github.com/iurysza/android-use/tree/e23bb1ade98b22049a8342412b31d7648c7842d4) | Structured ADB CLI output and Agent-facing command design. |
| `uiautomator2/` | [openatx/uiautomator2](https://github.com/openatx/uiautomator2/tree/b788548cf5a8e318ecbdd22461d47d19bf53b04c) | Underlying Android automation, hierarchy, selectors, screenshots, and text input. |
| `mobilerun-core/` | [PyPI: mobilerun-core 1.6.1](https://pypi.org/project/mobilerun-core/1.6.1/#files) | Model-free device facade, accessibility helpers, structured actions, and human confirmation hooks. |
| `mobilerun-core-local/` | [PyPI: mobilerun-core-local 0.6.0](https://pypi.org/project/mobilerun-core-local/0.6.0/#files) | Local Android ADB/Portal drivers and transport; execution underneath the Core API. |
| `scrcpy/` | [Genymobile/scrcpy](https://github.com/Genymobile/scrcpy/tree/19c1261d2e2cbf2b5e6a71a8b64cc1dd3ede06ac) | Virtual displays, app launch/lifecycle, low-latency video transport, and display-aware input; Apache-2.0. |

## scrcpy reading guide

- `doc/develop.md`, `doc/virtual-display.md`: server/client responsibilities, transport, display lifecycle, app launch, and IME policy.
- `server/src/main/java/com/genymobile/scrcpy/video/`: `NewDisplayCapture`, `ScreenCapture`, and `SurfaceEncoder` cover capture and encoding.
- `server/src/main/java/com/genymobile/scrcpy/control/`: `Controller` and `PositionMapper` cover input routing and coordinate transforms.
- `app/src/`: `demuxer.c`, `decoder.c`, `screen.c`, and `input_manager.c` show the desktop receive/render/input pipeline, not an embeddable Android UI component.

scrcpy complements UI-tree tools; it does not supply semantic accessibility observations or an Agent Loop. Its device server runs with ADB-granted `shell` privileges and uses hidden Android APIs: copying server classes into an ordinary APK does not grant those privileges. Its wire protocol is internal and requires matching client/server versions. Native App embedding, human/Agent takeover, and same-device integration remain our design work, not capabilities certified by cloning this reference.

## PyPI source provenance

Original source archives are retained in Git-ignored `ref/.archives/`. SHA-256 values were checked against version-specific PyPI metadata before extraction; package directories preserve upstream contents with only the archive's outer directory stripped.

| Archive | SHA-256 |
| --- | --- |
| `mobilerun_core-1.6.1.tar.gz` | `3193cedbf5964e239ab98b93ee89bdd6f09c5ac834b2f5ef5d50c3a48e0a5024` |
| `mobilerun_core_local-0.6.0.tar.gz` | `6ee483d1c325c2a9ebd577d05291aacc87ad9bb9483eebd4a7028643f1c4417f` |

Core declares Apache-2.0 in its package metadata but this archive contains no standalone `LICENSE`; resolve that packaging gap before importing code. Core Local includes an MIT `LICENSE`. Source snapshots provide release code, not Git history or a guarantee of upstream maintenance.

## Reuse boundary

- Keep OpenClaw as the Agent runtime; these references do not authorize a second Agent Loop, Provider stack, cloud service, or runtime fork.
- Audit the license of each component before importing code. In particular, Mobilerun Portal declares AGPL-3.0-or-later, unlike the main Mobilerun framework's MIT license.
- Upstream automatic permission grants, safety acknowledgements, installation, or dependency updates are not our product policy.
- Core's advertised GitHub repository returned `Repository not found` on 2026-09-05. The PyPI source snapshots above are available independently; GitHub unavailability is not evidence that the published packages are closed-source.
