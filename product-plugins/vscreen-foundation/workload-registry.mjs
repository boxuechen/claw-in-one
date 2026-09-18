import {
  VSCREEN_AGENT_EVENT_STREAM,
  VSCREEN_PROTOCOL_VERSION,
  validateProducerId,
} from "./protocol.mjs";

const MAX_WORKLOADS = 64;
const REQUEST_ID_PATTERN = /^[A-Za-z0-9][A-Za-z0-9._:-]{7,127}$/;

function nonBlank(value, name) {
  if (typeof value !== "string" || !value.trim()) throw new Error(`${name} is required`);
  return value.trim();
}

function requestId(value) {
  const normalized = nonBlank(value, "workloadRequestId");
  if (!REQUEST_ID_PATTERN.test(normalized)) throw new Error("workloadRequestId is invalid");
  return normalized;
}

const PACKAGE_PATTERN = /^[A-Za-z][A-Za-z0-9_]*(?:\.[A-Za-z0-9_]+)+$/;

/**
 * Bounded provenance for workload mutations. A run owns only its pending request;
 * it never owns the App-global VScreen target or presentation.
 */
export class VScreenWorkloadRegistry {
  constructor({ now = Date.now, maxWorkloads = MAX_WORKLOADS } = {}) {
    this.now = now;
    this.maxWorkloads = maxWorkloads;
    this.workloads = new Map();
    this.publisher = null;
    this.nextPublisherGeneration = 1;
  }

  registerPublisher(publish) {
    if (typeof publish !== "function") throw new Error("VScreen workload publisher is required");
    const entry = Object.freeze({ generation: this.nextPublisherGeneration++, publish });
    this.publisher = entry;
    let disposed = false;
    return Object.freeze({
      generation: entry.generation,
      dispose: () => {
        if (disposed) return false;
        disposed = true;
        if (this.publisher !== entry) return false;
        this.publisher = null;
        return true;
      },
    });
  }

  publish(value) {
    this.prune();
    const workload = Object.freeze({
      producerId: validateProducerId(value?.producerId),
      workloadRequestId: requestId(value?.workloadRequestId),
      targetPackage: nonBlank(value?.targetPackage, "targetPackage"),
      sessionKey: nonBlank(value?.sessionKey, "sessionKey"),
      sessionId: nonBlank(value?.sessionId, "sessionId"),
      runId: nonBlank(value?.runId, "runId"),
      toolCallId: nonBlank(value?.toolCallId, "toolCallId"),
      expiresAtMs: value?.expiresAtMs,
    });
    if (!PACKAGE_PATTERN.test(workload.targetPackage)) throw new Error("targetPackage is invalid");
    if (!Number.isFinite(workload.expiresAtMs) || workload.expiresAtMs <= this.now()) {
      throw new Error("VScreen workload expiry is invalid");
    }
    const event = {
      runId: workload.runId,
      sessionKey: workload.sessionKey,
      stream: VSCREEN_AGENT_EVENT_STREAM,
      data: {
        protocolVersion: VSCREEN_PROTOCOL_VERSION,
        phase: "offered",
        producerId: workload.producerId,
        workloadRequestId: workload.workloadRequestId,
        executionSessionId: workload.sessionId,
        toolCallId: workload.toolCallId,
        producerPayload: { targetPackage: workload.targetPackage },
      },
    };
    let emitted;
    try {
      emitted = this.publisher?.publish(event) ?? {
        emitted: false,
        reason: "VScreen workload publisher is unavailable",
      };
    } catch (error) {
      emitted = { emitted: false, reason: error?.message ?? "VScreen workload publisher failed" };
    }
    if (emitted?.emitted === true) {
      this.workloads.set(workload.workloadRequestId, workload);
      while (this.workloads.size > this.maxWorkloads) {
        this.workloads.delete(this.workloads.keys().next().value);
      }
    }
    return Object.freeze({ event, emitted: emitted?.emitted === true, reason: emitted?.reason });
  }

  resolve(workloadRequestId) {
    this.prune();
    return this.workloads.get(workloadRequestId) ?? null;
  }

  retireOrigin(runId) {
    if (typeof runId !== "string") return;
    for (const [key, workload] of this.workloads) {
      if (workload.runId === runId) this.workloads.delete(key);
    }
  }

  prune() {
    const now = this.now();
    for (const [key, workload] of this.workloads) {
      if (workload.expiresAtMs <= now) this.workloads.delete(key);
    }
  }
}
