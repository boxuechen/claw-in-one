# Contributing

ClawInOne is an early-stage community project built around Android same-device
OpenClaw. Keep changes focused on the native product surface,
Bootstrap/Supervisor boundary, or reproducible validation.

## Before opening a pull request

1. Create a focused branch and keep unrelated refactors out of the change.
2. Preserve the `Route → immutable state/actions → Screen` UI boundary.
3. Do not add a second Agent loop, private Chat protocol, bundled rootfs, or a
   build-time dependency on a sibling OpenClaw checkout.
4. Before a PR, complete the checks for its changed behavior using
   [Verification workflow](docs/maintainers/testing.md). Reuse passing evidence for unchanged
   code; PR creation does not trigger a second run or a full repository gate.
   Broaden checks for Android-wide or cross-runtime integration changes only
   where focused scopes cannot establish the result. The complete gate runs at
   demo freeze or for such broad integration work.
5. Describe targeted device validation for changed onboarding, networking,
   permissions, Bootstrap, or interaction behavior, and identify unavailable
   checks. Full native-Chat Android Use smoke is not required merely because a
   Preview or Android Use file changed.

Upstream imports must be isolated in their own commit and recorded in
`UPSTREAM.md` with the exact tag and commit. Product changes belong in this
repository; generally reusable OpenClaw fixes should also be proposed upstream.

Never commit API keys, setup codes, signing material, generated APKs, Gradle
caches, or device logs containing private data.
