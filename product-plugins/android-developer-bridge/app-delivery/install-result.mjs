import { ANDROID_APP_TOOL_PROTOCOL_VERSION } from "./tool-protocol.mjs";

export const ANDROID_APP_INSTALLED_EVENT_STREAM = "claw-in-one-android-app.installed";
export const ANDROID_APP_INSTALLED_EVENT_PROTOCOL_VERSION = 1;

const MAX_CLAIMS = 64;
const CLAIM_TTL_MS = 10 * 60 * 1000;
const PACKAGE_PATTERN = /^[A-Za-z][A-Za-z0-9_]*(?:\.[A-Za-z0-9_]+)+$/;
const SHA256_PATTERN = /^[0-9a-f]{64}$/;
const VERSION_CODE_PATTERN = /^[0-9]+$/;

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

/** Correlates a canonical successful install result with its Project/Chat/run identity. */
export class AndroidInstalledAppEventStore {
  constructor({ now = Date.now, ttlMs = CLAIM_TTL_MS, maxClaims = MAX_CLAIMS } = {}) {
    this.now = now;
    this.ttlMs = ttlMs;
    this.maxClaims = maxClaims;
    this.claims = new Map();
  }

  claimToolCall(event, context) {
    const params = record(event?.params);
    if (event?.toolName !== "android_app" || params?.operation !== "install_apk") {
      return false;
    }
    const sessionKey = nonBlank(context?.sessionKey);
    const sessionId = nonBlank(context?.sessionId);
    const runId = matchingIdentity(event?.runId, context?.runId);
    const toolCallId = matchingIdentity(event?.toolCallId, context?.toolCallId);
    if (!sessionKey || !sessionId || !runId || !toolCallId) return false;
    this.prune();
    this.claims.set(this.#claimKey(runId, toolCallId), {
      runId,
      sessionKey,
      sessionId,
      toolCallId,
      claimedAtMs: this.now(),
    });
    while (this.claims.size > this.maxClaims) this.claims.delete(this.claims.keys().next().value);
    return true;
  }

  eventFromToolResult(event) {
    this.prune();
    const data = record(event?.data);
    const runId = nonBlank(event?.runId);
    const toolCallId = nonBlank(data?.toolCallId);
    if (
      !runId ||
      !toolCallId ||
      event?.stream !== "tool" ||
      data?.phase !== "result" ||
      data?.name !== "android_app"
    ) return null;
    const key = this.#claimKey(runId, toolCallId);
    const claim = this.claims.get(key);
    if (!claim) return null;
    this.claims.delete(key);
    if (nonBlank(event?.sessionKey) !== claim.sessionKey || data.isError !== false) return null;

    const details = record(data.result?.details);
    const artifact = record(details?.artifact);
    const installed = record(details?.installed);
    const packageName = nonBlank(artifact?.packageName);
    const versionCode = nonBlank(artifact?.versionCode);
    const sha256 = nonBlank(artifact?.sha256);
    if (
      details?.protocolVersion !== ANDROID_APP_TOOL_PROTOCOL_VERSION ||
      details?.operation !== "install_apk" ||
      !["succeeded", "verified"].includes(details?.status) ||
      installed?.installed !== true ||
      !PACKAGE_PATTERN.test(packageName ?? "") ||
      !VERSION_CODE_PATTERN.test(versionCode ?? "") ||
      !SHA256_PATTERN.test(sha256 ?? "") ||
      installed.versionCode !== versionCode ||
      installed.sha256 !== sha256
    ) return null;

    return Object.freeze({
      runId: claim.runId,
      sessionKey: claim.sessionKey,
      stream: ANDROID_APP_INSTALLED_EVENT_STREAM,
      data: {
        protocolVersion: ANDROID_APP_INSTALLED_EVENT_PROTOCOL_VERSION,
        phase: "installed",
        executionSessionId: claim.sessionId,
        toolCallId: claim.toolCallId,
        packageName,
        versionCode,
        sha256,
      },
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

  #claimKey(runId, toolCallId) {
    return `${runId}\0${toolCallId}`;
  }
}
