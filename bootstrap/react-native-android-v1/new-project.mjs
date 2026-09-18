#!/usr/bin/env node

import {spawnSync} from 'node:child_process';
import fs from 'node:fs/promises';
import path from 'node:path';
import process from 'node:process';
import {fileURLToPath} from 'node:url';

const PROFILE_ID = 'react-native-android-v1';
const PACKAGE_PATTERN = /^[a-z][a-z0-9_]*(?:\.[a-z][a-z0-9_]*){1,7}$/u;

function parseArguments(values) {
  const result = {};
  for (let index = 0; index < values.length; index += 2) {
    const name = values[index];
    const value = values[index + 1];
    if (!['--project-dir', '--app-name', '--package-name'].includes(name) || value === undefined) {
      throw new Error('usage: new-project --project-dir DIR --app-name NAME --package-name PACKAGE');
    }
    if (result[name]) throw new Error(`duplicate argument ${name}`);
    result[name] = value;
  }
  if (Object.keys(result).length !== 3) {
    throw new Error('usage: new-project --project-dir DIR --app-name NAME --package-name PACKAGE');
  }
  return result;
}

function run(command, args, options = {}) {
  const result = spawnSync(command, args, {
    cwd: options.cwd,
    env: options.env,
    encoding: 'utf8',
    stdio: ['ignore', 'inherit', 'inherit'],
  });
  if (result.error) throw result.error;
  if (result.status !== 0) throw new Error(`${path.basename(command)} failed with exit ${result.status}`);
}

function xmlText(value) {
  return value
    .replaceAll('&', '&amp;')
    .replaceAll('"', '&quot;')
    .replaceAll('<', '&lt;')
    .replaceAll('>', '&gt;');
}

async function replaceFile(file, replacements) {
  let content = await fs.readFile(file, 'utf8');
  for (const [from, to] of replacements) content = content.replaceAll(from, to);
  await fs.writeFile(file, content, {encoding: 'utf8', mode: 0o644});
}

async function inspectDestination(destination) {
  const stat = await fs.lstat(destination).catch(() => null);
  if (!stat) return 'absent';
  if (!stat.isDirectory() || stat.isSymbolicLink()) {
    throw new Error('project directory must be absent or a real repository directory');
  }
  const entries = await fs.readdir(destination);
  if (entries.length !== 1 || entries[0] !== '.git') {
    throw new Error('existing project directory must contain only .git');
  }
  const marker = await fs.lstat(path.join(destination, '.git'));
  if (marker.isSymbolicLink() || (!marker.isDirectory() && !marker.isFile())) {
    throw new Error('existing .git marker must be a real file or directory');
  }
  return 'repository';
}

async function activateProject(staging, destination, destinationState) {
  if (destinationState === 'absent') {
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
    await fs.rename(path.join(backup, '.git'), path.join(staging, '.git'));
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
      await fs.rename(path.join(staging, '.git'), path.join(backup, '.git'));
    }
    if (backupExists && !(await fs.lstat(destination).catch(() => null))) {
      await fs.rename(backup, destination);
    }
    throw error;
  }
}

async function main() {
  const args = parseArguments(process.argv.slice(2));
  const appName = args['--app-name'].trim();
  const packageName = args['--package-name'];
  if (appName.length < 1 || [...appName].length > 48 || /[\r\n\0]/u.test(appName)) {
    throw new Error('app name must contain 1-48 printable characters');
  }
  if (!PACKAGE_PATTERN.test(packageName)) {
    throw new Error('package name must be a lowercase reverse-DNS identifier');
  }

  const profileRoot = path.dirname(fileURLToPath(import.meta.url));
  const profile = JSON.parse(await fs.readFile(path.join(profileRoot, 'profile.json'), 'utf8'));
  if (
    profile.schemaVersion !== 1 ||
    profile.profileId !== PROFILE_ID ||
    profile.generation !== 1 ||
    profile.status !== 'ready' ||
    profile.targetPlatform !== 'android-arm64' ||
    profile.reactNativeVersion !== '0.87.1' ||
    profile.nodeVersion !== '22.22.0' ||
    typeof profile.builder !== 'string' ||
    profile.builder.length === 0 ||
    typeof profile.buildCommand !== 'string' ||
    profile.buildCommand.length === 0 ||
    profile.newArchitecture !== true ||
    profile.hermes !== true ||
    profile.metroRequired !== false
  ) {
    throw new Error(`verified ${PROFILE_ID} profile is unavailable or stale`);
  }

  const destination = path.resolve(process.cwd(), args['--project-dir']);
  const parent = path.dirname(destination);
  if (!(await fs.stat(parent).catch(() => null))?.isDirectory()) {
    throw new Error('project parent directory does not exist');
  }
  const destinationState = await inspectDestination(destination);
  const staging = path.join(
    parent,
    `.${path.basename(destination)}.claw-in-one-staging-${process.pid}-${Date.now()}`,
  );
  const nodeBin = path.dirname(profile.nodeBinary);
  const environment = {
    ...process.env,
    CI: 'true',
    JAVA_HOME: profile.javaHome,
    ANDROID_HOME: profile.androidSdkRoot,
    ANDROID_SDK_ROOT: profile.androidSdkRoot,
    GRADLE_USER_HOME: profile.gradleUserHome,
    npm_config_cache: profile.npmCache,
    npm_config_audit: 'false',
    npm_config_fund: 'false',
    PATH: `${nodeBin}:${process.env.PATH ?? ''}`,
  };

  let stagingCreated = false;
  try {
    await fs.cp(path.join(profileRoot, 'template'), staging, {recursive: true, force: false});
    stagingCreated = true;
    const packagePath = packageName.replaceAll('.', '/');
    const sourceRoot = path.join(staging, 'android/app/src/main/java/starter');
    const targetRoot = path.join(staging, 'android/app/src/main/java', packagePath);
    await fs.mkdir(path.dirname(targetRoot), {recursive: true});
    await fs.rename(sourceRoot, targetRoot);
    const replacements = [
      ['__PACKAGE_NAME__', packageName],
      ['__DEVELOPER_NODE__', profile.nodeBinary],
    ];
    for (const file of [
      'android/build.gradle',
      'android/app/build.gradle',
      `android/app/src/main/java/${packagePath}/MainActivity.kt`,
      `android/app/src/main/java/${packagePath}/MainApplication.kt`,
    ]) {
      await replaceFile(path.join(staging, file), replacements);
    }
    await replaceFile(path.join(staging, 'App.tsx'), [
      ['__APP_NAME_JSON__', JSON.stringify(appName)],
    ]);
    await replaceFile(path.join(staging, 'app.json'), [
      ['__APP_NAME_JSON__', JSON.stringify(appName)],
    ]);
    await replaceFile(path.join(staging, 'android/app/src/main/res/values/strings.xml'), [
      ['__APP_NAME_XML__', xmlText(appName)],
    ]);

    run(profile.npmBinary, ['ci', '--offline', '--ignore-scripts', '--no-audit', '--no-fund'], {
      cwd: staging,
      env: environment,
    });

    const metadataDirectory = path.join(staging, '.claw-in-one');
    await fs.mkdir(metadataDirectory, {mode: 0o755});
    await fs.writeFile(
      path.join(metadataDirectory, 'android-project.v1.json'),
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
      {encoding: 'utf8', mode: 0o644},
    );
    await fs.writeFile(
      path.join(staging, 'DEVELOPMENT.md'),
      `# Development\n\n- Profile: \`${PROFILE_ID}\` generation ${profile.generation}\n- Project: \`${destination}\`\n- Application ID: \`${packageName}\`\n- React Native: \`${profile.reactNativeVersion}\` with New Architecture and Hermes\n- Node: \`${profile.nodeVersion}\` from the Developer Node component\n- Build: \`${profile.buildCommand}\`\n- APK: \`${profile.artifact}\`\n- Target: standalone Android ARM64; Metro is not required\n\nReuse this Project, application ID, profile, dependencies, caches, and debug signing identity for follow-up changes. Do not run \`npx init\`, upgrade the framework, or replace the qualified dependency closure.\n`,
      {encoding: 'utf8', mode: 0o644},
    );
    await activateProject(staging, destination, destinationState);
    stagingCreated = false;
    process.stdout.write(`${JSON.stringify({
      status: 'created',
      profileId: PROFILE_ID,
      projectDirectory: destination,
      applicationId: packageName,
      buildCommand: profile.buildCommand,
      artifact: profile.artifact,
    })}\n`);
  } catch (error) {
    if (stagingCreated) await fs.rm(staging, {recursive: true, force: true});
    throw error;
  }
}

main().catch(error => {
  process.stderr.write(`${error instanceof Error ? error.message : String(error)}\n`);
  process.exitCode = 2;
});
