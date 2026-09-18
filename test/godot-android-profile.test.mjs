import assert from "node:assert/strict";
import { spawnSync } from "node:child_process";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { test } from "node:test";
import { fileURLToPath } from "node:url";

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");
const source = path.join(root, "bootstrap/godot-android-v1");

function fixture() {
  const directory = fs.mkdtempSync(path.join(os.tmpdir(), "claw-godot-profile-"));
  const profile = path.join(directory, "profile");
  fs.cpSync(source, profile, { recursive: true });
  fs.writeFileSync(path.join(profile, "profile.json"), `${JSON.stringify({
    schemaVersion: 1,
    profileId: "godot-android-v1",
    generation: 1,
    status: "ready",
    targetPlatform: "android-arm64",
    godotVersion: "4.7.2",
    androidDebugTemplate: "/opt/claw/godot-templates/4.7.2/android_debug.apk",
    buildCommand: "env CLAW_GODOT=qualified /opt/claw/godot --headless --path . --export-debug Android build/app-debug.apk",
    artifact: "build/app-debug.apk",
  })}\n`);
  return { directory, profile };
}

function materialize(profile, project, appName = "Orbit Studio", packageName = "dev.claw.orbitstudio") {
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

test("Godot materialization preserves the Project repository and pins headless ARM64 export", () => {
  const { directory, profile } = fixture();
  try {
    const project = path.join(directory, "orbit-studio");
    fs.mkdirSync(path.join(project, ".git"), { recursive: true });
    fs.writeFileSync(path.join(project, ".git/HEAD"), "ref: refs/heads/main\n");

    const result = materialize(profile, project);
    assert.equal(result.status, 0, result.stderr);
    assert.equal(fs.readFileSync(path.join(project, ".git/HEAD"), "utf8"), "ref: refs/heads/main\n");
    assert.match(fs.readFileSync(path.join(project, "project.godot"), "utf8"), /config\/name="Orbit Studio"/u);
    assert.match(fs.readFileSync(path.join(project, "main.gd"), "utf8"), /InputEventScreenTouch/u);
    const preset = fs.readFileSync(path.join(project, "export_presets.cfg"), "utf8");
    assert.match(preset, /architectures\/arm64-v8a=true/u);
    assert.match(preset, /architectures\/x86_64=false/u);
    assert.match(preset, /package\/unique_name="dev\.claw\.orbitstudio"/u);
    assert.match(preset, /custom_template\/debug="\/opt\/claw\/godot-templates\/4\.7\.2\/android_debug\.apk"/u);
    const metadata = JSON.parse(fs.readFileSync(path.join(project, ".claw-in-one/android-project.v1.json"), "utf8"));
    assert.equal(metadata.profileId, "godot-android-v1");
    assert.equal(metadata.applicationId, "dev.claw.orbitstudio");
    assert.equal(metadata.artifact, "build/app-debug.apk");
    assert.match(metadata.buildCommand, /--headless/u);
    assert.equal(JSON.stringify(metadata).includes("__"), false);
  } finally {
    fs.rmSync(directory, { recursive: true, force: true });
  }
});

test("Godot materialization rejects invalid input without modifying an occupied Project", () => {
  const { directory, profile } = fixture();
  try {
    const project = path.join(directory, "occupied");
    fs.mkdirSync(project);
    fs.writeFileSync(path.join(project, "keep.txt"), "keep\n");
    const occupied = materialize(profile, project);
    assert.equal(occupied.status, 2);
    assert.match(occupied.stderr, /contain only \.git/u);
    assert.equal(fs.readFileSync(path.join(project, "keep.txt"), "utf8"), "keep\n");

    const invalid = materialize(profile, path.join(directory, "invalid"), "Bad", "Bad Package");
    assert.equal(invalid.status, 2);
    assert.match(invalid.stderr, /reverse-DNS/u);
    assert.equal(fs.existsSync(path.join(directory, "invalid")), false);
  } finally {
    fs.rmSync(directory, { recursive: true, force: true });
  }
});
