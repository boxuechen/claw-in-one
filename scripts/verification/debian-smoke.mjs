// Observe one native-Chat test. Never sends prompts, chooses permissions, resolves
// approvals, or invokes Android actions on behalf of the model.
import fs from 'node:fs';
import path from 'node:path';
import os from 'node:os';
import {createHash} from 'node:crypto';
import {fileURLToPath} from 'node:url';
import {corePrompt, evaluateCore, messageText} from './smoke-contract.mjs';
import {publishReport} from './report.mjs';

const directory = path.dirname(fileURLToPath(import.meta.url));
const request = JSON.parse(fs.readFileSync(path.join(directory, 'smoke-request.json'), 'utf8'));
const root = path.join(os.homedir(), '.local/share/claw-in-one');
const started = Date.now();
const output = path.join(directory, 'smoke.json');
const report = {schema: 1, id: request.id, source: request.source, startedAt: started, done: false, status: 'NOT RUN', stage: 'prepare'};
const save = () => publishReport(output, report);
let lock;
let workspaceDirectory;
const timeout = setTimeout(() => {
  report.status = report.session ? 'FAIL' : 'BLOCKED';
  report.reason = report.session ? 'Observation deadline; task outcome/cleanup must be reconciled, never resent' : 'No matching native Chat within the deadline';
  report.done = true; report.finishedAt = Date.now(); save(); process.exit(3);
}, 240000);
try {
  if (!/^[0-9a-f-]{36}$/.test(request.id)) throw new Error('Invalid run');
  // One invocation per run, even if an earlier watcher timed out.
  lock = fs.openSync(path.join(directory, 'smoke.started'), 'wx', 0o600);
  save();
  const runtime = path.join(root, 'current/lib/node_modules/openclaw/dist/plugin-sdk');
  const {loadConfig, resolveDefaultAgentId} = await import(path.join(runtime, 'config-runtime.js'));
  const {resolveAgentWorkspaceDir} = await import(path.join(runtime, 'agent-runtime.js'));
  const config = loadConfig();
  const agentId = resolveDefaultAgentId(config);
  const workspace = resolveAgentWorkspaceDir(config, agentId);
  const base = path.join(workspace, '.claw-verification');
  fs.mkdirSync(base, {recursive: true, mode: 0o700});
  if (!fs.lstatSync(base).isDirectory() || fs.lstatSync(base).isSymbolicLink()) throw new Error('Unsafe fixture directory');
  workspaceDirectory = path.join(base, request.id);
  fs.mkdirSync(workspaceDirectory, {mode: 0o700});
  const bytes = fs.readFileSync(path.join(directory, 'fixture-v1.apk'));
  if (createHash('sha256').update(bytes).digest('hex') !== request.fixtureSha256) throw new Error('Fixture digest differs');
  fs.writeFileSync(path.join(workspaceDirectory, 'fixture-v1.apk'), bytes, {flag: 'wx', mode: 0o600});
  const {callGatewayFromCli} = await import(path.join(runtime, 'gateway-runtime.js'));
  const token = fs.readFileSync(path.join(root, 'gateway/gateway.token'), 'utf8').trim();
  const call = (method, params) => callGatewayFromCli(method, {token, timeout: '8000', json: true}, params, {progress: false, sharedStateMode: 'read-only'});
  const list = () => call('sessions.list', {agentId, limit: 100, includeUnknown: false});
  report.stage = 'ready'; save();
  console.log('READY: send prompt.txt once from a new native Full-access Chat. Keep that Chat visible and observe the Preview.');
  let selected;
  while (Date.now() - started < 235000) {
    const rows = (await list()).sessions;
    if (!selected) {
      for (const row of rows.filter(s => s.key.startsWith(`agent:${agentId}:claw-in-one:`)).slice(0,5)) {
        const history = await call('chat.history', {agentId, sessionKey: row.key, limit: 100});
        if (history.messages?.some(m => m.role === 'user' && messageText(m).includes(request.id))) {
          if (selected) throw new Error('Ambiguous smoke Chat');
          selected = {key: row.key, sessionId: row.sessionId, agentId};
        }
      }
    }
    if (selected) {
      report.session = selected;
      report.matchedAt ??= Date.now();
      const session = rows.find(row => row.key === selected.key);
      if (session?.sessionId !== selected.sessionId) throw new Error('Smoke Session was replaced or absent');
      const history = await call('chat.history', {agentId, sessionKey: selected.key, limit: 100});
      const result = evaluateCore(history.messages ?? [], request, session);
      if (result.status === 'NOT RUN' && result.reason === 'Waiting for the exact native Chat prompt') {
        result.status = 'FAIL'; result.reason = 'Native prompt differs from prompt.txt; inspect input, do not resend';
      }
      // A failed assertion does not mean the Agent stopped. Keep observing the
      // same Session, without sending another message or canceling its work.
      const runIds = [...new Set((history.messages ?? []).filter(m => m.role === 'assistant').map(m => m.__openclaw?.runId).filter(Boolean))];
      report.runSettled = runIds.length > 0 && session.hasActiveRun === false && session.lastRunId === runIds.at(-1);
      if (runIds.length > 0) report.runId = runIds.at(-1);
      if (result.status === 'FAIL') report.failure ??= result.reason;
      if (result.status === 'PASS') delete report.reason;
      Object.assign(report, result, {stage: 'observing', phase: result.phase ?? 'control'});
      if (report.failure) { report.status = 'FAIL'; report.reason = report.failure; }
      save();
      if (report.runSettled && ['PASS', 'FAIL'].includes(report.status)) break;
    }
    await new Promise(resolve => setTimeout(resolve, 2000));
  }
  if (report.status !== 'PASS') {
    report.status = report.session ? 'FAIL' : 'BLOCKED';
    report.reason ??= 'No completed matching workflow within deadline';
    process.exitCode = 3;
  }
} catch {
  // Do not copy SDK/config errors to a shared report.
  report.status = report.session ? 'FAIL' : 'BLOCKED';
  report.reason = 'Observer could not continue; inspect the dedicated Terminal, do not resend';
  process.exitCode = 3;
} finally {
  clearTimeout(timeout);
  if (lock !== undefined) {
    fs.closeSync(lock);
    // Only remove our immutable fixture after confirmed completion; retain it on unknown work.
    if (report.status === 'PASS' && workspaceDirectory) {
      try {
        const fixture = path.join(workspaceDirectory, 'fixture-v1.apk');
        if (!fs.lstatSync(fixture).isFile() || createHash('sha256').update(fs.readFileSync(fixture)).digest('hex') !== request.fixtureSha256) throw new Error('Fixture changed');
        fs.unlinkSync(fixture);
        fs.rmdirSync(workspaceDirectory);
        report.fixtureCleanup = 'PASS';
      } catch { report.fixtureCleanup = 'FAIL'; }
    }
    report.done = true; report.finishedAt = Date.now(); report.elapsedMs = Date.now() - started;
    if (report.matchedAt) report.workflowMs = report.finishedAt - report.matchedAt;
    save();
    console.log(`${report.status}: ${report.reason ?? 'Tool workflow completed; close preview before host collection.'}`);
  }
}
