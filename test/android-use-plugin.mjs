import assert from "node:assert/strict";
import { randomUUID } from "node:crypto";
import { readFileSync } from "node:fs";
import {
  ANDROID_USE_COMMAND,
  ANDROID_USE_PROTOCOL_VERSION,
  parseAndroidUseRequest,
  registerAndroidUsePlugin,
} from "../product-plugins/android-use/runtime.mjs";
import {
  VSCREEN_INTERNAL_METHODS,
  VSCREEN_PROTOCOL_VERSION,
} from "../product-plugins/vscreen-foundation/protocol.mjs";
import "./android-use-authorization.mjs";

const manifest = JSON.parse(
  readFileSync(new URL("../product-plugins/android-use/openclaw.plugin.json", import.meta.url), "utf8"),
);
const skill = readFileSync(
  new URL("../product-skills/android-use/SKILL.md", import.meta.url),
  "utf8",
);
assert.equal(manifest.skills, undefined);
assert.match(skill, /^name: android-use$/m);
assert.match(skill, /bounded `android_use`\s+tool/);

const targetPackage = "com.example.fixture";
const acquire = { operation: "observe", targetPackage, display: "main" };

function scenario(mode = "guarded") {
  const state = {
    calls: [], approvals: [], warnings: [], notices: [], entries: new Map(),
    controllers: new Map(),
    assignments: [],
    failCleanup: false, stopConfirmed: true,
    nodes: [{ nodeId: "phone-1", connected: true, platform: "android", caps: ["clawAndroidUseConsentV1"], invocableCommands: [ANDROID_USE_COMMAND] }],
  };
  const runtimeKey = `/test/android-use/${randomUUID()}`;
  const services = [];
  const pluginRuntime = {};
  function register() {
    const hooks = new Map();
    let policy;
    let factory;
    Object.assign(pluginRuntime, {
      state: { resolveStateDir: () => runtimeKey },
      gateway: {
        async request(method, params, options) {
          assert.equal(method, VSCREEN_INTERNAL_METHODS.assignApp);
          assert.equal(params.protocolVersion, VSCREEN_PROTOCOL_VERSION);
          assert.deepEqual(options, { timeoutMs: 5_000 });
          state.assignments.push(params);
          return {
            protocolVersion: VSCREEN_PROTOCOL_VERSION,
            status: "published",
            producerId: params.producerId,
            workloadRequestId: params.workloadRequestId,
          };
        },
      },
      agent: { session: {
        resolveStorePath: (_store, { agentId }) => `/host/${agentId}/sessions.json`,
        getSessionEntry: ({ sessionKey }) => state.entries.get(sessionKey),
        async runWithWorkAdmission({ signal }, run) { signal.throwIfAborted(); return await run(signal); },
      } },
      nodes: {
        async list() { return { nodes: state.nodes }; },
        async invoke(request) {
          request.signal?.throwIfAborted();
          const result = await policy.handle({
            ...request,
            approvals: { request: () => assert.fail("Node policy must consume a ticket, never request another approval") },
            async invokeNode() {
              state.calls.push(request);
              if (["release", "revoke"].includes(request.params.operation) && state.failCleanup) throw new Error("private cleanup failure details");
              if (request.params.operation === "stop" && !state.stopConfirmed) return { ok:true, payload:{status:"unknown"} };
              return { ok: true, payload: { snapshotId: "snapshot-1", package: targetPackage,
                status: request.params.operation === "release" ? "released" : ["stop", "revoke"].includes(request.params.operation) ? "stopped" : "active" } };
            },
          });
          if (!result.ok) throw new Error(result.message);
          return result;
        },
      },
    });
    const api = {
      config: {},
      logger: { warn: (message) => state.warnings.push(message) },
      runtime: pluginRuntime,
      registerNodeInvokePolicy(value) { policy = value; },
      registerTool(value, options) { assert.equal(options.name, "android_use"); factory = value; },
      on(name, handler) { hooks.set(name, handler); },
      registerService(value) { services.push(value); if (services.length === 1) void value.start(); },
    };
    registerAndroidUsePlugin(api, { runEndGraceMs: 25, async publishControlNotice(params) {
      state.notices.push(params);
      if (state.beforeNotice) await state.beforeNotice(params);
      return {ok:true};
    } });
    return {
      hooks, get policy() { return policy; }, get factory() { return factory; },
      async execute(context, params, toolCallId = randomUUID(), decision = "allow-once") {
        const hostContext = { ...context, toolCallId };
        const prepared = await hooks.get("before_tool_call")(
          { toolName: "android_use", params, toolCallId, runId: context.runId }, hostContext);
        if (prepared?.block) throw new Error(prepared.blockReason);
        if (prepared.requireApproval) {
          state.approvals.push(prepared.requireApproval);
          prepared.requireApproval.onResolution(decision);
        }
        return await factory(context).execute(toolCallId, prepared.params, context.abortSignal);
      },
    };
  }
  const context = (name = "first") => {
    const controller = new AbortController();
    const value = {
      agentId: "main", sessionKey: `agent:main:claw-in-one:${name}`,
      sessionId: `session-${name}`, runId: `run-${name}`, abortSignal: controller.signal,
    };
    state.controllers.set(value.runId, controller);
    state.entries.set(value.sessionKey, { sessionId: value.sessionId, permissionMode: mode });
    return value;
  };
  return { state, register, context, services, pluginRuntime, async close() { for (const service of services) await service.stop(); } };
}

// The caller explicitly chooses the display; VScreen receives an exact generic app assignment.
{
  const s = scenario("full");
  const registry = s.register();
  try {
    const context = s.context("vscreen");
    await registry.execute(context, { ...acquire, display: "vscreen" });
    assert.equal(s.state.calls.at(-1).params.display, "vscreen");
    assert.equal(s.state.calls.at(-1).params.assignmentId, s.state.assignments[0].workloadRequestId);
    assert.equal(s.state.assignments[0].targetPackage, targetPackage);

    const unrelated = s.context("unrelated");
    await registry.execute(unrelated, acquire);
    assert.equal(s.state.calls.at(-1).params.display, "main");
    assert.equal(s.state.calls.at(-1).params.assignmentId, null);
  } finally {
    await s.close();
  }
}

// Revocation failure is reported through the exact transcript, never only a log or a new turn.
{
  const s = scenario("full");
  let releaseNotice;
  let noticeStarted;
  const started = new Promise(resolve => { noticeStarted = resolve; });
  s.state.beforeNotice = () => new Promise(resolve => { releaseNotice = resolve; noticeStarted(); });
  try {
    const registry = s.register();
    const context = s.context();
    const observed = await registry.execute(context, acquire);
    s.state.failCleanup = true;
    let finished = false;
    const ended = registry.hooks.get("agent_end")({}, context).then(() => { finished = true; });
    await started;
    assert.equal(finished, false, "run cleanup must await bounded notice delivery");
    assert.equal(s.state.notices.length, 1);
    const notice = s.state.notices[0];
    assert.equal(notice.agentId, context.agentId);
    assert.equal(notice.sessionKey, context.sessionKey);
    assert.equal(notice.sessionId, context.sessionId);
    assert.equal(notice.storePath, "/host/main/sessions.json");
    assert.equal(notice.idempotencyKey, `claw-in-one:android-use:stop-unconfirmed:${observed.details.controlId}`);
    assert.equal(notice.signal.aborted, false);
    assert.notEqual(notice.signal, context.abortSignal);
    assert.match(notice.text, /Stop unconfirmed/);
    assert.ok(!notice.text.includes("private cleanup failure details"));
    assert.ok(!notice.text.includes(observed.details.controlId));
    assert.ok(!notice.text.includes(context.sessionKey));
    releaseNotice();
    await ended;
    await registry.hooks.get("agent_end")({}, context);
    assert.equal(s.state.notices.length, 1, "retired control cannot publish twice");
    await assert.rejects(registry.execute(context, {...acquire,controlId:observed.details.controlId}), /exact active control/);
  } finally { releaseNotice?.(); await s.close(); }
}

// An explicit confirmed stop needs no redundant cleanup; discovery-only services cannot revoke.
{
  const s = scenario("full");
  try {
    const registry = s.register();
    const context = s.context();
    const observed = await registry.execute(context, acquire);
    s.register();
    await s.services[1].stop();
    await registry.execute(context, {...acquire,controlId:observed.details.controlId});
    s.state.failCleanup = true;
    await registry.execute(context, {...acquire,operation:"stop",controlId:observed.details.controlId});
    await registry.hooks.get("agent_end")({}, context);
    assert.equal(s.state.calls.filter(c=>c.params.operation==="revoke").length,0);
    assert.equal(s.state.notices.length,0);
  } finally { await s.close(); }
}

// A malformed success payload cannot be presented as a confirmed stop.
{
  const s = scenario("full");
  try {
    const registry = s.register();
    const context = s.context();
    const observed = await registry.execute(context, acquire);
    s.state.stopConfirmed = false;
    s.state.failCleanup = true;
    await assert.rejects(registry.execute(context, {...acquire,operation:"stop",controlId:observed.details.controlId}), /stop is unconfirmed/);
    await registry.hooks.get("agent_end")({}, context);
    assert.equal(s.state.notices.length,1);
  } finally { await s.close(); }
}

for (const mode of ["guarded", "full"]) {
  const s = scenario(mode);
  try {
    const a = s.register();
    const context = s.context();
    const observed = await a.execute(context, acquire);
    const tool = a.factory(context);
    assert.match(tool.description, /Prefer semantic refs/);
    assert.match(tool.description, /Finish normally/);
    assert.match(tool.parameters.properties.snapshotId.description, /latest snapshotId/);
    assert.match(tool.parameters.properties.text.description, /Chinese/);
    assert.equal(s.state.approvals.length, 0);
    assert.equal(s.state.calls[0].params.protocolVersion, ANDROID_USE_PROTOCOL_VERSION);
    assert.equal(s.state.calls[0].timeoutMs, 35000, "VScreen readiness fits inside the bounded Node invocation");
    assert.equal(s.state.calls[0].params.acquire, true);
    assert.equal(s.state.calls[0].params.targetPackage, targetPackage);
    assert.notEqual(s.state.calls[0].params.ownerKey, context.sessionKey);
    assert.equal(observed.details.controlId, s.state.calls[0].params.controlId);
    const continuation = { targetPackage, display: "main", controlId: observed.details.controlId };
    const actions = [
      [{ operation: "set_text", snapshotId: "s1", ref: "n1", text: "你好，Android" }, { type: "set_text", ref: "n1", text: "你好，Android" }],
      [{ operation: "scroll", snapshotId: "s2", ref: "n2", direction: "forward" }, { type: "scroll", ref: "n2", direction: "forward" }],
      [{ operation: "tap", snapshotId: "s3", x: 10, y: 20 }, { type: "tap", x: 10, y: 20 }],
      [{ operation: "swipe", snapshotId: "s4", x1: 1, y1: 2, x2: 3, y2: 4, durationMs: 500 },
        { type: "swipe", x1: 1, y1: 2, x2: 3, y2: 4, durationMs: 500 }],
      [{ operation: "back", snapshotId: "s5" }, { type: "global_action", name: "back" }],
      [{ operation: "wait", snapshotId: "s6", ms: 750 }, { type: "wait", ms: 750 }],
    ];
    for (const [params, expected] of actions) {
      await a.execute(context, { ...params, ...continuation });
      assert.deepEqual(s.state.calls.at(-1).params.request, { snapshotId: params.snapshotId, action: expected });
      assert.equal(s.state.calls.at(-1).params.acquire, false);
    }
    assert.equal(s.state.calls.at(-1).timeoutMs, 20000);
    const rebuilt = s.register();
    await rebuilt.execute(context, { ...continuation, operation: "activate", snapshotId: "s7", ref: "n1" });
    assert.equal(s.state.calls.at(-1).params.controlId, observed.details.controlId);
    assert.equal(s.state.calls.at(-1).params.executionKey, s.state.calls[0].params.executionKey);
    const stop = await rebuilt.execute(context, { ...continuation, operation: "stop" });
    assert.equal(stop.details.status, "stopped");
    assert.equal(s.state.approvals.length, 0);
    // Fresh work uses saved consent without another product prompt in either Chat mode.
    await rebuilt.execute(context, acquire);
    assert.equal(s.state.approvals.length, 0);
  } finally { await s.close(); }
}

// Successful completion releases operation authority without issuing a destructive Stop.
{
  const s = scenario("full");
  try {
    const registry = s.register();
    const context = s.context("completed");
    const observed = await registry.execute(context, acquire);
    s.state.controllers.get(context.runId).abort();
    await registry.hooks.get("agent_end")({ success: true }, context);
    await new Promise(resolve => setTimeout(resolve, 50));
    const released = s.state.calls.filter((call) => call.params.operation === "release");
    assert.equal(released.length, 1);
    assert.equal(released[0].params.controlId, observed.details.controlId);
    assert.equal(s.state.calls.some((call) => call.params.operation === "revoke"), false);
  } finally { await s.close(); }
}

{
  const s = scenario("full");
  try {
    const registry = s.register();
    const first = s.context("one");
    const second = s.context("two");
    const observed = await registry.execute(first, acquire);
    await assert.rejects(registry.execute(second, { ...acquire, controlId: observed.details.controlId }), /exact active control/);
    await registry.execute(second, acquire);
    assert.notEqual(s.state.calls[0].params.ownerKey, s.state.calls[1].params.ownerKey);
    assert.notEqual(s.state.calls[0].params.executionKey, s.state.calls[1].params.executionKey);
    await registry.hooks.get("agent_end")({}, first);
    const revoked = s.state.calls.filter((call) => call.params.operation === "revoke");
    assert.equal(revoked.length, 1);
    assert.equal(revoked[0].params.controlId, observed.details.controlId);
    assert.ok(!revoked[0].signal.aborted, "cleanup uses a fresh bounded signal");
  } finally { await s.close(); }
}

{
  const s = scenario("full");
  try {
    const registry = s.register();
    assert.equal(registry.factory({}), null);
    for (const params of [
      { protocolVersion: 3, acquire: true, operation: "observe", controlId: randomUUID(), ownerKey: "b".repeat(64), targetPackage, request: {} },
      { protocolVersion: 6, acquire: true, operation: "observe", controlId: randomUUID(), ownerKey: "b".repeat(64),
        targetPackage, display: "vscreen", assignmentId: randomUUID(), request: {}, executionKey: randomUUID(), sessionId: "session-forged", runId: "run-forged", authorizationNonce: randomUUID() },
    ]) {
      const result = await registry.policy.handle({
        nodeId: "phone-1", params,
        approvals: { request: () => assert.fail("raw Node call cannot solicit an approval") },
        invokeNode: () => assert.fail("raw Node call"),
      });
      assert.equal(result.ok, false);
    }
    assert.deepEqual(registry.policy.commands, [ANDROID_USE_COMMAND]);
  } finally { await s.close(); }
}

{
  const s = scenario();
  try {
    const registry = s.register();
    s.state.nodes[0].caps = [];
    await assert.rejects(registry.execute(s.context(), acquire), /Enable Android Use/);
    assert.equal(s.state.calls.length, 0);
  } finally { await s.close(); }
}

for (const nodes of [[], [
  { nodeId: "one", connected: true, platform: "android", caps: ["clawAndroidUseConsentV1"], invocableCommands: [ANDROID_USE_COMMAND] },
  { nodeId: "two", connected: true, platform: "android", caps: ["clawAndroidUseConsentV1"], invocableCommands: [ANDROID_USE_COMMAND] },
]]) {
  const s = scenario("full");
  try {
    s.state.nodes = nodes;
    await assert.rejects(s.register().execute(s.context(), acquire), /Enable Android Use|exactly one/);
    assert.equal(s.state.calls.length, 0);
  } finally { await s.close(); }
}

const continuation = { targetPackage, display: "main", controlId: randomUUID() };
for (const params of [
  { operation: "observe", targetPackage: "not a package", display: "main" },
  { operation: "back", targetPackage, display: "main" },
  { operation: "observe", targetPackage, display: "main", controlId: "invalid" },
  { ...continuation, operation: "scroll", snapshotId: "s1", ref: "n1", direction: "sideways" },
  { ...continuation, operation: "tap", snapshotId: "s1", x: -1, y: 2 },
  { ...continuation, operation: "swipe", snapshotId: "s1", x1: 0, y1: 0, x2: 1, y2: 1, durationMs: 10001 },
  { ...continuation, operation: "wait", snapshotId: "s1", ms: 10001 },
  { operation: "revoke", targetPackage, display: "main" },
]) assert.throws(() => parseAndroidUseRequest(params));

console.log("android-use plugin contract: ok");
