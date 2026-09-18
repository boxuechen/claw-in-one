#!/usr/bin/env node

import {spawnSync} from 'node:child_process';
import fs from 'node:fs';
import path from 'node:path';
import process from 'node:process';
import {fileURLToPath} from 'node:url';

const PROFILE_ID = 'react-native-android-v1';
const EXPECTED_ARTIFACT = 'android/app/build/outputs/apk/debug/app-debug.apk';

function parseArguments(values) {
  if (values.length !== 2 || values[0] !== '--project-dir' || values[1].length === 0) {
    throw new Error('usage: build-project --project-dir DIR');
  }
  return path.resolve(process.cwd(), values[1]);
}

function readJson(file, label) {
  try {
    return JSON.parse(fs.readFileSync(file, 'utf8'));
  } catch {
    throw new Error(`${label} is unavailable or invalid`);
  }
}

function requireRealDirectory(directory) {
  const metadata = fs.lstatSync(directory, {throwIfNoEntry: false});
  if (!metadata?.isDirectory() || metadata.isSymbolicLink()) {
    throw new Error('project directory must be a real directory');
  }
}

function run(command, args, options) {
  const result = spawnSync(command, args, {
    cwd: options.cwd,
    env: options.env,
    encoding: 'utf8',
    stdio: 'inherit',
  });
  if (result.error) throw result.error;
  if (result.status !== 0) throw new Error(`React Native Android build failed with exit ${result.status}`);
}

function main() {
  const requestedProjectDirectory = parseArguments(process.argv.slice(2));
  requireRealDirectory(requestedProjectDirectory);
  const projectDirectory = fs.realpathSync(requestedProjectDirectory);

  const profileRoot = path.dirname(fileURLToPath(import.meta.url));
  const profile = readJson(path.join(profileRoot, 'profile.json'), 'verified React Native profile');
  const project = readJson(
    path.join(projectDirectory, '.claw-in-one/android-project.v1.json'),
    'React Native project metadata',
  );
  if (
    profile.schemaVersion !== 1 ||
    profile.profileId !== PROFILE_ID ||
    profile.generation !== 1 ||
    profile.status !== 'ready' ||
    profile.targetPlatform !== 'android-arm64' ||
    profile.reactNativeVersion !== '0.87.1' ||
    profile.nodeVersion !== '22.22.0' ||
    profile.newArchitecture !== true ||
    profile.hermes !== true ||
    profile.metroRequired !== false
  ) {
    throw new Error(`verified ${PROFILE_ID} profile is unavailable or stale`);
  }
  if (
    project.schemaVersion !== 1 ||
    project.profileId !== PROFILE_ID ||
    project.profileGeneration !== profile.generation ||
    fs.realpathSync(project.projectDirectory) !== projectDirectory ||
    project.buildCommand !== profile.buildCommand ||
    project.artifact !== EXPECTED_ARTIFACT
  ) {
    throw new Error('project was not materialized by the active React Native profile');
  }

  const wrapper = path.join(projectDirectory, 'android/gradlew');
  if (!fs.statSync(wrapper, {throwIfNoEntry: false})?.isFile()) {
    throw new Error('verified Gradle wrapper is unavailable');
  }
  const nodeBin = path.dirname(profile.nodeBinary);
  run(
    wrapper,
    [
      '--project-dir',
      path.join(projectDirectory, 'android'),
      '--offline',
      '--max-workers=1',
      ':app:assembleDebug',
    ],
    {
      cwd: projectDirectory,
      env: {
        ...process.env,
        CI: 'true',
        JAVA_HOME: profile.javaHome,
        ANDROID_HOME: profile.androidSdkRoot,
        ANDROID_SDK_ROOT: profile.androidSdkRoot,
        GRADLE_USER_HOME: profile.gradleUserHome,
        PATH: `${nodeBin}:${process.env.PATH ?? ''}`,
      },
    },
  );

  const artifact = path.join(projectDirectory, EXPECTED_ARTIFACT);
  if (!fs.statSync(artifact, {throwIfNoEntry: false})?.isFile()) {
    throw new Error('React Native Android build did not produce the recorded APK');
  }
  process.stdout.write(`${JSON.stringify({
    status: 'built',
    profileId: PROFILE_ID,
    projectDirectory,
    artifact,
    targetPlatform: 'android-arm64',
    metroRequired: false,
  })}\n`);
}

try {
  main();
} catch (error) {
  process.stderr.write(`${error instanceof Error ? error.message : String(error)}\n`);
  process.exitCode = 2;
}
