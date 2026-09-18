import { randomUUID } from "node:crypto";
import {
  inspectApkFingerprint,
  projectArtifactReceipt,
  sameApkFingerprint,
} from "./artifact-service.mjs";
import {
  ANDROID_APP_AUTHORIZATION_NONCE,
  ANDROID_APP_TOOL,
  parseAndroidAppToolParams,
} from "./tool-protocol.mjs";
import { assertDeviceBinding, androidAppOwnerFor } from "./tool-security.mjs";

const APPROVAL_TIMEOUT_MS = 120000;
const AUTHORIZATION_TTL_MS = APPROVAL_TIMEOUT_MS;
const MAX_PENDING_AUTHORIZATIONS = 64;
const PROJECT_WORKSPACE_ADMISSION_KEY = Symbol.for(
  "io.github.boxuechen.clawinone.project-workspaces.admission.v2",
);

function admittedProjectRun(identity) {
  return globalThis[PROJECT_WORKSPACE_ADMISSION_KEY]?.admitProjectMutation?.(
    identity,
    "android_kotlin",
  ) ?? null;
}

function hostIdentity(event, context) {
  const fields = ["agentId", "sessionKey", "sessionId", "runId", "toolCallId"];
  const identity = Object.fromEntries(fields.map((name) => [name, context?.[name]?.trim()]));
  if (fields.some((name) => !identity[name]) ||
      !identity.sessionKey.startsWith(`agent:${identity.agentId}:`) ||
      (event.toolCallId && event.toolCallId !== identity.toolCallId) ||
      (event.runId && event.runId !== identity.runId) ||
      !(context?.abortSignal instanceof AbortSignal)) {
    throw new Error("Android installation requires a trusted host Chat, Session, run, Tool call, and cancellation signal");
  }
  context.abortSignal.throwIfAborted();
  return { ...identity, signal: context.abortSignal };
}

function approvalDescription(artifact, current, mutationId) {
  return [
    `Install ${artifact.packageName} version ${artifact.versionName || artifact.versionCode} on ${artifact.target.model}?`,
    "",
    `Package: ${artifact.packageName}`,
    `Version code: ${artifact.versionCode}`,
    `Artifact SHA-256: ${artifact.sha256}`,
    ...artifact.signerSha256.map((digest) => `Signer SHA-256: ${digest}`),
    `Target: ${artifact.target.model} / API ${artifact.target.androidApi}`,
    `Currently installed: ${current.package.installed ? `yes, version ${current.package.versionCode ?? "unknown"}` : "no"}`,
    `Mutation: ${mutationId}`,
  ].join("\n");
}

export function createInstallApprovalBroker(dependencies) {
  const resolveDelivery = dependencies.resolveDelivery;
  const receiptStore = dependencies.receiptStore;
  const now = dependencies.now ?? Date.now;
  const createId = dependencies.randomUUID ?? randomUUID;
  const runTool = dependencies.runTool;
  const session = dependencies.session;
  const pending = new Map();
  const lifetime = new AbortController();

  function remove(nonce) {
    const record = pending.get(nonce);
    record?.identity.signal.removeEventListener("abort", record.onAbort);
    pending.delete(nonce);
    return record;
  }

  function retire(nonce) {
    remove(nonce)?.cancellation.abort(new Error("Android installation authorization was revoked"));
  }

  function readPolicy(identity, storePath) {
    lifetime.signal.throwIfAborted();
    identity.signal.throwIfAborted();
    const entry = session.getSessionEntry({
      agentId: identity.agentId,
      sessionKey: identity.sessionKey,
      storePath,
      readConsistency: "latest",
    });
    if (!entry || entry.sessionId !== identity.sessionId || entry.permissionModePending === true) {
      throw new Error("Android installation Session identity or applied policy is unavailable");
    }
    const mode = entry.permissionMode ?? null;
    if (![null, "guarded", "full", "workspace"].includes(mode)) {
      throw new Error("This Chat permission mode does not allow Android installation");
    }
    return mode;
  }

  function assertCurrent(record, signal = record.identity.signal) {
    signal.throwIfAborted();
    if (now() >= record.expiresAtMs || readPolicy(record.identity, record.storePath) !== record.mode) {
      throw new Error("Android installation authorization expired or the Chat permission changed; start fresh work");
    }
    if (receiptStore.get(record.ownerKey, record.artifactId) !== record.receipt) {
      throw new Error("Android artifact receipt is missing, expired, replaced, or belongs to another Chat");
    }
    if (record.mode === "workspace") {
      const current = admittedProjectRun(record.identity);
      if (
        !current ||
        current.projectId !== record.projectAdmission.projectId ||
        current.projectRoot !== record.projectAdmission.projectRoot ||
        current.leaseGeneration !== record.projectAdmission.leaseGeneration
      ) throw new Error("Android installation requires the active ready Project run");
    }
  }

  function pruneExpired() {
    const current = now();
    for (const [nonce, authorization] of pending) {
      if ((!authorization.consumed && current >= authorization.expiresAtMs) || authorization.identity.signal.aborted) retire(nonce);
    }
  }

  function assertCapacity() {
    pruneExpired();
    if (pending.size >= MAX_PENDING_AUTHORIZATIONS) {
      throw new Error("Too many Android installation approvals are pending");
    }
  }

  return {
    get closed() { return lifetime.signal.aborted; },
    async beforeToolCall(event, context) {
      if (event.toolName !== ANDROID_APP_TOOL || event.params?.operation !== "install_apk") {
        return undefined;
      }
      const visibleParams = { ...event.params };
      delete visibleParams[ANDROID_APP_AUTHORIZATION_NONCE];
      try {
        const params = parseAndroidAppToolParams(visibleParams);
        const identity = hostIdentity(event, context);
        const storePath = session.resolveStorePath(dependencies.sessionStore?.(), { agentId: identity.agentId });
        const mode = readPolicy(identity, storePath);
        const projectAdmission = mode === "workspace" ? admittedProjectRun(identity) : null;
        if (mode === "workspace" && !projectAdmission) {
          throw new Error("Android installation requires the active ready Project run");
        }
        const owner = androidAppOwnerFor(identity);
        const receipt = receiptStore.get(owner.ownerKey, params.artifactId);
        if (!receipt) throw new Error("Android artifact receipt is missing, expired, replaced, or belongs to another Chat");
        const delivery = resolveDelivery();
        if (!delivery) throw new Error("Android App Delivery is starting");
        const fingerprint = await inspectApkFingerprint({
          workspaceRoot: receipt.fingerprint.workspaceRoot,
          apkPath: receipt.fingerprint.apkPath,
          ...(runTool ? { runTool } : {}),
        });
        if (!sameApkFingerprint(receipt.fingerprint, fingerprint)) {
          throw new Error("The APK changed after inspection; inspect it again");
        }
        const current = await delivery.installedPackageState(receipt.fingerprint.metadata.packageName);
        assertDeviceBinding(receipt.deviceBinding, current.binding);
        assertCapacity();
        const mutationId = createId().toLowerCase();
        const nonce = createId().toLowerCase();
        const artifact = projectArtifactReceipt(receipt);
        const record = {
          identity, storePath, mode, projectAdmission, receipt,
          ownerKey: owner.ownerKey,
          toolCallId: identity.toolCallId,
          artifactId: receipt.artifactId,
          mutationId,
          expiresAtMs: now() + AUTHORIZATION_TTL_MS,
          approved: mode === "full" || mode === "workspace",
          resolved: mode === "full" || mode === "workspace",
          consumed: false,
          cancellation: new AbortController(),
          onAbort: () => retire(nonce),
        };
        assertCurrent(record);
        pending.set(nonce, record);
        identity.signal.addEventListener("abort", record.onAbort, { once: true });
        const rewritten = { ...visibleParams, [ANDROID_APP_AUTHORIZATION_NONCE]: nonce };
        if (mode === "full" || mode === "workspace") return { params: rewritten };
        return {
          params: rewritten,
          requireApproval: {
            title: `Install ${artifact.packageName}`,
            description: approvalDescription(artifact, current, mutationId),
            severity: "warning",
            timeoutMs: APPROVAL_TIMEOUT_MS,
            allowedDecisions: ["allow-once", "deny"],
            onResolution(decision) {
              // Canonical decision recording only. No admission, RPC, or installation here.
              if (pending.get(nonce) !== record || record.resolved) return;
              record.resolved = true;
              if (decision === "allow-once" && !identity.signal.aborted && now() < record.expiresAtMs) {
                record.approved = true;
              } else retire(nonce);
            },
          },
        };
      } catch (error) {
        return {
          block: true,
          blockReason: error instanceof Error ? error.message : "Android installation cannot be approved",
        };
      }
    },

    consumeAuthorization(nonce, request) {
      pruneExpired();
      const authorization = typeof nonce === "string" ? pending.get(nonce) : null;
      if (
        !authorization?.approved || authorization.consumed ||
        authorization.ownerKey !== request.ownerKey ||
        authorization.toolCallId !== request.toolCallId ||
        authorization.artifactId !== request.artifactId ||
        !["agentId", "sessionKey", "sessionId"].every((name) =>
          authorization.identity[name] === request.context?.[name])
      ) {
        throw new Error("Android installation was not authorized for this exact host Tool call");
      }
      try { assertCurrent(authorization); } catch (error) {
        retire(nonce);
        throw error;
      }
      authorization.consumed = true;
      let started = false;
      return {
        mutationId: authorization.mutationId,
        async run(signal, execute) {
          if (started) throw new Error("Android installation authorization was already consumed");
          started = true;
          const signals = [authorization.identity.signal, authorization.cancellation.signal, lifetime.signal];
          if (signal) signals.push(signal);
          const combined = AbortSignal.any(signals);
          try {
            assertCurrent(authorization, combined);
            return await session.runWithWorkAdmission({
              storePath: authorization.storePath,
              sessionKey: authorization.identity.sessionKey,
              signal: combined,
            }, async (admittedSignal) => {
              const executionSignal = AbortSignal.any([combined, admittedSignal]);
              const check = () => assertCurrent(authorization, executionSignal);
              check();
              return await execute({ signal: executionSignal, check });
            });
          } finally { remove(nonce); }
        },
      };
    },

    endRun(_event, context) {
      for (const [nonce, record] of pending) {
        if (["agentId", "sessionKey", "sessionId", "runId"].every((name) =>
          record.identity[name] === context?.[name])) retire(nonce);
      }
    },

    close() {
      lifetime.abort(new Error("Android App Delivery runtime was stopped"));
      for (const nonce of pending.keys()) remove(nonce);
    },
  };
}
