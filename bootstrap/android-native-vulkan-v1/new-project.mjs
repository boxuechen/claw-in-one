#!/usr/bin/env node

import fs from "node:fs/promises";
import path from "node:path";
import process from "node:process";
import { fileURLToPath } from "node:url";

const PROFILE_ID = "android-native-vulkan-v1";
const PACKAGE_PATTERN = /^[a-z][a-z0-9_]*(?:\.[a-z][a-z0-9_]*){1,7}$/;
const TEXT_FILES = [
  "settings.gradle.kts",
  "app/build.gradle.kts",
  "app/src/main/AndroidManifest.xml",
];

function fail(message) {
  process.stderr.write(`${message}\n`);
  process.exitCode = 2;
}

function parseArguments(values) {
  const result = {};
  for (let index = 0; index < values.length; index += 2) {
    const name = values[index];
    const value = values[index + 1];
    if (!["--project-dir", "--app-name", "--package-name"].includes(name) || value === undefined) {
      throw new Error("usage: new-project --project-dir DIR --app-name NAME --package-name PACKAGE");
    }
    if (result[name]) throw new Error(`duplicate argument ${name}`);
    result[name] = value;
  }
  if (Object.keys(result).length !== 3) {
    throw new Error("usage: new-project --project-dir DIR --app-name NAME --package-name PACKAGE");
  }
  return result;
}

function validateAppName(value) {
  const normalized = value.trim();
  if (normalized.length < 1 || normalized.length > 48 || /[\r\n\0]/u.test(normalized)) {
    throw new Error("app name must contain 1-48 printable characters");
  }
  return normalized;
}

function xmlText(value) {
  return value.replaceAll("&", "&amp;").replaceAll('"', "&quot;").replaceAll("<", "&lt;").replaceAll(">", "&gt;");
}

async function replaceFile(file, replacements) {
  let content = await fs.readFile(file, "utf8");
  for (const [from, to] of replacements) content = content.replaceAll(from, to);
  await fs.writeFile(file, content, { encoding: "utf8", mode: 0o644 });
}

async function inspectDestination(destination) {
  const stat = await fs.lstat(destination).catch(() => null);
  if (!stat) return "absent";
  if (!stat.isDirectory() || stat.isSymbolicLink()) {
    throw new Error("project directory must be absent or a real repository directory");
  }
  const entries = await fs.readdir(destination);
  if (entries.length !== 1 || entries[0] !== ".git") {
    throw new Error("existing project directory must contain only .git");
  }
  const marker = await fs.lstat(path.join(destination, ".git"));
  if (marker.isSymbolicLink() || (!marker.isDirectory() && !marker.isFile())) {
    throw new Error("existing .git marker must be a real file or directory");
  }
  return "repository";
}

async function activateProject(staging, destination, destinationState) {
  if (destinationState === "absent") {
    await fs.rename(staging, destination);
    return;
  }
  const backup = path.join(
    path.dirname(destination),
    `.${path.basename(destination)}.claw-in-one-repository-${process.pid}-${Date.now()}`,
  );
  let backupExists = false;
  let repositoryMarkerInStaging = false;
  try {
    await fs.rename(destination, backup);
    backupExists = true;
    await fs.rename(path.join(backup, ".git"), path.join(staging, ".git"));
    repositoryMarkerInStaging = true;
    await fs.rmdir(backup);
    backupExists = false;
    await fs.rename(staging, destination);
  } catch (error) {
    if (repositoryMarkerInStaging) {
      if (!backupExists) {
        await fs.mkdir(backup);
        backupExists = true;
      }
      await fs.rename(path.join(staging, ".git"), path.join(backup, ".git"));
    }
    if (backupExists && !(await fs.lstat(destination).catch(() => null))) {
      await fs.rename(backup, destination);
    }
    throw error;
  }
}

async function main() {
  const args = parseArguments(process.argv.slice(2));
  const appName = validateAppName(args["--app-name"]);
  const packageName = args["--package-name"];
  if (!PACKAGE_PATTERN.test(packageName)) {
    throw new Error("package name must be a lowercase reverse-DNS identifier");
  }

  const profileRoot = path.dirname(fileURLToPath(import.meta.url));
  const profile = JSON.parse(await fs.readFile(path.join(profileRoot, "profile.json"), "utf8"));
  if (profile.schemaVersion !== 1 || profile.profileId !== PROFILE_ID || profile.status !== "ready" || profile.generation !== 1) {
    throw new Error(`verified ${PROFILE_ID} profile is unavailable or stale`);
  }

  const destination = path.resolve(process.cwd(), args["--project-dir"]);
  const parent = path.dirname(destination);
  if (!(await fs.stat(parent).catch(() => null))?.isDirectory()) throw new Error("project parent directory does not exist");
  const destinationState = await inspectDestination(destination);
  const staging = path.join(parent, `.${path.basename(destination)}.claw-in-one-staging-${process.pid}-${Date.now()}`);

  let stagingCreated = false;
  try {
    await fs.cp(path.join(profileRoot, "template"), staging, { recursive: true, force: false });
    stagingCreated = true;
    const replacements = [
      ["__PACKAGE_NAME__", packageName],
      ["__PROJECT_NAME__", appName.replaceAll("\\", "\\\\").replaceAll('"', '\\"').replaceAll("$", "\\$")],
      ["__APP_LABEL__", xmlText(appName)],
    ];
    for (const relative of TEXT_FILES) await replaceFile(path.join(staging, relative), replacements);
    await fs.chmod(path.join(staging, "gradlew"), 0o755);

    const metadataDirectory = path.join(staging, ".claw-in-one");
    await fs.mkdir(metadataDirectory, { mode: 0o755 });
    const buildCommand = `GRADLE_USER_HOME=${profile.gradleUserHome} ./gradlew --offline --max-workers=2 :app:assembleDebug`;
    const artifact = "app/build/outputs/apk/debug/app-debug.apk";
    await fs.writeFile(path.join(metadataDirectory, "android-project.v1.json"), `${JSON.stringify({
      schemaVersion: 1,
      profileId: PROFILE_ID,
      profileGeneration: profile.generation,
      projectDirectory: destination,
      applicationId: packageName,
      appName,
      buildCommand,
      artifact,
    }, null, 2)}\n`, { encoding: "utf8", mode: 0o644 });
    await fs.writeFile(path.join(staging, "DEVELOPMENT.md"), `# Development\n\n- Profile: \`${PROFILE_ID}\` generation ${profile.generation}\n- Project: \`${destination}\`\n- Application ID: \`${packageName}\`\n- Build: \`${buildCommand}\`\n- APK: \`${artifact}\`\n- Native target: Android ARM64 Vulkan\n\nReuse this project, application ID, profile, Gradle cache, and debug signing identity for follow-up changes.\n`, { encoding: "utf8", mode: 0o644 });
    await activateProject(staging, destination, destinationState);
    stagingCreated = false;
    process.stdout.write(`${JSON.stringify({ status: "created", profileId: PROFILE_ID, projectDirectory: destination, applicationId: packageName, buildCommand, artifact })}\n`);
  } catch (error) {
    if (stagingCreated) await fs.rm(staging, { recursive: true, force: true });
    throw error;
  }
}

main().catch((error) => fail(error instanceof Error ? error.message : String(error)));
