import assert from "node:assert/strict";
import fs from "node:fs/promises";
import os from "node:os";
import path from "node:path";
import { randomUUID } from "node:crypto";
import { registerAndroidDeveloperBridgePlugin } from "../product-plugins/android-developer-bridge/runtime.mjs";
import { ANDROID_APP_AUTHORIZATION_NONCE as NONCE } from "../product-plugins/android-developer-bridge/app-delivery/tool-protocol.mjs";

// Exercise the real registration -> host hook -> tool -> receipt/mutation/broker chain.
// Only the public host Session API, SDK binaries, and physical phone are test doubles.
const root = await fs.mkdtemp(path.join(os.tmpdir(), "clawinone-install-authorization-"));
const packageName = "io.github.clawinone.authorizationfixture";
const workspaceAdmissionKey = Symbol.for(
  "io.github.boxuechen.clawinone.project-workspaces.admission.v2",
);

async function fixture(mode = "guarded") {
  const workspace = path.join(root, randomUUID());
  await fs.mkdir(workspace);
  await fs.writeFile(path.join(workspace, "fixture.apk"), "signed-fixture");
  const host = new AbortController();
  const lifecycle = new AbortController();
  const context = { agentId: "main", sessionKey: "agent:main:install-test", sessionId: "session-1", workspaceDir: workspace };
  const hooks = new Map();
  const f = {
    clock: 1000,
    entry: {
      sessionId: context.sessionId,
      permissionMode: mode,
      ...(mode === "workspace" ? { projectId: "project-1" } : {}),
    },
    context, host, lifecycle, admissions: 0, dispatches: 0, inspections: 0,
    binding: { generation: "generation-1", target: { id: "phone-1", model: "Pixel 8", androidApi: "37" } },
    beforeAdmission: () => {}, beforeValidation: async () => {}, beforeDispatch: () => {},
    afterDispatch: async () => ({ status: "succeeded" }), beforeReview: () => {},
  };
  const registration = { tools: new Map() };
  const api = {
    agent: {
      events: {
        registerAgentEventSubscription() {},
        emitAgentEvent(event) {
          return { emitted: true, stream: event.stream };
        },
      },
    },
    logger: { warn() {} },
    config: { session: { store: "/fixture/{agentId}/sessions.json" } },
    runtime: { agent: { session: {
      resolveStorePath(store, { agentId }) {
        assert.equal(store, "/fixture/{agentId}/sessions.json");
        assert.equal(agentId, "main");
        return "/fixture/main/sessions.json";
      },
      getSessionEntry(request) {
        assert.deepEqual(request, {
          agentId: "main", sessionKey: context.sessionKey,
          storePath: "/fixture/main/sessions.json", readConsistency: "latest",
        });
        return f.entry;
      },
      async runWithWorkAdmission({ storePath, sessionKey, signal }, execute) {
        assert.equal(storePath, "/fixture/main/sessions.json");
        assert.equal(sessionKey, context.sessionKey);
        f.admissions += 1;
        await f.beforeAdmission();
        return await execute(AbortSignal.any([signal, lifecycle.signal]));
      },
    } } },
    registerService: (value) => { registration.service = value; },
    registerTool: (value, options) => { registration.tools.set(options.name, value); },
    registerGatewayMethod() {}, registerHttpRoute() {},
    on: (name, handler) => { hooks.set(name, handler); },
  };
  const service = {
    async start() {}, async stop() {},
    onBindingInvalidated() {},
    async deviceBinding() { return structuredClone(f.binding); },
    async installedPackageState() {
      f.beforeReview();
      return { binding: structuredClone(f.binding), package: { installed: false } };
    },
    async installApk(params) {
      await f.beforeValidation();
      await params.validate(structuredClone(f.binding));
      f.beforeDispatch();
      params.authorizeDispatch(structuredClone(f.binding));
      params.signal.throwIfAborted();
      f.dispatches += 1;
      return await f.afterDispatch(params.signal);
    },
  };
  registerAndroidDeveloperBridgePlugin(api, {
    bridgeRuntimeEnabled: true,
    createBridge: () => service,
    createDelivery: () => service,
    createDeviceProducer: () => ({ async close() {} }),
    now: () => f.clock,
    runTool: async (command) => {
      f.inspections += 1;
      if (command === "aapt2") return { stdout: `package: name='${packageName}' versionCode='1' versionName='1.0'\n` };
      assert.equal(command, "apksigner");
      return { stdout: `Signer #1 certificate SHA-256 digest: ${"a".repeat(64)}` };
    },
  });
  await registration.service.start({ stateDir: workspace });
  const appToolFactory = registration.tools.get("android_app");
  const tool = appToolFactory(context);
  f.inspect = async () => {
    f.artifact = (await tool.execute("inspect", { operation: "inspect_apk", apkPath: "fixture.apk" })).details.artifact;
  };
  await f.inspect();
  f.identity = (call = "install") => ({ ...context, runId: "run-1", toolCallId: call, abortSignal: host.signal });
  f.prepare = (call = "install", identity = f.identity(call), extra = {}) => hooks.get("before_tool_call")({
    toolName: "android_app", toolCallId: call,
    params: { operation: "install_apk", artifactId: f.artifact.artifactId, ...extra },
  }, identity);
  f.execute = (prepared, call = "install", extra = {}, signal) =>
    appToolFactory({ ...context, ...extra }).execute(call, prepared.params, signal);
  f.end = (identity = f.identity()) => hooks.get("agent_end")({}, identity);
  f.close = () => registration.service.stop();
  return f;
}

try {
  for (const mode of ["guarded", "workspace", undefined, "full"]) {
    const f = await fixture(mode);
    if (mode === undefined) delete f.entry.permissionMode;
    if (mode === "workspace") globalThis[workspaceAdmissionKey] = {
      admitProjectMutation: (_identity, capability) => capability === "android_kotlin" ? {
        projectId: "project-1",
        projectRoot: f.context.workspaceDir,
        leaseGeneration: 1,
      } : null,
    };
    const p = await f.prepare("install", f.identity(), { [NONCE]: "model-injected" });
    assert.notEqual(p.params[NONCE], "model-injected");
    assert.equal(f.admissions, 0, "human waiting must not hold work admission");
    assert.equal(f.dispatches, 0);
    if (mode === "full" || mode === "workspace") assert.equal(p.requireApproval, undefined);
    else {
      assert.deepEqual(p.requireApproval.allowedDecisions, ["allow-once", "deny"]);
      assert.equal(p.requireApproval.onResolution("allow-once"), undefined);
      assert.equal(f.admissions, 0, "decision callback records only");
    }
    const [first, duplicate] = await Promise.all([f.execute(p), f.execute(p)]);
    assert.deepEqual(first, duplicate);
    assert.equal(first.details.status, "succeeded");
    assert.equal(f.dispatches, 1);
    assert.equal(f.admissions, 1);
    await f.close();
    delete globalThis[workspaceAdmissionKey];
  }

  {
    const f = await fixture("workspace");
    globalThis[workspaceAdmissionKey] = { admitProjectMutation: () => null };
    const p = await f.prepare();
    assert.equal(p.block, true);
    assert.match(p.blockReason, /active ready Project run/);
    await f.close();
    delete globalThis[workspaceAdmissionKey];
  }

  for (const decision of ["deny", "timeout", "cancel", "allow-always"]) {
    const f = await fixture();
    const p = await f.prepare();
    p.requireApproval.onResolution(decision);
    p.requireApproval.onResolution("allow-once");
    await assert.rejects(f.execute(p), /not authorized/);
    assert.equal(f.dispatches, 0);
    await f.close();
  }

  for (const field of ["agentId", "sessionKey", "sessionId", "runId", "toolCallId", "abortSignal"]) {
    const f = await fixture("full");
    const identity = f.identity();
    delete identity[field];
    assert.equal((await f.prepare("install", identity)).block, true, field);
    assert.equal(f.admissions, 0);
    await f.close();
  }

  for (const change of [
    (f) => { f.entry.permissionMode = "read-only"; },
    (f) => { f.entry.permissionMode = "future-mode"; },
    (f) => { f.entry.permissionModePending = true; },
    (f) => { f.entry.sessionId = "replacement"; },
    (f) => { f.entry = null; },
    (f) => f.host.abort(),
  ]) {
    const f = await fixture("full");
    change(f);
    assert.equal((await f.prepare()).block, true);
    assert.equal(f.dispatches, 0);
    await f.close();
  }

  {
    const f = await fixture("full");
    assert.equal((await f.prepare("install", f.identity(), { full: true })).block, true);
    assert.equal((await f.prepare("install", { ...f.identity(), toolCallId: "other" })).block, true);
    const p = await f.prepare();
    await assert.rejects(f.execute(p, "other"), /not authorized/);
    await assert.rejects(f.execute(p, "install", { agentId: "other" }), /not authorized/);
    await assert.rejects(f.execute(p, "install", { sessionId: "replacement" }), /receipt/);
    assert.equal(f.dispatches, 0);
    await f.close();
  }

  // Recheck before consumption, after lifecycle admission, and at physical dispatch.
  for (const point of ["consume", "admission", "dispatch"]) {
    for (const mode of ["full", "guarded"]) {
      const f = await fixture(mode);
      const p = await f.prepare();
      p.requireApproval?.onResolution("allow-once");
      const change = () => { f.entry.permissionMode = mode === "full" ? "guarded" : "full"; };
      if (point === "consume") change();
      if (point === "admission") f.beforeAdmission = change;
      if (point === "dispatch") f.beforeDispatch = change;
      await assert.rejects(f.execute(p), /permission changed/);
      assert.equal(f.dispatches, 0, `${point}/${mode}`);
      await f.close();
    }
  }

  for (const point of ["consume", "admission", "dispatch"]) {
    for (const cancel of ["host", "lifecycle", "tool", "end", "close"]) {
      const f = await fixture("full");
      const caller = new AbortController();
      const p = await f.prepare();
      const change = () => {
        if (cancel === "host") f.host.abort();
        if (cancel === "lifecycle") f.lifecycle.abort();
        if (cancel === "tool") caller.abort();
        if (cancel === "end") f.end();
        if (cancel === "close") void f.close();
      };
      if (point === "consume") change();
      if (point === "admission") f.beforeAdmission = change;
      if (point === "dispatch") f.beforeDispatch = change;
      const result = await f.execute(p, "install", {}, caller.signal).catch(() => null);
      // Cancellation before tool admission may return its explicit cancelled outcome.
      assert.ok(
        result === null || result.details.status === "cancelled",
        `${point}/${cancel} returned ${result?.details?.status ?? "unknown"}`,
      );
      assert.equal(f.dispatches, 0, `${point}/${cancel}`);
      await f.close();
    }
  }

  for (const change of [
    async (f) => { f.clock += 120001; },
    async (f) => { f.entry.sessionId = "replacement"; },
    async (f) => { await f.inspect(); },
    async (f) => { f.binding.generation = "replacement"; },
    async (f) => { f.binding.target.id = "other-phone"; },
    async (f) => { await fs.writeFile(path.join(f.context.workspaceDir, "fixture.apk"), "rebuilt"); },
  ]) {
    const f = await fixture("full");
    const p = await f.prepare();
    f.beforeValidation = () => change(f);
    await assert.rejects(f.execute(p));
    assert.equal(f.dispatches, 0);
    await f.close();
  }

  {
    const f = await fixture();
    const p = await f.prepare();
    f.clock += 120001;
    p.requireApproval.onResolution("allow-once");
    await assert.rejects(f.execute(p), /not authorized/);
    assert.equal(f.admissions, 0);
    await f.close();
  }

  for (const cancel of ["host", "end", "close"]) {
    const f = await fixture("full");
    const p = await f.prepare();
    let entered;
    const dispatched = new Promise((resolve) => { entered = resolve; });
    f.afterDispatch = async (signal) => {
      entered(signal);
      await new Promise((resolve) => signal.addEventListener("abort", resolve, { once: true }));
      return { status: "unknown" }; // Package manager may have committed; never claim rollback.
    };
    const result = f.execute(p);
    const activeSignal = await dispatched;
    f.end({ ...f.identity(), runId: "other-run" });
    f.end({ ...f.identity(), sessionKey: "agent:main:another-chat" });
    assert.equal(activeSignal.aborted, false, "another owner cannot cancel this install");
    if (cancel === "host") f.host.abort();
    if (cancel === "end") f.end();
    if (cancel === "close") await f.close();
    assert.equal((await result).details.status, "unknown");
    assert.equal(f.dispatches, 1);
    if (cancel !== "close") {
      assert.equal((await f.execute(p)).details.status, "unknown");
      assert.equal(f.dispatches, 1, "unknown mutation cannot be redispatched");
    }
    await f.close();
  }

  {
    const f = await fixture("full");
    for (const call of ["iteration-1", "iteration-2"]) {
      await f.inspect();
      const p = await f.prepare(call);
      assert.equal(p.requireApproval, undefined);
      assert.equal((await f.execute(p, call)).details.status, "succeeded");
    }
    assert.equal(f.dispatches, 2);
    await f.close();
  }
  console.log("Android App host authorization checks passed");
} finally {
  delete globalThis[workspaceAdmissionKey];
  await fs.rm(root, { recursive: true, force: true });
}
