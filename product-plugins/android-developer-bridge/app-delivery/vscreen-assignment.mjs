import { ANDROID_APP_TOOL_PROTOCOL_VERSION } from "./tool-protocol.mjs";
import { ANDROID_VSCREEN_PRODUCER_ID } from "../vscreen/producer.mjs";

const CLAIM_TTL_MS = 10 * 60 * 1000;
const MAX_CLAIMS = 64;
const PACKAGE_PATTERN = /^[A-Za-z][A-Za-z0-9_]*(?:\.[A-Za-z0-9_]+)+$/;
const UUID_PATTERN = /^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/;

function record(value) {
  return value !== null && typeof value === "object" && !Array.isArray(value) ? value : null;
}

function nonBlank(value) {
  return typeof value === "string" && value.trim() ? value.trim() : null;
}

function matchingIdentity(primary, secondary) {
  const left = nonBlank(primary);
  const right = nonBlank(secondary);
  if (left && right && left !== right) return null;
  return left ?? right;
}

/** Correlates App Delivery's verified result with one generic VScreen app assignment. */
export class AndroidAppVScreenAssignmentStore {
  constructor({ now = Date.now, ttlMs = CLAIM_TTL_MS, maxClaims = MAX_CLAIMS } = {}) {
    this.now = now;
    this.ttlMs = ttlMs;
    this.maxClaims = maxClaims;
    this.claims = new Map();
  }

  claimToolCall(event, context) {
    const params = record(event?.params);
    if (event?.toolName !== "android_app" || params?.operation !== "place_vscreen_workload") return false;
    const sessionKey = nonBlank(context?.sessionKey);
    const sessionId = nonBlank(context?.sessionId);
    const runId = matchingIdentity(event?.runId, context?.runId);
    const toolCallId = matchingIdentity(event?.toolCallId, context?.toolCallId);
    if (!sessionKey || !sessionId || !runId || !toolCallId) return false;
    this.prune();
    this.claims.set(`${runId}\0${toolCallId}`, {
      runId,
      sessionKey,
      sessionId,
      toolCallId,
      claimedAtMs: this.now(),
    });
    while (this.claims.size > this.maxClaims) this.claims.delete(this.claims.keys().next().value);
    return true;
  }

  assignmentFromToolResult(event) {
    this.prune();
    const data = record(event?.data);
    const runId = nonBlank(event?.runId);
    const toolCallId = nonBlank(data?.toolCallId);
    if (!runId || !toolCallId || event?.stream !== "tool" || data?.phase !== "result" || data?.name !== "android_app") {
      return null;
    }
    const key = `${runId}\0${toolCallId}`;
    const claim = this.claims.get(key);
    if (!claim) return null;
    this.claims.delete(key);
    if (nonBlank(event?.sessionKey) !== claim.sessionKey || data.isError !== false) return null;
    const details = record(data.result?.details);
    const artifact = record(details?.artifact);
    const targetPackage = nonBlank(details?.packageName);
    const workloadRequestId = nonBlank(details?.workloadId);
    if (
      details?.protocolVersion !== ANDROID_APP_TOOL_PROTOCOL_VERSION ||
      details?.operation !== "place_vscreen_workload" ||
      details?.status !== "vscreen_workload_requested" ||
      !PACKAGE_PATTERN.test(targetPackage ?? "") ||
      !UUID_PATTERN.test(workloadRequestId ?? "") ||
      artifact?.packageName !== targetPackage
    ) return null;
    return Object.freeze({
      producerId: ANDROID_VSCREEN_PRODUCER_ID,
      workloadRequestId,
      targetPackage,
      runId: claim.runId,
      sessionKey: claim.sessionKey,
      sessionId: claim.sessionId,
      toolCallId: claim.toolCallId,
      expiresAtMs: this.now() + this.ttlMs,
    });
  }

  clearRun(runId) {
    const expected = nonBlank(runId);
    if (!expected) return;
    for (const [key, claim] of this.claims) {
      if (claim.runId === expected) this.claims.delete(key);
    }
  }

  prune() {
    const now = this.now();
    for (const [key, claim] of this.claims) {
      if (claim.claimedAtMs + this.ttlMs <= now) this.claims.delete(key);
    }
  }
}
