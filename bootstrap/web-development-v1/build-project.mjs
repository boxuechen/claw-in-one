#!/usr/bin/env node

import {spawnSync} from 'node:child_process';
import fs from 'node:fs';
import path from 'node:path';
import process from 'node:process';
import {fileURLToPath} from 'node:url';

const PROFILE_ID = 'web-development-v1';
const EXPECTED_ARTIFACT = 'dist/index.html';

function projectDirectory(values) {
  if (values.length !== 2 || values[0] !== '--project-dir' || values[1].length === 0) {
    throw new Error('usage: build-project --project-dir DIR');
  }
  const requested = path.resolve(process.cwd(), values[1]);
  const stat = fs.lstatSync(requested, {throwIfNoEntry: false});
  if (!stat?.isDirectory() || stat.isSymbolicLink()) throw new Error('project directory must be a real directory');
  return fs.realpathSync(requested);
}

function readJson(file, label) {
  try { return JSON.parse(fs.readFileSync(file, 'utf8')); }
  catch { throw new Error(`${label} is unavailable or invalid`); }
}

function main() {
  const project = projectDirectory(process.argv.slice(2));
  const profileRoot = path.dirname(fileURLToPath(import.meta.url));
  const profile = readJson(path.join(profileRoot, 'profile.json'), 'verified Web profile');
  const metadata = readJson(path.join(project, '.claw-in-one/web-project.v1.json'), 'Web project metadata');
  if (
    profile.schemaVersion !== 1 || profile.profileId !== PROFILE_ID || profile.generation !== 1 ||
    profile.status !== 'ready' || profile.nodeVersion !== '22.22.0' || profile.cdpRequired !== false ||
    metadata.schemaVersion !== 1 || metadata.profileId !== PROFILE_ID ||
    metadata.profileGeneration !== profile.generation || fs.realpathSync(metadata.projectDirectory) !== project ||
    metadata.buildCommand !== profile.buildCommand || metadata.artifact !== EXPECTED_ARTIFACT ||
    metadata.serverScript !== 'server.mjs'
  ) throw new Error('project was not materialized by the active Web profile');

  const result = spawnSync(profile.npmBinary, ['run', 'build'], {
    cwd: project,
    env: {...process.env, PATH: `${path.dirname(profile.nodeBinary)}:${process.env.PATH ?? ''}`, npm_config_offline: 'true', CI: 'true'},
    encoding: 'utf8',
    stdio: 'inherit',
  });
  if (result.error) throw result.error;
  if (result.status !== 0) throw new Error(`Web build failed with exit ${result.status}`);
  const artifact = path.join(project, EXPECTED_ARTIFACT);
  if (!fs.statSync(artifact, {throwIfNoEntry: false})?.isFile()) throw new Error('Web build did not produce the recorded artifact');
  process.stdout.write(`${JSON.stringify({status: 'built', profileId: PROFILE_ID, projectDirectory: project, artifact})}\n`);
}

try { main(); }
catch (error) {
  process.stderr.write(`${error instanceof Error ? error.message : String(error)}\n`);
  process.exitCode = 2;
}
