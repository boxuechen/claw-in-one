import { randomUUID } from "node:crypto";
import path from "node:path";
import {
  VSCREEN_INTERNAL_METHODS,
  VSCREEN_PROTOCOL_VERSION,
} from "../vscreen-foundation/protocol.mjs";
import { ANDROID_USE_AUTHORIZATION_NONCE, createAndroidUseAuthorization } from "./tool-authorization.mjs";

export const ANDROID_USE_COMMAND = "claw.android_use";
export const ANDROID_USE_TOOL = "android_use";
export const ANDROID_USE_PROTOCOL_VERSION = 8;

const NODE_OPERATION_TIMEOUT_MS = 30000;
const NODE_WAIT_OPERATION_TIMEOUT_MS = 15000;
const INVOKE_TIMEOUT_BUFFER_MS = 5000;
const ANDROID_USE_OPERATIONS = new Set([
  "observe",
  "activate",
  "set_text",
  "scroll",
  "tap",
  "swipe",
  "back",
  "wait",
  "stop",
]);
const UUID_PATTERN = /^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;
const OPAQUE_KEY_PATTERN = /^[0-9a-f]{64}$/;
const ANDROID_PACKAGE_PATTERN = /^[A-Za-z][A-Za-z0-9_]*(?:\.[A-Za-z0-9_]+)+$/;
const RUNTIMES_KEY = Symbol.for("io.github.boxuechen.clawinone.android-use.authorization");
const processRuntimes = globalThis[RUNTIMES_KEY] ??= new Map();
const DISPLAYS = new Set(["main", "vscreen"]);
const TOOL_PARAMETERS = {
  type: "object",
  additionalProperties: false,
  required: ["operation", "targetPackage", "display"],
  properties: {
    operation: {
      type: "string",
      enum: ["observe", "activate", "set_text", "scroll", "tap", "swipe", "back", "wait", "stop"],
      description: "Observe the approved target, perform one bounded action, wait, or stop the control lease.",
    },
    targetPackage: {
      type: "string",
      minLength: 1,
      maxLength: 255,
      description: "Exact Android package. Required by every call and fixed for the approved control session.",
    },
    display: {
      type: "string",
      enum: ["main", "vscreen"],
      description: "Where to operate the exact app. main uses the phone screen; vscreen presents it on VScreen first.",
    },
    controlId: {
      type: "string",
      minLength: 36,
      maxLength: 36,
      description: "Opaque continuation ID returned by the first observe. Required by every later call, including stop.",
    },
    snapshotId: {
      type: "string",
      minLength: 1,
      description: "The latest snapshotId returned by observe. Re-observe after every UI change.",
    },
    ref: {
      type: "string",
      minLength: 1,
      description: "A semantic node ref from the same latest observation.",
    },
    text: { type: "string", description: "Unicode text for set_text, including Chinese text." },
    direction: { type: "string", enum: ["forward", "backward"], description: "Semantic scroll direction." },
    x: { type: "integer", minimum: 0, description: "Fallback screen x coordinate for tap." },
    y: { type: "integer", minimum: 0, description: "Fallback screen y coordinate for tap." },
    x1: { type: "integer", minimum: 0, description: "Fallback swipe start x coordinate." },
    y1: { type: "integer", minimum: 0, description: "Fallback swipe start y coordinate." },
    x2: { type: "integer", minimum: 0, description: "Fallback swipe end x coordinate." },
    y2: { type: "integer", minimum: 0, description: "Fallback swipe end y coordinate." },
    durationMs: { type: "integer", minimum: 1, maximum: 10000, description: "Swipe duration in milliseconds." },
    ms: { type: "integer", minimum: 0, maximum: 10000, description: "Bounded wait duration in milliseconds." },
    [ANDROID_USE_AUTHORIZATION_NONCE]: { type: "string", description: "Internal host authorization. Never provide this field yourself." },
  },
};

function isRecord(value) {
  return value !== null && typeof value === "object" && !Array.isArray(value);
}

function requiredString(params, name) {
  const value = params[name];
  if (typeof value !== "string" || value.trim().length === 0) {
    throw new Error(`android_use requires ${name} for ${params.operation}`);
  }
  return value;
}

function requiredInteger(params, name, minimum = Number.MIN_SAFE_INTEGER, maximum = Number.MAX_SAFE_INTEGER) {
  const value = params[name];
  if (!Number.isSafeInteger(value) || value < minimum || value > maximum) {
    throw new Error(`android_use requires integer ${name} in [${minimum}, ${maximum}] for ${params.operation}`);
  }
  return value;
}

function targetPackage(value) {
  if (typeof value !== "string") throw new Error("android_use requires targetPackage for every call");
  const normalized = value.trim();
  if (normalized.length > 255 || !ANDROID_PACKAGE_PATTERN.test(normalized)) {
    throw new Error("android_use requires an exact Android targetPackage");
  }
  return normalized;
}

function controlId(value) {
  if (typeof value !== "string" || !UUID_PATTERN.test(value.trim())) {
    throw new Error("android_use requires the controlId returned by the first observe");
  }
  return value.trim();
}

function display(value) {
  if (!DISPLAYS.has(value)) throw new Error("android_use requires display main or vscreen for every call");
  return value;
}

function actionRequest(params) {
  switch (params.operation) {
    case "observe":
    case "stop":
      return {};
    case "activate":
      return {
        snapshotId: requiredString(params, "snapshotId"),
        action: { type: "activate", ref: requiredString(params, "ref") },
      };
    case "set_text":
      return {
        snapshotId: requiredString(params, "snapshotId"),
        action: {
          type: "set_text",
          ref: requiredString(params, "ref"),
          text: typeof params.text === "string" ? params.text : requiredString(params, "text"),
        },
      };
    case "scroll":
      if (params.direction !== "forward" && params.direction !== "backward") {
        throw new Error("android_use requires direction forward or backward for scroll");
      }
      return {
        snapshotId: requiredString(params, "snapshotId"),
        action: {
          type: "scroll",
          ref: requiredString(params, "ref"),
          direction: params.direction,
        },
      };
    case "tap":
      return {
        snapshotId: requiredString(params, "snapshotId"),
        action: { type: "tap", x: requiredInteger(params, "x", 0), y: requiredInteger(params, "y", 0) },
      };
    case "swipe":
      return {
        snapshotId: requiredString(params, "snapshotId"),
        action: {
          type: "swipe",
          x1: requiredInteger(params, "x1", 0),
          y1: requiredInteger(params, "y1", 0),
          x2: requiredInteger(params, "x2", 0),
          y2: requiredInteger(params, "y2", 0),
          durationMs: requiredInteger(params, "durationMs", 1, 10000),
        },
      };
    case "back":
      return {
        snapshotId: requiredString(params, "snapshotId"),
        action: { type: "global_action", name: "back" },
      };
    case "wait":
      return {
        snapshotId: requiredString(params, "snapshotId"),
        action: { type: "wait", ms: requiredInteger(params, "ms", 0, 10000) },
      };
    default:
      throw new Error(`unsupported android_use operation: ${String(params.operation)}`);
  }
}

function parseNodePayload(raw) {
  const result = isRecord(raw) ? raw : null;
  if (isRecord(result?.payload)) return result.payload;
  if (typeof result?.payloadJSON === "string") {
    const parsed = JSON.parse(result.payloadJSON);
    if (isRecord(parsed)) return parsed;
  }
  throw new Error("Android node returned an invalid android_use payload");
}

function selectAndroidNode(nodes) {
  const candidates = nodes.filter((node) => {
    if (node?.connected !== true || node?.platform !== "android") return false;
    const commands = Array.isArray(node.invocableCommands) ? node.invocableCommands : node.commands;
    return Array.isArray(commands) && commands.includes(ANDROID_USE_COMMAND) &&
      Array.isArray(node.caps) && node.caps.includes("clawAndroidUseConsentV1");
  });
  if (candidates.length === 0) {
    throw new Error("Enable Android Use for current and future Chats on this phone, then check its connection");
  }
  if (candidates.length !== 1) {
    throw new Error("Android Use requires exactly one connected eligible phone");
  }
  return candidates[0];
}

export function parseAndroidUseRequest(rawParams) {
  if (!isRecord(rawParams) || !ANDROID_USE_OPERATIONS.has(rawParams.operation)) {
    throw new Error(`unsupported android_use operation: ${String(rawParams?.operation)}`);
  }
  for (const key of Object.keys(rawParams)) {
    if (!Object.hasOwn(TOOL_PARAMETERS.properties, key)) throw new Error(`android_use does not accept ${key}`);
  }
  const acquire = rawParams.operation === "observe" && rawParams.controlId === undefined;
  return {
    operation: rawParams.operation,
    acquire,
    controlId: acquire ? null : controlId(rawParams.controlId),
    targetPackage: targetPackage(rawParams.targetPackage),
    display: display(rawParams.display),
    request: actionRequest(rawParams),
  };
}

export function createAndroidUseTool(api, context, dependencies = {}) {
  const sessionKey = context?.sessionKey?.trim();
  if (!sessionKey) return null;

  const authorization = dependencies.authorization;
  if (!authorization) throw new Error("Android Use host authorization is unavailable");

  return {
    name: ANDROID_USE_TOOL,
    label: "Android Use",
    description:
      "Observe and operate any exact installed launchable Android app requested by the user, using this phone's saved Android Use consent. Choose display=main for the phone screen or display=vscreen to present the app on the independent global VScreen before control. APK installation, VScreen presentation, and Android Use authority remain separate. First call observe with targetPackage, display, and no controlId. Pass the returned controlId plus the same targetPackage and display to every later call. Prefer semantic refs over coordinates and observe again after every UI change. Finish normally after the final observation; call stop only when the user explicitly asks to end Android Use. Stop never closes or hides VScreen.",
    parameters: TOOL_PARAMETERS,
    async execute(toolCallId, rawParams, signal) {
      return await authorization.runTool(toolCallId, rawParams, context, signal, async ({ request, control, signal: admittedSignal, dispatch }) => {
        if (request.acquire && control.display === "vscreen" && !control.assignmentId) {
          control.assignmentId = await dependencies.assignVScreenApp(control, admittedSignal);
        }
        const listed = await api.runtime.nodes.list({ connected: true });
        const node = selectAndroidNode(listed.nodes);
        const envelope = controlEnvelope(control, request);
        const raw = await dispatch(node.nodeId, envelope, (params) => api.runtime.nodes.invoke({
          nodeId: node.nodeId,
          command: ANDROID_USE_COMMAND,
          params,
          timeoutMs: (request.operation === "wait" ? NODE_WAIT_OPERATION_TIMEOUT_MS : NODE_OPERATION_TIMEOUT_MS) + INVOKE_TIMEOUT_BUFFER_MS,
          idempotencyKey: (dependencies.randomUUID ?? randomUUID)(),
          sessionKey,
          signal: admittedSignal,
        }));
        const payload = parseNodePayload(raw);
        if (request.operation === "stop" && payload.status !== "stopped") {
          throw new Error("Android Use native stop is unconfirmed");
        }
        const result = request.operation === "stop" ? payload : {
          ...payload,
          controlId: control.controlId,
          targetPackage: control.targetPackage,
          display: control.display,
        };
        return { content: [{ type: "text", text: JSON.stringify(result) }], details: result };
      });
    },
  };
}

function validEnvelope(params) {
  return (
    isRecord(params) &&
    params.protocolVersion === ANDROID_USE_PROTOCOL_VERSION &&
    typeof params.acquire === "boolean" &&
    (ANDROID_USE_OPERATIONS.has(params.operation) || ["release", "revoke"].includes(params.operation)) &&
    typeof params.controlId === "string" &&
    UUID_PATTERN.test(params.controlId) &&
    typeof params.ownerKey === "string" &&
    OPAQUE_KEY_PATTERN.test(params.ownerKey) &&
    typeof params.executionKey === "string" && UUID_PATTERN.test(params.executionKey) &&
    typeof params.sessionId === "string" && params.sessionId.length > 0 &&
    typeof params.runId === "string" && params.runId.length > 0 &&
    typeof params.authorizationNonce === "string" && UUID_PATTERN.test(params.authorizationNonce) &&
    typeof params.targetPackage === "string" &&
    params.targetPackage.length <= 255 &&
    ANDROID_PACKAGE_PATTERN.test(params.targetPackage) &&
    DISPLAYS.has(params.display) &&
    ((params.display === "main" && params.assignmentId === null) ||
      (params.display === "vscreen" && typeof params.assignmentId === "string" && UUID_PATTERN.test(params.assignmentId))) &&
    isRecord(params.request)
  );
}

export function createAndroidUseNodePolicy(authorization) {
  return {
    commands: [ANDROID_USE_COMMAND],
    dangerous: true,
    classifyRisk: ({ command, params }) => {
      if (command !== ANDROID_USE_COMMAND || !validEnvelope(params)) {
        throw new Error("invalid Android Use envelope");
      }
      const lifecycle = ["stop", "release", "revoke"].includes(params.operation);
      const family = params.operation === "observe" ? "observation" : lifecycle ? "execution_lifecycle" : "input";
      return { level: params.operation === "observe" || lifecycle ? "ordinary" : "high", family };
    },
    handle: async (context) => {
      if (!validEnvelope(context.params)) {
        return { ok: false, code: "ANDROID_USE_INVALID_ENVELOPE", message: "Invalid Android Use envelope" };
      }
      if (context.params.acquire && context.params.operation !== "observe") {
        return {
          ok: false,
          code: "ANDROID_USE_ACQUIRE_REQUIRES_OBSERVE",
          message: "Android Use control must begin with an observation",
        };
      }
      return await authorization.handleNode(context);
    },
  };
}

export function registerAndroidUsePlugin(api, dependencies = {}) {
  if (typeof dependencies.publishControlNotice !== "function") {
    throw new Error("Android Use requires the scoped transcript notice publisher");
  }
  const stateDir = api.runtime.state.resolveStateDir(process.env);
  const key = path.resolve(stateDir);
  let authorization = processRuntimes.get(key);
  if (!authorization || authorization.closed) {
    authorization = createAndroidUseAuthorization(api, {
      ...dependencies,
      parseRequest: parseAndroidUseRequest,
      async assertNativeConsentSupport() {
        const listed = await api.runtime.nodes.list({ connected: true });
        selectAndroidNode(listed.nodes);
      },
      async revokeControl(control, dispatch) {
        const envelope = controlEnvelope(control, { acquire: false, operation: "revoke", request: {} });
        const result = await dispatch(envelope, (params) => api.runtime.nodes.invoke({
          nodeId: control.nodeId,
          command: ANDROID_USE_COMMAND,
          params,
          sessionKey: control.identity.sessionKey,
          idempotencyKey: randomUUID(),
          timeoutMs: 5000,
          signal: AbortSignal.timeout(5000),
        }));
        if (parseNodePayload(result).status !== "stopped") throw new Error("Android Use native revocation is unconfirmed");
      },
      async releaseControl(control, dispatch) {
        const envelope = controlEnvelope(control, { acquire: false, operation: "release", request: {} });
        const result = await dispatch(envelope, (params) => api.runtime.nodes.invoke({
          nodeId: control.nodeId,
          command: ANDROID_USE_COMMAND,
          params,
          sessionKey: control.identity.sessionKey,
          idempotencyKey: randomUUID(),
          timeoutMs: 5000,
          signal: AbortSignal.timeout(5000),
        }));
        if (parseNodePayload(result).status !== "released") throw new Error("Android Use native release is unconfirmed");
      },
      async onRevocationUnconfirmed(control) {
        api.logger.warn("Android Use native revocation is unconfirmed");
        try {
          // The public accessor rechecks exact Session identity under its write lock.
          // This is a product notice, not a new Agent turn or a grant/retry authority.
          const result = await dependencies.publishControlNotice({
            config: api.config,
            agentId: control.identity.agentId,
            sessionKey: control.identity.sessionKey,
            sessionId: control.identity.sessionId,
            storePath: control.storePath,
            idempotencyKey: `claw-in-one:android-use:stop-unconfirmed:${control.controlId}`,
            signal: AbortSignal.timeout(5000),
            text: `ClawInOne · Android Use\n\n停止尚未确认（Stop unconfirmed）：${control.targetPackage}。本次控制的后续调用已被撤销，但尚未收到手机释放本次控制的确认。请使用手机上的“停止控制”或关闭 Android Use 无障碍服务；不要把此提示视为已停止，也不要重试刚才的操作。`,
          });
          if (!result?.ok && result?.code !== "session-rebound") {
            api.logger.warn("Android Use could not publish its stop-unconfirmed notice");
          }
        } catch {
          api.logger.warn("Android Use could not publish its stop-unconfirmed notice");
        }
      },
    });
    processRuntimes.set(key, authorization);
  }
  api.registerNodeInvokePolicy(createAndroidUseNodePolicy(authorization));
  const assignVScreenApp =
    dependencies.assignVScreenApp ??
    (async (control, signal) => {
      signal.throwIfAborted();
      const assignmentId = (dependencies.randomUUID ?? randomUUID)().toLowerCase();
      if (!UUID_PATTERN.test(assignmentId)) throw new Error("Android Use VScreen assignment identity is unavailable");
      const result = await api.runtime.gateway.request(
        VSCREEN_INTERNAL_METHODS.assignApp,
        {
          protocolVersion: VSCREEN_PROTOCOL_VERSION,
          producerId: "claw-in-one-android-vscreen-producer",
          workloadRequestId: assignmentId,
          targetPackage: control.targetPackage,
          sessionKey: control.identity.sessionKey,
          sessionId: control.identity.sessionId,
          runId: control.identity.runId,
          toolCallId: control.identity.toolCallId,
          expiresAtMs: Date.now() + 5 * 60 * 1000,
        },
        { timeoutMs: 5_000 },
      );
      signal.throwIfAborted();
      if (result?.status !== "published" || result.workloadRequestId !== assignmentId) {
        throw new Error("VScreen did not accept the Android app assignment");
      }
      return assignmentId;
    });
  api.registerTool(
    (context) => createAndroidUseTool(api, context, { ...dependencies, authorization, assignVScreenApp }),
    { name: ANDROID_USE_TOOL },
  );
  api.on("before_tool_call", (event, context) => authorization.beforeToolCall(event, context), { matcher: [ANDROID_USE_TOOL] });
  api.on("agent_end", (event, context) => authorization.endRun(event, context));
  let started = false;
  api.registerService({
    id: "claw-in-one-android-use-authorization",
    async start() { started = true; },
    async stop() {
      // A discovery-only registry must not retire the running service's broker.
      if (!started) return;
      started = false;
      await authorization.close();
    },
  });
}

function controlEnvelope(control, request) {
  return {
    protocolVersion: ANDROID_USE_PROTOCOL_VERSION,
    acquire: request.acquire,
    operation: request.operation,
    controlId: control.controlId,
    ownerKey: control.ownerKey,
    executionKey: control.executionKey,
    sessionId: control.identity.sessionId,
    runId: control.identity.runId,
    targetPackage: control.targetPackage,
    display: control.display,
    assignmentId: control.assignmentId ?? null,
    request: request.request,
  };
}
