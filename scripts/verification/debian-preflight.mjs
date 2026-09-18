// Standalone, finite Debian probe. Never exports configuration, credentials, or raw RPC output.
import fs from 'node:fs';
import path from 'node:path';
import os from 'node:os';
import {createHash} from 'node:crypto';
import {execFileSync} from 'node:child_process';
import {fileURLToPath} from 'node:url';

const directory = path.dirname(fileURLToPath(import.meta.url));
const request = JSON.parse(fs.readFileSync(path.join(directory, 'request.json'), 'utf8'));
const root = path.join(os.homedir(), '.local/share/claw-in-one');
const report = {schema: 1, id: request.id, source: request.source, checks: {}, done: false};
const output = path.join(directory, 'preflight.json');
const save = () => fs.writeFileSync(output, JSON.stringify(report), {mode: 0o600});
let step = 'runtime';
const finish = () => { report.done = true; report.finishedAt = Date.now(); save(); };
const deadline = setTimeout(() => {
  report.checks[step] = 'BLOCKED'; finish(); process.exit(3);
}, 40000);
try {
  if (request.schema !== 1 || !/^[0-9a-f-]{36}$/.test(request.id)) throw new Error('Invalid request');
  const runtime = path.join(root, 'current/lib/node_modules/openclaw');
  if (JSON.parse(fs.readFileSync(path.join(runtime, 'package.json'), 'utf8')).version !== request.pinned.runtime || process.versions.node !== request.pinned.node) throw new Error('Runtime pin differs');
  report.checks.runtime = 'PASS';
  const {callGatewayFromCli} = await import(path.join(runtime, 'dist/plugin-sdk/gateway-runtime.js'));
  const token = fs.readFileSync(path.join(root, 'gateway/gateway.token'), 'utf8').trim();
  const call = (method, params) => callGatewayFromCli(method, {token, timeout: '8000', json: true}, params, {progress: false, sharedStateMode: 'read-only'});
  step = 'gateway';
  await call('health', {});
  report.checks.gateway = 'PASS';
  step = 'plugins';
  // Disk fingerprints alone do not prove that the running process loaded those bytes.
  const pid = fs.readFileSync(path.join(root, 'gateway/gateway.pid'), 'utf8').trim();
  if (!/^[1-9][0-9]*$/.test(pid)) throw new Error('Gateway process identity missing');
  const processStat = fs.readFileSync(`/proc/${pid}/stat`, 'utf8');
  const fields = processStat.slice(processStat.lastIndexOf(')') + 2).split(' ');
  const bootSeconds = Number(fs.readFileSync('/proc/stat', 'utf8').match(/^btime (\d+)$/m)?.[1]);
  const ticks = Number(execFileSync('getconf', ['CLK_TCK'], {encoding: 'utf8', timeout: 2000}));
  const startedMs = (bootSeconds + Number(fields[19]) / ticks) * 1000;
  if (!Number.isFinite(startedMs) || startedMs <= 0) throw new Error('Gateway start time unavailable');
  const {loadConfig} = await import(path.join(runtime, 'dist/plugin-sdk/config-runtime.js'));
  const config = loadConfig();
  for (const plugin of ['android-use', 'vscreen-foundation', 'android-developer-bridge', 'web-development', 'project-workspaces']) {
    const id = `claw-in-one-${plugin}`;
    if ((config.plugins?.load?.paths ?? []).some(p => path.basename(p) === plugin) || config.plugins?.entries?.[id]?.enabled !== true || config.plugins?.deny?.includes(id)) throw new Error('Product Plugin not enabled uniquely');
    const inspected = await call('plugins.inspect', {pluginId: id});
    if (inspected?.plugin?.origin !== 'bundled' || inspected.plugin.status !== 'loaded') throw new Error('Product Plugin is not active host-bundled code');
    const expected = Object.entries(request.plugins).filter(([f]) => f.startsWith(`${plugin}/`));
    if (expected.length === 0) throw new Error('Missing Plugin inventory');
    for (const [file, digest] of expected) {
      const relative = file.slice(plugin.length + 1);
      if (path.isAbsolute(relative) || relative.split('/').includes('..')) throw new Error('Invalid Plugin file');
      const deployed = path.join(runtime, 'dist/extensions', plugin, relative);
      if (fs.statSync(deployed).mtimeMs > startedMs) throw new Error('Restart Gateway explicitly after Plugin deployment');
      const actual = createHash('sha256').update(fs.readFileSync(deployed)).digest('hex');
      if (actual !== digest) throw new Error('Deployed Plugin differs');
    }
  }
  report.checks.plugins = 'PASS';
  step = 'node';
  const nodes = (await call('node.list', {})).nodes;
  if (nodes.filter(n => n.connected && n.commands?.includes('claw.android_use')).length !== 1) throw new Error('Expected one connected Android Use Node');
  report.checks.node = 'PASS';
  step = 'deviceBridge';
  const deviceBridge = await call('claw.androidDevice.status', {protocolVersion: 1});
  if (deviceBridge.status !== 'ready' || deviceBridge.connected !== true) throw new Error('Android Device Bridge not ready');
  report.checks.deviceBridge = 'PASS';
} catch {
  report.checks[step] = 'BLOCKED';
  process.exitCode = 3;
} finally {
  clearTimeout(deadline);
  finish();
  console.log(JSON.stringify(report.checks));
}
