import assert from "node:assert/strict";
import fs from "node:fs";
import path from "node:path";
import test from "node:test";
import {fileURLToPath} from "node:url";

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");
const expectedVersion = "2026.9.4";
const pluginDirectories = [
  "android-use",
  "vscreen-foundation",
  "android-developer-bridge",
  "project-workspaces",
  "web-development",
];

test("the runtime and every product plugin share one exact OpenClaw release", () => {
  const release = fs.readFileSync(
    path.join(root, "apps/android/app/src/main/java/ai/openclaw/app/bootstrap/OpenClawRelease.kt"),
    "utf8",
  );
  const escapedVersion = expectedVersion.replaceAll(".", "\\.");
  assert.match(release, new RegExp(`\\bversion = "${escapedVersion}"`));
  assert.match(release, new RegExp(`openclaw-${escapedVersion}\\.tgz`));

  for (const directory of pluginDirectories) {
    const manifest = JSON.parse(
      fs.readFileSync(path.join(root, "product-plugins", directory, "package.json"), "utf8"),
    );
    assert.equal(manifest.peerDependencies?.openclaw, expectedVersion, directory);
    assert.equal(manifest.openclaw?.compat?.pluginApi, expectedVersion, directory);
    assert.equal(manifest.openclaw?.compat?.minGatewayVersion, expectedVersion, directory);
    assert.equal(manifest.openclaw?.build?.openclawVersion, expectedVersion, directory);
    assert.equal(manifest.openclaw?.build?.pluginSdkVersion, expectedVersion, directory);
  }
});

test("the pinned release retains the required Gateway SDK seams", () => {
  const startGateway = fs.readFileSync(path.join(root, "bootstrap/start-gateway.sh"), "utf8");
  assert.match(startGateway, /dist\/plugin-sdk\/device-bootstrap\.js/);
  assert.match(startGateway, /reconcile_bundled_plugin_allowlist/);

  const androidUse = fs.readFileSync(path.join(root, "product-plugins/android-use/index.mjs"), "utf8");
  assert.match(androidUse, /openclaw\/plugin-sdk\/session-transcript-runtime/);
});
