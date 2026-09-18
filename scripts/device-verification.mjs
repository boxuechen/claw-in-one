import fs from 'node:fs';
import path from 'node:path';
import {randomUUID} from 'node:crypto';
import {fileURLToPath} from 'node:url';
import {readCandidate, sha256} from './verification/candidate.mjs';
import {adbClient, checkDevice, checkDebianReport} from './verification/device.mjs';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const [operation, candidateInput, serial, ...rest] = process.argv.slice(2);
const usage = 'Usage: node scripts/device-verification.mjs stage|check <candidate-directory> <adb-serial> [run-directory (check only)]';
if (!['stage', 'check'].includes(operation) || !candidateInput || !serial || rest.length !== (operation === 'check' ? 1 : 0)) {
  console.error(usage);
  process.exitCode = 2;
} else {
  let step = 'candidate';
  const started = Date.now();
  try {
    const candidateDirectory = path.resolve(candidateInput);
    const candidate = readCandidate(root, candidateDirectory);
    const adb = adbClient(process.env.ADB ?? 'adb', serial);
    step = 'device';
    if (operation === 'stage') {
      if (adb('get-state') !== 'device') throw new Error('Selected device unavailable');
      const id = randomUUID();
      const directory = path.join(root, '.verification', `run-${id}`);
      fs.mkdirSync(directory, {mode: 0o700});
      const plugins = Object.fromEntries(Object.entries(candidate.source.files).filter(([f]) => f.startsWith('product-plugins/')).map(([f, hash]) => [f.slice('product-plugins/'.length), hash]));
      const request = {schema: 1, id, serialHash: sha256(serial), source: candidate.source.digest, pinned: candidate.pinned, plugins};
      fs.writeFileSync(path.join(directory, 'request.json'), JSON.stringify(request), {flag: 'wx', mode: 0o600});
      fs.copyFileSync(path.join(root, 'scripts/verification/debian-preflight.mjs'), path.join(directory, 'debian-preflight.mjs'));
      const shared = `/sdcard/Download/ClawVerification-${id}`;
      adb('push', directory, shared);
      console.log(`STAGED ${path.relative(root, directory)}\nNo APK, runtime, settings, or grants changed.\nIn a dedicated Debian Terminal tab, run:\n~/.local/share/claw-in-one/runtimes/node-v${candidate.pinned.node}-linux-arm64/bin/node /mnt/shared/Download/ClawVerification-${id}/debian-preflight.mjs`);
    } else {
      const directory = path.resolve(rest[0]);
      const request = JSON.parse(fs.readFileSync(path.join(directory, 'request.json'), 'utf8'));
      if (!/^[0-9a-f-]{36}$/.test(request.id) || request.source !== candidate.source.digest || request.serialHash !== sha256(serial)) throw new Error('Run does not match this candidate/device');
      const checks = checkDevice(adb, candidate, 1);
      step = 'Debian result';
      const report = JSON.parse(adb('shell', 'cat', `/sdcard/Download/ClawVerification-${request.id}/preflight.json`));
      Object.assign(checks, checkDebianReport(report, request));
      const evidence = {schema: 1, id: request.id, source: candidate.source.digest, checkedAt: new Date().toISOString(), elapsedMs: Date.now() - started, checks};
      fs.writeFileSync(path.join(directory, 'preflight.json'), JSON.stringify(evidence, null, 2), {mode: 0o600});
      console.log(`PASS preflight (${evidence.elapsedMs}ms). Provider and real-model behavior are NOT RUN.`);
    }
  } catch (error) {
    // Child process diagnostics can include private runtime output. Do not echo them.
    console.error(`BLOCKED ${step} (${Date.now() - started}ms): ${error.status !== undefined || error.code ? 'Command/file unavailable; check the selected device and prerequisite.' : error.message}`);
    process.exitCode = 3;
  }
}
