import {execFileSync} from 'node:child_process';
import {APP_PACKAGE, FIXTURE_PACKAGE} from './candidate.mjs';

export const shellQuote = text => `'${text.replaceAll("'", "'\\''")}'`;

export function inputTextArguments(text, displayId = 0) {
  if (!Number.isInteger(displayId) || displayId < 0) throw new Error('An explicit input display is required');
  return ['shell', 'input', '-d', String(displayId), 'text', shellQuote(text.replaceAll(' ', '%s'))];
}

export function adbClient(binary, serial, budgetMs = 45000) {
  if (!/^[A-Za-z0-9._:-]+$/.test(serial)) throw new Error('An explicit ADB serial is required');
  const deadline = Date.now() + budgetMs;
  return (...args) => {
    const remaining = deadline - Date.now();
    if (remaining <= 0) throw new Error('Device check time budget exhausted');
    return execFileSync(binary, ['-s', serial, ...args], {
      encoding: 'utf8', timeout: Math.min(remaining, 10000), maxBuffer: 1024 * 1024,
      stdio: ['ignore', 'pipe', 'pipe'],
    }).trim();
  };
}

export function foregroundPackageOnDisplay(activities, displayId = 0) {
  const section = activities
    .split(/^Display #/m)
    .find(candidate => candidate.startsWith(`${displayId} `));
  return section?.match(/topResumedActivity=ActivityRecord\{[^\n]* u\d+ ([^/ ]+)\//)?.[1] ?? null;
}

export function checkDevice(adb, candidate, fixtureVersion) {
  if (adb('get-state') !== 'device') throw new Error('Selected phone is not authorized and online');
  for (const [packageName, file] of [[APP_PACKAGE, 'app.apk'], [FIXTURE_PACKAGE, `fixture-v${fixtureVersion}.apk`]]) {
    const paths = adb('shell', 'pm', 'path', packageName).split('\n');
    if (paths.length !== 1 || !/^package:\/data\/app\/[A-Za-z0-9_./=+~-]+\/base\.apk$/.test(paths[0])) throw new Error(`Install candidate ${file} explicitly before checking`);
    const digest = adb('shell', 'sha256sum', paths[0].slice(8)).split(/\s+/)[0];
    if (digest !== candidate.artifacts.find(a => a.file === file)?.sha256) throw new Error(`Installed ${file} differs from candidate`);
  }
  const services = adb('shell', 'settings', 'get', 'secure', 'enabled_accessibility_services').split(':');
  if (!services.some(s => s.startsWith(`${APP_PACKAGE}/`) && s.endsWith('OpenClawAccessibilityService'))) throw new Error('Enable ClawInOne Android control Accessibility service');
  return {device: 'PASS', app: 'PASS', fixture: 'PASS', accessibility: 'PASS'};
}

export function checkDebianReport(report, request, now = Date.now()) {
  if (report.schema !== 1 || report.id !== request.id || report.source !== request.source || report.done !== true) throw new Error('Missing or mismatched Debian preflight; run the printed command');
  if (!Number.isFinite(report.finishedAt) || report.finishedAt > now + 5000 || now - report.finishedAt > 300000) throw new Error('Debian preflight expired; run it again');
  for (const name of ['runtime', 'plugins', 'gateway', 'node', 'deviceBridge']) {
    if (report.checks?.[name] !== 'PASS') throw new Error(`Debian ${name} is not ready; inspect the local preflight result`);
  }
  return report.checks;
}
