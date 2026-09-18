import { connectVScreenSourceRelay } from "./source-relay.mjs";
import { VSCREEN_PROTOCOL_VERSION, VScreenError, VScreenErrorCode, validateProducerId } from "./protocol.mjs";

const UUID_PATTERN = /^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/;

function responseSource(value) {
  if (
    value === null ||
    typeof value !== "object" ||
    value.protocolVersion !== VSCREEN_PROTOCOL_VERSION ||
    typeof value.sourceHandle !== "string" ||
    !UUID_PATTERN.test(value.sourceHandle) ||
    typeof value.targetRef !== "string" ||
    !value.targetRef ||
    value.codec !== "h264" ||
    !Number.isSafeInteger(value.display?.id) ||
    value.display.id <= 0 ||
    !Number.isSafeInteger(value.display?.width) ||
    value.display.width <= 0 ||
    !Number.isSafeInteger(value.display?.height) ||
    value.display.height <= 0 ||
    !Number.isSafeInteger(value.display?.dpi) ||
    value.display.dpi <= 0 ||
    !Array.isArray(value.capabilities) ||
    value.capabilities.some((capability) => typeof capability !== "string")
  ) {
    throw new VScreenError(
      VScreenErrorCode.unavailable,
      "VScreen producer returned an invalid source descriptor",
    );
  }
  return value;
}

function sameSource(descriptor, source) {
  return descriptor.sourceHandle === source.sourceHandle &&
    descriptor.targetRef === source.targetRef &&
    descriptor.codec === source.codec &&
    descriptor.display.id === source.display.id;
}

export function createRemoteVScreenProducer(api, binding, dependencies = {}) {
  const id = validateProducerId(binding?.id);
  const request =
    dependencies.request ??
    ((method, params, options) => api.runtime.gateway.request(method, params, options));
  const connectRelay = dependencies.connectRelay ?? connectVScreenSourceRelay;
  const call = async (method, params, timeoutMs = 25_000) => {
    try {
      return await request(method, { protocolVersion: VSCREEN_PROTOCOL_VERSION, ...params }, { timeoutMs });
    } catch (error) {
      const message =
        typeof error?.message === "string"
          ? error.message.replace(/^UNAVAILABLE:\s*/i, "").replace(/\s+/g, " ").trim().slice(0, 240)
          : "";
      throw new VScreenError(
        VScreenErrorCode.unavailable,
        message || "The VScreen producer bridge is unavailable",
        { retryable: true },
      );
    }
  };

  const openSource = async (method, params) => {
    const descriptor = responseSource(await call(method, params));
    try {
      const relay = await connectRelay(descriptor.transport);
      return {
        targetRef: descriptor.targetRef,
        sourceHandle: descriptor.sourceHandle,
        codec: descriptor.codec,
        display: { ...descriptor.display },
        capabilities: [...descriptor.capabilities],
        foreground: descriptor.foreground ?? "home",
        video: relay.video,
        closed: relay.closed,
        input: relay.input,
        relay,
      };
    } catch (error) {
      await call(binding.methods.close, {
        sourceHandle: descriptor.sourceHandle,
        reason: "relay_unavailable",
      }).catch(() => {});
      throw error;
    }
  };

  return Object.freeze({
    id,
    async ensureTarget({ ownerKey }) {
      return await openSource(binding.methods.ensure, { ownerKey });
    },
    async placeWorkload({ requestId, origin, ownerKey, source }) {
      const targetPackage = origin?.targetPackage;
      if (!source) {
        return await openSource(binding.methods.workload, {
          workloadRequestId: requestId,
          targetPackage,
          ownerKey,
        });
      }
      const descriptor = responseSource(
        await call(binding.methods.workload, {
          workloadRequestId: requestId,
          targetPackage,
          ownerKey,
          sourceHandle: source.sourceHandle,
          targetRef: source.targetRef,
        }),
      );
      if (!sameSource(descriptor, source)) {
        throw new VScreenError(
          VScreenErrorCode.unavailable,
          "VScreen producer changed source identity during workload placement",
        );
      }
      return source;
    },
    async verifyPresented({ requestId, ownerKey, source }) {
      return await call(binding.methods.presented, {
        sourceHandle: source.sourceHandle,
        workloadRequestId: requestId,
        ownerKey,
      });
    },
    async closeTarget(source, reason) {
      source?.relay?.close();
      if (!source?.sourceHandle) return;
      await call(binding.methods.close, {
        sourceHandle: source.sourceHandle,
        reason,
      }).catch(() => {});
    },
    async prepareProbe({ ownerKey, probeId }) {
      return await openSource(binding.methods.probe, { ownerKey, probeId });
    },
  });
}
