import { randomUUID } from "node:crypto";

export const ANDROID_VSCREEN_PRODUCER_ID = "claw-in-one-android-vscreen-producer";
const UUID_PATTERN = /^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/;

const POINTER_PHASE_ACTION = Object.freeze({ down: 0, up: 1, move: 2, cancel: 3 });

/** The only VScreen boundary that knows the pinned scrcpy v4.1 control bytes. */
export function serializeScrcpyPointerEvent(event, display) {
  if (
    !event ||
    event.type !== "pointer" ||
    typeof event.attachmentId !== "string" ||
    !Number.isSafeInteger(event.sequence) ||
    event.sequence < 0 ||
    !Number.isSafeInteger(event.pointerId) ||
    event.pointerId < 0 ||
    !Object.hasOwn(POINTER_PHASE_ACTION, event.phase) ||
    !Number.isFinite(event.normalizedX) ||
    event.normalizedX < 0 ||
    event.normalizedX > 1 ||
    !Number.isFinite(event.normalizedY) ||
    event.normalizedY < 0 ||
    event.normalizedY > 1 ||
    !Number.isFinite(event.pressure) ||
    event.pressure < 0 ||
    event.pressure > 1 ||
    !Number.isSafeInteger(display?.width) ||
    display.width < 1 ||
    display.width > 65535 ||
    !Number.isSafeInteger(display?.height) ||
    display.height < 1 ||
    display.height > 65535
  ) throw new Error("Android VScreen pointer event is invalid");
  const x = Math.min(display.width - 1, Math.max(0, Math.round(event.normalizedX * (display.width - 1))));
  const y = Math.min(display.height - 1, Math.max(0, Math.round(event.normalizedY * (display.height - 1))));
  const pressure = Math.round(event.pressure * 0xffff);
  const message = Buffer.alloc(32);
  message[0] = 2;
  message[1] = POINTER_PHASE_ACTION[event.phase];
  message.writeBigUInt64BE(BigInt(event.pointerId), 2);
  message.writeUInt32BE(x, 10);
  message.writeUInt32BE(y, 14);
  message.writeUInt16BE(display.width, 18);
  message.writeUInt16BE(display.height, 20);
  message.writeUInt16BE(pressure, 22);
  message.writeUInt32BE(0, 24);
  message.writeUInt32BE(0, 28);
  return message;
}

function writePointer(source, event) {
  const queued = { phase: event.phase, pointerId: event.pointerId, bytes: serializeScrcpyPointerEvent(event, source.display) };
  if (source.controlBackpressured) {
    const tail = source.controlQueue.at(-1);
    if (event.phase === "move" && tail?.phase === "move" && tail.pointerId === event.pointerId) {
      source.controlQueue[source.controlQueue.length - 1] = queued;
    } else {
      if (source.controlQueue.length >= 256) throw new Error("Android VScreen control queue is unavailable");
      source.controlQueue.push(queued);
    }
    return true;
  }
  source.controlBackpressured = !source.control.write(queued.bytes);
  return true;
}

function flushPointerQueue(source) {
  source.controlBackpressured = false;
  while (!source.controlBackpressured && source.controlQueue.length) {
    source.controlBackpressured = !source.control.write(source.controlQueue.shift().bytes);
  }
}

export function createAndroidVScreenProducer(options) {
  const createId = options.randomUUID ?? randomUUID;
  let activeDisplay = null;

  const createUuid = (name) => {
    const value = createId().toLowerCase();
    if (!UUID_PATTERN.test(value)) throw new Error(`Android VScreen ${name} identity is unavailable`);
    return value;
  };

  async function sourceFor({ helper, binding, probeId = null }) {
    const source = {
      targetRef: probeId ? binding.target.id : createUuid("target"),
      sourceHandle: createUuid("source"),
      codec: helper.codec,
      display: { ...helper.display },
      capabilities: probeId ? ["video/h264"] : ["video/h264", "pointer-v1"],
      video: helper.video,
      control: helper.control,
      closed: helper.closed,
      helper,
      binding,
      probeId,
      workload: null,
      closedByAdapter: false,
      controlQueue: [],
      controlBackpressured: false,
    };
    source.input = probeId ? undefined : (event) => writePointer(source, event);
    if (!probeId) helper.control.on("drain", () => flushPointerQueue(source));
    helper.closed.finally(() => {
      if (activeDisplay === source) activeDisplay = null;
      if (source.workload) {
        options.readinessStore.close({
          workloadId: source.workload.requestId,
          sourceHandle: source.sourceHandle,
          reason: "source_closed",
        });
      }
    });
    return source;
  }

  async function ensureDisplay() {
    if (activeDisplay && !activeDisplay.closedByAdapter) return activeDisplay;
    const deviceProducer = options.resolveDeviceProducer();
    if (!deviceProducer) throw new Error("Android VScreen producer is starting");
    const binding = await options.resolveBridge().deviceBinding();
    const helper = await deviceProducer.ensureTarget({ expectedGeneration: binding.generation });
    try {
      activeDisplay = await sourceFor({ helper, binding });
      return activeDisplay;
    } catch (error) {
      await helper.close("source_unavailable").catch(() => {});
      throw error;
    }
  }

  return Object.freeze({
    id: ANDROID_VSCREEN_PRODUCER_ID,
    async ensureTarget() {
      return await ensureDisplay();
    },
    async placeWorkload({ requestId, targetPackage, source }) {
      if (!UUID_PATTERN.test(requestId) || typeof targetPackage !== "string") {
        throw new Error("Android VScreen app assignment is invalid");
      }
      const active = source ?? await ensureDisplay();
      if (active !== activeDisplay || active.closedByAdapter || active.probeId) {
        throw new Error("Android VScreen target changed before workload placement");
      }
      const deviceProducer = options.resolveDeviceProducer();
      if (!deviceProducer) throw new Error("Android VScreen producer is starting");
      const placement = await deviceProducer.placeWorkload({
        expectedGeneration: active.binding.generation,
        displayId: active.display.id,
        packageName: targetPackage,
      });
      active.workload = Object.freeze({ requestId, targetPackage });
      options.readinessStore.attachVScreenIfPresent({
        workloadId: requestId,
        targetPackage,
        binding: active.binding,
        sourceHandle: active.sourceHandle,
        display: active.display,
        initialForegroundSamples: placement.observedSamples,
      });
      return active;
    },
    async verifyPresented({ requestId, targetRef, source }) {
      if (
        source !== activeDisplay ||
        source.closedByAdapter ||
        source.targetRef !== targetRef ||
        source.workload?.requestId !== requestId
      ) throw new Error("Android VScreen source identity changed before its workload frame");
      return options.readinessStore.frameReadyIfPresent({
        workloadId: source.workload.requestId,
        sourceHandle: source.sourceHandle,
      });
    },
    async closeSource(source, reason) {
      if (!source || source.closedByAdapter) return;
      source.closedByAdapter = true;
      if (activeDisplay === source) activeDisplay = null;
      if (source.workload) {
        options.readinessStore.close({
          workloadId: source.workload.requestId,
          sourceHandle: source.sourceHandle,
          reason,
        });
      }
      await source.helper.close(reason).catch(() => {});
    },
    async prepareProbe({ probeId }) {
      const deviceProducer = options.resolveDeviceProducer();
      if (!deviceProducer) throw new Error("Android VScreen producer is starting");
      const binding = await options.resolveBridge().deviceBinding();
      const helper = await deviceProducer.startProbe({ expectedGeneration: binding.generation });
      try {
        return await sourceFor({ helper, binding, probeId });
      } catch (error) {
        await helper.close("probe_unavailable").catch(() => {});
        throw error;
      }
    },
  });
}
