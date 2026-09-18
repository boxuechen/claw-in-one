import fs from 'node:fs';
import path from 'node:path';
import {createHash} from 'node:crypto';
import {execFileSync} from 'node:child_process';

export const APP_PACKAGE = 'io.github.boxuechen.clawinone.debug';
export const FIXTURE_PACKAGE = 'io.github.boxuechen.clawinone.fixture';
export const APP_OUTPUT = 'apps/android/app/build/outputs/apk/thirdParty/debug';
export const FIXTURE_OUTPUT = 'apps/android/android-use-fixture/build/outputs/apk/debug';
export const sha256 = bytes => createHash('sha256').update(bytes).digest('hex');
export const fileHash = file => sha256(fs.readFileSync(file));
const roots = ['apps/android', 'apps/shared', 'bootstrap', 'product-plugins', 'supervisor'];

export function buildArtifact(directory, packageName, versionCode) {
  const metadata = JSON.parse(fs.readFileSync(path.join(directory, 'output-metadata.json'), 'utf8'));
  const entry = metadata.elements?.[0];
  if (metadata.applicationId !== packageName || metadata.elements?.length !== 1 ||
      !entry || entry.type !== 'SINGLE' || path.basename(entry.outputFile) !== entry.outputFile ||
      !entry.outputFile.endsWith('.apk') || !Number.isInteger(entry.versionCode) ||
      (versionCode !== null && entry.versionCode !== versionCode)) throw new Error('Unexpected build metadata');
  const file = path.join(directory, entry.outputFile);
  if (!fs.lstatSync(file).isFile()) throw new Error('Expected a regular APK');
  return {file, versionCode: entry.versionCode, versionName: entry.versionName};
}

export function sourceFiles(root) {
  return [...new Set(execFileSync('git', ['ls-files', '-z', '--cached', '--others', '--exclude-standard', '--', ...roots], {cwd: root, encoding: 'utf8'}).split('\0'))]
    .filter(name => name && !name.endsWith('.md')).sort();
}
export function fingerprintFiles(root, files) {
  const hashes = {};
  for (const file of files) {
    if (path.isAbsolute(file) || file.split('/').includes('..')) throw new Error('Invalid candidate source path');
    const target = path.join(root, file);
    if (!fs.lstatSync(target).isFile()) throw new Error(`Candidate requires a regular source file: ${file}`);
    hashes[file] = fileHash(target);
  }
  return {digest: sha256(JSON.stringify(hashes)), files: hashes};
}
export function pinnedVersions(root) {
  const text = fs.readFileSync(path.join(root, 'apps/android/app/src/main/java/ai/openclaw/app/bootstrap/OpenClawRelease.kt'), 'utf8');
  const runtime = text.match(/\bversion = "([0-9.]+)"/)?.[1];
  const node = text.match(/\bnodeVersion = "([0-9.]+)"/)?.[1];
  if (!runtime || !node) throw new Error('Cannot read pinned release');
  return {runtime, node};
}
export function readCandidate(root, directory) {
  const candidate = JSON.parse(fs.readFileSync(path.join(directory, 'candidate.json'), 'utf8'));
  if (candidate.schema !== 1 || !candidate.source?.digest || !Array.isArray(candidate.artifacts)) throw new Error('Invalid verification candidate');
  const current = fingerprintFiles(root, sourceFiles(root));
  if (current.digest !== candidate.source.digest || sha256(JSON.stringify(candidate.source.files)) !== current.digest) throw new Error('Candidate source changed; prepare again');
  const expected = [['app.apk', APP_PACKAGE, null], ['fixture-v1.apk', FIXTURE_PACKAGE, 1], ['fixture-v2.apk', FIXTURE_PACKAGE, 2]];
  if (candidate.artifacts.length !== expected.length) throw new Error('Incomplete candidate');
  for (const [name, packageName, versionCode] of expected) {
    const a = candidate.artifacts.find(row => row.file === name);
    if (!a || a.packageName !== packageName || (versionCode !== null && a.versionCode !== versionCode) || !/^[a-f0-9]{64}$/.test(a.sha256 ?? '')) throw new Error('Unexpected candidate artifact');
    const target = path.join(directory, name);
    if (!fs.lstatSync(target).isFile() || fileHash(target) !== a.sha256) throw new Error(`Candidate artifact changed: ${name}`);
  }
  const pins = pinnedVersions(root);
  if (pins.runtime !== candidate.pinned?.runtime || pins.node !== candidate.pinned?.node) throw new Error('Candidate runtime pin changed');
  return candidate;
}
