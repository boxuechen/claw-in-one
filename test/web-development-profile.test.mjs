import assert from "node:assert/strict";
import {spawn} from "node:child_process";
import crypto from "node:crypto";
import fs from "node:fs/promises";
import os from "node:os";
import path from "node:path";
import test from "node:test";
import {once} from "node:events";
import {execFile} from "node:child_process";
import {promisify} from "node:util";
import {createSupervisorCapabilityReadiness} from "../product-plugins/project-workspaces/capability-readiness.mjs";

const runFile = promisify(execFile);
const profileSource = new URL("../bootstrap/web-development-v1/", import.meta.url);

async function activeProfile(root) {
  const profile = path.join(root, "profile");
  const npmBinary = path.join(root, "npm-fixture.mjs");
  await fs.cp(profileSource, profile, {recursive: true});
  await fs.writeFile(npmBinary, `#!/usr/bin/env node
import fs from "node:fs";
import path from "node:path";
if (process.argv[2] === "ci") process.exit(0);
if (process.argv[2] !== "run" || process.argv[3] !== "build") process.exit(2);
fs.mkdirSync("dist", {recursive: true});
fs.writeFileSync(path.join("dist", "index.html"), fs.readFileSync("index.html"));
fs.writeFileSync(path.join("dist", "app.js"), "globalThis.webFixture = true;\\n");
`, {mode: 0o555});
  const release = JSON.parse(await fs.readFile(path.join(profile, "release.json"), "utf8"));
  const materializer = path.join(profile, "new-project.mjs");
  const builder = path.join(profile, "build-project.mjs");
  const server = path.join(profile, "serve-project.mjs");
  await Promise.all([materializer, builder, server].map(file => fs.chmod(file, 0o555)));
  const profilePath = path.join(profile, "profile.json");
  await fs.writeFile(profilePath, `${JSON.stringify({
    ...release,
    status: "ready",
    productionBuild: true,
    serverHost: "127.0.0.1",
    cdpRequired: false,
    nodeBinary: process.execPath,
    npmBinary,
    npmCache: path.join(root, "npm-cache"),
    materializer,
    builder,
    server,
    buildCommand: `${builder} --project-dir .`,
    artifact: "dist/index.html",
  }, null, 2)}\n`, {mode: 0o444});
  await fs.writeFile(path.join(profile, "qualified"), `${release.packageLockSha256}\n`);
  return {profile, profilePath, release, materializer, builder, server};
}

test("Web profile pins one production React/Vite closure without CDP", async () => {
  const release = JSON.parse(await fs.readFile(new URL("release.json", profileSource), "utf8"));
  const lock = await fs.readFile(new URL("template/package-lock.json", profileSource));
  const server = await fs.readFile(new URL("template/server.mjs", profileSource), "utf8");
  const main = await fs.readFile(new URL("template/src/main.tsx", profileSource), "utf8");
  const skill = await fs.readFile(
    new URL("../product-plugins/web-development/skills/web-development/SKILL.md", import.meta.url),
    "utf8",
  );
  assert.equal(release.profileId, "web-development-v1");
  assert.equal(release.nodeVersion, "22.22.0");
  assert.equal(release.host, "127.0.0.1");
  assert.equal(release.productionBuild, true);
  assert.equal(release.cdpRequired, false);
  assert.equal(crypto.createHash("sha256").update(lock).digest("hex"), release.packageLockSha256);
  assert.match(server, /\/api\/hello/u);
  assert.doesNotMatch(server, /cdp|devtools|9222/iu);
  assert.match(main, /reference types="vite\/client"/u);
  assert.match(skill, /Never invoke `build-project\.mjs` or `serve-project\.mjs` through the shell/u);
  assert.match(skill, /never publish the site through a portal/u);
});

test("Web profile materializes atomically, builds and serves the Project on loopback", async () => {
  const root = await fs.mkdtemp(path.join(os.tmpdir(), "clawinone-web-profile-"));
  let child;
  try {
    const active = await activeProfile(root);
    const project = path.join(root, "workspace", "web-project");
    await fs.mkdir(path.join(project, ".git"), {recursive: true});
    await fs.writeFile(path.join(project, ".git", "sentinel"), "keep\n");
    await runFile(process.execPath, [active.materializer, "--project-dir", project, "--app-name", "Web <Lab>"], {cwd: root});
    assert.equal(await fs.readFile(path.join(project, ".git", "sentinel"), "utf8"), "keep\n");
    assert.match(await fs.readFile(path.join(project, "index.html"), "utf8"), /Web &lt;Lab>/u);
    assert.match(await fs.readFile(path.join(project, "src/main.tsx"), "utf8"), /Web <Lab>/u);
    const metadata = JSON.parse(await fs.readFile(path.join(project, ".claw-in-one/web-project.v1.json"), "utf8"));
    assert.equal(metadata.buildCommand, `${active.builder} --project-dir .`);

    await runFile(process.execPath, [active.builder, "--project-dir", project], {cwd: project});
    child = spawn(process.execPath, [active.server, "--project-dir", project], {
      cwd: project,
      env: {...process.env, CLAW_IN_ONE_WEB_HOST: "127.0.0.1", CLAW_IN_ONE_WEB_PORT: "0"},
      stdio: ["ignore", "pipe", "pipe"],
    });
    const [chunk] = await once(child.stdout, "data");
    const ready = JSON.parse(chunk.toString("utf8").trim());
    assert.equal(ready.host, "127.0.0.1");
    assert.equal((await fetch(`http://127.0.0.1:${ready.port}/api/hello`)).status, 200);

    const readiness = createSupervisorCapabilityReadiness({
      environment: {
        CLAW_IN_ONE_WEB_PROFILE: active.profilePath,
        CLAW_IN_ONE_WEB_NEW_PROJECT: active.materializer,
        CLAW_IN_ONE_WEB_BUILD_PROJECT: active.builder,
        CLAW_IN_ONE_WEB_SERVE_PROJECT: active.server,
      },
    });
    assert.deepEqual(readiness.current(), {
      revision: "android-kotlin:none|android-native:none|flutter:none|godot-android:none|react-native:none|web-development-v1:1",
      readyCapabilities: ["web_development"],
      profiles: {
        androidKotlin: null,
        androidNative: null,
        flutter: null,
        godotAndroid: null,
        reactNative: null,
        webDevelopment: {profileId: "web-development-v1", generation: 1},
      },
    });
    await fs.chmod(active.profilePath, 0o644);
    assert.equal(readiness.current(), null);
  } finally {
    if (child?.exitCode === null) {
      const closed = once(child, "close");
      child.kill("SIGTERM");
      await closed.catch(() => {});
    }
    await fs.rm(root, {recursive: true, force: true});
  }
});
