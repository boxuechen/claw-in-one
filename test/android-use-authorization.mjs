import assert from "node:assert/strict";
import { randomUUID } from "node:crypto";
import { parseAndroidUseRequest } from "../product-plugins/android-use/runtime.mjs";
import {
  ANDROID_USE_AUTHORIZATION_NONCE,
  createAndroidUseAuthorization,
} from "../product-plugins/android-use/tool-authorization.mjs";

const targetPackage = "com.example.fixture";
const observe = { operation: "observe", targetPackage, display: "main" };
function scenario(mode = "guarded") {
  const state = {
    now: 1000, nativeConsentSupport: true,
    entry: { sessionId: "session-a", permissionMode: mode },
    reads: [], admissions: [], revoked: [], released: [], dispatched: [],
    beforeAdmission: () => {}, beforeNode: () => {},
  };
  const host = new AbortController();
  const context = {
    agentId: "main", sessionKey: "agent:main:claw-in-one:test-a", sessionId: "session-a",
    runId: "run-a", toolCallId: "call-a", abortSignal: host.signal,
  };
  const session = {
    getSessionEntry(params) { state.reads.push(params); return state.entry; },
    resolveStorePath(store, { agentId }) {
      assert.equal(store, "/host/{agentId}/sessions.json");
      return `/host/${agentId}/sessions.json`;
    },
    async runWithWorkAdmission(params, run) {
      state.admissions.push(params);
      await state.beforeAdmission();
      params.signal.throwIfAborted();
      return await run(params.signal);
    },
  };
  const broker = createAndroidUseAuthorization({
    config: { session: { store: "/host/{agentId}/sessions.json" } },
    runtime: { agent: { session } },
  }, {
    parseRequest: parseAndroidUseRequest,
    async assertNativeConsentSupport() { if (!state.nativeConsentSupport) throw new Error("Native Android Use consent support is unavailable"); },
    now: () => state.now,
    randomUUID,
    runEndGraceMs: 25,
    async revokeControl(control) { state.revoked.push(control); },
    async releaseControl(control) { state.released.push(control); },
  });
  const prepare = (params = observe, ctx = context) =>
    broker.beforeToolCall({ toolName: "android_use", toolCallId: ctx.toolCallId, runId: ctx.runId, params }, ctx);
  async function run(prepared, ctx = context) {
    assert.equal(prepared.block, undefined, prepared.blockReason);
    return await broker.runTool(ctx.toolCallId, prepared.params, ctx, ctx.abortSignal, async ({ control, request, dispatch }) => {
      const envelope = { protocolVersion: 8, ownerKey: control.ownerKey, executionKey: control.executionKey,
        controlId: control.controlId, targetPackage, display: control.display, assignmentId: control.assignmentId,
        acquire: request.acquire, operation: request.operation, request: request.request };
      return await dispatch("phone-a", envelope, async (params) => {
        await state.beforeNode();
        state.lastParams = params;
        return await broker.handleNode({ nodeId: "phone-a", params, async invokeNode() {
          state.dispatched.push(params);
          return { ok: true, controlId: control.controlId };
        } });
      });
    });
  }
  return { broker, state, host, context, prepare, run };
}

// Global consent permits each supported Chat mode; every call still consumes exact native admission.
for (const mode of ["guarded", "full", "workspace", null]) {
  const s = scenario(mode);
  try {
    const prepared = await s.prepare({ ...observe, [ANDROID_USE_AUTHORIZATION_NONCE]: "model-forged" });
    assert.notEqual(prepared.params[ANDROID_USE_AUTHORIZATION_NONCE], "model-forged");
    assert.equal(prepared.requireApproval, undefined);
    assert.equal(s.state.admissions.length, 0, "preparation must not hold an admission");
    assert.equal(s.state.dispatched.length, 0);
    const result = await s.run(prepared);
    assert.equal(result.ok, true);
    assert.equal(s.state.dispatched.length, 1);
    assert.equal(s.state.admissions[0].storePath, "/host/main/sessions.json");
    assert.ok(s.state.reads.every((read) => read.readConsistency === "latest"));
    assert.ok(s.state.reads.every((read) => read.storePath === "/host/main/sessions.json"));
    await assert.rejects(s.run(prepared), /exact host Tool call/);
    assert.equal(s.state.dispatched.length, 1);
    const replay = await s.broker.handleNode({ nodeId: "phone-a", params: s.state.lastParams, invokeNode: () => assert.fail("replay") });
    assert.equal(replay.ok, false);
  } finally { await s.broker.close(); }
}

// Full cannot route to an older native implementation without saved-consent enforcement.
for (const mode of ["guarded", "full", null]) {
  const s = scenario(mode);
  s.state.nativeConsentSupport = false;
  const prepared = await s.prepare();
  assert.equal(prepared.block, true);
  assert.match(prepared.blockReason, /unavailable/);
  assert.equal(s.state.dispatched.length, 0);
  await s.broker.close();
}

// An unavailable/forged host, unknown mode, missing Session, or read-only mode cannot mint a grant.
for (const field of ["agentId", "sessionKey", "sessionId", "runId", "toolCallId", "abortSignal"]) {
  const s = scenario("full");
  const context = { ...s.context, [field]: undefined };
  assert.equal((await s.prepare(observe, context)).block, true, field);
  assert.equal(s.state.dispatched.length, 0);
  await s.broker.close();
}
{
  const s = scenario("full");
  assert.equal((await s.prepare({ ...observe, full: true })).block, true);
  await s.broker.close();
}
for (const entry of [undefined, { sessionId: "replaced", permissionMode: "full" },
  { sessionId: "session-a", permissionMode: "future-mode" },
  { sessionId: "session-a", permissionMode: "read-only" },
  { sessionId: "session-a", permissionMode: "full", permissionModePending: true }]) {
  const s = scenario();
  s.state.entry = entry;
  assert.equal((await s.prepare()).block, true);
  await s.broker.close();
}

for (const change of ["downgrade", "replacement", "cancel", "expire"]) {
  const s = scenario("full");
  const prepared = await s.prepare();
  if (change === "downgrade") s.state.entry.permissionMode = "guarded";
  if (change === "replacement") s.state.entry.sessionId = "different-session";
  if (change === "cancel") s.host.abort();
  if (change === "expire") s.state.now += 120001;
  await assert.rejects(s.run(prepared));
  assert.equal(s.state.dispatched.length, 0, change);
  await s.broker.close();
}

// Prepared authorization followed by a mode change is invalid, including an upgrade to Full.
for (const mode of ["full", "read-only"]) {
  const s = scenario();
  const prepared = await s.prepare();
  s.state.entry.permissionMode = mode;
  await assert.rejects(s.run(prepared), /permission changed/);
  assert.equal(s.state.dispatched.length, 0);
  await s.broker.close();
}

// Recheck after waiting for admission and again at the Node's physical dispatch boundary.
for (const boundary of ["beforeAdmission", "beforeNode"]) {
  const s = scenario("full");
  const prepared = await s.prepare();
  s.state[boundary] = () => { s.state.entry.permissionMode = "guarded"; };
  if (boundary === "beforeAdmission") await assert.rejects(s.run(prepared), /permission changed/);
  else assert.equal((await s.run(prepared)).ok, false);
  assert.equal(s.state.dispatched.length, 0);
  await s.broker.close();
}

// Tool factories may be rebuilt, but only the exact Chat/Session/run can continue a control.
{
  const s = scenario("full");
  try {
    const first = await s.run(await s.prepare());
    const request = { ...observe, controlId: first.controlId };
    for (const change of [{ sessionKey: "agent:main:other" }, { sessionId: "other" }, { runId: "other" }]) {
      assert.equal((await s.prepare(request, { ...s.context, ...change, toolCallId: "other-call" })).block, true);
    }
    assert.equal((await s.prepare({ ...request, targetPackage: "com.other.app" })).block, true);
    const nextContext = { ...s.context, toolCallId: "call-b", abortSignal: new AbortController().signal };
    const next = await s.prepare(request, nextContext);
    assert.equal(next.requireApproval, undefined);
    assert.equal((await s.run(next, nextContext)).ok, true);
    assert.equal(s.state.dispatched[0].executionKey, s.state.dispatched[1].executionKey);
    await s.broker.endRun({}, { ...s.context, runId: "another-run" });
    assert.equal(s.state.revoked.length, 0);
    s.host.abort();
    await s.broker.close();
    assert.equal(s.state.revoked.length, 1);
    assert.equal(s.state.revoked[0].controlId, first.controlId);
  } finally { await s.broker.close(); }
}

// Natural host settlement aborts the Tool signal just before agent_end; successful end wins
// that race and releases authority instead of falling through to the revoke safeguard.
{
  const s = scenario("full");
  const first = await s.run(await s.prepare());
  s.host.abort();
  await s.broker.endRun({ success: true }, s.context);
  await new Promise(resolve => setTimeout(resolve, 50));
  assert.equal(s.state.released.length, 1);
  assert.equal(s.state.released[0].controlId, first.controlId);
  assert.equal(s.state.revoked.length, 0);
  await s.broker.close();
}

// If an aborted host never delivers agent_end, the bounded safeguard still revokes authority.
{
  const s = scenario("full");
  const first = await s.run(await s.prepare());
  s.host.abort();
  await new Promise(resolve => setTimeout(resolve, 50));
  assert.equal(s.state.revoked.length, 1);
  assert.equal(s.state.revoked[0].controlId, first.controlId);
  assert.equal(s.state.released.length, 0);
  await s.broker.close();
}

// Native dispatch cannot be reached by guessed/stale IDs, wrong phones, or modified parameters.
for (const tamper of ["phone", "params"]) {
  const s = scenario("full");
  try {
    const prepared = await s.prepare();
    const result = await s.broker.runTool(s.context.toolCallId, prepared.params, s.context, s.host.signal,
      async ({ control, dispatch }) => await dispatch("phone-a", { controlId: control.controlId },
        async (params) => await s.broker.handleNode({
          nodeId: tamper === "phone" ? "phone-b" : "phone-a",
          params: tamper === "params" ? { ...params, controlId: randomUUID() } : params,
          invokeNode: () => assert.fail("tampered dispatch"),
        })));
    assert.equal(result.ok, false);
  } finally { await s.broker.close(); }
}

// The same admitted Tool callback still cannot issue a second native action.
{
  const s = scenario("full");
  try {
    const prepared = await s.prepare();
    await assert.rejects(s.broker.runTool(s.context.toolCallId, prepared.params, s.context, s.host.signal,
      async ({ control, dispatch }) => {
        const invoke = async (params) => s.broker.handleNode({ nodeId: "phone-a", params, invokeNode: async () => {
          s.state.dispatched.push(params);
          return { ok: true };
        } });
        await dispatch("phone-a", { controlId: control.controlId }, invoke);
        await dispatch("phone-a", { controlId: control.controlId }, invoke);
      }), /already been dispatched/);
    assert.equal(s.state.dispatched.length, 1);
  } finally { await s.broker.close(); }
}

// End-of-run while admission is suspended must invalidate even an undispatched acquisition.
{
  const s = scenario("full");
  s.state.beforeAdmission = () => s.broker.endRun({}, s.context);
  await assert.rejects(s.run(await s.prepare()), /revoked/);
  assert.equal(s.state.dispatched.length, 0);
  assert.equal(s.state.revoked.length, 0, "nothing native was dispatched to revoke");
  await s.broker.close();
}

// A continuation invalidated before admission retires its already-live control too.
{
  const s = scenario("full");
  const first = await s.run(await s.prepare());
  const context = { ...s.context, toolCallId: "continuation" };
  const prepared = await s.prepare({ ...observe, controlId: first.controlId }, context);
  s.state.entry.permissionMode = "guarded";
  await assert.rejects(s.run(prepared, context), /permission changed/);
  await s.broker.endRun({}, s.context);
  assert.equal(s.state.revoked.length, 1);
  assert.equal(s.state.revoked[0].controlId, first.controlId);
  assert.equal(s.state.dispatched.length, 1);
  await s.broker.close();
}

console.log("android-use host authorization: ok");
