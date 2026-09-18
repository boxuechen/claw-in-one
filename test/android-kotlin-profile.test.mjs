import assert from "node:assert/strict";
import { spawnSync } from "node:child_process";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { test } from "node:test";
import { fileURLToPath } from "node:url";

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");
const source = path.join(root, "bootstrap/android-kotlin-compose-v1");

function fixture() {
  const directory = fs.mkdtempSync(path.join(os.tmpdir(), "claw-kotlin-profile-"));
  const profile = path.join(directory, "profile");
  fs.cpSync(source, profile, { recursive: true });
  fs.writeFileSync(path.join(profile, "profile.json"), `${JSON.stringify({
    schemaVersion: 1,
    profileId: "android-kotlin-compose-v1",
    generation: 1,
    status: "ready",
    gradleUserHome: "/opt/claw/gradle-home",
  })}\n`);
  fs.writeFileSync(path.join(profile, "template/gradlew"), "#!/bin/sh\nexit 0\n", { mode: 0o755 });
  return { directory, profile };
}

function materialize(profile, project, appName = "Tiny Memo", packageName = "dev.claw.tinymemo") {
  return spawnSync(
    process.execPath,
    [
      path.join(profile, "new-project.mjs"),
      "--project-dir",
      project,
      "--app-name",
      appName,
      "--package-name",
      packageName,
    ],
    { encoding: "utf8" },
  );
}

test("the verified profile materializes one deterministic Kotlin Compose project", () => {
  const { directory, profile } = fixture();
  try {
    const project = path.join(directory, "tiny-memo");
    const result = materialize(profile, project);
    assert.equal(result.status, 0, result.stderr);
    const output = JSON.parse(result.stdout);
    assert.equal(output.status, "created");
    assert.equal(output.profileId, "android-kotlin-compose-v1");
    assert.equal(output.projectDirectory, project);
    assert.equal(output.applicationId, "dev.claw.tinymemo");
    assert.match(output.buildCommand, /--offline --max-workers=2 :app:assembleDebug$/);
    assert.equal(fs.statSync(path.join(project, "gradlew")).mode & 0o777, 0o755);
    assert.match(fs.readFileSync(path.join(project, "settings.gradle.kts"), "utf8"), /Tiny Memo/);
    assert.match(
      fs.readFileSync(path.join(project, "app/src/main/java/dev/claw/tinymemo/MainActivity.kt"), "utf8"),
      /package dev\.claw\.tinymemo/,
    );
    assert.match(fs.readFileSync(path.join(project, "DEVELOPMENT.md"), "utf8"), /android-kotlin-compose-v1/);
    const metadata = JSON.parse(fs.readFileSync(path.join(project, ".claw-in-one/android-project.v1.json"), "utf8"));
    assert.equal(metadata.profileGeneration, 1);
    assert.equal(metadata.artifact, "app/build/outputs/apk/debug/app-debug.apk");
    assert.equal(JSON.stringify(metadata).includes("__PACKAGE"), false);
  } finally {
    fs.rmSync(directory, { recursive: true, force: true });
  }
});

test("the materializer refuses replacement projects and invalid application IDs", () => {
  const { directory, profile } = fixture();
  try {
    const project = path.join(directory, "existing");
    fs.mkdirSync(project);
    const existing = materialize(profile, project);
    assert.equal(existing.status, 2);
    assert.match(existing.stderr, /already exists/);

    const invalidProject = path.join(directory, "invalid");
    const invalid = materialize(profile, invalidProject, "Bad", "Bad Package");
    assert.equal(invalid.status, 2);
    assert.match(invalid.stderr, /reverse-DNS/);
    assert.equal(fs.existsSync(invalidProject), false);
  } finally {
    fs.rmSync(directory, { recursive: true, force: true });
  }
});
