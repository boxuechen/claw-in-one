import {
  createArtifactReceiptStore,
  inspectApkFingerprint,
  projectArtifactReceipt,
  sameApkFingerprint,
} from "./artifact-service.mjs";
import {
  ANDROID_APP_TOOL,
  ANDROID_APP_TOOL_PARAMETERS,
  ANDROID_APP_TOOL_PROTOCOL_VERSION,
  parseAndroidAppToolParams,
} from "./tool-protocol.mjs";
import { assertDeviceBinding, sameDeviceBinding, androidAppOwnerFor } from "./tool-security.mjs";

const MAX_MUTATION_RECEIPTS = 64;

function workspaceRootFor(context) {
  return context?.fsPolicy?.root?.trim() || context?.workspaceDir?.trim() || null;
}

export function createInstallMutationStore(dependencies = {}) {
  const entries = new Map();

  function prune() {
    if (entries.size < MAX_MUTATION_RECEIPTS) return;
    for (const [key, entry] of entries) {
      if (entry.settled) {
        entries.delete(key);
        return;
      }
    }
    throw new Error("Too many Android installation mutations are still active");
  }

  return {
    runOnce(ownerKey, toolCallId, artifactId, authorize, run) {
      const key = `${ownerKey}\0${toolCallId}`;
      const existing = entries.get(key);
      if (existing) {
        if (existing.artifactId !== artifactId) {
          throw new Error("A Tool call cannot be reused for a different Android artifact");
        }
        return existing.promise;
      }
      prune();
      const authorization = authorize();
      const entry = {
        artifactId,
        mutationId: authorization.mutationId,
        settled: false,
        promise: null,
      };
      entry.promise = Promise.resolve()
        .then(() => run(entry.mutationId, authorization))
        .then((result) => ({ mutationId: entry.mutationId, ...result }))
        .finally(() => {
          entry.settled = true;
        });
      entries.set(key, entry);
      return entry.promise;
    },
  };
}

function toolResult(payload) {
  return {
    content: [{ type: "text", text: JSON.stringify(payload) }],
    details: payload,
  };
}

function requireReceipt(store, ownerKey, artifactId) {
  const receipt = store.get(ownerKey, artifactId);
  if (!receipt) {
    throw new Error("Android artifact receipt is missing, expired, replaced, or belongs to another Chat");
  }
  return receipt;
}

function assertBinding(receipt, binding) {
  assertDeviceBinding(receipt.deviceBinding, binding);
}

function artifactOperationParams(receipt) {
  return {
    packageName: receipt.fingerprint.metadata.packageName,
    versionCode: receipt.fingerprint.metadata.versionCode,
    sha256: receipt.fingerprint.sha256,
    validate(binding) {
      assertBinding(receipt, binding);
    },
  };
}

async function inspectForReceipt(receipt, runTool) {
  const current = await inspectApkFingerprint({
    workspaceRoot: receipt.fingerprint.workspaceRoot,
    apkPath: receipt.fingerprint.apkPath,
    runTool,
  });
  if (!sameApkFingerprint(receipt.fingerprint, current)) {
    throw new Error("The APK changed after inspection; inspect it again");
  }
  return current;
}

export function createAndroidAppTool(resolveDelivery, context, dependencies = {}) {
  const owner = androidAppOwnerFor(context);
  const workspaceRoot = workspaceRootFor(context);
  if (!owner || !workspaceRoot) return null;
  const receiptStore = dependencies.receiptStore ?? createArtifactReceiptStore(dependencies);
  const mutationStore = dependencies.mutationStore ?? createInstallMutationStore(dependencies);
  const approvalBroker = dependencies.approvalBroker;
  const readinessStore = dependencies.readinessStore;
  const runTool = dependencies.runTool;

  return {
    name: ANDROID_APP_TOOL,
    label: "Android App",
    description:
      "Inspect a workspace APK, install that exact inspected artifact on this phone, optionally place it on the App-global VScreen, then await exact artifact/display/first-frame/initial-foreground/log readiness evidence. VScreen direct input does not grant Android Use. Standard installation requires approval; explicit Full access and a server-verified active Project Workspace run do not. Always inspect after every rebuild. Never claim an install or VScreen succeeded from an unknown or vscreen_workload_requested result.",
    parameters: ANDROID_APP_TOOL_PARAMETERS,
    async execute(toolCallId, rawParams, signal) {
      const delivery =
        typeof resolveDelivery === "function" ? resolveDelivery() : resolveDelivery;
      if (!delivery) {
        throw new Error("Android App Delivery is not running");
      }
      const params = parseAndroidAppToolParams(rawParams);
      if (params.operation === "inspect_apk") {
        const before = await delivery.deviceBinding();
        const fingerprint = await inspectApkFingerprint({
          workspaceRoot,
          apkPath: params.apkPath,
          ...(runTool ? { runTool } : {}),
        });
        const after = await delivery.deviceBinding();
        if (!sameDeviceBinding(before, after)) {
          throw new Error("The verified Android phone connection changed during APK inspection");
        }
        const receipt = receiptStore.issue(owner, fingerprint, after);
        return toolResult({
          protocolVersion: ANDROID_APP_TOOL_PROTOCOL_VERSION,
          operation: params.operation,
          status: "inspected",
          artifact: projectArtifactReceipt(receipt),
        });
      }
      const receipt = requireReceipt(receiptStore, owner.ownerKey, params.artifactId);
      const artifact = projectArtifactReceipt(receipt);
      // An already-dispatched install keeps its canonical mutation result, even if its caller
      // has since stopped. Do not relabel an unknown installation as safely cancelled.
      if (signal?.aborted && params.operation !== "install_apk") return toolResult({ status: "cancelled", operation: params.operation });
      if (params.operation === "place_vscreen_workload") {
        const launched = await delivery.prepareInstalledWorkload(artifactOperationParams(receipt));
        if (!readinessStore) throw new Error("Android VScreen readiness service is unavailable");
        const workloadId = readinessStore.issue({
          ownerKey: owner.ownerKey,
          artifactId: receipt.artifactId,
          packageName: launched.packageName,
          component: launched.component,
          artifact,
          binding: launched.binding,
          requestedAtMs: launched.requestedAtMs,
        });
        return toolResult({
          protocolVersion: ANDROID_APP_TOOL_PROTOCOL_VERSION,
          operation: params.operation,
          status: launched.status,
          packageName: launched.packageName,
          component: launched.component,
          workloadId,
          artifact,
        });
      }
      if (params.operation === "await_vscreen_ready") {
        if (!readinessStore) throw new Error("Android VScreen readiness service is unavailable");
        const frame = await readinessStore.awaitFrame({
          ownerKey: owner.ownerKey,
          artifactId: receipt.artifactId,
          workloadId: params.workloadId,
          signal,
        });
        if (frame.status !== "frame_ready") {
          return toolResult({
            protocolVersion: ANDROID_APP_TOOL_PROTOCOL_VERSION,
            operation: params.operation,
            ...frame,
            artifact,
          });
        }
        try {
          signal?.throwIfAborted();
          const verified = await delivery.verifyPresentedApp({
            ...artifactOperationParams(receipt),
            component: frame.entry.component,
            displayId: frame.entry.vscreen.display.id,
            expectedGeneration: frame.entry.binding.generation,
            requestedAtMs: frame.entry.requestedAtMs,
          });
          return toolResult({
            protocolVersion: ANDROID_APP_TOOL_PROTOCOL_VERSION,
            operation: params.operation,
            status: "vscreen_ready",
            workloadId: params.workloadId,
            packageName: frame.entry.packageName,
            component: frame.entry.component,
            display: { ...frame.entry.vscreen.display },
            evidence: {
              installed: verified.installed,
              firstFrameAtMs: frame.entry.firstFrameAtMs,
              initialForegroundSamples: frame.entry.vscreen.initialForegroundSamples,
              launchLogLines: verified.launchLogLines,
              fatalCrash: false,
            },
            artifact,
          });
        } catch (error) {
          // A stopped Chat is cancellation, not a degraded VScreen result.
          // Preserve the abort reason instead of turning it into delivery output.
          signal?.throwIfAborted();
          return toolResult({
            protocolVersion: ANDROID_APP_TOOL_PROTOCOL_VERSION,
            operation: params.operation,
            status: "vscreen_unavailable",
            reason: error?.code ?? "readiness_check_failed",
            message: error instanceof Error ? error.message : "VScreen readiness could not be verified",
            workloadId: params.workloadId,
            artifact,
          });
        }
      }
      if (params.operation === "capture_screen") {
        const captured = await delivery.captureScreen(artifactOperationParams(receipt));
        const summary = {
          protocolVersion: ANDROID_APP_TOOL_PROTOCOL_VERSION,
          operation: params.operation,
          status: captured.status,
          packageName: captured.packageName,
          bytes: captured.png.length,
          artifact,
        };
        return {
          content: [
            { type: "text", text: JSON.stringify(summary) },
            { type: "image", data: captured.png.toString("base64"), mimeType: "image/png" },
          ],
          details: summary,
        };
      }
      if (params.operation === "read_logs") {
        const logs = await delivery.readPackageLogs({
          ...artifactOperationParams(receipt),
          maxLines: params.maxLines,
        });
        return toolResult({
          protocolVersion: ANDROID_APP_TOOL_PROTOCOL_VERSION,
          operation: params.operation,
          ...logs,
          artifact,
        });
      }
      if (typeof toolCallId !== "string" || toolCallId.trim().length === 0) {
        throw new Error("android_app installation requires a host Tool call identity");
      }
      if (!approvalBroker) {
        throw new Error("android_app installation authorization policy is unavailable");
      }
      const mutation = await mutationStore.runOnce(
        owner.ownerKey,
        toolCallId.trim(),
        receipt.artifactId,
        () =>
          approvalBroker.consumeAuthorization(params.authorizationNonce, {
            ownerKey: owner.ownerKey,
            toolCallId: toolCallId.trim(),
            artifactId: receipt.artifactId,
            context,
          }),
        async (_mutationId, authorization) =>
          authorization.run(signal, async ({ signal: admittedSignal, check }) => {
            const installed = await delivery.installApk({
              apkPath: receipt.fingerprint.realPath,
              packageName: artifact.packageName,
              versionCode: artifact.versionCode,
              sha256: artifact.sha256,
              signal: admittedSignal,
              async validate(binding) {
                check();
                assertBinding(receipt, binding);
                await inspectForReceipt(receipt, runTool);
              },
              authorizeDispatch(binding) {
                assertBinding(receipt, binding);
                check();
              },
            });
            return { ...installed, artifact };
          }),
      );
      return toolResult({
        protocolVersion: ANDROID_APP_TOOL_PROTOCOL_VERSION,
        operation: params.operation,
        ...mutation,
      });
    },
  };
}
