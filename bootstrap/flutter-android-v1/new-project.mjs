#!/usr/bin/env node

import fs from "node:fs/promises";
import path from "node:path";
import process from "node:process";
import { spawnSync } from "node:child_process";
import { fileURLToPath } from "node:url";

const PROFILE_ID = "flutter-android-v1";
const PACKAGE_PATTERN = /^[a-z][a-z0-9_]*(?:\.[a-z][a-z0-9_]*){1,7}$/u;

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

function run(command, args, options = {}) {
  const result = spawnSync(command, args, {
    cwd: options.cwd,
    env: options.env,
    encoding: "utf8",
    stdio: ["ignore", "inherit", "inherit"],
  });
  if (result.error) throw result.error;
  if (result.status !== 0) throw new Error(`${path.basename(command)} failed with exit ${result.status}`);
}

function dartString(value) {
  return JSON.stringify(value).replaceAll("$", "\\$");
}

function xmlText(value) {
  return value
    .replaceAll("&", "&amp;")
    .replaceAll('"', "&quot;")
    .replaceAll("<", "&lt;")
    .replaceAll(">", "&gt;");
}

async function inspectDestination(destination) {
  const destinationStat = await fs.lstat(destination).catch(() => null);
  if (!destinationStat) return "absent";
  if (!destinationStat.isDirectory() || destinationStat.isSymbolicLink()) {
    throw new Error("project directory must be absent or a real repository directory");
  }
  const entries = await fs.readdir(destination);
  if (entries.length !== 1 || entries[0] !== ".git") {
    throw new Error("existing project directory must contain only .git");
  }
  const repositoryMarker = await fs.lstat(path.join(destination, ".git"));
  if (
    repositoryMarker.isSymbolicLink() ||
    (!repositoryMarker.isDirectory() && !repositoryMarker.isFile())
  ) {
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
  const appName = args["--app-name"].trim();
  const packageName = args["--package-name"];
  if (appName.length < 1 || [...appName].length > 48 || /[\r\n\0]/u.test(appName)) {
    throw new Error("app name must contain 1-48 printable characters");
  }
  if (!PACKAGE_PATTERN.test(packageName)) {
    throw new Error("package name must be a lowercase reverse-DNS identifier");
  }

  const profileRoot = path.dirname(fileURLToPath(import.meta.url));
  const profile = JSON.parse(await fs.readFile(path.join(profileRoot, "profile.json"), "utf8"));
  if (
    profile.schemaVersion !== 1 ||
    profile.profileId !== PROFILE_ID ||
    profile.generation !== 2 ||
    profile.status !== "ready" ||
    profile.targetPlatform !== "android-arm64"
  ) {
    throw new Error(`verified ${PROFILE_ID} profile is unavailable or stale`);
  }

  const destination = path.resolve(process.cwd(), args["--project-dir"]);
  const parent = path.dirname(destination);
  if (!(await fs.stat(parent).catch(() => null))?.isDirectory()) {
    throw new Error("project parent directory does not exist");
  }
  const destinationState = await inspectDestination(destination);
  const staging = path.join(
    parent,
    `.${path.basename(destination)}.claw-in-one-staging-${process.pid}-${Date.now()}`,
  );

  const projectName = packageName.split(".").at(-1);
  const organization = packageName.slice(0, -(projectName.length + 1));
  const flutter = path.join(profile.flutterRoot, "bin/flutter");
  const environment = {
    ...process.env,
    CI: "true",
    JAVA_HOME: profile.javaHome,
    ANDROID_HOME: profile.androidSdkRoot,
    ANDROID_SDK_ROOT: profile.androidSdkRoot,
    PUB_CACHE: profile.pubCache,
  };
  let stagingCreated = false;
  let activated = false;
  try {
    stagingCreated = true;
    run(
      flutter,
      [
        "create",
        "--empty",
        "--platforms=android",
        "--android-language=kotlin",
        "--org",
        organization,
        "--project-name",
        projectName,
        "--no-pub",
        staging,
      ],
      { env: environment },
    );
    await fs.writeFile(
      path.join(staging, "lib/main.dart"),
      `import 'package:flutter/material.dart';\n\nvoid main() => runApp(const MaterialApp(home: Scaffold(body: Center(child: Text(${dartString(appName)})))));\n`,
      "utf8",
    );
    const manifestPath = path.join(staging, "android/app/src/main/AndroidManifest.xml");
    const manifest = await fs.readFile(manifestPath, "utf8");
    await fs.writeFile(
      manifestPath,
      manifest.replace(/android:label="[^"]*"/u, `android:label="${xmlText(appName)}"`),
      "utf8",
    );
    await fs.writeFile(
      path.join(staging, "android/gradle.properties"),
      "org.gradle.jvmargs=-Xmx1536m -XX:MaxMetaspaceSize=512m -XX:ReservedCodeCacheSize=256m -Dfile.encoding=UTF-8\norg.gradle.workers.max=1\norg.gradle.parallel=false\norg.gradle.daemon=false\nkotlin.compiler.execution.strategy=in-process\nandroid.useAndroidX=true\n",
      "utf8",
    );
    run(flutter, ["pub", "get", "--offline"], { cwd: staging, env: environment });

    const metadataDirectory = path.join(staging, ".claw-in-one");
    await fs.mkdir(metadataDirectory, { mode: 0o755 });
    await fs.writeFile(
      path.join(metadataDirectory, "android-project.v1.json"),
      `${JSON.stringify({
        schemaVersion: 1,
        profileId: PROFILE_ID,
        profileGeneration: profile.generation,
        projectDirectory: destination,
        applicationId: packageName,
        appName,
        buildCommand: profile.buildCommand,
        artifact: profile.artifact,
      }, null, 2)}\n`,
      { encoding: "utf8", mode: 0o644 },
    );
    await fs.writeFile(
      path.join(staging, "DEVELOPMENT.md"),
      `# Development\n\n- Profile: \`${PROFILE_ID}\` generation ${profile.generation}\n- Project: \`${destination}\`\n- Application ID: \`${packageName}\`\n- Build: \`${profile.buildCommand}\`\n- APK: \`${profile.artifact}\`\n\nReuse this project, application ID, profile, caches, and debug signing identity for follow-up changes.\n`,
      { encoding: "utf8", mode: 0o644 },
    );
    await activateProject(staging, destination, destinationState);
    stagingCreated = false;
    activated = true;
    process.stdout.write(`${JSON.stringify({
      status: "created",
      profileId: PROFILE_ID,
      projectDirectory: destination,
      applicationId: packageName,
      buildCommand: profile.buildCommand,
      artifact: profile.artifact,
    })}\n`);
  } catch (error) {
    if (stagingCreated) await fs.rm(staging, { recursive: true, force: true });
    if (!activated && destinationState === "absent") {
      await fs.rm(destination, { recursive: true, force: true });
    }
    throw error;
  }
}

main().catch((error) => {
  process.stderr.write(`${error instanceof Error ? error.message : String(error)}\n`);
  process.exitCode = 2;
});
