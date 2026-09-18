import assert from 'node:assert/strict';
import {test} from 'node:test';
import {spawnSync} from 'node:child_process';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import {fileURLToPath} from 'node:url';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const invoke = (args, env = process.env) => spawnSync('bash', [path.join(root, 'scripts/check.sh'), ...args], {cwd: os.tmpdir(), env, encoding: 'utf8', timeout: 15000});
test('default plan retains all original checks without executing them', () => {
  const r = invoke(['--list']);
  assert.equal(r.status, 0, r.stderr);
  for (const text of ['android-kotlin-profile.test.mjs', 'flutter-profile.test.mjs', 'godot-android-profile.test.mjs', 'web-development-profile.test.mjs', 'web-development-plugin.test.mjs', 'android-device-reverse-port.test.mjs', 'WebProjectResultsTest', 'cargo fmt', 'cargo clippy', 'cargo test', 'aarch64-unknown-linux-musl', 'openclaw-release-contract.test.mjs', 'bootstrap-contract.sh', 'terminal-mobile.mjs', 'project-workspaces-plugin.mjs', 'android-use-plugin.mjs', 'android-developer-bridge-plugin.mjs', ':app:ktlintCheck', ':app:testThirdPartyDebugUnitTest', ':app:lintThirdPartyDebug', ':app:assembleThirdPartyDebug', ':android-use-fixture:assembleDebug']) assert.ok(r.stdout.includes(text), text);
  assert.match(r.stdout, /NOT RUN/);
  assert.doesNotMatch(r.stdout, /^PASS/m);
});
test('combined scopes deduplicate shared publication checks', () => {
  const r = invoke(['--scope', 'android-use', '--scope', 'supervisor', '--scope', 'android-use', '--list']);
  assert.equal(r.status, 0);
  assert.equal(r.stdout.match(/bootstrap-contract.sh/g)?.length, 1);
  assert.equal(r.stdout.match(/android-use-plugin.mjs/g)?.length, 1);
  assert.equal(r.stdout.match(/project-workspaces-plugin.mjs/g)?.length, 1);
  assert.doesNotMatch(r.stdout, /gradlew|terminal-mobile/);
});
test('UI/docs scope does not silently invoke unrelated Rust or AI work', () => {
  for (const scope of ['android', 'docs']) {
    const r = invoke(['--scope', scope, '--list']);
    assert.equal(r.status, 0);
    assert.doesNotMatch(r.stdout, /cargo|android-use-plugin|bootstrap-contract|chat.send/);
  }
});
test('invalid options fail before dispatch', () => {
  for (const args of [['--scope'], ['--scope', 'unknown'], ['--other'], ['android']]) {
    const r = invoke(args);
    assert.equal(r.status, 2);
    assert.doesNotMatch(r.stdout, /CHECK|PASS/);
  }
});
test('failed command stops later checks and records NOT RUN, without retry', () => {
  const directory = fs.mkdtempSync(path.join(os.tmpdir(), 'claw-check-'));
  try {
    const log = path.join(directory, 'calls');
    fs.writeFileSync(path.join(directory, 'cargo'), '#!/bin/sh\nprintf "called\\n" >> "$CHECK_TEST_LOG"\nexit 7\n', {mode: 0o700});
    const r = invoke(['--scope', 'supervisor', '--scope', 'android'], {...process.env, PATH: `${directory}:${process.env.PATH}`, CHECK_TEST_LOG: log});
    assert.equal(r.status, 7, r.stderr);
    assert.match(r.stdout, /FAIL supervisor/);
    assert.match(r.stdout, /NOT RUN bootstrap/);
    assert.match(r.stdout, /NOT RUN android/);
    assert.doesNotMatch(r.stdout, /^PASS/m);
    assert.equal(fs.readFileSync(log, 'utf8'), 'called\n');
  } finally { fs.rmSync(directory, {recursive: true, force: true}); }
});
