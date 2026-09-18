#!/usr/bin/env node

import fs from "node:fs";
import path from "node:path";

const [currentJson, bundledExtensionsRoot] = process.argv.slice(2);
if (!currentJson || !bundledExtensionsRoot) {
  throw new Error(
    "usage: reconcile-plugin-allowlist.mjs <current-json> <bundled-extensions-root>",
  );
}

const current = JSON.parse(currentJson);
if (!Array.isArray(current) || !current.every((entry) => typeof entry === "string")) {
  throw new Error("plugins.allow must be a string array");
}

const bundledIds = [];
for (const entry of fs
  .readdirSync(bundledExtensionsRoot, {withFileTypes: true})
  .filter((candidate) => candidate.isDirectory())
  .sort((left, right) => left.name.localeCompare(right.name))) {
  const manifestPath = path.join(bundledExtensionsRoot, entry.name, "openclaw.plugin.json");
  if (!fs.existsSync(manifestPath)) continue;
  const manifest = JSON.parse(fs.readFileSync(manifestPath, "utf8"));
  if (typeof manifest.id !== "string" || !manifest.id.trim()) {
    throw new Error(`bundled plugin manifest has no id: ${manifestPath}`);
  }
  bundledIds.push(manifest.id.trim());
}

if (bundledIds.length === 0) {
  throw new Error("pinned OpenClaw release exposes no bundled plugin manifests");
}

process.stdout.write(JSON.stringify([...new Set([...current, ...bundledIds])]));
