import { randomUUID } from "node:crypto";
import { createVScreenSourceRelay } from "../../vscreen-foundation/source-relay.mjs";
import { AndroidDeviceError, AndroidDeviceErrorCode } from "../bridge/protocol.mjs";

export const ANDROID_VSCREEN_PRODUCER_METHODS = Object.freeze({
  ensure: "claw.androidVScreen.ensure",
  workload: "claw.androidVScreen.workload",
  presented: "claw.androidVScreen.presented",
  close: "claw.androidVScreen.close",
  probe: "claw.androidVScreen.probe",
});

export const VSCREEN_FOUNDATION_PLUGIN_ID = "claw-in-one-vscreen-foundation";

const UUID_PATTERN = /^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/;
const OPAQUE_KEY_PATTERN = /^[0-9a-f]{64}$/;
const PROBE_ID_PATTERN = /^[0-9a-f]{32}$/;
const REQUEST_ID_PATTERN = /^[A-Za-z0-9][A-Za-z0-9._:-]{7,127}$/;
const ANDROID_PACKAGE_PATTERN = /^[A-Za-z][A-Za-z0-9_]*(?:\.[A-Za-z0-9_]+)+$/;

function invalid(message) {
  throw new AndroidDeviceError(AndroidDeviceErrorCode.invalidRequest, message);
}

function record(value) {
  if (value === null || typeof value !== "object" || Array.isArray(value)) {
    invalid("VScreen producer request must be an object");
  }
  if (value.protocolVersion !== 3) invalid("VScreen producer protocolVersion must be 3");
  return value;
}

function exactKeys(value, allowed) {
  for (const key of Object.keys(value)) {
    if (!allowed.includes(key)) invalid(`Unexpected VScreen producer request field: ${key}`);
  }
}

function opaqueKey(value, name = "ownerKey") {
  if (typeof value !== "string" || !OPAQUE_KEY_PATTERN.test(value)) invalid(`${name} is invalid`);
  return value;
}

function uuid(value, name = "sourceHandle") {
  if (typeof value !== "string" || !UUID_PATTERN.test(value)) invalid(`${name} is invalid`);
  return value;
}

function requestId(value) {
  if (typeof value !== "string" || !REQUEST_ID_PATTERN.test(value)) invalid("workloadRequestId is invalid");
  return value;
}

function closeReason(value) {
  if (typeof value !== "string" || !/^[a-z][a-z0-9_]{0,63}$/.test(value)) {
    invalid("VScreen close reason is invalid");
  }
  return value;
}

export function parseVScreenEnsureParams(raw) {
  const value = record(raw);
  exactKeys(value, ["protocolVersion", "ownerKey"]);
  return { ownerKey: opaqueKey(value.ownerKey) };
}

export function parseVScreenWorkloadParams(raw) {
  const value = record(raw);
  exactKeys(value, ["protocolVersion", "workloadRequestId", "targetPackage", "ownerKey", "sourceHandle", "targetRef"]);
  const sourceHandle = value.sourceHandle === undefined ? null : uuid(value.sourceHandle);
  const targetRef = value.targetRef === undefined ? null : uuid(value.targetRef, "targetRef");
  if ((sourceHandle === null) !== (targetRef === null)) invalid("sourceHandle and targetRef must be supplied together");
  return {
    workloadRequestId: requestId(value.workloadRequestId),
    targetPackage:
      typeof value.targetPackage === "string" && ANDROID_PACKAGE_PATTERN.test(value.targetPackage)
        ? value.targetPackage
        : invalid("targetPackage is invalid"),
    ownerKey: opaqueKey(value.ownerKey),
    sourceHandle,
    targetRef,
  };
}

export function parseVScreenPresentedParams(raw) {
  const value = record(raw);
  exactKeys(value, ["protocolVersion", "sourceHandle", "workloadRequestId", "ownerKey"]);
  return {
    sourceHandle: uuid(value.sourceHandle),
    workloadRequestId: requestId(value.workloadRequestId),
    ownerKey: opaqueKey(value.ownerKey),
  };
}

export function parseVScreenCloseParams(raw) {
  const value = record(raw);
  exactKeys(value, ["protocolVersion", "sourceHandle", "reason"]);
  return { sourceHandle: uuid(value.sourceHandle), reason: closeReason(value.reason) };
}

export function parseVScreenProbeParams(raw) {
  const value = record(raw);
  exactKeys(value, ["protocolVersion", "ownerKey", "probeId"]);
  if (typeof value.probeId !== "string" || !PROBE_ID_PATTERN.test(value.probeId)) {
    invalid("probeId is invalid");
  }
  return { ownerKey: opaqueKey(value.ownerKey), probeId: value.probeId };
}

export function createAndroidVScreenRpcAdapter(options) {
  const producer = options.producer;
  const createId = options.randomUUID ?? randomUUID;
  const createRelay = options.createRelay ?? createVScreenSourceRelay;
  const sources = new Map();

  const descriptor = (entry, foreground) => ({
    protocolVersion: 3,
    sourceHandle: entry.sourceHandle,
    targetRef: entry.source.targetRef,
    codec: entry.source.codec,
    display: { ...entry.source.display },
    capabilities: [...entry.source.capabilities],
    foreground,
    transport: entry.relay.descriptor,
  });

  const publish = async (source, identity, foreground) => {
    const existing = [...sources.values()].find((entry) => !entry.closed && entry.source === source);
    if (existing) {
      existing.identity = identity;
      existing.foreground = foreground;
      return descriptor(existing, foreground);
    }
    const sourceHandle = source.sourceHandle ?? createId().toLowerCase();
    if (!UUID_PATTERN.test(sourceHandle)) throw new Error("Android VScreen source handle is unavailable");
    const relay = await createRelay(source.video, { onInput: source.input });
    if (!relay?.closed || typeof relay.closed.then !== "function") {
      await Promise.resolve(relay?.close?.("invalid_relay")).catch(() => {});
      throw new Error("Android VScreen source relay has no close signal");
    }
    const entry = { sourceHandle, source, relay, identity, foreground, closed: false };
    sources.set(sourceHandle, entry);
    relay.closed.then(
      () => {
        if (sources.get(sourceHandle) === entry) void close(entry, "relay_closed");
      },
      () => {
        if (sources.get(sourceHandle) === entry) void close(entry, "relay_failed");
      },
    );
    source.closed.then(
      () => {
        if (sources.get(sourceHandle) === entry) sources.delete(sourceHandle);
        void relay.close("producer_closed");
      },
      () => {
        if (sources.get(sourceHandle) === entry) sources.delete(sourceHandle);
        void relay.close("producer_failed");
      },
    );
    return descriptor(entry, foreground);
  };

  const resolve = (sourceHandle) => {
    const entry = sources.get(sourceHandle);
    if (!entry || entry.closed) {
      throw new AndroidDeviceError(
        AndroidDeviceErrorCode.vscreenUnauthorized,
        "Android VScreen source is unavailable",
      );
    }
    return entry;
  };

  const close = async (entry, reason) => {
    if (entry.closed) return;
    entry.closed = true;
    if (sources.get(entry.sourceHandle) === entry) sources.delete(entry.sourceHandle);
    await entry.relay.close(reason);
    await producer.closeSource(entry.source, reason);
  };

  return Object.freeze({
    async ensure(params) {
      const source = await producer.ensureTarget(params);
      try {
        return await publish(source, { kind: "display", ...params }, "home");
      } catch (error) {
        await producer.closeSource(source, "relay_unavailable").catch(() => {});
        throw error;
      }
    },
    async workload(params) {
      const current = params.sourceHandle === null ? null : resolve(params.sourceHandle);
      if (current && current.source.targetRef !== params.targetRef) {
        throw new AndroidDeviceError(
          AndroidDeviceErrorCode.vscreenUnauthorized,
          "Android VScreen target identity changed before workload placement",
        );
      }
      const source = await producer.placeWorkload({
        requestId: params.workloadRequestId,
        targetPackage: params.targetPackage,
        ownerKey: params.ownerKey,
        source: current?.source ?? null,
      });
      return await publish(
        source,
        { kind: "workload", requestId: params.workloadRequestId, ownerKey: params.ownerKey },
        "workload",
      );
    },
    async presented(params) {
      const entry = resolve(params.sourceHandle);
      if (
        entry.identity.kind !== "workload" ||
        entry.identity.requestId !== params.workloadRequestId ||
        entry.identity.ownerKey !== params.ownerKey
      ) {
        throw new AndroidDeviceError(
          AndroidDeviceErrorCode.vscreenUnauthorized,
          "Android VScreen workload identity changed before its first frame",
        );
      }
      return await producer.verifyPresented({
        requestId: params.workloadRequestId,
        ownerKey: params.ownerKey,
        targetRef: entry.source.targetRef,
        source: entry.source,
      });
    },
    async close(params) {
      const entry = sources.get(params.sourceHandle);
      if (entry) await close(entry, params.reason);
      return { protocolVersion: 3, status: "closed", sourceHandle: params.sourceHandle };
    },
    async probe(params) {
      const source = await producer.prepareProbe(params);
      try {
        return await publish(source, { kind: "probe", ...params }, "system_ui");
      } catch (error) {
        await producer.closeSource(source, "relay_unavailable").catch(() => {});
        throw error;
      }
    },
    async closeAll(reason = "producer_stopped") {
      await Promise.all([...sources.values()].map((entry) => close(entry, reason).catch(() => {})));
    },
  });
}
