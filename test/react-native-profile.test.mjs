import assert from "node:assert/strict";
import { execFile } from "node:child_process";
import crypto from "node:crypto";
import fs from "node:fs/promises";
import os from "node:os";
import path from "node:path";
import test from "node:test";
import { promisify } from "node:util";

const runFile = promisify(execFile);
const profileSource = new URL("../bootstrap/react-native-android-v1/", import.meta.url);

test("React Native profile pins the qualified standalone Android closure", async () => {
  const release = JSON.parse(await fs.readFile(new URL("release.json", profileSource), "utf8"));
  const lock = await fs.readFile(new URL("template/package-lock.json", profileSource));
  const manifest = await fs.readFile(
    new URL("template/android/app/src/main/AndroidManifest.xml", profileSource),
    "utf8",
  );
  assert.equal(release.profileId, "react-native-android-v1");
  assert.equal(release.generation, 1);
  assert.equal(release.nodeVersion, "22.22.0");
  assert.equal(release.reactNativeVersion, "0.87.1");
  assert.equal(release.abi, "arm64-v8a");
  assert.equal(release.newArchitecture, true);
  assert.equal(release.hermes, true);
  assert.equal(release.metroRequired, false);
  assert.equal(crypto.createHash("sha256").update(lock).digest("hex"), release.packageLockSha256);
  assert.match(manifest, /android\.permission\.ACCESS_LOCAL_NETWORK" tools:node="remove"/u);
  assert.match(manifest, /android\.permission\.SYSTEM_ALERT_WINDOW" tools:node="remove"/u);
});

test("React Native materializer preserves repository identity and resolves all placeholders", async () => {
  const root = await fs.mkdtemp(path.join(os.tmpdir(), "clawinone-rn-profile-"));
  const profile = path.join(root, "profile");
  const workspace = path.join(root, "workspace");
  const project = path.join(workspace, "project");
  const fakeNpm = path.join(root, "npm");
  const builder = path.join(profile, "build-project.mjs");
  await fs.cp(profileSource, profile, { recursive: true });
  await fs.mkdir(path.join(project, ".git"), { recursive: true });
  await fs.writeFile(path.join(project, ".git", "sentinel"), "keep\n");
  await fs.writeFile(fakeNpm, "#!/bin/sh\nexit 0\n", { mode: 0o755 });
  await fs.writeFile(
    path.join(profile, "profile.json"),
    JSON.stringify({
      schemaVersion: 1,
      profileId: "react-native-android-v1",
      generation: 1,
      status: "ready",
      targetPlatform: "android-arm64",
      reactNativeVersion: "0.87.1",
      nodeVersion: "22.22.0",
      newArchitecture: true,
      hermes: true,
      metroRequired: false,
      javaHome: "/opt/java",
      androidSdkRoot: "/opt/android",
      gradleUserHome: "/opt/gradle-cache",
      npmCache: "/opt/npm-cache",
      nodeBinary: process.execPath,
      npmBinary: fakeNpm,
      builder,
      buildCommand: `${builder} --project-dir .`,
      artifact: "android/app/build/outputs/apk/debug/app-debug.apk",
    }),
  );
  await runFile(
    process.execPath,
    [
      path.join(profile, "new-project.mjs"),
      "--project-dir",
      project,
      "--app-name",
      "Pocket Notes & Tasks",
      "--package-name",
      "dev.clawinone.pocketnotes",
    ],
    { cwd: workspace },
  );
  assert.equal(await fs.readFile(path.join(project, ".git", "sentinel"), "utf8"), "keep\n");
  const app = await fs.readFile(path.join(project, "App.tsx"), "utf8");
  const gradle = await fs.readFile(path.join(project, "android/app/build.gradle"), "utf8");
  const strings = await fs.readFile(path.join(project, "android/app/src/main/res/values/strings.xml"), "utf8");
  assert.match(app, /Pocket Notes & Tasks/u);
  assert.match(gradle, /namespace "dev\.clawinone\.pocketnotes"/u);
  assert.match(strings, /Pocket Notes &amp; Tasks/u);
  assert.equal((await fs.readFile(path.join(project, "app.json"), "utf8")).includes("ClawInOneApp"), true);
  const files = await fs.readdir(path.join(project, "android/app/src/main/java/dev/clawinone/pocketnotes"));
  assert.deepEqual(files.sort(), ["MainActivity.kt", "MainApplication.kt"]);
  const metadata = JSON.parse(
    await fs.readFile(path.join(project, ".claw-in-one/android-project.v1.json"), "utf8"),
  );
  assert.equal(metadata.buildCommand, `${builder} --project-dir .`);
  await fs.rm(root, { recursive: true, force: true });
});

test("React Native builder owns the qualified environment and validates the Project", async () => {
  const root = await fs.mkdtemp(path.join(os.tmpdir(), "clawinone-rn-builder-"));
  const profile = path.join(root, "profile");
  const project = path.join(root, "project");
  const builder = path.join(profile, "build-project.mjs");
  const artifact = path.join(project, "android/app/build/outputs/apk/debug/app-debug.apk");
  await fs.cp(profileSource, profile, { recursive: true });
  await fs.mkdir(path.join(project, ".claw-in-one"), { recursive: true });
  await fs.mkdir(path.join(project, "android"), { recursive: true });
  await fs.writeFile(
    path.join(project, "android/gradlew"),
    `#!/bin/sh\nset -eu\ntest "$CI" = true\ntest "$JAVA_HOME" = /opt/java\ntest "$ANDROID_HOME" = /opt/android\ntest "$ANDROID_SDK_ROOT" = /opt/android\ntest "$GRADLE_USER_HOME" = /opt/gradle-cache\nprintf '%s\\n' "$@" > .build-arguments\nmkdir -p android/app/build/outputs/apk/debug\n: > android/app/build/outputs/apk/debug/app-debug.apk\n`,
    { mode: 0o755 },
  );
  const buildCommand = `${builder} --project-dir .`;
  await fs.writeFile(
    path.join(profile, "profile.json"),
    JSON.stringify({
      schemaVersion: 1,
      profileId: "react-native-android-v1",
      generation: 1,
      status: "ready",
      targetPlatform: "android-arm64",
      reactNativeVersion: "0.87.1",
      nodeVersion: "22.22.0",
      newArchitecture: true,
      hermes: true,
      metroRequired: false,
      javaHome: "/opt/java",
      androidSdkRoot: "/opt/android",
      gradleUserHome: "/opt/gradle-cache",
      nodeBinary: process.execPath,
      builder,
      buildCommand,
    }),
  );
  await fs.writeFile(
    path.join(project, ".claw-in-one/android-project.v1.json"),
    JSON.stringify({
      schemaVersion: 1,
      profileId: "react-native-android-v1",
      profileGeneration: 1,
      projectDirectory: project,
      applicationId: "dev.clawinone.builder",
      appName: "Builder",
      buildCommand,
      artifact: "android/app/build/outputs/apk/debug/app-debug.apk",
    }),
  );

  const { stdout } = await runFile(process.execPath, [builder, "--project-dir", "."], { cwd: project });
  assert.equal(JSON.parse(stdout).artifact, path.join(await fs.realpath(project), "android/app/build/outputs/apk/debug/app-debug.apk"));
  assert.deepEqual(
    (await fs.readFile(path.join(project, ".build-arguments"), "utf8")).trim().split("\n"),
    ["--project-dir", path.join(await fs.realpath(project), "android"), "--offline", "--max-workers=1", ":app:assembleDebug"],
  );
  await fs.rm(root, { recursive: true, force: true });
});
