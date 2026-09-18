import assert from "node:assert/strict";
import fs from "node:fs/promises";
import os from "node:os";
import path from "node:path";
import test from "node:test";
import {createWebProjectAuthorizationBroker} from "../product-plugins/web-development/authorization.mjs";
import {createWebProjectService, parseOpenResultParams} from "../product-plugins/web-development/project-service.mjs";
import {registerWebDevelopmentPlugin} from "../product-plugins/web-development/runtime.mjs";
import {WebProjectResultStore} from "../product-plugins/web-development/result-store.mjs";
import {WEB_PROJECT_AUTHORIZATION_NONCE, WEB_PROJECT_TOOLS, parseWebProjectParams} from "../product-plugins/web-development/tool-protocol.mjs";

const ADMISSION_KEY = Symbol.for("io.github.boxuechen.clawinone.project-workspaces.admission.v2");
const TARGET_ID = "a".repeat(64);

function context(root, call = "tool-1") {
  return {
    agentId: "main",
    sessionKey: "agent:main:web-project",
    sessionId: "session-1",
    runId: "run-1",
    toolCallId: call,
    workspaceDir: root,
    abortSignal: new AbortController().signal,
  };
}

function toolHookContext(call = "tool-1") {
  const {workspaceDir: _workspaceDir, ...trusted} = context("/not-exposed-by-openclaw", call);
  return trusted;
}

test("Web tools accept no model path, command, host or port and require exact Web admission", async () => {
  const root = await fs.mkdtemp(path.join(os.tmpdir(), "clawinone-web-auth-"));
  let leaseGeneration = 7;
  let capability;
  globalThis[ADMISSION_KEY] = {
    admitProjectMutation(_identity, requested) {
      capability = requested;
      return requested === "web_development" ? {
        projectId: "project-1",
        projectRoot: root,
        leaseGeneration,
      } : null;
    },
  };
  try {
    assert.deepEqual(parseWebProjectParams({}), {authorizationNonce: undefined});
    assert.throws(() => parseWebProjectParams({port: 5173}), /do not accept parameters/);
    const broker = createWebProjectAuthorizationBroker({randomUUID: () => "12345678-1234-4123-8123-123456789abc"});
    const identity = toolHookContext();
    const prepared = broker.beforeToolCall({toolName: WEB_PROJECT_TOOLS.build, toolCallId: "tool-1", params: {}}, identity);
    assert.equal(capability, "web_development");
    assert.equal(typeof prepared.params[WEB_PROJECT_AUTHORIZATION_NONCE], "string");
    const authorized = broker.consumeAuthorization(prepared.params[WEB_PROJECT_AUTHORIZATION_NONCE], {
      toolName: WEB_PROJECT_TOOLS.build,
      toolCallId: "tool-1",
      context: identity,
    });
    assert.equal(await authorized.run(undefined, async (_signal, current) => current.projectId), "project-1");

    const stale = broker.beforeToolCall({toolName: WEB_PROJECT_TOOLS.stop, toolCallId: "tool-2", params: {}}, toolHookContext("tool-2"));
    leaseGeneration = 8;
    assert.throws(
      () => broker.consumeAuthorization(stale.params[WEB_PROJECT_AUTHORIZATION_NONCE], {
        toolName: WEB_PROJECT_TOOLS.stop,
        toolCallId: "tool-2",
        context: toolHookContext("tool-2"),
      }),
      /active ready Project run/,
    );
    broker.close();
  } finally {
    delete globalThis[ADMISSION_KEY];
    await fs.rm(root, {recursive: true, force: true});
  }
});

async function serviceFixture() {
  const root = await fs.mkdtemp(path.join(os.tmpdir(), "clawinone-web-service-"));
  const project = path.join(root, "project");
  const profile = path.join(root, "profile");
  const builder = path.join(profile, "build-project.mjs");
  const server = path.join(profile, "serve-project.mjs");
  await fs.mkdir(path.join(project, ".claw-in-one"), {recursive: true});
  await fs.mkdir(path.join(project, "dist"));
  await fs.mkdir(profile);
  await fs.writeFile(path.join(project, "dist/index.html"), "<!doctype html><title>Web Fixture</title>");
  await fs.writeFile(builder, "#!/usr/bin/env node\n", {mode: 0o555});
  await fs.writeFile(server, `#!/usr/bin/env node
import http from "node:http";
const server = http.createServer((request, response) => {
  const body = request.url === "/.claw-in-one/health" ? JSON.stringify({status: "ready"}) : "fixture";
  response.writeHead(200, {"content-type": "application/json"});
  response.end(body);
});
server.listen(0, "127.0.0.1", () => process.stdout.write(JSON.stringify({status: "listening", host: "127.0.0.1", port: server.address().port}) + "\\n"));
process.on("SIGTERM", () => server.close(() => process.exit(0)));
`, {mode: 0o555});
  await fs.writeFile(path.join(project, ".claw-in-one/web-project.v1.json"), `${JSON.stringify({
    schemaVersion: 1,
    profileId: "web-development-v1",
    profileGeneration: 1,
    projectDirectory: project,
    appName: "Web Fixture",
    buildCommand: `${builder} --project-dir .`,
    artifact: "dist/index.html",
    serverScript: "server.mjs",
  })}\n`);
  return {root, project: await fs.realpath(project), builder, server};
}

test("Web server replacement becomes current only after health and Stop removes its exact reverse", async () => {
  const fixture = await serviceFixture();
  const reverseCalls = [];
  let publication = null;
  const reverse = {
    async publish(request) {
      reverseCalls.push({operation: "publish", ...request});
      publication = {
        ...request,
        targetId: TARGET_ID,
        devicePort: 38444,
      };
      return {targetId: TARGET_ID, deviceUrl: "http://127.0.0.1:38444/"};
    },
    async remove(request) {
      reverseCalls.push({operation: "remove", ...request});
      if (publication?.generation === request.generation) publication = null;
      return {status: "removed"};
    },
    snapshot() {
      return publication;
    },
  };
  const ids = ["11111111-1111-4111-8111-111111111111", "22222222-2222-4222-8222-222222222222"];
  const service = createWebProjectService({
    profileNode: process.execPath,
    profileBuilder: fixture.builder,
    profileServer: fixture.server,
    reversePort: () => reverse,
    randomUUID: () => ids.shift(),
  });
  const admission = {projectId: "project-1", projectRoot: fixture.project, leaseGeneration: 1};
  try {
    const first = await service.serve(admission, new AbortController().signal);
    assert.equal(service.openResult(first).status, "ready");
    const second = await service.serve(admission, new AbortController().signal);
    assert.throws(() => service.openResult(first), /no longer current/);
    assert.equal(service.openResult(second).url, "http://127.0.0.1:38444/");
    assert.deepEqual(reverseCalls.filter(call => call.operation === "publish").map(call => call.generation), [1, 2]);
    publication = null;
    assert.throws(() => service.openResult(second), /no longer current/);
    publication = {generation: 2, targetId: TARGET_ID, devicePort: 38444};
    assert.deepEqual(await service.stop(admission), {status: "stopped", projectId: "project-1", generation: 2});
    assert.deepEqual(reverseCalls.at(-1), {
      operation: "remove",
      ownerId: "claw-in-one-web-development",
      projectId: "project-1",
      generation: 2,
    });
    assert.throws(() => service.openResult(second), /no longer current/);
  } finally {
    await service.close();
    await fs.rm(fixture.root, {recursive: true, force: true});
  }
});

test("only exact successful serve and stop results become native Web result events", () => {
  const store = new WebProjectResultStore();
  const host = context("/project");
  assert.equal(store.claimToolCall({toolName: WEB_PROJECT_TOOLS.serve}, host), true);
  const details = {
    protocolVersion: 1,
    operation: "serve",
    status: "ready",
    resultId: "result-1",
    projectId: "project-1",
    generation: 1,
    targetId: TARGET_ID,
    url: "http://127.0.0.1:38444/",
    appName: "Web Fixture",
  };
  const event = store.eventFromToolResult({
    stream: "tool",
    runId: host.runId,
    sessionKey: host.sessionKey,
    data: {phase: "result", name: WEB_PROJECT_TOOLS.serve, toolCallId: host.toolCallId, isError: false, result: {details}},
  });
  assert.equal(event.stream, "claw-in-one-web-project.result");
  assert.equal(event.data.url, details.url);
  const stopHost = {...host, toolCallId: "stop-1"};
  assert.equal(store.claimToolCall({toolName: WEB_PROJECT_TOOLS.stop}, stopHost), true);
  const stopped = store.eventFromToolResult({
    stream: "tool",
    runId: stopHost.runId,
    sessionKey: stopHost.sessionKey,
    data: {
      phase: "result",
      name: WEB_PROJECT_TOOLS.stop,
      toolCallId: stopHost.toolCallId,
      isError: false,
      result: {details: {protocolVersion: 1, operation: "stop", status: "stopped", projectId: "project-1", generation: 1}},
    },
  });
  assert.equal(stopped.stream, "claw-in-one-web-project.result");
  assert.equal(stopped.data.phase, "stopped");
  assert.equal(stopped.data.projectId, "project-1");
  assert.throws(() => parseOpenResultParams({...details, operation: undefined}), /request is invalid/);
  assert.deepEqual(parseOpenResultParams({
    resultId: details.resultId,
    projectId: details.projectId,
    generation: details.generation,
    targetId: details.targetId,
    url: details.url,
  }).targetId, TARGET_ID);
});

test("ordinary task completion retires authorization but does not stop a healthy Web server", async () => {
  const hooks = new Map();
  let ended = 0;
  let closed = 0;
  let authorizationClosed = false;
  let serviceRegistration;
  const authorization = {
    get closed() { return authorizationClosed; },
    beforeToolCall() {},
    endRun() { ended += 1; },
    close() { authorizationClosed = true; },
  };
  const service = {
    async close() { closed += 1; },
    openResult() {},
  };
  registerWebDevelopmentPlugin({
    runtime: {state: {resolveStateDir: () => "/tmp/web-plugin-runtime-test"}},
    agent: {events: {registerAgentEventSubscription() {}, emitAgentEvent() {}}},
    logger: {warn() {}},
    registerService(value) { serviceRegistration = value; },
    registerTool() {},
    registerGatewayMethod() {},
    on(name, handler) { hooks.set(name, handler); },
  }, {
    runtimeKey: `task-lifecycle-${process.pid}-${Date.now()}`,
    authorization,
    service,
    results: {claimToolCall() {}, clearRun() {}, eventFromToolResult() {}},
  });
  hooks.get("agent_end")({}, {runId: "run-1"});
  assert.equal(ended, 1);
  assert.equal(closed, 0);
  await serviceRegistration.stop();
  assert.equal(closed, 1);
  assert.equal(authorizationClosed, true);
});
