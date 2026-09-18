import {randomUUID} from "node:crypto";
import {
  WEB_PROJECT_AUTHORIZATION_NONCE,
  WEB_PROJECT_TOOLS,
  parseWebProjectParams,
} from "./tool-protocol.mjs";

const PROJECT_WORKSPACE_ADMISSION_KEY = Symbol.for(
  "io.github.boxuechen.clawinone.project-workspaces.admission.v2",
);
const TOOL_NAMES = new Set(Object.values(WEB_PROJECT_TOOLS));
const AUTHORIZATION_TTL_MS = 2 * 60 * 1000;
const MAX_PENDING = 64;

function admissionFor(identity) {
  return globalThis[PROJECT_WORKSPACE_ADMISSION_KEY]?.admitProjectMutation?.(
    identity,
    "web_development",
  ) ?? null;
}

function hostIdentity(event, context) {
  const fields = ["agentId", "sessionKey", "sessionId", "runId", "toolCallId"];
  const identity = Object.fromEntries(fields.map(name => [name, context?.[name]?.trim()]));
  if (
    fields.some(name => !identity[name]) ||
    !identity.sessionKey.startsWith(`agent:${identity.agentId}:`) ||
    (event.toolCallId && event.toolCallId !== identity.toolCallId) ||
    (event.runId && event.runId !== identity.runId) ||
    !(context?.abortSignal instanceof AbortSignal)
  ) throw new Error("Web Project tools require a trusted current Project run");
  context.abortSignal.throwIfAborted();
  return {...identity, signal: context.abortSignal};
}

export function createWebProjectAuthorizationBroker(dependencies = {}) {
  const now = dependencies.now ?? Date.now;
  const createId = dependencies.randomUUID ?? randomUUID;
  const pending = new Map();
  const lifetime = new AbortController();

  function remove(nonce) {
    const record = pending.get(nonce);
    record?.identity.signal.removeEventListener("abort", record.onAbort);
    pending.delete(nonce);
    return record;
  }
  function retire(nonce) {
    remove(nonce)?.cancellation.abort(new Error("Web Project authorization was revoked"));
  }
  function assertCurrent(record, signal = record.identity.signal) {
    signal.throwIfAborted();
    const current = admissionFor(record.identity);
    if (
      now() >= record.expiresAtMs || !current ||
      current.projectId !== record.admission.projectId ||
      current.projectRoot !== record.admission.projectRoot ||
      current.leaseGeneration !== record.admission.leaseGeneration
    ) throw new Error("Web Project tool requires the active ready Project run");
  }
  function prune() {
    for (const [nonce, record] of pending) {
      if (now() >= record.expiresAtMs || record.identity.signal.aborted) retire(nonce);
    }
  }

  return Object.freeze({
    get closed() { return lifetime.signal.aborted; },

    beforeToolCall(event, context) {
      if (!TOOL_NAMES.has(event.toolName)) return undefined;
      const visible = {...event.params};
      delete visible[WEB_PROJECT_AUTHORIZATION_NONCE];
      try {
        parseWebProjectParams(visible);
        const identity = hostIdentity(event, context);
        const admission = admissionFor(identity);
        if (!admission) throw new Error("Web Project tool requires the active ready Project run");
        prune();
        if (pending.size >= MAX_PENDING) throw new Error("Too many Web Project tools are pending");
        const nonce = createId().toLowerCase();
        const record = {
          toolName: event.toolName,
          identity,
          admission,
          expiresAtMs: now() + AUTHORIZATION_TTL_MS,
          consumed: false,
          cancellation: new AbortController(),
          onAbort: () => retire(nonce),
        };
        assertCurrent(record);
        pending.set(nonce, record);
        identity.signal.addEventListener("abort", record.onAbort, {once: true});
        return {params: {[WEB_PROJECT_AUTHORIZATION_NONCE]: nonce}};
      } catch (error) {
        return {block: true, blockReason: error instanceof Error ? error.message : "Web Project tool is unavailable"};
      }
    },

    consumeAuthorization(nonce, request) {
      prune();
      const record = typeof nonce === "string" ? pending.get(nonce) : null;
      if (
        !record || record.consumed || record.toolName !== request.toolName ||
        record.identity.toolCallId !== request.toolCallId ||
        !["agentId", "sessionKey", "sessionId"].every(name => record.identity[name] === request.context?.[name])
      ) throw new Error("Web Project tool was not authorized for this exact host Tool call");
      try { assertCurrent(record); }
      catch (error) { retire(nonce); throw error; }
      record.consumed = true;
      return Object.freeze({
        admission: record.admission,
        async run(signal, execute) {
          const combined = AbortSignal.any([
            record.identity.signal,
            record.cancellation.signal,
            lifetime.signal,
            ...(signal ? [signal] : []),
          ]);
          try {
            assertCurrent(record, combined);
            return await execute(combined, record.admission);
          } finally { remove(nonce); }
        },
      });
    },

    endRun(_event, context) {
      for (const [nonce, record] of pending) {
        if (["agentId", "sessionKey", "sessionId", "runId"].every(name => record.identity[name] === context?.[name])) {
          retire(nonce);
        }
      }
    },

    close() {
      lifetime.abort(new Error("Web Project runtime was stopped"));
      for (const nonce of pending.keys()) remove(nonce);
    },
  });
}
