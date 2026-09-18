# Security policy

## Supported code

Until the first stable release, only the latest `main` commit is supported.
Security fixes are not backported to development snapshots.

## Report a vulnerability

Do not open a public issue for a suspected vulnerability or include credentials,
tokens, private logs, or setup codes in a report. Use GitHub's
[private vulnerability reporting](https://github.com/boxuechen/claw-in-one/security/advisories/new)
and include the affected commit, Android build/device, reproduction steps, and
the expected impact.

## Trust boundary

- The Android App and its Debian environment are one same-device trust domain.
- Provider credentials are submitted directly to the local OpenClaw runtime and
  are not persisted by the Android App.
- Bootstrap publishes immutable scripts and a one-time shared handoff file.
  Supervisor moves the handoff into a mode-`600` Debian state file and deletes
  the shared copy before authenticating.
- A rooted device, a compromised Android system, a compromised Debian runtime,
  or physical access to an unlocked device can cross this boundary and is not
  treated as an isolation guarantee.

Never attach the contents of `bootstrap.env`, gateway token files, Android app
data, or unredacted diagnostics to a public issue.
