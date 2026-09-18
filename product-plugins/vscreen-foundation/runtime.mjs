import path from "node:path";
import {
  VSCREEN_FOUNDATION_SERVICE_ID,
  VSCREEN_INTERNAL_METHODS,
  VSCREEN_METHODS,
  VSCREEN_STREAM_ROUTE,
  parsePreviewProbeFinishParams,
  parsePreviewProbeStartParams,
  parsePreviewProbeStatusParams,
  parseVScreenCloseParams,
  parseVScreenEnsureParams,
  parseVScreenFramePresentedParams,
  parseVScreenStatusParams,
  parseVScreenAppAssignmentParams,
  parseVScreenWorkloadParams,
  VScreenError,
  VScreenErrorCode,
  vscreenGatewayError,
  vscreenOwnerKey,
} from "./protocol.mjs";
import { VScreenProducerRegistry } from "./producer-registry.mjs";
import { createRemoteVScreenProducer } from "./remote-producer.mjs";
import { VScreenDisplayService } from "./display-service.mjs";
import { VScreenWorkloadRegistry } from "./workload-registry.mjs";
import { createVScreenWebSocketRoute } from "./websocket.mjs";

const PROFILE_ACCESS = "required";
const PROCESS_RUNTIMES_KEY = Symbol.for(
  "io.github.boxuechen.clawinone.vscreen-foundation.runtimes.v3",
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

function runtimeKeyFor(api, dependencies) {
  if (typeof dependencies.runtimeKey === "string" && dependencies.runtimeKey.trim()) {
    return `explicit:${dependencies.runtimeKey.trim()}`;
  }
  const resolveStateDir = api?.runtime?.state?.resolveStateDir;
  if (typeof resolveStateDir !== "function") return null;
  return `state:${path.resolve(resolveStateDir(process.env))}`;
}

function runtimeFor(api, dependencies) {
  const key = runtimeKeyFor(api, dependencies);
  const create = () => {
    const registry = dependencies.registry ?? new VScreenProducerRegistry();
    const bindings = new Map();
    const callers = new Map();
    for (const binding of dependencies.producerBindings ?? []) {
      if (
        typeof binding?.pluginId !== "string" ||
        !binding.pluginId.trim() ||
        !binding.methods ||
        ["ensure", "workload", "presented", "close", "probe"].some(
          (name) => typeof binding.methods[name] !== "string" || !binding.methods[name],
        ) ||
        !Array.isArray(binding.assignmentPluginIds) ||
        binding.assignmentPluginIds.some((id) => typeof id !== "string" || !id.trim())
      ) {
        throw new Error("VScreen Foundation producer binding is invalid");
      }
      const normalized = Object.freeze({
        ...binding,
        pluginId: binding.pluginId.trim(),
        assignmentPluginIds: Object.freeze(binding.assignmentPluginIds.map((id) => id.trim())),
      });
      if (bindings.has(normalized.id)) {
        throw new Error(`VScreen Foundation producer ${normalized.id} is duplicated`);
      }
      const producer = createRemoteVScreenProducer(null, normalized, {
        ...dependencies,
        request(method, params, options) {
          const caller = callers.get(normalized.id);
          if (!caller) {
            throw new VScreenError(
              VScreenErrorCode.unavailable,
              "The VScreen producer bridge is unavailable",
              { retryable: true },
            );
          }
          return caller.request(method, params, options);
        },
      });
      registry.register(producer);
      bindings.set(producer.id, normalized);
    }
    const workloads = dependencies.workloadRegistry ?? new VScreenWorkloadRegistry();
    return {
      registry,
      bindings,
      callers,
      workloads,
      service:
        dependencies.service ??
        new VScreenDisplayService({
          registry,
          workloads,
          ...(dependencies.now ? { now: dependencies.now } : {}),
          ...(dependencies.randomUUID ? { randomUUID: dependencies.randomUUID } : {}),
          ...(dependencies.createToken ? { createToken: dependencies.createToken } : {}),
        }),
      activeGeneration: 0,
    };
  };
  if (!key) return create();
  const existing = processRuntimes.get(key);
  if (existing) return existing;
  const created = create();
  processRuntimes.set(key, created);
  return created;
}

function internalCallerId(client) {
  return client?.internal?.syntheticClient === true &&
    typeof client.internal.pluginRuntimeOwnerId === "string"
    ? client.internal.pluginRuntimeOwnerId
    : null;
}

function requireInternalCaller(client, expectedPluginId) {
  if (internalCallerId(client) !== expectedPluginId) {
    throw new VScreenError(
      VScreenErrorCode.unauthorized,
      "VScreen internal caller is unauthorized",
    );
  }
}

function internalGatewayHandler(parse, invoke) {
  return async ({ params, client, respond }) => {
    try {
      respond(true, await invoke(parse(params), client));
    } catch (error) {
      respond(false, undefined, vscreenGatewayError(error));
    }
  };
}

function gatewayHandler(parse, invoke) {
  return async ({ params, client, respond }) => {
    try {
      const parsed = parse(params);
      const ownerKey = vscreenOwnerKey(client);
      respond(true, await invoke(ownerKey, parsed));
    } catch (error) {
      respond(false, undefined, vscreenGatewayError(error));
    }
  };
}

export function registerVScreenFoundationPlugin(api, dependencies = {}) {
  const runtime = runtimeFor(api, dependencies);
  const service = runtime.service;
  const ownedGeneration = ++runtime.activeGeneration;
  for (const producerId of runtime.bindings.keys()) {
    runtime.callers.set(producerId, {
      generation: ownedGeneration,
      request: (method, params, options) =>
        api.runtime.gateway.request(method, params, options),
    });
  }
  let publisherRegistration = null;
  api.registerService({
    id: VSCREEN_FOUNDATION_SERVICE_ID,
    async start() {
      api.logger?.info?.(
        `VScreen Foundation started with ${service.registry?.entries?.size ?? 0} producer(s)`,
      );
      publisherRegistration = runtime.workloads.registerPublisher(
        (event) => api.agent.events.emitAgentEvent(event),
      );
    },
    async stop() {
      publisherRegistration?.dispose();
      publisherRegistration = null;
      if (runtime.activeGeneration !== ownedGeneration) return;
      runtime.activeGeneration += 1;
      for (const [producerId, caller] of runtime.callers) {
        if (caller.generation === ownedGeneration) runtime.callers.delete(producerId);
      }
      await service.closeAll();
    },
  });

  const methods = [
    [VSCREEN_METHODS.status, parseVScreenStatusParams, (owner) => service.status(owner), "operator.read"],
    [
      VSCREEN_METHODS.ensure,
      parseVScreenEnsureParams,
      (owner, params) => service.ensure(owner, params),
      "operator.write",
    ],
    [
      VSCREEN_METHODS.close,
      parseVScreenCloseParams,
      (owner, params) => service.close(owner, params),
      "operator.write",
    ],
    [
      VSCREEN_METHODS.workload,
      parseVScreenWorkloadParams,
      (owner, params) => service.placeWorkload(owner, params),
      "operator.write",
    ],
    [
      VSCREEN_METHODS.framePresented,
      parseVScreenFramePresentedParams,
      (owner, params) => service.framePresented(owner, params),
      "operator.write",
    ],
    [
      VSCREEN_METHODS.probeStatus,
      parsePreviewProbeStatusParams,
      (owner, params) => service.probeStatus(owner, params),
      "operator.read",
    ],
    [
      VSCREEN_METHODS.probeStart,
      parsePreviewProbeStartParams,
      (owner, params) => service.startProbe(owner, params),
      "operator.write",
    ],
    [
      VSCREEN_METHODS.probeFinish,
      parsePreviewProbeFinishParams,
      (owner, params) => service.finishProbe(owner, params),
      "operator.write",
    ],
  ];
  for (const [name, parse, invoke, scope] of methods) {
    api.registerGatewayMethod(name, gatewayHandler(parse, invoke), {
      scope,
      profileAccess: PROFILE_ACCESS,
    });
  }

  api.registerGatewayMethod(
    VSCREEN_INTERNAL_METHODS.assignApp,
    internalGatewayHandler(parseVScreenAppAssignmentParams, (params, client) => {
      const binding = runtime.bindings.get(params.producerId);
      if (!binding) {
        throw new VScreenError(
          VScreenErrorCode.unavailable,
          "The VScreen producer binding is unavailable",
        );
      }
      const callerId = internalCallerId(client);
      if (!binding.assignmentPluginIds?.includes(callerId)) {
        throw new VScreenError(
          VScreenErrorCode.unauthorized,
          "VScreen app assignment caller is unauthorized",
        );
      }
      const publication = runtime.workloads.publish(params);
      if (!publication.emitted) {
        throw new VScreenError(
          VScreenErrorCode.unavailable,
          publication.reason ?? "VScreen workload delivery is unavailable",
          { retryable: true },
        );
      }
      return {
        protocolVersion: 3,
        status: "published",
        producerId: params.producerId,
        workloadRequestId: params.workloadRequestId,
      };
    }),
    { scope: "operator.write", profileAccess: PROFILE_ACCESS },
  );

  api.agent.events.registerAgentEventSubscription({
    id: "vscreen-workload-lifecycle",
    description: "Retire workload provenance when its exact Agent run ends",
    streams: ["lifecycle"],
    handle(event) {
      if (["end", "error"].includes(event.data?.phase)) {
        runtime.workloads.retireOrigin(event.runId);
      }
    },
  });

  const route = createVScreenWebSocketRoute(() => service);
  api.registerHttpRoute({
    path: VSCREEN_STREAM_ROUTE,
    auth: "plugin",
    match: "exact",
    handler: route.handler,
    handleUpgrade: route.handleUpgrade,
  });
  return {
    service,
    registry: runtime.registry,
    workloads: runtime.workloads,
  };
}
