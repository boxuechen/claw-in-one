#!/usr/bin/env node

import {spawnSync} from 'node:child_process';
import fs from 'node:fs/promises';
import path from 'node:path';
import process from 'node:process';
import {fileURLToPath} from 'node:url';

const PROFILE_ID = 'web-development-v1';

function parseArguments(values) {
  const result = {};
  for (let index = 0; index < values.length; index += 2) {
    const name = values[index];
    const value = values[index + 1];
    if (!['--project-dir', '--app-name'].includes(name) || value === undefined) {
      throw new Error('usage: new-project --project-dir DIR --app-name NAME');
    }
    if (result[name]) throw new Error(`duplicate argument ${name}`);
    result[name] = value;
  }
  if (Object.keys(result).length !== 2) throw new Error('usage: new-project --project-dir DIR --app-name NAME');
  return result;
}

async function replaceFile(file, replacements) {
  let content = await fs.readFile(file, 'utf8');
  for (const [from, to] of replacements) content = content.replaceAll(from, to);
  await fs.writeFile(file, content, {encoding: 'utf8', mode: 0o644});
}

async function inspectDestination(destination) {
  const stat = await fs.lstat(destination).catch(() => null);
  if (!stat) return 'absent';
  if (!stat.isDirectory() || stat.isSymbolicLink()) throw new Error('project directory must be absent or a real repository directory');
  const entries = await fs.readdir(destination);
  if (entries.length !== 1 || entries[0] !== '.git') throw new Error('existing project directory must contain only .git');
  const marker = await fs.lstat(path.join(destination, '.git'));
  if (marker.isSymbolicLink() || (!marker.isDirectory() && !marker.isFile())) throw new Error('existing .git marker must be real');
  return 'repository';
}

async function activateProject(staging, destination, state) {
  if (state === 'absent') return await fs.rename(staging, destination);
  const backup = path.join(path.dirname(destination), `.${path.basename(destination)}.claw-in-one-repository-${process.pid}-${Date.now()}`);
  let backedUp = false;
  let markerMoved = false;
  try {
    await fs.rename(destination, backup); backedUp = true;
    await fs.rename(path.join(backup, '.git'), path.join(staging, '.git')); markerMoved = true;
    await fs.rmdir(backup); backedUp = false;
    await fs.rename(staging, destination);
  } catch (error) {
    if (markerMoved) {
      if (!backedUp) { await fs.mkdir(backup); backedUp = true; }
      await fs.rename(path.join(staging, '.git'), path.join(backup, '.git'));
    }
    if (backedUp && !(await fs.lstat(destination).catch(() => null))) await fs.rename(backup, destination);
    throw error;
  }
}

async function main() {
  const args = parseArguments(process.argv.slice(2));
  const appName = args['--app-name'].trim();
  if (appName.length < 1 || [...appName].length > 48 || /[\r\n\0]/u.test(appName)) {
    throw new Error('app name must contain 1-48 printable characters');
  }
  const profileRoot = path.dirname(fileURLToPath(import.meta.url));
  const profile = JSON.parse(await fs.readFile(path.join(profileRoot, 'profile.json'), 'utf8'));
  if (
    profile.schemaVersion !== 1 || profile.profileId !== PROFILE_ID || profile.generation !== 1 ||
    profile.status !== 'ready' || profile.nodeVersion !== '22.22.0' || profile.cdpRequired !== false
  ) throw new Error(`verified ${PROFILE_ID} profile is unavailable or stale`);

  const destination = path.resolve(process.cwd(), args['--project-dir']);
  const parent = path.dirname(destination);
  if (!(await fs.stat(parent).catch(() => null))?.isDirectory()) throw new Error('project parent directory does not exist');
  const destinationState = await inspectDestination(destination);
  const staging = path.join(parent, `.${path.basename(destination)}.claw-in-one-staging-${process.pid}-${Date.now()}`);
  let stagingCreated = false;
  try {
    await fs.cp(path.join(profileRoot, 'template'), staging, {recursive: true, force: false});
    stagingCreated = true;
    await replaceFile(path.join(staging, 'index.html'), [['__APP_NAME_HTML__', appName.replaceAll('&', '&amp;').replaceAll('<', '&lt;')]]);
    await replaceFile(path.join(staging, 'src/main.tsx'), [['__APP_NAME_JSON__', JSON.stringify(appName)]]);
    const environment = {
      ...process.env,
      PATH: `${path.dirname(profile.nodeBinary)}:${process.env.PATH ?? ''}`,
      npm_config_cache: profile.npmCache,
      npm_config_audit: 'false',
      npm_config_fund: 'false',
      CI: 'true',
    };
    const install = spawnSync(profile.npmBinary, ['ci', '--offline', '--ignore-scripts', '--no-audit', '--no-fund'], {
      cwd: staging, env: environment, encoding: 'utf8', stdio: 'inherit',
    });
    if (install.error) throw install.error;
    if (install.status !== 0) throw new Error(`Web dependency materialization failed with exit ${install.status}`);
    const metadataDirectory = path.join(staging, '.claw-in-one');
    await fs.mkdir(metadataDirectory, {mode: 0o755});
    await fs.writeFile(path.join(metadataDirectory, 'web-project.v1.json'), `${JSON.stringify({
      schemaVersion: 1,
      profileId: PROFILE_ID,
      profileGeneration: profile.generation,
      projectDirectory: destination,
      appName,
      buildCommand: profile.buildCommand,
      artifact: 'dist/index.html',
      serverScript: 'server.mjs',
    }, null, 2)}\n`, {encoding: 'utf8', mode: 0o644});
    await fs.writeFile(path.join(staging, 'DEVELOPMENT.md'), `# Development\n\n- Profile: \`${PROFILE_ID}\` generation ${profile.generation}\n- Project: \`${destination}\`\n- Stack: React ${profile.reactVersion}, TypeScript ${profile.typescriptVersion}, Vite ${profile.viteVersion}\n- Backend: Node built-in HTTP server\n- Build: \`${profile.buildCommand}\`\n- Result: production assets served on loopback\n\nReuse this Project, profile, dependency lock and server entrypoint for follow-up changes. Do not replace the framework closure, add a second server, expose a public listener or introduce CDP.\n`, {encoding: 'utf8', mode: 0o644});
    await activateProject(staging, destination, destinationState);
    stagingCreated = false;
    process.stdout.write(`${JSON.stringify({status: 'created', profileId: PROFILE_ID, projectDirectory: destination, buildCommand: profile.buildCommand})}\n`);
  } catch (error) {
    if (stagingCreated) await fs.rm(staging, {recursive: true, force: true});
    throw error;
  }
}

main().catch(error => {
  process.stderr.write(`${error instanceof Error ? error.message : String(error)}\n`);
  process.exitCode = 2;
});
