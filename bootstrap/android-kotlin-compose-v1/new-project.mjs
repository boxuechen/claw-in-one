#!/usr/bin/env node

import fs from "node:fs/promises";
import path from "node:path";
import process from "node:process";
import { fileURLToPath } from "node:url";

const PROFILE_ID = "android-kotlin-compose-v1";
const PACKAGE_PATTERN = /^[a-z][a-z0-9_]*(?:\.[a-z][a-z0-9_]*){1,7}$/;
const KOTLIN_KEYWORDS = new Set([
  "as", "break", "class", "continue", "do", "else", "false", "for", "fun", "if", "in",
  "interface", "is", "null", "object", "package", "return", "super", "this", "throw", "true",
  "try", "typealias", "typeof", "val", "var", "when", "while",
]);
const TEXT_FILES = [
  "settings.gradle.kts",
  "app/build.gradle.kts",
  "app/src/main/AndroidManifest.xml",
  "app/src/main/java/starter/MainActivity.kt",
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

function kotlinString(value) {
  return value.replaceAll("\\", "\\\\").replaceAll('"', '\\"').replaceAll("$", "\\$");
}

function xmlText(value) {
  return value
    .replaceAll("&", "&amp;")
    .replaceAll('"', "&quot;")
    .replaceAll("<", "&lt;")
    .replaceAll(">", "&gt;");
}

async function replaceFile(file, replacements) {
  let content = await fs.readFile(file, "utf8");
  for (const [from, to] of replacements) content = content.replaceAll(from, to);
  await fs.writeFile(file, content, { encoding: "utf8", mode: 0o644 });
}

async function main() {
  const args = parseArguments(process.argv.slice(2));
  const appName = validateAppName(args["--app-name"]);
  const packageName = args["--package-name"];
  if (!PACKAGE_PATTERN.test(packageName) || packageName.split(".").some((part) => KOTLIN_KEYWORDS.has(part))) {
    throw new Error("package name must be a lowercase reverse-DNS identifier without Kotlin keywords");
  }

  const profileRoot = path.dirname(fileURLToPath(import.meta.url));
  const profile = JSON.parse(await fs.readFile(path.join(profileRoot, "profile.json"), "utf8"));
  if (profile.schemaVersion !== 1 || profile.profileId !== PROFILE_ID || profile.status !== "ready" || profile.generation !== 1) {
    throw new Error(`verified ${PROFILE_ID} profile is unavailable or stale`);
  }

  const destination = path.resolve(process.cwd(), args["--project-dir"]);
  const parent = path.dirname(destination);
  const stat = await fs.stat(parent).catch(() => null);
  if (!stat?.isDirectory()) throw new Error("project parent directory does not exist");
  if (await fs.lstat(destination).catch(() => null)) throw new Error("project directory already exists");

  let created = false;
  try {
    await fs.mkdir(destination, { mode: 0o755 });
    created = true;
    await fs.cp(path.join(profileRoot, "template"), destination, { recursive: true, force: false });

    const packagePath = packageName.replaceAll(".", "/");
    const sourceRoot = path.join(destination, "app/src/main/java");
    const placeholderSource = path.join(sourceRoot, "starter");
    const actualSource = path.join(sourceRoot, packagePath);
    await fs.mkdir(path.dirname(actualSource), { recursive: true });
    await fs.rename(placeholderSource, actualSource);

    const replacements = [
      ["__PACKAGE_NAME__", packageName],
      ["__PROJECT_NAME__", kotlinString(appName)],
      ["__APP_LABEL__", xmlText(appName)],
    ];
    for (const relative of TEXT_FILES) {
      const resolved = relative.replace("starter", packagePath);
      await replaceFile(path.join(destination, resolved), replacements);
    }
    await fs.chmod(path.join(destination, "gradlew"), 0o755);

    const metadataDirectory = path.join(destination, ".claw-in-one");
    await fs.mkdir(metadataDirectory, { mode: 0o755 });
    const buildCommand = `GRADLE_USER_HOME=${profile.gradleUserHome} ./gradlew --offline --max-workers=2 :app:assembleDebug`;
    const artifact = "app/build/outputs/apk/debug/app-debug.apk";
    await fs.writeFile(
      path.join(metadataDirectory, "android-project.v1.json"),
      `${JSON.stringify({
        schemaVersion: 1,
        profileId: PROFILE_ID,
        profileGeneration: profile.generation,
        projectDirectory: destination,
        applicationId: packageName,
        appName,
        buildCommand,
        artifact,
      }, null, 2)}\n`,
      { encoding: "utf8", mode: 0o644 },
    );
    await fs.writeFile(
      path.join(destination, "DEVELOPMENT.md"),
      `# Development\n\n- Profile: \`${PROFILE_ID}\` generation ${profile.generation}\n- Project: \`${destination}\`\n- Application ID: \`${packageName}\`\n- Build: \`${buildCommand}\`\n- APK: \`${artifact}\`\n\nReuse this project, application ID, profile, Gradle cache, and debug signing identity for follow-up changes.\n`,
      { encoding: "utf8", mode: 0o644 },
    );

    process.stdout.write(`${JSON.stringify({ status: "created", profileId: PROFILE_ID, projectDirectory: destination, applicationId: packageName, buildCommand, artifact })}\n`);
  } catch (error) {
    if (created) await fs.rm(destination, { recursive: true, force: true });
    throw error;
  }
}

main().catch((error) => fail(error instanceof Error ? error.message : String(error)));
