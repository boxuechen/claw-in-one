import {WEB_PROJECT_TOOL_PROTOCOL_VERSION, WEB_PROJECT_TOOLS} from "./tool-protocol.mjs";

export const WEB_PROJECT_RESULT_EVENT_STREAM = "claw-in-one-web-project.result";
export const WEB_PROJECT_RESULT_EVENT_PROTOCOL_VERSION = 1;
const ID_PATTERN = /^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$/u;
const TARGET_ID_PATTERN = /^[0-9a-f]{64}$/u;
const DEVICE_URL_PATTERN = /^http:\/\/127\.0\.0\.1:38\d{3}\/$/u;

function text(value) {
  return typeof value === "string" && value.trim() ? value.trim() : null;
}
function record(value) {
  return value !== null && typeof value === "object" && !Array.isArray(value) ? value : null;
}

export class WebProjectResultStore {
  constructor({now = Date.now} = {}) {
    this.now = now;
    this.claims = new Map();
  }

  claimToolCall(event, context) {
    if (![WEB_PROJECT_TOOLS.serve, WEB_PROJECT_TOOLS.stop].includes(event?.toolName)) return false;
    const sessionKey = text(context?.sessionKey);
    const sessionId = text(context?.sessionId);
    const runId = text(context?.runId);
    const toolCallId = text(context?.toolCallId);
    if (!sessionKey || !sessionId || !runId || !toolCallId) return false;
    this.claims.set(`${runId}\0${toolCallId}`, {sessionKey, sessionId, runId, toolCallId, toolName: event.toolName});
    return true;
  }

  eventFromToolResult(event) {
    const data = record(event?.data);
    const runId = text(event?.runId);
    const toolCallId = text(data?.toolCallId);
    if (!runId || !toolCallId || event?.stream !== "tool" || data?.phase !== "result") return null;
    const key = `${runId}\0${toolCallId}`;
    const claim = this.claims.get(key);
    if (!claim) return null;
    this.claims.delete(key);
    if (data?.name !== claim.toolName || text(event?.sessionKey) !== claim.sessionKey || data.isError !== false) return null;
    const details = record(data.result?.details);
    if (claim.toolName === WEB_PROJECT_TOOLS.stop) {
      if (
        details?.protocolVersion !== WEB_PROJECT_TOOL_PROTOCOL_VERSION || details.operation !== "stop" ||
        details.status !== "stopped" || !ID_PATTERN.test(details.projectId ?? "") ||
        !Number.isSafeInteger(details.generation) || details.generation < 1
      ) return null;
      return Object.freeze({
        runId: claim.runId,
        sessionKey: claim.sessionKey,
        stream: WEB_PROJECT_RESULT_EVENT_STREAM,
        data: {
          protocolVersion: WEB_PROJECT_RESULT_EVENT_PROTOCOL_VERSION,
          phase: "stopped",
          executionSessionId: claim.sessionId,
          toolCallId: claim.toolCallId,
          projectId: details.projectId,
          generation: details.generation,
        },
      });
    }
    if (
      details?.protocolVersion !== WEB_PROJECT_TOOL_PROTOCOL_VERSION || details.operation !== "serve" ||
      details.status !== "ready" || !ID_PATTERN.test(details.resultId ?? "") ||
      !ID_PATTERN.test(details.projectId ?? "") ||
      !Number.isSafeInteger(details.generation) || details.generation < 1 ||
      !TARGET_ID_PATTERN.test(details.targetId ?? "") ||
      !DEVICE_URL_PATTERN.test(details.url ?? "") || !text(details.appName) ||
      [...details.appName.trim()].length > 48 || /[\r\n\0]/u.test(details.appName)
    ) return null;
    return Object.freeze({
      runId: claim.runId,
      sessionKey: claim.sessionKey,
      stream: WEB_PROJECT_RESULT_EVENT_STREAM,
      data: {
        protocolVersion: WEB_PROJECT_RESULT_EVENT_PROTOCOL_VERSION,
        phase: "ready",
        executionSessionId: claim.sessionId,
        toolCallId: claim.toolCallId,
        resultId: details.resultId,
        projectId: details.projectId,
        generation: details.generation,
        targetId: details.targetId,
        url: details.url,
        appName: details.appName,
      },
    });
  }

  clearRun(runId) {
    for (const [key, claim] of this.claims) if (claim.runId === runId) this.claims.delete(key);
  }
}
