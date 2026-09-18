import { createHash, randomUUID } from "node:crypto";
import { isDeepStrictEqual } from "node:util";

export const ANDROID_USE_AUTHORIZATION_NONCE = "__clawAndroidUseAuthorization";
export const ANDROID_USE_DISPATCH_NONCE = "authorizationNonce";
const CALL_LIFETIME_MS = 120000;
const CONTROL_LIFETIME_MS = 5 * 60 * 1000;
const RUN_END_GRACE_MS = 250;
const MAX_RECORDS = 64;

function requiredIdentity(context, event) {
  const fields = ["agentId", "sessionKey", "sessionId", "runId", "toolCallId"];
  const identity = Object.fromEntries(fields.map((name) => [name, context?.[name]?.trim()]));
  if (fields.some((name) => !identity[name]) ||
      !identity.sessionKey.startsWith(`agent:${identity.agentId}:`) ||
      (event?.toolCallId && event.toolCallId !== identity.toolCallId) ||
      (event?.runId && event.runId !== identity.runId) ||
      !(context?.abortSignal instanceof AbortSignal)) {
    throw new Error("Android Use requires a trusted host Chat, Session, run, Tool call, and cancellation signal");
  }
  context.abortSignal.throwIfAborted();
  return { ...identity, signal: context.abortSignal };
}

function sameRun(left, right) {
  return ["agentId", "sessionKey", "sessionId", "runId"].every((name) => left[name] === right[name]);
}

function ownerKey(sessionKey) {
  return createHash("sha256").update(sessionKey).digest("hex");
}

/**
 * Host-only authorization for Android Use. The tool grammar and Node transport are injected;
 * this owner contains only bounded, process-local calls, one-use dispatches, and live controls.
 * A nonce is never sufficient alone: every boundary checks host identity, policy, and signals.
 */
export function createAndroidUseAuthorization(api, dependencies) {
  const parseRequest = dependencies.parseRequest;
  const now = dependencies.now ?? Date.now;
  const createId = dependencies.randomUUID ?? randomUUID;
  const runEndGraceMs = dependencies.runEndGraceMs ?? RUN_END_GRACE_MS;
  const pending = new Map();
  const dispatches = new Map();
  const controls = new Map();
  const cleanupTasks = new Set();
  const session = api.runtime.agent.session;
  let closed = false;

  function readPolicy(identity, storePath) {
    identity.signal.throwIfAborted();
    const entry = session.getSessionEntry({
      agentId: identity.agentId,
      sessionKey: identity.sessionKey,
      storePath,
      readConsistency: "latest",
    });
    if (!entry || entry.sessionId !== identity.sessionId || entry.permissionModePending === true) {
      throw new Error("Android Use Session identity or applied policy is unavailable");
    }
    const mode = entry.permissionMode ?? null;
    if (![null, "guarded", "full", "workspace", "read-only"].includes(mode)) {
      throw new Error("Unsupported Android Use Session permission mode");
    }
    return mode;
  }

  function assertCurrent(record, signal = record.identity.signal) {
    if (closed) throw new Error("Android Use runtime was stopped");
    signal.throwIfAborted();
    if (now() >= record.expiresAtMs || readPolicy(record.identity, record.storePath) !== record.mode) {
      throw new Error("Android Use authorization expired or the Chat permission changed; start fresh work");
    }
    if (record.request.operation !== "stop" && record.mode === "read-only") {
      throw new Error("Read-only Chat cannot control Android apps");
    }
    if (record.control.retired) throw new Error("Android Use control generation was revoked");
  }

  function removePending(nonce) {
    const record = pending.get(nonce);
    if (record) record.identity.signal.removeEventListener("abort", record.onAbort);
    pending.delete(nonce);
    return record;
  }

  function retire(control, nativeStopConfirmed = false, cleanupMode = "revoke") {
    if (control.retired) return;
    control.retired = true;
    controls.delete(control.controlId);
    clearTimeout(control.expiryTimer);
    clearTimeout(control.abortTimer);
    control.identity.signal.removeEventListener("abort", control.onAbort);
    for (const [nonce, record] of pending) {
      if (record.control === control) removePending(nonce);
    }
    // Native cleanup removes only this exact control generation. It deliberately does not
    // borrow the aborted work signal, start another action, or resolve a pending approval.
    if (control.dispatched && !nativeStopConfirmed) {
      const cleanupAction = cleanupMode === "release" ? dependencies.releaseControl : dependencies.revokeControl;
      const cleanup = Promise.resolve().then(() => cleanupAction(control, async (envelope, invoke) => {
        const nonce = createId();
        const params = structuredClone({ ...envelope, [ANDROID_USE_DISPATCH_NONCE]: nonce });
        dispatches.set(nonce, { cleanup: true, nodeId: control.nodeId, params, expiresAtMs: now() + 5000 });
        try {
          return await invoke(params);
        } finally {
          dispatches.delete(nonce);
        }
      })).catch((error) => dependencies.onRevocationUnconfirmed?.(control, error));
      cleanupTasks.add(cleanup);
      // The cleanup owner waits for both native cleanup and its bounded notice delivery.
      void cleanup.finally(() => cleanupTasks.delete(cleanup)).catch(() => {});
    }
  }

  function prune() {
    for (const [nonce, record] of pending) {
      if (now() >= record.expiresAtMs || record.identity.signal.aborted) removePending(nonce);
    }
    for (const control of controls.values()) {
      if (now() >= control.expiresAtMs || control.identity.signal.aborted) retire(control);
    }
  }

  function activate(control) {
    if (controls.has(control.controlId)) return;
    control.identity.signal.throwIfAborted();
    control.expiresAtMs = now() + CONTROL_LIFETIME_MS;
    controls.set(control.controlId, control);
    // The host can abort Tool-call signals immediately before its awaited agent_end hook.
    // Leave a short window for that hook to choose release vs revoke, with a bounded revoke
    // fallback if the hook is unavailable or never arrives.
    control.onAbort = () => {
      control.abortTimer = setTimeout(() => retire(control), runEndGraceMs);
      control.abortTimer.unref?.();
    };
    control.identity.signal.addEventListener("abort", control.onAbort, { once: true });
    control.expiryTimer = setTimeout(() => retire(control), Math.max(1, control.expiresAtMs - now()));
    control.expiryTimer.unref?.();
  }

  return {
    get closed() { return closed; },
    async beforeToolCall(event, context) {
      if (event.toolName !== "android_use") return undefined;
      try {
        if (closed) throw new Error("Android Use runtime was stopped");
        prune();
        if (pending.size >= MAX_RECORDS || controls.size >= MAX_RECORDS) {
          throw new Error("Too many Android Use calls are pending");
        }
        const identity = requiredIdentity(context, event);
        const visibleParams = { ...event.params };
        delete visibleParams[ANDROID_USE_AUTHORIZATION_NONCE];
        const request = parseRequest(visibleParams);
        const storePath = session.resolveStorePath(api.config?.session?.store, { agentId: identity.agentId });
        const mode = readPolicy(identity, storePath);
        if (mode === "read-only" && request.operation !== "stop") {
          throw new Error("Read-only Chat cannot control Android apps");
        }
        // Only the native consent-aware protocol can receive these calls. Its lease admission
        // checks the phone's saved grant immediately before any target launch or action.
        // Full and model parameters cannot replace that native grant.
        await dependencies.assertNativeConsentSupport();
        identity.signal.throwIfAborted();
        let control;
        if (request.acquire) {
          control = {
            identity,
            storePath,
            controlId: createId(),
            executionKey: createId(),
            ownerKey: ownerKey(identity.sessionKey),
            targetPackage: request.targetPackage,
            display: request.display,
            assignmentId: null,
            mode,
            expiresAtMs: now() + CONTROL_LIFETIME_MS,
            retired: false,
            dispatched: false,
          };
        } else {
          control = controls.get(request.controlId);
          if (!control || !sameRun(control.identity, identity) || control.mode !== mode ||
              control.targetPackage !== request.targetPackage || control.display !== request.display || control.retired) {
            throw new Error("Android Use continuation requires this run's exact active control");
          }
        }
        const nonce = createId();
        const record = {
          identity, request, control, mode, storePath,
          expiresAtMs: now() + CALL_LIFETIME_MS,
          onAbort: () => removePending(nonce),
        };
        pending.set(nonce, record);
        identity.signal.addEventListener("abort", record.onAbort, { once: true });
        const params = { ...visibleParams, [ANDROID_USE_AUTHORIZATION_NONCE]: nonce };
        return { params };
      } catch (error) {
        return { block: true, blockReason: error instanceof Error ? error.message : "Android Use authorization is unavailable" };
      }
    },

    async runTool(toolCallId, params, toolContext, signal, run) {
      prune();
      const nonce = params?.[ANDROID_USE_AUTHORIZATION_NONCE];
      const record = removePending(nonce);
      if (!record || record.identity.toolCallId !== toolCallId ||
          ["agentId", "sessionKey", "sessionId"].some((name) => record.identity[name] !== toolContext?.[name]) ||
          !isDeepStrictEqual(record.request, parseRequest(params))) {
        throw new Error("Android Use was not authorized for this exact host Tool call");
      }
      const combined = signal ? AbortSignal.any([signal, record.identity.signal]) : record.identity.signal;
      try {
        assertCurrent(record, combined);
        // Register the run's cancellation before asynchronous admission, even for a control
        // not yet sent to Android. agent_end must fence work still waiting at this boundary.
        activate(record.control);
        return await session.runWithWorkAdmission({ storePath: record.storePath, sessionKey: record.identity.sessionKey, signal: combined }, async (lifecycleSignal) => {
          const admittedSignal = AbortSignal.any([combined, lifecycleSignal]);
          assertCurrent(record, admittedSignal);
          const onAbort = () => retire(record.control);
          admittedSignal.addEventListener("abort", onAbort, { once: true });
          try {
            const result = await run({
              request: record.request,
              control: record.control,
              signal: admittedSignal,
              async dispatch(nodeId, envelope, invoke) {
                assertCurrent(record, admittedSignal);
                if (record.dispatched) throw new Error("Android Use Tool call has already been dispatched");
                record.dispatched = true;
                const ticket = createId();
                const params = structuredClone({ ...envelope, [ANDROID_USE_DISPATCH_NONCE]: ticket });
                dispatches.set(ticket, { record, signal: admittedSignal, nodeId, params });
                try {
                  return await invoke(params);
                } finally {
                  dispatches.delete(ticket);
                }
              },
            });
            if (record.request.operation === "stop") retire(record.control, true);
            return result;
          } finally {
            admittedSignal.removeEventListener("abort", onAbort);
          }
        });
      } catch (error) {
        retire(record.control);
        throw error;
      }
    },

    async handleNode(context) {
      const nonce = context.params?.[ANDROID_USE_DISPATCH_NONCE];
      const dispatch = dispatches.get(nonce);
      dispatches.delete(nonce);
      if (!dispatch || context.nodeId !== dispatch.nodeId || !isDeepStrictEqual(context.params, dispatch.params)) {
        return { ok: false, code: "ANDROID_USE_NOT_AUTHORIZED", message: "Android Use requires a fresh host dispatch authorization" };
      }
      if (dispatch.cleanup) {
        if (now() >= dispatch.expiresAtMs) {
          return { ok: false, code: "ANDROID_USE_REVOKE_EXPIRED", message: "Android Use cleanup was not confirmed in time" };
        }
        return await context.invokeNode();
      }
      try {
        assertCurrent(dispatch.record, dispatch.signal);
      } catch {
        return { ok: false, code: "ANDROID_USE_AUTHORIZATION_REVOKED", message: "Android Use authorization changed before dispatch" };
      }
      dispatch.record.control.dispatched = true;
      dispatch.record.control.nodeId = context.nodeId;
      // No await between the last policy read and the one native dispatch.
      return await context.invokeNode();
    },

    async endRun(event, context) {
      const identity = { ...context, runId: context?.runId ?? event?.runId };
      if (!["agentId", "sessionKey", "sessionId", "runId"].every((name) => typeof identity[name] === "string" && identity[name])) return;
      for (const [nonce, record] of pending) {
        if (sameRun(record.identity, identity)) removePending(nonce);
      }
      for (const control of controls.values()) {
        if (sameRun(control.identity, identity)) retire(control, false, event?.success === true ? "release" : "revoke");
      }
      await Promise.allSettled([...cleanupTasks]);
    },

    async close() {
      closed = true;
      for (const nonce of pending.keys()) removePending(nonce);
      for (const control of controls.values()) retire(control);
      await Promise.allSettled([...cleanupTasks]);
      dispatches.clear();
    },
  };
}
