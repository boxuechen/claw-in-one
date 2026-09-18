import assert from 'node:assert/strict';
import test from 'node:test';
import {APP_PACKAGE, FIXTURE_PACKAGE} from '../scripts/verification/candidate.mjs';
import {adbClient, checkDevice, checkDebianReport, foregroundPackageOnDisplay, inputTextArguments, shellQuote} from '../scripts/verification/device.mjs';
import {execFileSync} from 'node:child_process';

const candidate = {artifacts: [{file: 'app.apk', sha256: 'a'}, {file: 'fixture-v1.apk', sha256: 'b'}]};

test('ADB text is one shell argument, including quotes and command delimiters', () => {
  const text = "a;b ' quote $(exit 9) & literal";
  assert.equal(execFileSync('/bin/sh', ['-c', `printf %s ${shellQuote(text)}`], {encoding: 'utf8'}), text);
});

test('operator foreground is resolved from display 0, not a preview display', () => {
  const activities = `Display #59 (activities from top to bottom):
    topResumedActivity=ActivityRecord{a u0 ${FIXTURE_PACKAGE}/.FixtureFormActivity t1}
Display #0 (activities from top to bottom):
    topResumedActivity=ActivityRecord{b u0 ${APP_PACKAGE}/ai.openclaw.app.MainActivity t2}`;
  assert.equal(foregroundPackageOnDisplay(activities), APP_PACKAGE);
  assert.equal(foregroundPackageOnDisplay(activities, 59), FIXTURE_PACKAGE);
  assert.equal(foregroundPackageOnDisplay(activities, 7), null);
});

test('native text input is always bound to an explicit display', () => {
  assert.deepEqual(
    inputTextArguments('hello world'),
    ['shell', 'input', '-d', '0', 'text', "'hello%sworld'"],
  );
  assert.deepEqual(inputTextArguments('preview', 59).slice(0, 5), ['shell', 'input', '-d', '59', 'text']);
  assert.throws(() => inputTextArguments('unsafe', -1), /explicit input display/);
});
function phone(overrides = {}) {
  const answers = {
    'get-state': 'device',
    [`shell pm path ${APP_PACKAGE}`]: 'package:/data/app/~~a==/a/base.apk',
    [`shell pm path ${FIXTURE_PACKAGE}`]: 'package:/data/app/b/base.apk',
    'shell sha256sum /data/app/~~a==/a/base.apk': 'a  /data/app/~~a==/a/base.apk',
    'shell sha256sum /data/app/b/base.apk': 'b  /data/app/b/base.apk',
    'shell settings get secure enabled_accessibility_services': `${APP_PACKAGE}/ai.openclaw.app.OpenClawAccessibilityService`,
    ...overrides,
  };
  const calls = [];
  const adb = (...args) => {
    const command = args.join(' ');
    calls.push(command);
    assert.ok(Object.hasOwn(answers, command), `Unexpected command ${command}`);
    return answers[command];
  };
  return {adb, calls};
}

test('device preflight checks exact candidate bytes and Accessibility without mutations', () => {
  const f = phone();
  assert.equal(checkDevice(f.adb, candidate, 1).accessibility, 'PASS');
  assert.equal(f.calls.length, 6);
  assert.ok(f.calls.every(c => !/install|push|force-stop|settings put/.test(c)));
});

test('offline, stale package, and missing permission fail early without retries', () => {
  const offline = phone({'get-state': 'offline'});
  assert.throws(() => checkDevice(offline.adb, candidate, 1), /online/);
  assert.equal(offline.calls.length, 1);
  const stale = phone({'shell sha256sum /data/app/~~a==/a/base.apk': 'old'});
  assert.throws(() => checkDevice(stale.adb, candidate, 1), /differs/);
  assert.equal(stale.calls.length, 3);
  const denied = phone({'shell settings get secure enabled_accessibility_services': 'null'});
  assert.throws(() => checkDevice(denied.adb, candidate, 1), /Accessibility/);
});

test('Debian preflight requires fresh, complete evidence for the same candidate and run', () => {
  const request = {id: 'run', source: 'hash'};
  const report = {schema: 1, ...request, done: true, finishedAt: 1000, checks: Object.fromEntries(['runtime', 'plugins', 'gateway', 'node', 'deviceBridge'].map(k => [k, 'PASS']))};
  assert.equal(checkDebianReport(report, request, 2000).node, 'PASS');
  assert.throws(() => checkDebianReport({...report, source: 'old'}, request, 2000), /mismatched/);
  assert.throws(() => checkDebianReport({...report, done: false}, request, 2000), /mismatched/);
  assert.throws(() => checkDebianReport(report, request, 400000), /expired/);
  assert.throws(() => checkDebianReport({...report, checks: {...report.checks, plugins: 'BLOCKED'}}, request, 2000), /plugins/);
});

test('explicit serial and elapsed budget are enforced before any subprocess', () => {
  assert.throws(() => adbClient('never-run', ''), /explicit/);
  assert.throws(() => adbClient('never-run', 'serial; bad'), /explicit/);
  assert.throws(() => adbClient('never-run', 'serial', 0)('get-state'), /budget exhausted/);
});
