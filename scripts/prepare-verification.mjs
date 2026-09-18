import fs from 'node:fs';
import path from 'node:path';
import {spawnSync} from 'node:child_process';
import {fileURLToPath} from 'node:url';
import {APP_OUTPUT, APP_PACKAGE, FIXTURE_OUTPUT, FIXTURE_PACKAGE, buildArtifact, fileHash, fingerprintFiles, sourceFiles, pinnedVersions} from './verification/candidate.mjs';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
if (process.argv.length !== 2) {
  console.error('Usage: node scripts/prepare-verification.mjs\nBuilds local candidate APKs; never installs or changes device state. Requires the normal Android build environment.');
  process.exitCode = 2;
} else {
  const base = path.join(root, '.verification');
  fs.mkdirSync(base, {recursive: true, mode: 0o700});
  const lock = path.join(base, 'prepare.lock');
  let ownsLock = false;
  let log;
  try {
    fs.writeFileSync(lock, JSON.stringify({pid: process.pid, startedAt: new Date().toISOString()}), {flag: 'wx', mode: 0o600});
    ownsLock = true;
    const directory = fs.mkdtempSync(path.join(base, 'candidate-'));
    log = fs.openSync(path.join(directory, 'build.log'), 'wx', 0o600);
    const candidate = {schema: 1, createdAt: new Date().toISOString(), pinned: pinnedVersions(root), source: fingerprintFiles(root, sourceFiles(root)), artifacts: [], timingMs: {}};
    const build = (name, args) => {
      const start = Date.now();
      console.log(`Building ${name}…`);
      const result = spawnSync('./gradlew', args, {cwd: path.join(root, 'apps/android'), stdio: ['ignore', log, log]});
      candidate.timingMs[name] = Date.now() - start;
      if (result.error || result.status !== 0) throw new Error(`Build ${name} failed; inspect ${path.relative(root, directory)}/build.log`);
    };
    const capture = (source, file, packageName, versionCode) => {
      const entry = buildArtifact(path.join(root, source), packageName, versionCode);
      fs.copyFileSync(entry.file, path.join(directory, file), fs.constants.COPYFILE_EXCL);
      candidate.artifacts.push({file, packageName, versionCode: entry.versionCode, versionName: entry.versionName, sha256: fileHash(path.join(directory, file))});
    };
    build('app', [':app:assembleThirdPartyDebug']);
    capture(APP_OUTPUT, 'app.apk', APP_PACKAGE, null);
    for (const version of [1, 2]) {
      build(`fixture-v${version}`, [':android-use-fixture:assembleDebug', `-PfixtureVersion=${version}`]);
      capture(FIXTURE_OUTPUT, `fixture-v${version}.apk`, FIXTURE_PACKAGE, version);
    }
    if (fingerprintFiles(root, sourceFiles(root)).digest !== candidate.source.digest) throw new Error('Sources changed during preparation; candidate not published');
    fs.writeFileSync(path.join(directory, 'candidate.json'), JSON.stringify(candidate, null, 2), {flag: 'wx', mode: 0o600});
    console.log(`PASS preparation: ${path.relative(root, directory)}\n${JSON.stringify(candidate.timingMs)}\nNo device installation performed.`);
  } catch (error) {
    console.error(error.code === 'EEXIST' && !ownsLock ? 'Preparation is locked; inspect the recorded process before removing a stale lock.' : error.message);
    process.exitCode = 1;
  } finally {
    if (log !== undefined) fs.closeSync(log);
    if (ownsLock) fs.unlinkSync(lock);
  }
}
