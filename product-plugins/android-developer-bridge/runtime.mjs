import path from "node:path";
import { AndroidDeviceBridge } from "./bridge/device-bridge.mjs";
import {
  ANDROID_DEVICE_REVERSE_PORT_KEY,
  createAndroidDeviceReversePort,
} from "./bridge/reverse-port.mjs";
import { AndroidAppDelivery } from "./app-delivery/device-service.mjs";
import { createArtifactReceiptStore } from "./app-delivery/artifact-service.mjs";
import { createInstallApprovalBroker } from "./app-delivery/install-approval.mjs";
import { AndroidInstalledAppEventStore } from "./app-delivery/install-result.mjs";
import { AndroidAppVScreenAssignmentStore } from "./app-delivery/vscreen-assignment.mjs";
import {
  ANDROID_DEVICE_METHODS,
  ANDROID_DEVICE_BRIDGE_SERVICE_ID,
  AndroidDeviceError,
  AndroidDeviceErrorCode,
  gatewayErrorFor,
  parseConnectParams,
  parseForgetParams,
  parsePairParams,
  parseStatusParams,
  parseVerifyReconnectParams,
} from "./bridge/protocol.mjs";
import { ANDROID_APP_TOOL } from "./app-delivery/tool-protocol.mjs";
import { createAndroidAppTool, createInstallMutationStore } from "./app-delivery/tool.mjs";
import { createAndroidProjectBuildTool } from "./project-build/tool.mjs";
import { ANDROID_PROJECT_BUILD_TOOL } from "./project-build/tool-protocol.mjs";
import { createProjectBuildAuthorizationBroker } from "./project-build/authorization.mjs";
import { createAndroidVScreenProducer } from "./vscreen/producer.mjs";
import { AndroidVScreenDeviceProducer } from "./vscreen/device-producer.mjs";
import { AndroidVScreenReadinessStore } from "./vscreen/readiness.mjs";
import {
  VSCREEN_INTERNAL_METHODS,
  VSCREEN_PROTOCOL_VERSION,
} from "../vscreen-foundation/protocol.mjs";
import {
  ANDROID_VSCREEN_PRODUCER_METHODS,
  VSCREEN_FOUNDATION_PLUGIN_ID,
  createAndroidVScreenRpcAdapter,
  parseVScreenCloseParams,
  parseVScreenEnsureParams,
  parseVScreenPresentedParams,
  parseVScreenProbeParams,
  parseVScreenWorkloadParams,
} from "./vscreen/rpc.mjs";

const PROFILE_ACCESS = "required";
const PROCESS_RUNTIMES_KEY = Symbol.for(
  "io.github.boxuechen.clawinone.android-developer-bridge.process-runtimes.v1",
);
const processRuntimes =
  globalThis[PROCESS_RUNTIMES_KEY] instanceof Map
    ? globalThis[PROCESS_RUNTIMES_KEY]
    : new Map();
if (globalThis[PROCESS_RUNTIMES_KEY] !== processRuntimes) {
  Object.defineProperty(globalThis, PROCESS_RUNTIMES_KEY, {
    value: processRuntimes,
    configurable: false,
    enumerable: false,
    writable: false,
  });
}

function unavailableServiceError() {
  return new AndroidDeviceError(
    AndroidDeviceErrorCode.unavailable,
    "Android development connection is starting",
    { retryable: true },
  );
}

function gatewayHandler(getService, parseParams, invoke) {
  return async ({ params, respond }) => {
    try {
      const parsed = parseParams(params);
      const service = getService();
      if (!service) throw unavailableServiceError();
      respond(true, await invoke(service, parsed));
    } catch (error) {
      respond(false, undefined, gatewayErrorFor(error));
    }
  };
}

function vscreenGatewayHandler(getAdapter, parseParams, invoke) {
  return async ({ params, client, respond }) => {
    try {
      if (
        client?.internal?.syntheticClient !== true ||
        client.internal.pluginRuntimeOwnerId !== VSCREEN_FOUNDATION_PLUGIN_ID
      ) {
        throw new AndroidDeviceError(
          AndroidDeviceErrorCode.vscreenUnauthorized,
          "Android VScreen producer caller is unauthorized",
        );
      }
      respond(true, await invoke(getAdapter(), parseParams(params)));
    } catch (error) {
      respond(false, undefined, gatewayErrorFor(error));
    }
  };
}

function runtimeKeyFor(api, dependencies) {
  if (typeof dependencies.runtimeKey === "string" && dependencies.runtimeKey.trim()) {
    return `explicit:${dependencies.runtimeKey.trim()}`;
  }
  const resolveStateDir = api?.runtime?.state?.resolveStateDir;
  if (typeof resolveStateDir !== "function") return null;
  return `state:${path.resolve(resolveStateDir(process.env))}`;
}

function createProcessRuntime(api, dependencies) {
  const runtime = {
    bridge: null,
    delivery: null,
    deviceProducer: null,
    receiptStore: dependencies.receiptStore ?? createArtifactReceiptStore(dependencies),
    mutationStore: dependencies.mutationStore ?? createInstallMutationStore(),
    installedEventStore:
      dependencies.installedEventStore ?? new AndroidInstalledAppEventStore({
        ...(dependencies.now ? { now: dependencies.now } : {}),
      }),
    approvalBroker: null,
    projectBuildAuthorizationBroker:
      dependencies.projectBuildAuthorizationBroker ??
      createProjectBuildAuthorizationBroker({
        ...(dependencies.now ? { now: dependencies.now } : {}),
        ...(dependencies.randomUUID ? { randomUUID: dependencies.randomUUID } : {}),
      }),
    assignmentStore: dependencies.assignmentStore ?? new AndroidAppVScreenAssignmentStore({
      ...(dependencies.now ? { now: dependencies.now } : {}),
    }),
    vscreenProducer: null,
    vscreenRpc: null,
    readinessStore:
      dependencies.readinessStore ?? new AndroidVScreenReadinessStore({
        ...(dependencies.vscreenNow ? { now: dependencies.vscreenNow } : {}),
        ...(dependencies.vscreenRandomUUID ? { randomUUID: dependencies.vscreenRandomUUID } : {}),
      }),
    reversePort: null,
  };
  runtime.reversePort =
    dependencies.reversePort ?? createAndroidDeviceReversePort(() => runtime.bridge);
  runtime.approvalBroker =
    dependencies.approvalBroker ??
    createInstallApprovalBroker({
      session: api.runtime.agent.session,
      sessionStore: () => api.config?.session?.store,
      resolveDelivery: () => runtime.delivery,
      receiptStore: runtime.receiptStore,
      ...(dependencies.runTool ? { runTool: dependencies.runTool } : {}),
      ...(dependencies.now ? { now: dependencies.now } : {}),
      ...(dependencies.randomUUID ? { randomUUID: dependencies.randomUUID } : {}),
    });
  runtime.vscreenProducer =
    dependencies.vscreenProducer ??
    createAndroidVScreenProducer({
      resolveBridge: () => runtime.bridge,
      resolveDeviceProducer: () => runtime.deviceProducer,
      readinessStore: runtime.readinessStore,
      ...(dependencies.vscreenSourceRandomUUID ? { randomUUID: dependencies.vscreenSourceRandomUUID } : {}),
    });
  runtime.vscreenRpc =
    dependencies.vscreenRpc ??
    createAndroidVScreenRpcAdapter({
      producer: runtime.vscreenProducer,
      ...(dependencies.vscreenRpcRandomUUID ? { randomUUID: dependencies.vscreenRpcRandomUUID } : {}),
      ...(dependencies.createVScreenSourceRelay
        ? { createRelay: dependencies.createVScreenSourceRelay }
        : {}),
    });
  return runtime;
}

function processRuntimeFor(api, dependencies) {
  const key = runtimeKeyFor(api, dependencies);
  if (!key) return createProcessRuntime(api, dependencies);
  const existing = processRuntimes.get(key);
  if (existing && !existing.approvalBroker.closed) return existing;
  const created = createProcessRuntime(api, dependencies);
  processRuntimes.set(key, created);
  return created;
}

export function registerAndroidDeveloperBridgePlugin(api, dependencies = {}) {
  const bridgeRuntimeEnabled =
    dependencies.bridgeRuntimeEnabled ??
    api.pluginConfig?.runtime === "android-avf";
  const createBridge =
    dependencies.createBridge ??
    ((options) => new AndroidDeviceBridge(options));
  const createDelivery =
    dependencies.createDelivery ??
    ((options) => new AndroidAppDelivery(options));
  const createDeviceProducer =
    dependencies.createDeviceProducer ??
    ((options) => new AndroidVScreenDeviceProducer(options));
  const processRuntime = processRuntimeFor(api, dependencies);
  const {
    receiptStore,
    mutationStore,
    installedEventStore,
    approvalBroker,
    projectBuildAuthorizationBroker,
    readinessStore,
    reversePort,
  } = processRuntime;
  globalThis[ANDROID_DEVICE_REVERSE_PORT_KEY] = reversePort;
  let ownedBridge = null;
  let ownedDeviceProducer = null;

  api.registerService({
    id: ANDROID_DEVICE_BRIDGE_SERVICE_ID,
    async start(context) {
      // The real Bridge belongs to the phone's AVF runtime. A desktop Gateway may
      // load the product Plugin for development, but must never start a second host
      // ADB server that can claim the developer's USB device.
      if (!bridgeRuntimeEnabled) return;
      const nextBridge = createBridge({
        stateDir: path.join(context.stateDir, "android-developer-bridge"),
      });
      const nextDelivery = createDelivery({ bridge: nextBridge, ...(dependencies.now ? { now: dependencies.now } : {}) });
      const nextDeviceProducer = createDeviceProducer({
        bridge: nextBridge,
        ...(dependencies.startVScreenHelper ? { startVScreenHelper: dependencies.startVScreenHelper } : {}),
        ...(dependencies.vscreenSleep ? { sleep: dependencies.vscreenSleep } : {}),
      });
      try {
        await nextBridge.start();
        ownedBridge = nextBridge;
        ownedDeviceProducer = nextDeviceProducer;
        processRuntime.bridge = nextBridge;
        processRuntime.delivery = nextDelivery;
        processRuntime.deviceProducer = nextDeviceProducer;
        nextBridge.onBindingInvalidated(() => reversePort.invalidate());
      } catch (error) {
        await nextDeviceProducer.close().catch(() => {});
        await nextBridge.stop().catch(() => {});
        throw error;
      }
    },
    async stop() {
      const activeBridge = ownedBridge;
      const activeDeviceProducer = ownedDeviceProducer;
      ownedBridge = null;
      ownedDeviceProducer = null;
      if (!activeBridge) return;
      if (processRuntime.bridge === activeBridge) {
        processRuntime.bridge = null;
        processRuntime.delivery = null;
        processRuntime.deviceProducer = null;
        approvalBroker.close();
        projectBuildAuthorizationBroker.close();
        receiptStore.clearAll();
        readinessStore.clearAll();
        reversePort.invalidate();
        await processRuntime.vscreenRpc.closeAll();
      }
      await activeDeviceProducer?.close().catch(() => {});
      await activeBridge.stop();
    },
  });

  const getBridge = () => processRuntime.bridge;
  api.registerGatewayMethod(
    ANDROID_DEVICE_METHODS.status,
    gatewayHandler(getBridge, parseStatusParams, (active) => active.status()),
    { scope: "operator.read", profileAccess: PROFILE_ACCESS },
  );
  const vscreenMethods = [
    [ANDROID_VSCREEN_PRODUCER_METHODS.ensure, parseVScreenEnsureParams, (adapter, params) => adapter.ensure(params)],
    [ANDROID_VSCREEN_PRODUCER_METHODS.workload, parseVScreenWorkloadParams, (adapter, params) => adapter.workload(params)],
    [ANDROID_VSCREEN_PRODUCER_METHODS.presented, parseVScreenPresentedParams, (adapter, params) => adapter.presented(params)],
    [ANDROID_VSCREEN_PRODUCER_METHODS.close, parseVScreenCloseParams, (adapter, params) => adapter.close(params)],
    [ANDROID_VSCREEN_PRODUCER_METHODS.probe, parseVScreenProbeParams, (adapter, params) => adapter.probe(params)],
  ];
  for (const [method, parse, invoke] of vscreenMethods) {
    api.registerGatewayMethod(
      method,
      vscreenGatewayHandler(() => processRuntime.vscreenRpc, parse, invoke),
      { scope: "operator.write", profileAccess: PROFILE_ACCESS },
    );
  }
  api.registerGatewayMethod(
    ANDROID_DEVICE_METHODS.pair,
    gatewayHandler(getBridge, parsePairParams, (active, params) => active.pair(params)),
    { scope: "operator.write", profileAccess: PROFILE_ACCESS },
  );
  api.registerGatewayMethod(
    ANDROID_DEVICE_METHODS.connect,
    gatewayHandler(getBridge, parseConnectParams, (active, params) => active.connect(params)),
    { scope: "operator.write", profileAccess: PROFILE_ACCESS },
  );
  api.registerGatewayMethod(
    ANDROID_DEVICE_METHODS.verifyReconnect,
    gatewayHandler(getBridge, parseVerifyReconnectParams, (active, params) =>
      active.verifyReconnect(params),
    ),
    { scope: "operator.write", profileAccess: PROFILE_ACCESS },
  );
  api.registerGatewayMethod(
    ANDROID_DEVICE_METHODS.forget,
    gatewayHandler(getBridge, parseForgetParams, (active) => active.forget()),
    { scope: "operator.write", profileAccess: PROFILE_ACCESS },
  );

  api.registerTool(
    (context) =>
      createAndroidAppTool(() => processRuntime.delivery, context, {
        receiptStore,
        mutationStore,
        approvalBroker,
        readinessStore,
        ...(dependencies.runTool ? { runTool: dependencies.runTool } : {}),
      }),
    { name: ANDROID_APP_TOOL },
  );
  api.registerTool(
    (context) =>
      createAndroidProjectBuildTool(context, {
        approvalBroker: projectBuildAuthorizationBroker,
        ...(dependencies.projectBuildRunner ? { runBuilder: dependencies.projectBuildRunner } : {}),
        ...(dependencies.reactNativeProfileBuilder
          ? { profileBuilder: dependencies.reactNativeProfileBuilder }
          : {}),
      }),
    { name: ANDROID_PROJECT_BUILD_TOOL },
  );
  api.on(
    "before_tool_call",
    (event, context) => projectBuildAuthorizationBroker.beforeToolCall(event, context),
    { matcher: [ANDROID_PROJECT_BUILD_TOOL] },
  );
  api.on(
    "before_tool_call",
    async (event, context) => {
      const decision = await approvalBroker.beforeToolCall(event, context);
      if (decision?.block !== true) {
        installedEventStore.claimToolCall(event, context);
        processRuntime.assignmentStore.claimToolCall(event, context);
      }
      return decision;
    },
    { matcher: [ANDROID_APP_TOOL] },
  );
  api.agent.events.registerAgentEventSubscription({
    id: "android-app-delivery",
    description: "Publish exact successful installs and optional VScreen workloads to ClawInOne clients",
    streams: ["tool", "lifecycle"],
    async handle(event) {
      if (event.stream === "lifecycle") {
        if (["end", "error"].includes(event.data?.phase)) {
          processRuntime.assignmentStore.clearRun(event.runId);
          installedEventStore.clearRun(event.runId);
        }
        return;
      }
      const installedEvent = installedEventStore.eventFromToolResult(event);
      if (installedEvent) {
        try {
          const emitted = api.agent.events.emitAgentEvent(installedEvent);
          if (emitted?.emitted !== true) throw new Error("event publisher is unavailable");
        } catch (error) {
          api.logger.warn(`Android installed app result was not emitted: ${error?.message ?? "unavailable"}`);
        }
      }
      const publication = processRuntime.assignmentStore.assignmentFromToolResult(event);
      if (!publication) return;
      try {
        const result = await api.runtime.gateway.request(
          VSCREEN_INTERNAL_METHODS.assignApp,
          { protocolVersion: VSCREEN_PROTOCOL_VERSION, ...publication },
          { timeoutMs: 5_000 },
        );
        if (result?.status !== "published") throw new Error("VScreen workload was not acknowledged");
      } catch (error) {
        api.logger.warn(`Android VScreen workload was not emitted: ${error?.message ?? "unavailable"}`);
      }
    },
  });
  api.on("agent_end", (event, context) => {
    processRuntime.assignmentStore.clearRun(event?.runId ?? context?.runId);
    installedEventStore.clearRun(event?.runId ?? context?.runId);
    approvalBroker.endRun(event, context);
    projectBuildAuthorizationBroker.endRun(event, context);
  });
}
