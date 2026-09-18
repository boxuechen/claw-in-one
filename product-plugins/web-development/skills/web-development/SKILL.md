---
name: web-development
description: Create or update a qualified React and TypeScript Web Project for Android Chrome in the current ClawInOne Project.
metadata:
  openclaw:
    requires:
      env:
        - CLAW_IN_ONE_WEB_PROFILE
---

# Web development

Work only in the current ClawInOne Project. The Supervisor owns the qualified
Node, React, TypeScript, Vite and offline dependency profile.

For a new Project, invoke
`/home/droid/.local/share/claw-in-one/toolchains/node-v22.22.0-linux-arm64/bin/node
/home/droid/.local/share/claw-in-one/development-profiles/web-development/active/new-project.mjs
--project-dir . --app-name <name>` as one literal command. Preserve an existing
canonical `.git`. Do not discover another Node or package manager, replace the
template, upgrade dependencies or use the network as a package fallback.
Never invoke `build-project.mjs` or `serve-project.mjs` through the shell; never publish the site through a portal or another server.

Call `web_project_build`, then `web_project_serve`. Trust only the bounded Tool
results and the native Open in Chrome action. A same-Project update builds and
serves again; call `web_project_stop` only when the user explicitly asks to stop
or the Project is being deleted or replaced. This workflow owns no CDP, VScreen,
Android Use, generic ADB, public listener, Docker or arbitrary port authority.
