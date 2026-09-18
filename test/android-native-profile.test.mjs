import assert from "node:assert/strict";
import { spawnSync } from "node:child_process";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { test } from "node:test";
import { fileURLToPath } from "node:url";

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");
const source = path.join(root, "bootstrap/android-native-vulkan-v1");

function fixture() {
  const directory = fs.mkdtempSync(path.join(os.tmpdir(), "claw-native-profile-"));
  const profile = path.join(directory, "profile");
  fs.cpSync(source, profile, { recursive: true });
  fs.writeFileSync(path.join(profile, "profile.json"), `${JSON.stringify({
    schemaVersion: 1,
    profileId: "android-native-vulkan-v1",
    generation: 1,
    status: "ready",
    gradleUserHome: "/opt/claw/gradle-home",
  })}\n`);
  fs.writeFileSync(path.join(profile, "template/gradlew"), "#!/bin/sh\nexit 0\n", { mode: 0o755 });
  return { directory, profile };
}

function materialize(profile, project) {
  return spawnSync(process.execPath, [
    path.join(profile, "new-project.mjs"),
    "--project-dir",
    project,
    "--app-name",
    "Vulkan Pulse",
    "--package-name",
    "dev.claw.vulkanpulse",
  ], { encoding: "utf8" });
}

test("Android Native materialization preserves the Project repository and pins ARM64 Vulkan", () => {
  const { directory, profile } = fixture();
  try {
    const project = path.join(directory, "vulkan-pulse");
    fs.mkdirSync(path.join(project, ".git"), { recursive: true });
    fs.writeFileSync(path.join(project, ".git/HEAD"), "ref: refs/heads/main\n");
    const result = materialize(profile, project);
    assert.equal(result.status, 0, result.stderr);
    assert.equal(fs.readFileSync(path.join(project, ".git/HEAD"), "utf8"), "ref: refs/heads/main\n");
    const build = fs.readFileSync(path.join(project, "app/build.gradle.kts"), "utf8");
    assert.match(build, /ndkVersion = "29\.0\.14206865"/u);
    assert.match(build, /abiFilters \+= "arm64-v8a"/u);
    const mainSource = fs.readFileSync(path.join(project, "app/src/main/cpp/main.c"), "utf8");
    assert.match(mainSource, /CLAW_NATIVE_VULKAN_READY/u);
    assert.doesNotMatch(mainSource, /app_dummy\(\)/u);
    const cmake = fs.readFileSync(path.join(project, "app/src/main/cpp/CMakeLists.txt"), "utf8");
    assert.match(cmake, /set\(NATIVE_APP_GLUE\s+\$\{ANDROID_NDK\}\/sources\/android\/native_app_glue\/android_native_app_glue\.c/u);
    assert.match(cmake, /add_library\(claw_vulkan SHARED main\.c \$\{NATIVE_APP_GLUE\}\)/u);
    assert.match(cmake, /set_source_files_properties\(\$\{NATIVE_APP_GLUE\} PROPERTIES\s+COMPILE_OPTIONS -Wno-unused-parameter/u);
    assert.doesNotMatch(cmake, /add_library\(native_app_glue STATIC/u);
    const metadata = JSON.parse(fs.readFileSync(path.join(project, ".claw-in-one/android-project.v1.json"), "utf8"));
    assert.equal(metadata.profileId, "android-native-vulkan-v1");
    assert.equal(metadata.applicationId, "dev.claw.vulkanpulse");
  } finally {
    fs.rmSync(directory, { recursive: true, force: true });
  }
});

test("Android Native materialization rejects a non-empty Project atomically", () => {
  const { directory, profile } = fixture();
  try {
    const project = path.join(directory, "occupied");
    fs.mkdirSync(project);
    fs.writeFileSync(path.join(project, "keep.txt"), "keep\n");
    const result = materialize(profile, project);
    assert.equal(result.status, 2);
    assert.match(result.stderr, /contain only \.git/u);
    assert.equal(fs.readFileSync(path.join(project, "keep.txt"), "utf8"), "keep\n");
  } finally {
    fs.rmSync(directory, { recursive: true, force: true });
  }
});
