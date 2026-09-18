import assert from "node:assert/strict";
import { spawnSync } from "node:child_process";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { test } from "node:test";
import { fileURLToPath } from "node:url";

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");
const materializerSource = path.join(root, "bootstrap/flutter-android-v1/new-project.mjs");

function fixture() {
  const directory = fs.mkdtempSync(path.join(os.tmpdir(), "claw-flutter-profile-"));
  const profile = path.join(directory, "profile");
  const flutterRoot = path.join(directory, "flutter");
  fs.mkdirSync(path.join(flutterRoot, "bin"), { recursive: true });
  fs.mkdirSync(profile);
  fs.copyFileSync(materializerSource, path.join(profile, "new-project.mjs"));
  fs.writeFileSync(
    path.join(flutterRoot, "bin/flutter"),
    `#!/bin/sh
set -eu
case "$1" in
  create)
    for destination do :; done
    mkdir -p "$destination/lib" "$destination/android/app/src/main"
    printf '%s\n' '<manifest><application android:label="placeholder" /></manifest>' > "$destination/android/app/src/main/AndroidManifest.xml"
    printf '%s\n' 'name: fixture' > "$destination/pubspec.yaml"
    ;;
  pub) ;;
  *) exit 9 ;;
esac
`,
    { mode: 0o755 },
  );
  fs.writeFileSync(
    path.join(profile, "profile.json"),
    `${JSON.stringify({
      schemaVersion: 1,
      profileId: "flutter-android-v1",
      generation: 2,
      status: "ready",
      targetPlatform: "android-arm64",
      flutterRoot,
      javaHome: "/opt/claw/java",
      androidSdkRoot: "/opt/claw/android-sdk",
      pubCache: "/opt/claw/pub-cache",
      buildCommand: "flutter build apk --debug --target-platform android-arm64 --no-pub",
      artifact: "build/app/outputs/flutter-apk/app-debug.apk",
    })}\n`,
  );
  return { directory, profile };
}

function materialize(profile, project) {
  return spawnSync(
    process.execPath,
    [
      path.join(profile, "new-project.mjs"),
      "--project-dir",
      project,
      "--app-name",
      "Focus Flow",
      "--package-name",
      "dev.claw.focusflow",
    ],
    { encoding: "utf8" },
  );
}

test("Flutter materialization preserves a canonical repository-only Project root", () => {
  const { directory, profile } = fixture();
  try {
    const project = path.join(directory, "focus-flow");
    fs.mkdirSync(path.join(project, ".git"), { recursive: true });
    fs.writeFileSync(path.join(project, ".git/HEAD"), "ref: refs/heads/main\n");

    const result = materialize(profile, project);
    assert.equal(result.status, 0, result.stderr);
    assert.equal(JSON.parse(result.stdout).projectDirectory, project);
    assert.equal(fs.readFileSync(path.join(project, ".git/HEAD"), "utf8"), "ref: refs/heads/main\n");
    assert.match(fs.readFileSync(path.join(project, "lib/main.dart"), "utf8"), /Focus Flow/);
    const metadata = JSON.parse(
      fs.readFileSync(path.join(project, ".claw-in-one/android-project.v1.json"), "utf8"),
    );
    assert.equal(metadata.applicationId, "dev.claw.focusflow");
    assert.equal(
      fs.readdirSync(directory).some((entry) => entry.includes("claw-in-one-staging") || entry.includes("claw-in-one-repository")),
      false,
    );
  } finally {
    fs.rmSync(directory, { recursive: true, force: true });
  }
});

test("Flutter materialization rejects a non-empty Project without modifying it", () => {
  const { directory, profile } = fixture();
  try {
    const project = path.join(directory, "existing");
    fs.mkdirSync(project);
    fs.writeFileSync(path.join(project, "keep.txt"), "keep\n");

    const result = materialize(profile, project);
    assert.equal(result.status, 2);
    assert.match(result.stderr, /contain only \.git/);
    assert.equal(fs.readFileSync(path.join(project, "keep.txt"), "utf8"), "keep\n");
  } finally {
    fs.rmSync(directory, { recursive: true, force: true });
  }
});
