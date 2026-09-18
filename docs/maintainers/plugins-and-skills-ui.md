# Plugins and Skills UI

Plugins is the OpenClaw ecosystem surface. DevKit is the first-party ClawInOne
capability center. They are peers in the drawer and must not duplicate
installation, readiness, permission, or recovery UI.

## Product boundary

- A **Plugin** is an installable runtime integration and may own settings or
  Connections.
- A **Skill** is an installable workflow package with its own catalog lifecycle.
- A **Connection** is configuration owned by a Plugin or MCP integration, not a
  third package type.
- A **built-in capability** is supplied by ClawInOne/OpenClaw and has no catalog
  install flow. Its product-facing setup belongs in DevKit.

Internal Device Bridge, App Delivery, VScreen, and Android Use packages never
appear as installable or disableable ecosystem rows. Development Skills become
eligible only when their DevKit profile is Ready; their presence on disk is not
readiness. See [DevKit](devkit.md).

## Non-negotiable behavior

- ClawHub discovery metadata is not installed/readiness truth. Gateway owns
  installed state, compatibility, mutations, and authoritative readback.
- Never invent artwork, publisher, trust, category, compatibility, or grant
  claims that the source did not provide.
- Unknown mutation outcomes disable repetition until Gateway reconciliation.
- Plugin, Skill, Connection, catalog, and built-in-capability states remain
  distinct even when one page composes them.
- UI never exposes secrets, runtime IDs, review tokens, raw status codes, or
  product-internal package topology.
- Preserve `Route -> immutable state/actions -> Screen`; Compose performs no
  catalog networking, RPC construction, or authority decision.

General marketplace navigation, ranking, categories, update presentation, and
Connection hierarchy are deferred until a narrow Plugins release milestone is
selected. That work does not gate the first ClawInOne experience release and
must not reopen DevKit or Chat activation boundaries.
