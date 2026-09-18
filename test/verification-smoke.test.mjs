import assert from 'node:assert/strict';
import test from 'node:test';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import {publishReport} from '../scripts/verification/report.mjs';
import {corePrompt, evaluateCore, FIXTURE, COUNTER} from '../scripts/verification/smoke-contract.mjs';

function reportFile(t) {
  const directory = fs.mkdtempSync(path.join(os.tmpdir(), 'claw-report-'));
  t.after(() => fs.rmSync(directory, {recursive: true, force: true}));
  return path.join(directory, 'smoke.json');
}

test('report publication exposes only whole snapshots and private files', t => {
  const output = reportFile(t);
  const first = {id: 'owned', done: false, stage: 'ready'};
  const final = {id: 'owned', done: true, status: 'PASS'};
  publishReport(output, first);
  const rename = fs.renameSync;
  let publications = 0;
  t.mock.method(fs, 'renameSync', (temporary, target) => {
    assert.equal(target, output);
    assert.equal(path.dirname(temporary), path.dirname(output));
    assert.deepEqual(JSON.parse(fs.readFileSync(output, 'utf8')), first);
    assert.deepEqual(JSON.parse(fs.readFileSync(temporary, 'utf8')), final);
    assert.equal(fs.statSync(temporary).mode & 0o777, 0o600);
    publications++;
    rename(temporary, target);
  });
  publishReport(output, final);
  assert.equal(publications, 1);
  assert.deepEqual(JSON.parse(fs.readFileSync(output, 'utf8')), final);
  assert.deepEqual(fs.readdirSync(path.dirname(output)), ['smoke.json']);
});

test('failed publication retains the previous report without a false terminal result', t => {
  const output = reportFile(t);
  const prior = {id: 'owned', done: false};
  publishReport(output, prior);
  t.mock.method(fs, 'renameSync', () => { throw new Error('publication failed'); });
  assert.throws(() => publishReport(output, {done: true, status: 'PASS'}), /publication failed/);
  assert.deepEqual(JSON.parse(fs.readFileSync(output, 'utf8')), prior);
  assert.deepEqual(fs.readdirSync(path.dirname(output)), ['smoke.json']);
});

test('invalid report serialization does not alter the published snapshot', t => {
  const output = reportFile(t);
  publishReport(output, {id: 'owned', done: false});
  const circular = {}; circular.self = circular;
  assert.throws(() => publishReport(output, circular), TypeError);
  assert.deepEqual(JSON.parse(fs.readFileSync(output, 'utf8')), {id: 'owned', done: false});
  assert.deepEqual(fs.readdirSync(path.dirname(output)), ['smoke.json']);
});

function transcript() {
  const request = {id: 'owned-run', fixtureSha256: 'apk-hash'};
  const messages = [{role: 'user', content: [{type: 'text', text: corePrompt(request.id)}]}];
  const add = (name, args, data) => {
    const id = `tool-${messages.length}`;
    messages.push({role: 'assistant', __openclaw: {runId: 'gateway-run'}, content: [{type: 'toolCall', id, name, arguments: args}]});
    messages.push({role: 'toolResult', __openclaw: {runId: 'gateway-run'}, toolCallId: id, toolName: name, content: [{type: 'text', text: JSON.stringify(data)}]});
  };
  const artifact = {artifactId: 'receipt', packageName: FIXTURE, sha256: request.fixtureSha256};
  add('android_app', {operation: 'inspect_apk'}, {status: 'inspected', artifact});
  add('android_app', {operation: 'place_vscreen_workload', artifactId: 'receipt'}, {status: 'vscreen_workload_requested', packageName: FIXTURE, artifact});
  const common = {targetPackage: FIXTURE, controlId: 'control'};
  const observation = count => ({...common, package: FIXTURE, snapshotId: `snapshot-${count}`, display: {kind: 'vscreen', id: 7}, nodes: [{viewId: COUNTER, ref: 'n1', text: `SMOKE COUNT: ${count}`}]});
  add('android_use', {operation: 'observe', targetPackage: FIXTURE}, observation(0));
  add('android_use', {...common, operation: 'activate', snapshotId: 'snapshot-0', ref: 'n1'}, {...common, code: 'completed'});
  add('android_use', {...common, operation: 'observe'}, observation(1));
  add('android_use', {...common, operation: 'stop'}, {status: 'stopped'});
  messages.push({role: 'assistant', __openclaw: {runId: 'gateway-run'}, stopReason: 'stop', content: [{type: 'text', text: 'Done'}]});
  return {request, messages, session: {permissionMode: 'full', hasActiveRun: false, lastRunId: 'gateway-run'}};
}

test('only actual correlated inspection, launch, VScreen action/verify, and stop pass', () => {
  const f = transcript();
  const result = evaluateCore(f.messages, f.request, f.session);
  assert.equal(result.status, 'PASS');
  assert.equal(result.displayId, 7);
  assert.equal(result.toolCalls, 6);
  assert.equal(evaluateCore(f.messages, f.request, {...f.session, hasActiveRun: true}).status, 'NOT RUN');
});

test('model success text and stale or wrong candidate evidence never pass', () => {
  const f = transcript();
  assert.equal(evaluateCore([f.messages[0], f.messages.at(-1)], f.request, f.session).status, 'FAIL');
  assert.equal(evaluateCore(f.messages, {...f.request, fixtureSha256: 'other'}, f.session).status, 'FAIL');
  assert.equal(evaluateCore(f.messages, {...f.request, id: 'other'}, f.session).status, 'NOT RUN');
  assert.equal(evaluateCore(f.messages, f.request, {...f.session, permissionMode: 'guarded'}).status, 'FAIL');
});

test('Android action acceptance requires a subsequent semantic state change', () => {
  const f = transcript();
  const action = JSON.parse(f.messages[8].content[0].text);
  action.code = 'accepted_but_unverified';
  f.messages[8].content[0].text = JSON.stringify(action);
  assert.equal(evaluateCore(f.messages, f.request, f.session).status, 'PASS');
  f.messages.splice(9, 2);
  assert.equal(evaluateCore(f.messages, f.request, f.session).status, 'FAIL');
});

test('run attribution, Tool identity, and errors cannot be ignored', () => {
  for (const change of [m => { delete m[2].__openclaw; }, m => { m[2].toolCallId = 'other'; }, m => { m[8].isError = true; }, m => { m[5].content[0].name = 'exec'; }]) {
    const f = transcript(); change(f.messages);
    assert.equal(evaluateCore(f.messages, f.request, f.session).status, 'FAIL');
  }
});

test('main-display fallback, wrong semantic ref, and missing stop cannot pass', () => {
  for (const change of [
    m => { const data = JSON.parse(m[6].content[0].text); data.display.kind = 'main'; m[6].content[0].text = JSON.stringify(data); },
    m => { m[7].content[0].arguments.ref = 'other'; },
    m => { m.splice(11, 2); },
  ]) {
    const f = transcript(); change(f.messages);
    assert.equal(evaluateCore(f.messages, f.request, f.session).status, 'FAIL');
  }
});

test('launch alone cannot satisfy the single-run smoke', () => {
  const f = transcript();
  const result = evaluateCore(f.messages.slice(0, 5), f.request, {...f.session, hasActiveRun: true});
  assert.equal(result.status, 'NOT RUN');
  assert.equal(evaluateCore(f.messages.slice(0, 5), f.request, f.session).status, 'NOT RUN');
});

test('unexpected retries, extra actions, and cross-run tool results fail', () => {
  for (const [change, expected] of [
    [m => { m[7].content[0].arguments.operation = 'tap'; }, 'FAIL'],
    [m => { m[6].__openclaw.runId = 'other-run'; }, 'FAIL'],
    [m => { m.splice(5, 0, {role: 'user', content: 'repeat'}); }, 'FAIL'],
    [m => { m[13].stopReason = 'toolUse'; }, 'NOT RUN'],
  ]) {
    const f = transcript(); change(f.messages);
    assert.equal(evaluateCore(f.messages, f.request, f.session).status, expected);
  }
});
