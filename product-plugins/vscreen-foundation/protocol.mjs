import { createHash } from "node:crypto";

export const VSCREEN_PROTOCOL_VERSION = 3;
export const VSCREEN_FOUNDATION_SERVICE_ID = "claw-in-one-vscreen-foundation";
export const VSCREEN_STREAM_ROUTE = "/claw-in-one/vscreen";
export const VSCREEN_AGENT_EVENT_STREAM = "claw-in-one-vscreen.workload";
export const VSCREEN_METHODS = Object.freeze({
  status: "claw.vscreen.status",
  ensure: "claw.vscreen.ensure",
  close: "claw.vscreen.close",
  workload: "claw.vscreen.workload",
  framePresented: "claw.vscreen.frame_presented",
  probeStatus: "claw.preview.probe.status",
  probeStart: "claw.preview.probe.start",
  probeFinish: "claw.preview.probe.finish",
});
export const VSCREEN_INTERNAL_METHODS = Object.freeze({
  assignApp: "claw.vscreen.internal.assign-app",
});

export const VScreenErrorCode = Object.freeze({
  invalidRequest: "VSCREEN_INVALID_REQUEST",
  unavailable: "VSCREEN_UNAVAILABLE",
  busy: "VSCREEN_BUSY",
  unauthorized: "VSCREEN_UNAUTHORIZED",
  producerConflict: "VSCREEN_PRODUCER_CONFLICT",
});

const PRODUCER_ID_PATTERN = /^[a-z0-9][a-z0-9.-]{0,127}$/;
const REQUEST_ID_PATTERN = /^[A-Za-z0-9][A-Za-z0-9._:-]{7,127}$/;
const ATTACHMENT_ID_PATTERN = /^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/;
const PROBE_ID_PATTERN = /^[0-9a-f]{32}$/;

export class VScreenError extends Error {
  constructor(code, message, options = {}) {
    super(message);
    this.name = "VScreenError";
    this.code = code;
    this.retryable = options.retryable === true;
  }
}

function invalid(message) {
  throw new VScreenError(VScreenErrorCode.invalidRequest, message);
}

function requireRecord(value) {
  if (value === null || typeof value !== "object" || Array.isArray(value)) {
    invalid("Request parameters must be an object");
  }
  return value;
}

function requireKeys(record, allowed) {
  for (const key of Object.keys(record)) {
    if (!allowed.includes(key)) invalid(`Unexpected request field: ${key}`);
  }
  if (record.protocolVersion !== VSCREEN_PROTOCOL_VERSION) {
    invalid(`protocolVersion must be ${VSCREEN_PROTOCOL_VERSION}`);
  }
}

function producerId(value) {
  if (typeof value !== "string" || !PRODUCER_ID_PATTERN.test(value)) {
    invalid("producerId is invalid");
  }
  return value;
}

function requestId(value, name = "workloadRequestId") {
  if (typeof value !== "string" || !REQUEST_ID_PATTERN.test(value)) {
    invalid(`${name} is invalid`);
  }
  return value;
}

function attachmentId(value) {
  if (typeof value !== "string" || !ATTACHMENT_ID_PATTERN.test(value)) {
    invalid("attachmentId is invalid");
  }
  return value;
}

function generation(value, name) {
  if (!Number.isSafeInteger(value) || value <= 0) invalid(`${name} is invalid`);
  return value;
}

function probeId(value) {
  if (typeof value !== "string" || !PROBE_ID_PATTERN.test(value)) {
    invalid("probeId must be 32 lowercase hexadecimal characters");
  }
  return value;
}

function nonBlank(value, name) {
  if (typeof value !== "string" || !value.trim() || value.length > 512) {
    invalid(`${name} is invalid`);
  }
  return value.trim();
}

export function parseVScreenStatusParams(value) {
  const record = requireRecord(value);
  requireKeys(record, ["protocolVersion"]);
  return {};
}

export function parseVScreenEnsureParams(value) {
  const record = requireRecord(value);
  requireKeys(record, ["protocolVersion", "producerId"]);
  return { producerId: producerId(record.producerId) };
}

export function parseVScreenCloseParams(value) {
  const record = requireRecord(value);
  requireKeys(record, [
    "protocolVersion",
    "attachmentId",
    "targetGeneration",
    "sourceGeneration",
  ]);
  return {
    attachmentId: attachmentId(record.attachmentId),
    targetGeneration: generation(record.targetGeneration, "targetGeneration"),
    sourceGeneration: generation(record.sourceGeneration, "sourceGeneration"),
  };
}

export function parseVScreenWorkloadParams(value) {
  const record = requireRecord(value);
  requireKeys(record, ["protocolVersion", "producerId", "workloadRequestId"]);
  return {
    producerId: producerId(record.producerId),
    workloadRequestId: requestId(record.workloadRequestId),
  };
}

export function parseVScreenFramePresentedParams(value) {
  const record = requireRecord(value);
  requireKeys(record, ["protocolVersion", "attachmentId", "workloadRequestId"]);
  return {
    attachmentId: attachmentId(record.attachmentId),
    ...(record.workloadRequestId === undefined
      ? {}
      : { workloadRequestId: requestId(record.workloadRequestId) }),
  };
}

export function parsePreviewProbeStatusParams(value) {
  const record = requireRecord(value);
  requireKeys(record, ["protocolVersion", "probeId"]);
  return { probeId: probeId(record.probeId) };
}

export function parsePreviewProbeStartParams(value) {
  const record = requireRecord(value);
  requireKeys(record, ["protocolVersion", "producerId", "probeId"]);
  return { producerId: producerId(record.producerId), probeId: probeId(record.probeId) };
}

export function parsePreviewProbeFinishParams(value) {
  const record = requireRecord(value);
  requireKeys(record, ["protocolVersion", "probeId", "attachmentId"]);
  return { probeId: probeId(record.probeId), attachmentId: attachmentId(record.attachmentId) };
}

export function parseVScreenAppAssignmentParams(value) {
  const record = requireRecord(value);
  requireKeys(record, [
    "protocolVersion",
    "producerId",
    "workloadRequestId",
    "targetPackage",
    "sessionKey",
    "sessionId",
    "runId",
    "expiresAtMs",
    "toolCallId",
  ]);
  if (!Number.isFinite(record.expiresAtMs)) invalid("expiresAtMs is invalid");
  const targetPackage = nonBlank(record.targetPackage, "targetPackage");
  if (!/^[A-Za-z][A-Za-z0-9_]*(?:\.[A-Za-z0-9_]+)+$/.test(targetPackage)) {
    invalid("targetPackage is invalid");
  }
  return {
    producerId: producerId(record.producerId),
    workloadRequestId: requestId(record.workloadRequestId),
    targetPackage,
    sessionKey: nonBlank(record.sessionKey, "sessionKey"),
    sessionId: nonBlank(record.sessionId, "sessionId"),
    runId: nonBlank(record.runId, "runId"),
    expiresAtMs: record.expiresAtMs,
    toolCallId: nonBlank(record.toolCallId, "toolCallId"),
  };
}

export function vscreenOwnerKey(client) {
  const deviceId = client?.connect?.device?.id;
  if (typeof deviceId !== "string" || deviceId.trim().length === 0) {
    throw new VScreenError(
      VScreenErrorCode.invalidRequest,
      "VScreen requires an authenticated device identity",
    );
  }
  return createHash("sha256")
    .update("claw-in-one-vscreen-owner\0")
    .update(deviceId)
    .digest("hex");
}

export function vscreenGatewayError(error) {
  const normalized =
    error instanceof VScreenError
      ? error
      : new VScreenError(VScreenErrorCode.unavailable, "VScreen is unavailable", {
          retryable: true,
        });
  return {
    code: normalized.code === VScreenErrorCode.invalidRequest ? "INVALID_REQUEST" : "UNAVAILABLE",
    message: normalized.message,
    retryable: normalized.retryable,
    details: { code: normalized.code },
  };
}

export function validateProducerId(value) {
  return producerId(value);
}
