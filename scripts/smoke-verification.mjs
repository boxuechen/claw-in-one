import fs from 'node:fs';
import path from 'node:path';
import {fileURLToPath} from 'node:url';
import {APP_PACKAGE, FIXTURE_PACKAGE, readCandidate, sha256} from './verification/candidate.mjs';
import {adbClient, checkDevice, checkDebianReport, foregroundPackageOnDisplay, inputTextArguments} from './verification/device.mjs';
import {corePrompt} from './verification/smoke-contract.mjs';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const [operation, candidateInput, serial, runInput, ...rest] = process.argv.slice(2);
if (!['stage', 'type', 'watch', 'collect'].includes(operation) || !candidateInput || !serial || !runInput || rest.length) {
  console.error('Usage: node scripts/smoke-verification.mjs stage|type|watch|collect <candidate-directory> <adb-serial> <run-directory>');
  process.exitCode = 2;
} else {
  let step = 'candidate';
  try {
    const candidateDirectory = path.resolve(candidateInput);
    const candidate = readCandidate(root, candidateDirectory);
    const directory = path.resolve(runInput);
    const request = JSON.parse(fs.readFileSync(path.join(directory, 'request.json'), 'utf8'));
    if (!/^[0-9a-f-]{36}$/.test(request.id) || request.source !== candidate.source.digest || request.serialHash !== sha256(serial)) throw new Error('Candidate/device/run mismatch');
    const shared = `/sdcard/Download/ClawVerification-${request.id}`;
    const adb = adbClient(process.env.ADB ?? 'adb', serial, operation === 'watch' ? 250000 : 45000);
    step = 'device';
    checkDevice(adb, candidate, 1);
    if (operation === 'stage') {
      step = 'preflight';
      checkDebianReport(JSON.parse(adb('shell', 'cat', `${shared}/preflight.json`)), request);
      step = 'stage smoke';
      const smoke = {schema: 1, id: request.id, source: request.source, fixtureSha256: candidate.artifacts.find(a => a.file === 'fixture-v1.apk').sha256};
      fs.writeFileSync(path.join(directory, 'smoke-request.json'), JSON.stringify(smoke), {flag: 'wx', mode: 0o600});
      fs.writeFileSync(path.join(directory, 'prompt.txt'), corePrompt(request.id), {flag: 'wx', mode: 0o600});
      for (const name of ['debian-smoke.mjs', 'smoke-contract.mjs', 'report.mjs']) fs.copyFileSync(path.join(root, 'scripts/verification', name), path.join(directory, name), fs.constants.COPYFILE_EXCL);
      fs.copyFileSync(path.join(candidateDirectory, 'fixture-v1.apk'), path.join(directory, 'fixture-v1.apk'), fs.constants.COPYFILE_EXCL);
      for (const name of ['smoke-request.json', 'debian-smoke.mjs', 'smoke-contract.mjs', 'report.mjs', 'fixture-v1.apk']) adb('push', path.join(directory, name), `${shared}/${name}`);
      console.log(`STAGED core smoke. Start the finite Debian observer:\n~/.local/share/claw-in-one/runtimes/node-v${candidate.pinned.node}-linux-arm64/bin/node /mnt/shared/Download/ClawVerification-${request.id}/debian-smoke.mjs\nWait for READY, then send ${path.relative(root, directory)}/prompt.txt ONCE in a new native Full-access Chat. Keep Chat visible; observe the live preview. The observer never sends or approves anything.`);
    } else if (operation === 'type') {
      step = 'native input';
      const activities = adb('shell', 'dumpsys', 'activity', 'activities');
      const foreground = foregroundPackageOnDisplay(activities);
      if (foreground !== APP_PACKAGE) throw new Error('Focus an empty composer in a new native Chat first');
      const prompt = corePrompt(request.id);
      // Avoid overrunning the native composer's asynchronous state updates with
      // one long burst of input events. Bind every event to the operator display;
      // after Preview starts, Android otherwise routes unqualified input to the
      // fixture's virtual display. This types once; it never retries text.
      for (const chunk of prompt.match(/.{1,40}/g)) {
        adb(...inputTextArguments(chunk));
        await new Promise(resolve => setTimeout(resolve, 50));
      }
      console.log('Typed prompt only. Check the composer and permission choice before sending once; no Send or approval was clicked.');
    } else if (operation === 'watch') {
      // place_vscreen_workload brings the fixture to display 0 first. Return the operator to
      // the native Chat once, so its existing selected-Chat preview owner resumes.
      // This is test navigation, not Android Use execution or authority.
      step = 'watch';
      const deadline = Date.now() + 240000;
      let returned = false;
      let fixtureForegroundSince = null;
      while (Date.now() < deadline) {
        const report = JSON.parse(adb('shell', 'cat', `${shared}/smoke.json`));
        if (report.id !== request.id || report.source !== request.source) throw new Error('Observer identity changed');
        if (report.done === true) {
          console.log(`Observer finished: ${report.status}. Use collect after reviewing/closing this preview.`);
          break;
        }
        if (!returned && report.session) {
          const activities = adb('shell', 'dumpsys', 'activity', 'activities');
          const foreground = foregroundPackageOnDisplay(activities);
          if (foreground === FIXTURE_PACKAGE) {
            fixtureForegroundSince ??= Date.now();
            // App Delivery verifies the launched package after Android reports it in front.
            // Keep the fixture stable long enough for that read-only confirmation before
            // test navigation returns to Chat; otherwise the observer races place_vscreen_workload.
            if (Date.now() - fixtureForegroundSince >= 1500) {
              adb('shell', 'am', 'start', '-n', `${APP_PACKAGE}/ai.openclaw.app.MainActivity`);
              returned = true;
              console.log('Returned from the stably foreground test fixture to the native Chat once. No fixture action was injected.');
            }
          } else {
            fixtureForegroundSince = null;
          }
        }
        await new Promise(resolve => setTimeout(resolve, 1000));
      }
      if (Date.now() >= deadline) throw new Error('Watch deadline reached; reconcile the existing run, do not resend');
    } else {
      step = 'smoke result';
      const result = JSON.parse(adb('shell', 'cat', `${shared}/smoke.json`));
      if (result.id !== request.id || result.source !== request.source || result.schema !== 1 || result.done !== true) throw new Error('Observer is not finished for this candidate/run');
      fs.writeFileSync(path.join(directory, 'smoke.json'), JSON.stringify(result, null, 2), {mode: 0o600});
      if (result.status !== 'PASS' || result.fixtureCleanup !== 'PASS') {
        console.log(`${result.status === 'FAIL' ? 'FAIL' : 'BLOCKED'} smoke: inspect the private smoke.json. Do not resend an unknown operation.`);
        process.exitCode = result.status === 'FAIL' ? 1 : 3;
      } else {
        step = 'preview cleanup';
        const displays = [...adb('shell', 'dumpsys', 'activity', 'activities').matchAll(/^Display #(\d+) /gm)].map(m => Number(m[1]));
        if (!displays.includes(0) || !Number.isInteger(result.displayId) || result.displayId <= 0) throw new Error('Display cleanup cannot be established');
        if (displays.includes(result.displayId)) throw new Error('Close this smoke Chat preview, then collect again; never close unrelated displays');
        result.previewCleanup = 'PASS';
        result.collectedAt = new Date().toISOString();
        fs.writeFileSync(path.join(directory, 'smoke.json'), JSON.stringify(result, null, 2), {mode: 0o600});
        console.log(`PASS core (${result.elapsedMs}ms including native send/navigation): ${result.checks.join(', ')}; owned fixture and preview removed. Visual rendering still requires operator screenshot review.`);
      }
    }
  } catch (error) {
    console.error(`BLOCKED ${step}: ${error.status !== undefined || error.code ? 'Prerequisite command/file unavailable; inspect the selected environment.' : error.message}`);
    process.exitCode = 3;
  }
}
