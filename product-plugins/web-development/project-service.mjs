import {spawn} from "node:child_process";
import {randomUUID} from "node:crypto";
import {constants} from "node:fs";
import fs from "node:fs/promises";
import http from "node:http";
import path from "node:path";

export const WEB_DEVELOPMENT_PLUGIN_ID = "claw-in-one-web-development";
export const WEB_PROJECT_RESULT_METHOD = "claw.web.result.open";

const PROFILE_ID = "web-development-v1";
const PROFILE_GENERATION = 1;
const PROFILE_NODE = "/home/droid/.local/share/claw-in-one/toolchains/node-v22.22.0-linux-arm64/bin/node";
const PROFILE_BUILDER = "/home/droid/.local/share/claw-in-one/development-profiles/web-development/active/build-project.mjs";
const PROFILE_SERVER = "/home/droid/.local/share/claw-in-one/development-profiles/web-development/active/serve-project.mjs";
const MAX_METADATA_BYTES = 64 * 1024;
const MAX_OUTPUT_BYTES = 4 * 1024 * 1024;
const BUILD_TIMEOUT_MS = 8 * 60 * 1000;
const START_TIMEOUT_MS = 15 * 1000;
const ID_PATTERN = /^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$/u;
const TARGET_ID_PATTERN = /^[0-9a-f]{64}$/u;
const DEVICE_URL_PATTERN = /^http:\/\/127\.0\.0\.1:38\d{3}\/$/u;

function inside(root, file) {
  const relative = path.relative(root, file);
  return relative === "" || (relative !== ".." && !relative.startsWith(`..${path.sep}`) && !path.isAbsolute(relative));
}

async function readMetadata(requestedRoot) {
  const beforeRoot = await fs.lstat(requestedRoot).catch(() => null);
  if (!beforeRoot?.isDirectory() || beforeRoot.isSymbolicLink()) throw new Error("Web Project requires a real Project workspace directory");
  const root = await fs.realpath(requestedRoot);
  const file = path.join(root, ".claw-in-one/web-project.v1.json");
  const before = await fs.lstat(file).catch(() => null);
  if (!before?.isFile() || before.isSymbolicLink() || before.nlink !== 1 || before.size > MAX_METADATA_BYTES) {
    throw new Error("Web Project metadata is unavailable or unsafe");
  }
  const handle = await fs.open(file, constants.O_RDONLY | constants.O_NOFOLLOW);
  let content;
  try {
    const opened = await handle.stat();
    if (!opened.isFile() || opened.nlink !== 1 || opened.dev !== before.dev || opened.ino !== before.ino) throw new Error("Web Project metadata changed while it was opened");
    content = await handle.readFile({encoding: "utf8"});
  } finally { await handle.close(); }
  let metadata;
  try { metadata = JSON.parse(content); }
  catch { throw new Error("Web Project metadata is invalid"); }
  const recorded = typeof metadata?.projectDirectory === "string" ? await fs.realpath(metadata.projectDirectory).catch(() => null) : null;
  if (
    metadata?.schemaVersion !== 1 || metadata.profileId !== PROFILE_ID ||
    metadata.profileGeneration !== PROFILE_GENERATION || recorded !== root ||
    typeof metadata.appName !== "string" || !metadata.appName.trim() ||
    [...metadata.appName.trim()].length > 48 || /[\r\n\0]/u.test(metadata.appName) ||
    metadata.artifact !== "dist/index.html" || metadata.serverScript !== "server.mjs"
  ) throw new Error("Current Project is not bound to the qualified Web Development profile");
  return {root, metadata};
}

function kill(child) {
  if (!child?.pid) return;
  try { process.platform === "win32" ? child.kill("SIGTERM") : process.kill(-child.pid, "SIGTERM"); }
  catch (error) { if (error?.code !== "ESRCH") child.kill("SIGTERM"); }
}

function runProcess(executable, args, options = {}) {
  const signal = options.signal;
  signal?.throwIfAborted();
  const child = (options.spawnProcess ?? spawn)(executable, args, {
    cwd: options.cwd,
    env: options.env ?? process.env,
    detached: process.platform !== "win32",
    stdio: ["ignore", "pipe", "pipe"],
  });
  let stdout = "";
  let stderr = "";
  let bytes = 0;
  const onAbort = () => kill(child);
  signal?.addEventListener("abort", onAbort, {once: true});
  const timeout = setTimeout(() => kill(child), options.timeoutMs ?? BUILD_TIMEOUT_MS);
  return new Promise((resolve, reject) => {
    const collect = (current, chunk) => {
      bytes += chunk.length;
      if (bytes > MAX_OUTPUT_BYTES) { kill(child); reject(new Error("Web Project process output exceeded its limit")); }
      return current + chunk.toString("utf8");
    };
    child.stdout.on("data", chunk => { stdout = collect(stdout, chunk); });
    child.stderr.on("data", chunk => { stderr = collect(stderr, chunk); });
    child.once("error", reject);
    child.once("close", code => {
      clearTimeout(timeout);
      signal?.removeEventListener("abort", onAbort);
      if (signal?.aborted) reject(signal.reason);
      else if (code !== 0) reject(new Error(`Qualified Web Project process failed${stderr.trim() ? `:\n${stderr.trim().slice(-12000)}` : ""}`));
      else resolve({stdout, stderr});
    });
  });
}

function waitForServer(executable, args, options = {}) {
  options.signal?.throwIfAborted();
  const child = (options.spawnProcess ?? spawn)(executable, args, {
    cwd: options.cwd,
    env: options.env,
    detached: process.platform !== "win32",
    stdio: ["ignore", "pipe", "pipe"],
  });
  let stdout = "";
  let stderr = "";
  return new Promise((resolve, reject) => {
    let settled = false;
    const finish = (callback) => {
      if (settled) return;
      settled = true;
      clearTimeout(timeout);
      options.signal?.removeEventListener("abort", onAbort);
      callback();
    };
    const fail = error => finish(() => { kill(child); reject(error); });
    const onAbort = () => fail(options.signal.reason ?? new Error("Web server start was cancelled"));
    const timeout = setTimeout(() => fail(new Error("Web server did not become ready")), START_TIMEOUT_MS);
    options.signal?.addEventListener("abort", onAbort, {once: true});
    child.stderr.on("data", chunk => {
      stderr += chunk.toString("utf8");
      if (Buffer.byteLength(stderr) > MAX_OUTPUT_BYTES) fail(new Error("Web server output exceeded its limit"));
    });
    child.stdout.on("data", chunk => {
      stdout += chunk.toString("utf8");
      if (Buffer.byteLength(stdout) > MAX_OUTPUT_BYTES) return fail(new Error("Web server output exceeded its limit"));
      const lineEnd = stdout.indexOf("\n");
      if (lineEnd < 0) return;
      const line = stdout.slice(0, lineEnd).trim();
      if (!line) return fail(new Error("Web server returned an empty ready result"));
      let payload;
      try { payload = JSON.parse(line); } catch { return fail(new Error("Web server returned an invalid ready result")); }
      if (payload?.status !== "listening" || payload.host !== "127.0.0.1" || !Number.isSafeInteger(payload.port) || payload.port < 1024) {
        return fail(new Error("Web server returned an unsafe listener"));
      }
      finish(() => resolve({child, port: payload.port}));
    });
    child.once("error", fail);
    child.once("close", code => fail(new Error(`Web server exited before ready (${code ?? "signal"})${stderr.trim() ? `: ${stderr.trim().slice(-1000)}` : ""}`)));
  });
}

function health(port, timeoutMs = 5000) {
  return new Promise((resolve, reject) => {
    const request = http.get({host: "127.0.0.1", port, path: "/.claw-in-one/health", timeout: timeoutMs}, response => {
      let body = "";
      response.setEncoding("utf8");
      response.on("data", chunk => { body += chunk; });
      response.on("end", () => {
        try {
          const parsed = JSON.parse(body);
          if (response.statusCode === 200 && parsed?.status === "ready") resolve();
          else reject(new Error("Web server health check failed"));
        } catch { reject(new Error("Web server health response is invalid")); }
      });
    });
    request.once("timeout", () => request.destroy(new Error("Web server health check timed out")));
    request.once("error", reject);
  });
}

async function stopChild(child) {
  if (!child || child.exitCode !== null) return;
  kill(child);
  await Promise.race([
    new Promise(resolve => child.once("close", resolve)),
    new Promise(resolve => setTimeout(resolve, 3000)),
  ]);
  if (child.exitCode === null) {
    try { process.platform === "win32" ? child.kill("SIGKILL") : process.kill(-child.pid, "SIGKILL"); } catch {}
  }
}

export function createWebProjectService(dependencies = {}) {
  const servers = dependencies.servers ?? new Map();
  const node = dependencies.profileNode ?? PROFILE_NODE;
  const builder = dependencies.profileBuilder ?? PROFILE_BUILDER;
  const server = dependencies.profileServer ?? PROFILE_SERVER;
  const createId = dependencies.randomUUID ?? randomUUID;
  let generation = dependencies.initialGeneration ?? 0;

  const reversePort = () => {
    const port = dependencies.reversePort?.() ?? globalThis[Symbol.for("io.github.boxuechen.clawinone.android-device-bridge.reverse-port.v1")];
    if (!port) throw new Error("Android Device Bridge reverse port is unavailable");
    return port;
  };

  return Object.freeze({
    servers,

    async build(admission, signal) {
      const {root, metadata} = await readMetadata(admission.projectRoot);
      if (root !== admission.projectRoot || metadata.buildCommand !== `${builder} --project-dir .`) throw new Error("Web Project build profile is stale");
      await runProcess(node, [builder, "--project-dir", root], {cwd: root, signal, spawnProcess: dependencies.spawnProcess});
      const artifact = path.resolve(root, metadata.artifact);
      const stat = await fs.lstat(artifact).catch(() => null);
      if (!inside(root, artifact) || !stat?.isFile() || stat.isSymbolicLink() || await fs.realpath(artifact) !== artifact) {
        throw new Error("Qualified Web Project build did not produce a safe result");
      }
      return Object.freeze({root, metadata});
    },

    async serve(admission, signal) {
      const {root, metadata} = await readMetadata(admission.projectRoot);
      if (root !== admission.projectRoot || metadata.buildCommand !== `${builder} --project-dir .`) throw new Error("Web Project server profile is stale");
      const artifact = path.resolve(root, metadata.artifact);
      if (!inside(root, artifact) || !(await fs.lstat(artifact).catch(() => null))?.isFile()) throw new Error("Build the Web Project before serving it");
      generation += 1;
      const nextGeneration = generation;
      const started = await waitForServer(node, [server, "--project-dir", root], {
        cwd: root,
        signal,
        spawnProcess: dependencies.spawnProcess,
        env: {...process.env, CLAW_IN_ONE_WEB_HOST: "127.0.0.1", CLAW_IN_ONE_WEB_PORT: "0"},
      });
      try {
        await health(started.port);
        signal.throwIfAborted();
        const previous = servers.get(admission.projectId);
        const resultId = createId().toLowerCase();
        const publication = await reversePort().publish({
          ownerId: WEB_DEVELOPMENT_PLUGIN_ID,
          projectId: admission.projectId,
          hostPort: started.port,
          generation: nextGeneration,
        });
        const record = {
          projectId: admission.projectId,
          projectRoot: root,
          generation: nextGeneration,
          resultId,
          targetId: publication.targetId,
          url: publication.deviceUrl,
          appName: metadata.appName,
          child: started.child,
        };
        servers.set(admission.projectId, record);
        started.child.once("close", () => {
          if (servers.get(admission.projectId) !== record) return;
          servers.delete(admission.projectId);
          Promise.resolve().then(() => reversePort().remove({
            ownerId: WEB_DEVELOPMENT_PLUGIN_ID,
            projectId: admission.projectId,
            generation: nextGeneration,
          })).catch(() => {});
        });
        await stopChild(previous?.child);
        return Object.freeze({...record, child: undefined});
      } catch (error) {
        await stopChild(started.child);
        throw error;
      }
    },

    async stop(admission) {
      const record = servers.get(admission.projectId);
      if (!record || record.projectRoot !== admission.projectRoot) return Object.freeze({status: "already_stopped", projectId: admission.projectId});
      servers.delete(admission.projectId);
      let cleanupError;
      try {
        await reversePort().remove({
          ownerId: WEB_DEVELOPMENT_PLUGIN_ID,
          projectId: admission.projectId,
          generation: record.generation,
        });
      } catch (error) {
        cleanupError = error;
      } finally {
        await stopChild(record.child);
      }
      if (cleanupError) throw cleanupError;
      return Object.freeze({status: "stopped", projectId: admission.projectId, generation: record.generation});
    },

    openResult(params) {
      const record = servers.get(params.projectId);
      const publication = record ? reversePort().snapshot(WEB_DEVELOPMENT_PLUGIN_ID, params.projectId) : null;
      if (
        !record || record.resultId !== params.resultId || record.generation !== params.generation ||
        record.targetId !== params.targetId || record.url !== params.url || record.child.exitCode !== null ||
        publication?.generation !== record.generation || publication.targetId !== record.targetId ||
        Number(new URL(record.url).port) !== publication.devicePort
      ) throw new Error("Web Project result is no longer current");
      return Object.freeze({status: "ready", url: record.url});
    },

    async close() {
      const records = [...servers.values()];
      servers.clear();
      for (const record of records) {
        try {
          await reversePort().remove({
            ownerId: WEB_DEVELOPMENT_PLUGIN_ID,
            projectId: record.projectId,
            generation: record.generation,
          });
        } catch {}
        await stopChild(record.child);
      }
    },
  });
}

export function parseOpenResultParams(value) {
  if (
    !value || typeof value !== "object" || Array.isArray(value) ||
    !ID_PATTERN.test(value.resultId ?? "") || !ID_PATTERN.test(value.projectId ?? "") ||
    !Number.isSafeInteger(value.generation) || value.generation < 1 ||
    !TARGET_ID_PATTERN.test(value.targetId ?? "") || !DEVICE_URL_PATTERN.test(value.url ?? "") ||
    Object.keys(value).some(key => !["resultId", "projectId", "generation", "targetId", "url"].includes(key))
  ) throw new Error("Web Project result request is invalid");
  return value;
}
