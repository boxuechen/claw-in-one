#!/usr/bin/env node

const [currentJson, installRootInput, sharedDirInput] = process.argv.slice(2);

if (!currentJson || !installRootInput || !sharedDirInput) {
  throw new Error("usage: reconcile-plugin-paths.mjs <current-json> <install-root> <shared-dir>");
}

const current = JSON.parse(currentJson);
if (!Array.isArray(current) || !current.every((entry) => typeof entry === "string")) {
  throw new Error("plugins.load.paths must be a string array");
}

const trimTrailingSlashes = (value) => value.replace(/\/+$/, "");
const installRoot = trimTrailingSlashes(installRootInput);
const sharedDir = trimTrailingSlashes(sharedDirInput);
const productPlugins = [
  "android-use",
  "vscreen-foundation",
  "android-developer-bridge",
  "web-development",
  "project-workspaces",
];

const isProductOwnedPath = (entry) => {
  const normalized = trimTrailingSlashes(entry);
  const supervisorPrefix = `${installRoot}/supervisor/`;
  const supervisorRelative = normalized.startsWith(supervisorPrefix)
    ? normalized.slice(supervisorPrefix.length)
    : null;
  const matchesProductOverlay =
    supervisorRelative !== null &&
    (/^current\/[^/]+$/.test(supervisorRelative) || /^releases\/[^/]+\/[^/]+$/.test(supervisorRelative));
  const matchesKnownLayout = productPlugins.some((plugin) => {
    if (normalized === `${sharedDir}/${plugin}` || normalized === `${installRoot}/dev/${plugin}`) {
      return true;
    }
    return false;
  });
  return (
    matchesKnownLayout ||
    matchesProductOverlay ||
    /^\/mnt\/shared\/Download\/ClawInOne\/bootstrap-[0-9a-f]{32}\/[^/]+$/.test(
      normalized,
    )
  );
};

const preserved = current.filter((entry) => !isProductOwnedPath(entry));
process.stdout.write(JSON.stringify([...new Set(preserved)]));
