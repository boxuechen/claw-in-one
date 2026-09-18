import assert from "node:assert/strict";
import { Duplex, PassThrough } from "node:stream";
import {
  VSCREEN_AGENT_EVENT_STREAM,
  VSCREEN_INTERNAL_METHODS,
  VSCREEN_METHODS,
  VSCREEN_PROTOCOL_VERSION,
  VScreenErrorCode,
  parsePreviewProbeFinishParams,
  parsePreviewProbeStartParams,
  parseVScreenCloseParams,
  parseVScreenEnsureParams,
  parseVScreenFramePresentedParams,
  parseVScreenAppAssignmentParams,
  parseVScreenWorkloadParams,
  vscreenOwnerKey,
} from "../product-plugins/vscreen-foundation/protocol.mjs";
import { VScreenProducerRegistry } from "../product-plugins/vscreen-foundation/producer-registry.mjs";
import { VScreenWorkloadRegistry } from "../product-plugins/vscreen-foundation/workload-registry.mjs";
import { createRemoteVScreenProducer } from "../product-plugins/vscreen-foundation/remote-producer.mjs";
import {
  connectVScreenSourceRelay,
  createVScreenSourceRelay,
} from "../product-plugins/vscreen-foundation/source-relay.mjs";
import { VScreenDisplayService } from "../product-plugins/vscreen-foundation/display-service.mjs";
import { registerVScreenFoundationPlugin } from "../product-plugins/vscreen-foundation/runtime.mjs";
import { createVScreenWebSocketRoute } from "../product-plugins/vscreen-foundation/websocket.mjs";

const OWNER = "a".repeat(64);
const OTHER_OWNER = "b".repeat(64);
const ATTACHMENT_A = "12345678-1234-4123-8123-123456789abc";
const ATTACHMENT_B = "22345678-1234-4123-8123-123456789abc";
const SOURCE_A = "32345678-1234-4123-8123-123456789abc";
const SOURCE_B = "42345678-1234-4123-8123-123456789abc";
const REQUEST_A = "request-a";
const REQUEST_B = "request-b";
const PROBE_ID = "c".repeat(32);
const TOKEN_A = "d".repeat(64);
const TOKEN_B = "e".repeat(64);
const TOKEN_C = "f".repeat(64);
const TOKEN_D = "1".repeat(64);

function deferred() {
  let resolve;
  let reject;
  const promise = new Promise((done, fail) => {
    resolve = done;
    reject = fail;
  });
  return { promise, resolve, reject };
}

function maskedWebSocketFrame(opcode, payload, mask = Buffer.from([1, 2, 3, 4])) {
  const content = Buffer.from(payload);
  const header = Buffer.from([0x80 | opcode, 0x80 | content.length]);
  const masked = Buffer.alloc(content.length);
  for (let index = 0; index < content.length; index += 1) {
    masked[index] = content[index] ^ mask[index % 4];
  }
  return Buffer.concat([header, mask, masked]);
}

function fakeProducer(id = "fixture.vscreen") {
  const calls = [];
  const sources = [];
  const createSource = (targetRef, sourceHandle, capabilities = ["video/h264", "pointer-v1"]) => {
    const ended = deferred();
    const source = {
      targetRef,
      sourceHandle,
      codec: "h264",
      display: { id: sources.length + 41, width: 720, height: 1560, dpi: 320 },
      capabilities,
      foreground: "home",
      video: new PassThrough(),
      inputEvents: [],
      input(event) {
        this.inputEvents.push(event);
        return true;
      },
      closed: ended.promise,
      ended,
    };
    sources.push(source);
    return source;
  };
  let target = null;
  return {
    id,
    calls,
    sources,
    async ensureTarget(input) {
      calls.push(["ensure", input]);
      target ??= createSource("display:one", SOURCE_A);
      return target;
    },
    async placeWorkload(input) {
      calls.push(["workload", input.requestId, input.origin.runId]);
      target ??= createSource("display:one", SOURCE_A);
      assert.equal(input.source, target);
      return target;
    },
    async verifyPresented(input) {
      calls.push(["presented", input.requestId]);
      return { status: "ready" };
    },
    async closeTarget(source, reason) {
      calls.push(["close", source.targetRef, reason]);
      if (source === target) target = null;
      source.video.end();
    },
    async prepareProbe(input) {
      calls.push(["probe", input]);
      return createSource(`probe:${input.probeId}`, SOURCE_B, ["video/h264"]);
    },
  };
}

function publishWorkload(workloads, requestId = REQUEST_A, runId = "run-a") {
  return workloads.publish({
    producerId: "fixture.vscreen",
    workloadRequestId: requestId,
    sessionKey: "agent:main:a",
    sessionId: "session-a",
    runId,
    expiresAtMs: 2_000,
    toolCallId: `tool-${requestId}`,
    targetPackage: "com.example.fixture",
  });
}

assert.deepEqual(
  parseVScreenEnsureParams({
    protocolVersion: VSCREEN_PROTOCOL_VERSION,
    producerId: "fixture.vscreen",
  }),
  { producerId: "fixture.vscreen" },
);
assert.deepEqual(
  parseVScreenCloseParams({
    protocolVersion: VSCREEN_PROTOCOL_VERSION,
    attachmentId: ATTACHMENT_A,
    targetGeneration: 4,
    sourceGeneration: 9,
  }),
  { attachmentId: ATTACHMENT_A, targetGeneration: 4, sourceGeneration: 9 },
);
assert.deepEqual(
  parseVScreenWorkloadParams({
    protocolVersion: VSCREEN_PROTOCOL_VERSION,
    producerId: "fixture.vscreen",
    workloadRequestId: REQUEST_A,
  }),
  { producerId: "fixture.vscreen", workloadRequestId: REQUEST_A },
);
assert.deepEqual(
  parseVScreenFramePresentedParams({
    protocolVersion: VSCREEN_PROTOCOL_VERSION,
    attachmentId: ATTACHMENT_A,
    workloadRequestId: REQUEST_A,
  }),
  { attachmentId: ATTACHMENT_A, workloadRequestId: REQUEST_A },
);
assert.deepEqual(
  parsePreviewProbeStartParams({
    protocolVersion: VSCREEN_PROTOCOL_VERSION,
    producerId: "fixture.vscreen",
    probeId: PROBE_ID,
  }),
  { producerId: "fixture.vscreen", probeId: PROBE_ID },
);
assert.deepEqual(
  parsePreviewProbeFinishParams({
    protocolVersion: VSCREEN_PROTOCOL_VERSION,
    probeId: PROBE_ID,
    attachmentId: ATTACHMENT_A,
  }),
  { probeId: PROBE_ID, attachmentId: ATTACHMENT_A },
);
assert.deepEqual(
  parseVScreenAppAssignmentParams({
    protocolVersion: VSCREEN_PROTOCOL_VERSION,
    producerId: "fixture.vscreen",
    workloadRequestId: REQUEST_A,
    targetPackage: "com.example.fixture",
    sessionKey: "agent:main:a",
    sessionId: "session-a",
    runId: "run-a",
    expiresAtMs: 2_000,
    toolCallId: "tool-a",
  }),
  {
    producerId: "fixture.vscreen",
    workloadRequestId: REQUEST_A,
    targetPackage: "com.example.fixture",
    sessionKey: "agent:main:a",
    sessionId: "session-a",
    runId: "run-a",
    expiresAtMs: 2_000,
    toolCallId: "tool-a",
  },
);
assert.throws(
  () =>
    parseVScreenEnsureParams({
      protocolVersion: 1,
      producerId: "fixture.vscreen",
    }),
  /protocolVersion/,
);
assert.equal(vscreenOwnerKey({ connect: { device: { id: "phone" } } }).length, 64);

{
  let now = 1_000;
  const workloads = new VScreenWorkloadRegistry({ now: () => now, maxWorkloads: 2 });
  const events = [];
  const publisher = workloads.registerPublisher((event) => {
    events.push(event);
    return { emitted: true };
  });
  const first = publishWorkload(workloads);
  assert.equal(first.event.stream, VSCREEN_AGENT_EVENT_STREAM);
  assert.deepEqual(events, [first.event]);
  assert.equal(workloads.resolve(REQUEST_A)?.runId, "run-a");
  assert.equal(first.event.data.producerPayload.targetPackage, "com.example.fixture");
  workloads.retireOrigin("run-a");
  assert.equal(workloads.resolve(REQUEST_A), null);
  publishWorkload(workloads, REQUEST_B, "run-b");
  now = 2_001;
  assert.equal(workloads.resolve(REQUEST_B), null);
  assert.equal(publisher.dispose(), true);
  assert.equal(publisher.dispose(), false);
}

{
  const registry = new VScreenProducerRegistry();
  const producer = fakeProducer();
  const registration = registry.register(producer);
  assert.equal(registry.resolve(producer.id)?.generation, registration.generation);
  assert.throws(
    () => registry.register(fakeProducer()),
    (error) => error.code === VScreenErrorCode.producerConflict,
  );
  assert.equal(registration.dispose(), true);
  assert.equal(registration.dispose(), false);
}

{
  let now = 1_000;
  const producer = fakeProducer();
  const registry = new VScreenProducerRegistry();
  registry.register(producer);
  const workloads = new VScreenWorkloadRegistry({ now: () => now });
  workloads.registerPublisher(() => ({ emitted: true }));
  publishWorkload(workloads, REQUEST_A, "run-a");
  publishWorkload(workloads, REQUEST_B, "run-b");
  const ids = [ATTACHMENT_A, ATTACHMENT_B];
  const tokens = [TOKEN_A, TOKEN_B, TOKEN_C, TOKEN_D];
  const service = new VScreenDisplayService({
    registry,
    workloads,
    now: () => now,
    randomUUID: () => ids.shift(),
    createToken: () => tokens.shift(),
    tokenLifetimeMs: 50,
    setTimer: () => 1,
    clearTimer: () => {},
  });

  const ensured = await service.ensure(OWNER, { producerId: producer.id });
  assert.equal(ensured.kind, "display");
  assert.equal(ensured.foreground, "home");
  assert.equal(ensured.targetGeneration, 1);
  assert.equal(ensured.sourceGeneration, 1);
  assert.equal(ensured.attachmentId, ATTACHMENT_A);
  assert.equal(ensured.token, TOKEN_A);

  const claimed = service.claim(TOKEN_A);
  assert.equal(claimed.attachmentId, ATTACHMENT_A);
  assert.deepEqual(claimed.capabilities, ["video/h264", "pointer-v1"]);
  assert.equal(claimed.input({
    type: "pointer", attachmentId: ATTACHMENT_A, sequence: 1, pointerId: 7,
    phase: "down", normalizedX: 0.25, normalizedY: 0.75, pressure: 1,
  }), true);
  assert.equal(claimed.input({
    type: "pointer", attachmentId: ATTACHMENT_A, sequence: 1, pointerId: 7,
    phase: "move", normalizedX: 0.5, normalizedY: 0.5, pressure: 1,
  }), false, "stale pointer sequence is rejected");
  assert.equal(claimed.input({
    type: "pointer", attachmentId: ATTACHMENT_A, sequence: 2, pointerId: 7,
    phase: "up", normalizedX: 0.25, normalizedY: 0.75, pressure: 0,
  }), true);
  assert.deepEqual(producer.sources[0].inputEvents.map(({ phase }) => phase), ["down", "up"]);
  await claimed.close();
  assert.equal(producer.calls.filter(([kind]) => kind === "close").length, 0);
  const reopened = await service.status(OWNER);
  assert.equal(reopened.attachmentId, ATTACHMENT_A);
  assert.equal(reopened.token, TOKEN_B);
  assert.equal(reopened.targetGeneration, 1);

  const placed = await service.placeWorkload(OWNER, {
    producerId: producer.id,
    workloadRequestId: REQUEST_A,
  });
  assert.equal(placed.targetGeneration, 1);
  assert.equal(placed.sourceGeneration, 1);
  assert.deepEqual(placed.workload, { generation: 1, requestId: REQUEST_A });
  assert.equal(placed.foreground, "workload");
  const replay = await service.placeWorkload(OWNER, {
    producerId: producer.id,
    workloadRequestId: REQUEST_A,
  });
  assert.deepEqual(replay.workload, placed.workload);
  assert.equal(
    producer.calls.filter(([kind]) => kind === "workload").length,
    1,
    "an exact workload retry must reconcile without replay",
  );
  await service.framePresented(OWNER, {
    attachmentId: ATTACHMENT_A,
    workloadRequestId: REQUEST_A,
  });
  await service.framePresented(OWNER, {
    attachmentId: ATTACHMENT_A,
    workloadRequestId: REQUEST_A,
  });
  assert.equal(
    producer.calls.filter(([kind]) => kind === "presented").length,
    1,
    "one workload generation is acknowledged once",
  );

  await assert.rejects(
    service.placeWorkload(OTHER_OWNER, {
      producerId: producer.id,
      workloadRequestId: REQUEST_B,
    }),
    (error) => error.code === VScreenErrorCode.busy,
  );
  workloads.retireOrigin("run-b");
  await assert.rejects(
    service.placeWorkload(OWNER, {
      producerId: producer.id,
      workloadRequestId: REQUEST_B,
    }),
    (error) => error.code === VScreenErrorCode.unauthorized,
  );

  now = 1_051;
  assert.throws(
    () => service.claim(TOKEN_B),
    (error) => error.code === VScreenErrorCode.unauthorized,
  );
  const refreshed = await service.status(OWNER);
  assert.equal(refreshed.token, TOKEN_C);
  assert.equal(refreshed.targetGeneration, 1);
  assert.equal(producer.calls.filter(([kind]) => kind === "close").length, 0);

  const oldSource = producer.sources[0];
  oldSource.ended.resolve({ reason: "helper_exit" });
  await new Promise((resolve) => setImmediate(resolve));
  assert.equal((await service.status(OWNER)).status, "unavailable");
  assert.equal(producer.calls.filter(([kind]) => kind === "close").length, 1);

  await service.ensure(OWNER, { producerId: producer.id });
  assert.equal((await service.status(OWNER)).targetGeneration, 2);
  oldSource.ended.resolve({ reason: "late_duplicate" });
  assert.equal((await service.status(OWNER)).targetGeneration, 2);
  await service.closeAll();
}

{
  const producer = fakeProducer();
  const registry = new VScreenProducerRegistry();
  registry.register(producer);
  const ids = [ATTACHMENT_A, ATTACHMENT_B];
  const tokens = [TOKEN_A, TOKEN_B];
  const service = new VScreenDisplayService({
    registry,
    workloads: new VScreenWorkloadRegistry(),
    randomUUID: () => ids.shift(),
    createToken: () => tokens.shift(),
    setTimer: () => 1,
    clearTimer: () => {},
  });
  await service.ensure(OWNER, { producerId: producer.id });
  const staleViewer = service.claim(TOKEN_A);
  const replacement = await service.ensure(OWNER, { producerId: producer.id });
  assert.equal(replacement.attachmentId, ATTACHMENT_B);
  assert.equal(replacement.token, TOKEN_B);
  assert.equal(replacement.targetGeneration, 2);
  assert.deepEqual(
    producer.calls.find(([kind, , reason]) => kind === "close" && reason === "viewer_replaced"),
    ["close", "display:one", "viewer_replaced"],
  );
  await staleViewer.close();
  assert.equal((await service.status(OWNER)).attachmentId, ATTACHMENT_B);
  await service.closeAll();
}

{
  const producer = fakeProducer();
  const registry = new VScreenProducerRegistry();
  registry.register(producer);
  const ids = [ATTACHMENT_A, ATTACHMENT_B];
  const tokens = [TOKEN_A, TOKEN_B, TOKEN_C];
  const service = new VScreenDisplayService({
    registry,
    workloads: new VScreenWorkloadRegistry(),
    randomUUID: () => ids.shift(),
    createToken: () => tokens.shift(),
    setTimer: () => 1,
    clearTimer: () => {},
  });
  await service.ensure(OWNER, { producerId: producer.id });
  const disconnectedViewer = service.claim(TOKEN_A);
  await disconnectedViewer.close();
  assert.equal((await service.status(OWNER)).attachmentId, ATTACHMENT_A);
  const replacement = await service.ensure(OWNER, { producerId: producer.id });
  assert.equal(replacement.attachmentId, ATTACHMENT_B);
  assert.equal(replacement.token, TOKEN_C);
  assert.equal(replacement.targetGeneration, 2);
  assert.deepEqual(
    producer.calls.find(([kind, , reason]) => kind === "close" && reason === "viewer_replaced"),
    ["close", "display:one", "viewer_replaced"],
  );
  await service.closeAll();
}

{
  const producer = fakeProducer();
  const registry = new VScreenProducerRegistry();
  registry.register(producer);
  const ids = [ATTACHMENT_A, ATTACHMENT_B];
  const tokens = [TOKEN_A, TOKEN_B];
  const service = new VScreenDisplayService({
    registry,
    workloads: new VScreenWorkloadRegistry(),
    randomUUID: () => ids.shift(),
    createToken: () => tokens.shift(),
    setTimer: () => 1,
    clearTimer: () => {},
  });
  const first = await service.ensure(OWNER, { producerId: producer.id });
  await assert.rejects(
    service.close(OWNER, {
      attachmentId: ATTACHMENT_B,
      targetGeneration: first.targetGeneration,
      sourceGeneration: first.sourceGeneration,
    }),
    (error) => error.code === VScreenErrorCode.unauthorized,
  );
  assert.equal(producer.calls.filter(([kind]) => kind === "close").length, 0);
  assert.deepEqual(
    await service.close(OWNER, {
      attachmentId: first.attachmentId,
      targetGeneration: first.targetGeneration,
      sourceGeneration: first.sourceGeneration,
    }),
    {
      protocolVersion: VSCREEN_PROTOCOL_VERSION,
      status: "closed",
      attachmentId: ATTACHMENT_A,
      targetGeneration: 1,
      sourceGeneration: 1,
    },
  );
  assert.equal((await service.status(OWNER)).status, "unavailable");
  assert.deepEqual(
    producer.calls.find(([kind, , reason]) => kind === "close" && reason === "user_closed"),
    ["close", "display:one", "user_closed"],
  );

  const second = await service.ensure(OWNER, { producerId: producer.id });
  await assert.rejects(
    service.close(OWNER, {
      attachmentId: first.attachmentId,
      targetGeneration: first.targetGeneration,
      sourceGeneration: first.sourceGeneration,
    }),
    (error) => error.code === VScreenErrorCode.unauthorized,
  );
  assert.equal((await service.status(OWNER)).attachmentId, second.attachmentId);
  await service.closeAll();
}

{
  const gate = deferred();
  const producer = fakeProducer();
  producer.ensureTarget = async () => await gate.promise;
  const registry = new VScreenProducerRegistry();
  registry.register(producer);
  const service = new VScreenDisplayService({
    registry,
    workloads: new VScreenWorkloadRegistry(),
    randomUUID: () => ATTACHMENT_A,
    createToken: () => TOKEN_A,
    setTimer: () => 1,
    clearTimer: () => {},
  });
  const pending = service.ensure(OWNER, { producerId: producer.id });
  await assert.rejects(
    service.ensure(OWNER, { producerId: producer.id }),
    (error) => error.code === VScreenErrorCode.busy,
  );
  gate.resolve(fakeProducer().ensureTarget({ ownerKey: OWNER }));
  await pending;
  await service.closeAll();
}

{
  const producer = fakeProducer();
  const registry = new VScreenProducerRegistry();
  registry.register(producer);
  const service = new VScreenDisplayService({
    registry,
    workloads: new VScreenWorkloadRegistry(),
    randomUUID: () => ATTACHMENT_A,
    createToken: () => TOKEN_A,
    setTimer: () => 1,
    clearTimer: () => {},
  });
  const started = await service.startProbe(OWNER, {
    producerId: producer.id,
    probeId: PROBE_ID,
  });
  assert.equal(started.kind, "probe");
  assert.deepEqual(started.capabilities, ["video/h264"]);
  await assert.rejects(
    service.ensure(OWNER, { producerId: producer.id }),
    (error) => error.code === VScreenErrorCode.busy,
  );
  await assert.rejects(
    service.finishProbe(OTHER_OWNER, {
      probeId: PROBE_ID,
      attachmentId: ATTACHMENT_A,
    }),
    (error) => error.code === VScreenErrorCode.unauthorized,
  );
  assert.equal(
    (
      await service.finishProbe(OWNER, {
        probeId: PROBE_ID,
        attachmentId: ATTACHMENT_A,
      })
    ).status,
    "unavailable",
  );
}

{
  const source = new PassThrough();
  const inputEvents = [];
  const inputReceived = deferred();
  const relay = await createVScreenSourceRelay(source, {
    createToken: () => TOKEN_A,
    claimTimeoutMs: 1_000,
    onInput(event) { inputEvents.push(event); inputReceived.resolve(); return true; },
  });
  const connected = await connectVScreenSourceRelay(relay.descriptor, {
    timeoutMs: 1_000,
  });
  const received = new Promise((resolve) => connected.video.once("data", resolve));
  connected.video.resume();
  source.write(Buffer.from("h264-frame"));
  assert.equal((await received).toString(), "h264-frame");
  assert.equal(connected.input({ type: "pointer", sequence: 1 }), true);
  await inputReceived.promise;
  assert.deepEqual(inputEvents, [{ type: "pointer", sequence: 1 }]);
  connected.close();
  await relay.close("test_closed");
  await assert.rejects(
    connectVScreenSourceRelay({ ...relay.descriptor, host: "0.0.0.0" }),
    /descriptor is invalid/,
  );
}

{
  const calls = [];
  const relayVideo = new PassThrough();
  const descriptor = {
    protocolVersion: VSCREEN_PROTOCOL_VERSION,
    sourceHandle: SOURCE_A,
    targetRef: "fixture-target",
    codec: "h264",
    display: { id: 42, width: 720, height: 1560, dpi: 320 },
    capabilities: ["video/h264", "pointer-v1"],
    foreground: "home",
    transport: {
      protocolVersion: 1,
      host: "127.0.0.1",
      port: 12345,
      token: TOKEN_A,
    },
  };
  const remote = createRemoteVScreenProducer(
    { runtime: { gateway: { request() {} } } },
    {
      id: "fixture.vscreen",
      methods: {
        ensure: "fixture.ensure",
        workload: "fixture.workload",
        presented: "fixture.presented",
        close: "fixture.close",
        probe: "fixture.probe",
      },
    },
    {
      async request(method, params, options) {
        calls.push([method, params, options]);
        if (["fixture.ensure", "fixture.workload", "fixture.probe"].includes(method)) {
          return descriptor;
        }
        return { protocolVersion: VSCREEN_PROTOCOL_VERSION, status: "ready" };
      },
      async connectRelay(received) {
        assert.equal(received.token, TOKEN_A);
        return {
          video: relayVideo,
          closed: deferred().promise,
          input() {},
          close() {},
        };
      },
    },
  );
  const source = await remote.ensureTarget({ ownerKey: OWNER });
  assert.equal(source.targetRef, "fixture-target");
  assert.equal(
    await remote.placeWorkload({
      requestId: REQUEST_A,
      ownerKey: OWNER,
      source,
    }),
    source,
  );
  await remote.verifyPresented({ requestId: REQUEST_A, ownerKey: OWNER, source });
  await remote.closeTarget(source, "foundation_stopped");
  assert.deepEqual(calls.map(([method]) => method), [
    "fixture.ensure",
    "fixture.workload",
    "fixture.presented",
    "fixture.close",
  ]);

  const failedRemote = createRemoteVScreenProducer(
    { runtime: { gateway: { request() {} } } },
    {
      id: "fixture.failed",
      methods: {
        ensure: "fixture.failed.ensure",
        workload: "fixture.failed.workload",
        presented: "fixture.failed.presented",
        close: "fixture.failed.close",
        probe: "fixture.failed.probe",
      },
    },
    {
      async request() {
        throw new Error("UNAVAILABLE: Android Home did not become active on the VScreen display");
      },
    },
  );
  await assert.rejects(
    failedRemote.ensureTarget({ ownerKey: OWNER }),
    (error) =>
      error.code === VScreenErrorCode.unavailable &&
      error.message === "Android Home did not become active on the VScreen display",
  );
}

{
  const methods = [];
  const routes = [];
  const services = [];
  const subscriptions = [];
  const events = [];
  const producerCalls = [];
  const workloadRegistry = new VScreenWorkloadRegistry();
  const producerBinding = {
    id: "fixture.vscreen",
    pluginId: "claw-in-one-android-developer-bridge",
    assignmentPluginIds: ["claw-in-one-android-developer-bridge", "claw-in-one-android-use"],
    methods: {
      ensure: "fixture.ensure",
      workload: "fixture.workload",
      presented: "fixture.presented",
      close: "fixture.close",
      probe: "fixture.probe",
    },
  };
  const sourceClosed = deferred();
  const sourceDescriptor = {
    protocolVersion: VSCREEN_PROTOCOL_VERSION,
    sourceHandle: SOURCE_A,
    targetRef: "fixture-target",
    codec: "h264",
    display: { id: 42, width: 720, height: 1560, dpi: 320 },
    capabilities: ["video/h264", "pointer-v1"],
    foreground: "home",
    transport: {
      protocolVersion: 1,
      host: "127.0.0.1",
      port: 12345,
      token: TOKEN_A,
    },
  };
  const api = {
    agent: {
      events: {
        emitAgentEvent(event) {
          events.push(event);
          return { emitted: true };
        },
        registerAgentEventSubscription(value) {
          subscriptions.push(value);
        },
      },
    },
    runtime: {
      state: { resolveStateDir: () => "/tmp/vscreen-foundation-test" },
      gateway: {
        async request(method, params) {
          producerCalls.push([method, params]);
          return sourceDescriptor;
        },
      },
    },
    registerService(value) {
      services.push(value);
    },
    registerGatewayMethod(name, handler, options) {
      methods.push({ name, handler, options });
    },
    registerHttpRoute(value) {
      routes.push(value);
    },
  };
  const registered = registerVScreenFoundationPlugin(api, {
    runtimeKey: "registration-test",
    registry: new VScreenProducerRegistry(),
    workloadRegistry,
    producerBindings: [producerBinding],
    async connectRelay() {
      return {
        video: new PassThrough(),
        closed: sourceClosed.promise,
        input() {},
        close() {},
      };
    },
    randomUUID: () => ATTACHMENT_A,
    createToken: () => TOKEN_B,
  });
  assert.equal(services[0].id, "claw-in-one-vscreen-foundation");
  assert.deepEqual(methods.map(({ name }) => name), [
    ...Object.values(VSCREEN_METHODS),
    ...Object.values(VSCREEN_INTERNAL_METHODS),
  ]);
  assert.equal(routes[0].path, "/claw-in-one/vscreen");
  assert.deepEqual(subscriptions.map(({ id }) => id), ["vscreen-workload-lifecycle"]);
  await services[0].start();

  async function invoke(method, params, pluginRuntimeOwnerId) {
    let response;
    await methods.find(({ name }) => name === method).handler({
      params,
      client: pluginRuntimeOwnerId
        ? { internal: { syntheticClient: true, pluginRuntimeOwnerId } }
        : { connect: { device: { id: "fixture-phone" } } },
      respond(...args) {
        response = args;
      },
    });
    return response;
  }

  const ensured = await invoke(VSCREEN_METHODS.ensure, {
    protocolVersion: VSCREEN_PROTOCOL_VERSION,
    producerId: "fixture.vscreen",
  });
  assert.equal(ensured[0], true);
  assert.equal(ensured[1].foreground, "home");

  const offer = {
    protocolVersion: VSCREEN_PROTOCOL_VERSION,
    sessionKey: "agent:main:foundation",
    sessionId: "session-foundation",
    runId: "run-foundation",
    producerId: "fixture.vscreen",
    workloadRequestId: REQUEST_A,
    targetPackage: "com.example.fixture",
    expiresAtMs: Date.now() + 60_000,
    toolCallId: "tool-foundation",
  };
  const unauthorized = await invoke(VSCREEN_INTERNAL_METHODS.assignApp, offer);
  assert.equal(unauthorized[0], false);
  assert.equal(unauthorized[2].details.code, VScreenErrorCode.unauthorized);
  assert.deepEqual(
    await invoke(
      VSCREEN_INTERNAL_METHODS.assignApp,
      offer,
      "claw-in-one-android-developer-bridge",
    ),
    [
      true,
      {
        protocolVersion: VSCREEN_PROTOCOL_VERSION,
        status: "published",
        producerId: "fixture.vscreen",
        workloadRequestId: REQUEST_A,
      },
    ],
  );
  assert.equal(events[0].stream, VSCREEN_AGENT_EVENT_STREAM);

  const placed = await invoke(VSCREEN_METHODS.workload, {
    protocolVersion: VSCREEN_PROTOCOL_VERSION,
    producerId: "fixture.vscreen",
    workloadRequestId: REQUEST_A,
  });
  assert.equal(placed[0], true);
  assert.equal(placed[1].targetGeneration, ensured[1].targetGeneration);
  assert.equal(placed[1].workload.requestId, REQUEST_A);

  subscriptions[0].handle({
    stream: "lifecycle",
    runId: offer.runId,
    data: { phase: "end" },
  });
  assert.equal(workloadRegistry.resolve(REQUEST_A), null);
  assert.equal(
    (await invoke(VSCREEN_METHODS.status, {
      protocolVersion: VSCREEN_PROTOCOL_VERSION,
    }))[1].targetGeneration,
    ensured[1].targetGeneration,
    "run completion retires provenance, not the global display",
  );
  assert.equal(producerCalls[0][0], "fixture.ensure");
  assert.equal(producerCalls[1][0], "fixture.workload");
  await services[0].stop();
}

{
  class CaptureSocket extends Duplex {
    constructor() {
      super();
      this.writes = [];
    }

    _read() {}

    _write(chunk, _encoding, callback) {
      this.writes.push(Buffer.from(chunk));
      callback();
    }
  }

  const video = new PassThrough();
  video.pause();
  let closeCalls = 0;
  const inputEvents = [];
  const route = createVScreenWebSocketRoute(() => ({
    claim(received) {
      assert.equal(received, TOKEN_A);
      return {
        video,
        input(event) { inputEvents.push(event); return true; },
        async close() {
          closeCalls += 1;
        },
      };
    },
  }));
  const headers = {
    authorization: `Bearer ${TOKEN_A}`,
    upgrade: "websocket",
    "sec-websocket-version": "13",
    "sec-websocket-key": "dGhlIHNhbXBsZSBub25jZQ==",
  };
  const socket = new CaptureSocket();
  assert.equal(route.handleUpgrade({ headers }, socket, Buffer.alloc(0)), true);
  assert.match(socket.writes[0].toString("utf8"), /^HTTP\/1\.1 101 Switching Protocols/);
  video.write(Buffer.from("h264"));
  assert.equal(socket.writes.at(-1)[0], 0x82);
  socket.push(maskedWebSocketFrame(0x1, Buffer.from('{"pointer":true}')));
  await new Promise((resolve) => setImmediate(resolve));
  assert.deepEqual(inputEvents, [{ pointer: true }]);
  socket.push(maskedWebSocketFrame(0x8, Buffer.alloc(0)));
  await new Promise((resolve) => setImmediate(resolve));
  assert.equal(closeCalls, 1);

  const unauthorized = new CaptureSocket();
  route.handleUpgrade(
    { headers: { ...headers, authorization: undefined } },
    unauthorized,
    Buffer.alloc(0),
  );
  assert.match(
    Buffer.concat(unauthorized.writes).toString("utf8"),
    /^HTTP\/1\.1 401 Unauthorized/,
  );
}

console.log("VScreen Foundation plugin tests passed.");
