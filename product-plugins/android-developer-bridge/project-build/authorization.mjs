import { randomUUID } from "node:crypto";
import {
  ANDROID_PROJECT_BUILD_AUTHORIZATION_NONCE,
  ANDROID_PROJECT_BUILD_TOOL,
  parseAndroidProjectBuildParams,
} from "./tool-protocol.mjs";

const PROJECT_WORKSPACE_ADMISSION_KEY = Symbol.for(
  "io.github.boxuechen.clawinone.project-workspaces.admission.v2",
);
const AUTHORIZATION_TTL_MS = 2 * 60 * 1000;
const MAX_PENDING_AUTHORIZATIONS = 64;

function admittedProjectRun(identity) {
  return globalThis[PROJECT_WORKSPACE_ADMISSION_KEY]?.admitProjectMutation?.(
    identity,
    "android_kotlin",
  ) ?? null;
}

function hostIdentity(event, context) {
  const fields = ["agentId", "sessionKey", "sessionId", "runId", "toolCallId"];
  const identity = Object.fromEntries(fields.map((name) => [name, context?.[name]?.trim()]));
  if (
    fields.some((name) => !identity[name]) ||
    !identity.sessionKey.startsWith(`agent:${identity.agentId}:`) ||
    (event.toolCallId && event.toolCallId !== identity.toolCallId) ||
    (event.runId && event.runId !== identity.runId) ||
    !(context?.abortSignal instanceof AbortSignal)
  ) {
    throw new Error(
      "Android Project build requires a trusted host Chat, Session, run, Tool call, and cancellation signal",
    );
  }
  context.abortSignal.throwIfAborted();
  const projectRoot = context?.fsPolicy?.root?.trim() || context?.workspaceDir?.trim();
  if (!projectRoot) throw new Error("Android Project build requires a Project workspace root");
  return { ...identity, projectRoot, signal: context.abortSignal };
}

export function createProjectBuildAuthorizationBroker(dependencies = {}) {
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
    remove(nonce)?.cancellation.abort(new Error("Android Project build authorization was revoked"));
  }

  function pruneExpired() {
    const current = now();
    for (const [nonce, record] of pending) {
      if (current >= record.expiresAtMs || record.identity.signal.aborted) retire(nonce);
    }
  }

  function assertCurrent(record, signal = record.identity.signal) {
    signal.throwIfAborted();
    const admission = admittedProjectRun(record.identity);
    if (
      now() >= record.expiresAtMs ||
      !admission ||
      admission.projectId !== record.admission.projectId ||
      admission.projectRoot !== record.admission.projectRoot ||
      admission.leaseGeneration !== record.admission.leaseGeneration
    ) {
      throw new Error("Android Project build requires the active ready Project run");
    }
  }

  return {
    get closed() {
      return lifetime.signal.aborted;
    },

    beforeToolCall(event, context) {
      if (event.toolName !== ANDROID_PROJECT_BUILD_TOOL) return undefined;
      const visibleParams = { ...event.params };
      delete visibleParams[ANDROID_PROJECT_BUILD_AUTHORIZATION_NONCE];
      try {
        parseAndroidProjectBuildParams(visibleParams);
        const identity = hostIdentity(event, context);
        const admission = admittedProjectRun(identity);
        if (!admission) {
          throw new Error("Android Project build requires the active ready Project run");
        }
        pruneExpired();
        if (pending.size >= MAX_PENDING_AUTHORIZATIONS) {
          throw new Error("Too many Android Project builds are pending");
        }
        const nonce = createId().toLowerCase();
        const record = {
          identity,
          admission,
          expiresAtMs: now() + AUTHORIZATION_TTL_MS,
          consumed: false,
          cancellation: new AbortController(),
          onAbort: () => retire(nonce),
        };
        assertCurrent(record);
        pending.set(nonce, record);
        identity.signal.addEventListener("abort", record.onAbort, { once: true });
        return { params: { [ANDROID_PROJECT_BUILD_AUTHORIZATION_NONCE]: nonce } };
      } catch (error) {
        return {
          block: true,
          blockReason: error instanceof Error ? error.message : "Android Project build is unavailable",
        };
      }
    },

    consumeAuthorization(nonce, request) {
      pruneExpired();
      const record = typeof nonce === "string" ? pending.get(nonce) : null;
      if (
        !record ||
        record.consumed ||
        record.identity.toolCallId !== request.toolCallId ||
        !["agentId", "sessionKey", "sessionId"].every(
          (name) => record.identity[name] === request.context?.[name],
        )
      ) {
        throw new Error("Android Project build was not authorized for this exact host Tool call");
      }
      try {
        assertCurrent(record);
      } catch (error) {
        retire(nonce);
        throw error;
      }
      record.consumed = true;
      return {
        async run(signal, execute) {
          const signals = [record.identity.signal, record.cancellation.signal, lifetime.signal];
          if (signal) signals.push(signal);
          const combined = AbortSignal.any(signals);
          try {
            assertCurrent(record, combined);
            return await execute(combined);
          } finally {
            remove(nonce);
          }
        },
      };
    },

    endRun(_event, context) {
      for (const [nonce, record] of pending) {
        if (
          ["agentId", "sessionKey", "sessionId", "runId"].every(
            (name) => record.identity[name] === context?.[name],
          )
        ) {
          retire(nonce);
        }
      }
    },

    close() {
      lifetime.abort(new Error("Android Project build runtime was stopped"));
      for (const nonce of pending.keys()) remove(nonce);
    },
  };
}
