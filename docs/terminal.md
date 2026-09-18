# Drawer Terminal

**Status:** verified on the retained Pixel 8 / Android 17 environment.

Drawer Terminal is the advanced shell entry into the same Debian environment as OpenClaw. Chat remains primary. It reuses the Gateway terminal; system Terminal is the recovery escape hatch when Gateway-dependent access is unavailable.

## Contract

- One drawer entry opens a full-screen destination with Back and Run environment. Leaving detaches the view without intentionally ending live work.
- One session supports input, resize, scroll, selection, clipboard, common control keys, and authoritative reconnect.
- Run environment reuses Local environment state/actions and preserves terminal input/session when closed.
- Loading, authorization-required, disconnected, unavailable, ended, and last-known states remain distinct. Never replay commands or claim survival without readback.
- Multiline/control-character paste requires confirmation. Android owns IME/clipboard; upstream owns rendering, PTY traffic, and session operations.
- Supported recovery is Recheck/Reconnect, Ensure OpenClaw, retry/skip current setup, and Open system Terminal. No invented stop/restart/update action.
- A lost mutation response is reconciled and never repeated. Credential renewal uses the trusted local endpoint; Android persists no terminal bytes or commands.

The native adapter hides upstream multi-tab/upload chrome and corrects mobile scroll/selection coordinates. Cold process recreation starts a new view; durable multi-session management is out of scope.

Device acceptance covers real I/O, Ctrl-C, keyboard, selection/copy/paste, resize/scroll, foreground/background, drawer/environment navigation, truthful outage recovery, and no input replay. Evidence is in [validation.md](validation.md). Independent PTY service, multiple sessions, uploads, full logs, proxy/VM/runtime management, and extra packs are deferred.

The application-level [runtime gate](maintainers/runtime-startup.md) owns outages that affect
the entire product. Drawer Terminal only presents its own Gateway terminal
session. System Terminal closing is presentation-only and never means Linux or
OpenClaw stopped.
