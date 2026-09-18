#!/usr/bin/env node

import fs from 'node:fs';
import path from 'node:path';
import process from 'node:process';
import {pathToFileURL} from 'node:url';

const PROFILE_ID = 'web-development-v1';

function main(values) {
  if (values.length !== 2 || values[0] !== '--project-dir' || values[1].length === 0) {
    throw new Error('usage: serve-project --project-dir DIR');
  }
  const requested = path.resolve(process.cwd(), values[1]);
  const stat = fs.lstatSync(requested, {throwIfNoEntry: false});
  if (!stat?.isDirectory() || stat.isSymbolicLink()) throw new Error('project directory must be a real directory');
  const project = fs.realpathSync(requested);
  const profile = JSON.parse(fs.readFileSync(new URL('./profile.json', import.meta.url), 'utf8'));
  const metadata = JSON.parse(fs.readFileSync(path.join(project, '.claw-in-one/web-project.v1.json'), 'utf8'));
  if (
    profile.schemaVersion !== 1 || profile.profileId !== PROFILE_ID || profile.generation !== 1 || profile.status !== 'ready' ||
    metadata.schemaVersion !== 1 || metadata.profileId !== PROFILE_ID || metadata.profileGeneration !== profile.generation ||
    fs.realpathSync(metadata.projectDirectory) !== project || metadata.serverScript !== 'server.mjs' ||
    !fs.statSync(path.join(project, 'dist/index.html'), {throwIfNoEntry: false})?.isFile()
  ) throw new Error('built project was not materialized by the active Web profile');
  process.chdir(project);
  return import(pathToFileURL(path.join(project, metadata.serverScript)).href);
}

main(process.argv.slice(2)).catch(error => {
  process.stderr.write(`${error instanceof Error ? error.message : String(error)}\n`);
  process.exitCode = 2;
});
