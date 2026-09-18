const OWNER_ID_PATTERN = /^claw-in-one-[a-z0-9-]{1,48}$/u;
const PROJECT_ID_PATTERN = /^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$/u;
const MIN_DEVICE_PORT = 38000;
const MAX_DEVICE_PORT = 38999;

export const ANDROID_DEVICE_REVERSE_PORT_KEY = Symbol.for(
  "io.github.boxuechen.clawinone.android-device-bridge.reverse-port.v1",
);

function requireRequest(value) {
  if (
    !value || typeof value !== "object" || Array.isArray(value) ||
    !OWNER_ID_PATTERN.test(value.ownerId ?? "") ||
    !PROJECT_ID_PATTERN.test(value.projectId ?? "") ||
    !Number.isSafeInteger(value.hostPort) || value.hostPort < 1024 || value.hostPort > 65535 ||
    !Number.isSafeInteger(value.generation) || value.generation < 1
  ) throw new Error("Android reverse request is invalid");
  return value;
}

function requireRemoval(value) {
  if (
    !value || typeof value !== "object" || Array.isArray(value) ||
    !OWNER_ID_PATTERN.test(value.ownerId ?? "") ||
    !PROJECT_ID_PATTERN.test(value.projectId ?? "") ||
    !Number.isSafeInteger(value.generation) || value.generation < 1 ||
    Object.keys(value).some(key => !["ownerId", "projectId", "generation"].includes(key))
  ) throw new Error("Android reverse removal is invalid");
  return value;
}

function keyFor(ownerId, projectId) {
  return `${ownerId}\0${projectId}`;
}

function preferredPort(projectId) {
  let hash = 2166136261;
  for (const value of projectId) hash = Math.imul(hash ^ value.codePointAt(0), 16777619);
  return MIN_DEVICE_PORT + ((hash >>> 0) % (MAX_DEVICE_PORT - MIN_DEVICE_PORT + 1));
}

export function createAndroidDeviceReversePort(resolveBridge) {
  const forwards = new Map();

  function allocate(projectId, existing) {
    if (existing) return existing.devicePort;
    const used = new Set([...forwards.values()].map(value => value.devicePort));
    const preferred = preferredPort(projectId);
    for (let offset = 0; offset <= MAX_DEVICE_PORT - MIN_DEVICE_PORT; offset += 1) {
      const candidate = MIN_DEVICE_PORT + ((preferred - MIN_DEVICE_PORT + offset) % (MAX_DEVICE_PORT - MIN_DEVICE_PORT + 1));
      if (!used.has(candidate)) return candidate;
    }
    throw new Error("No Android reverse port is available");
  }

  return Object.freeze({
    async publish(raw) {
      const request = requireRequest(raw);
      const key = keyFor(request.ownerId, request.projectId);
      const existing = forwards.get(key);
      const devicePort = allocate(request.projectId, existing);
      const bridge = resolveBridge();
      if (!bridge) throw new Error("Android Device Bridge is unavailable");
      const published = await bridge.withDevice("web_project_reverse", async device => {
        await device.run([
          "-s", device.adbSerial, "reverse",
          `tcp:${devicePort}`,
          `tcp:${request.hostPort}`,
        ]);
        return {
          targetId: device.binding.target.id,
          bindingGeneration: device.binding.generation,
        };
      });
      const record = Object.freeze({
        ownerId: request.ownerId,
        projectId: request.projectId,
        generation: request.generation,
        hostPort: request.hostPort,
        devicePort,
        ...published,
      });
      forwards.set(key, record);
      return Object.freeze({
        protocolVersion: 1,
        status: "published",
        projectId: record.projectId,
        generation: record.generation,
        targetId: record.targetId,
        deviceUrl: `http://127.0.0.1:${record.devicePort}/`,
      });
    },

    async remove(raw) {
      const request = requireRemoval(raw);
      const key = keyFor(request.ownerId, request.projectId);
      const record = forwards.get(key);
      if (!record || record.generation !== request.generation) return Object.freeze({status: "unchanged"});
      forwards.delete(key);
      const bridge = resolveBridge();
      if (!bridge) return Object.freeze({status: "removed", cleanup: "device_unavailable"});
      await bridge.withDevice("web_project_reverse_remove", device =>
        device.run(["-s", device.adbSerial, "reverse", "--remove", `tcp:${record.devicePort}`], {
          allowFailure: true,
        }),
      );
      return Object.freeze({status: "removed", cleanup: "complete"});
    },

    invalidate() {
      forwards.clear();
    },

    snapshot(ownerId, projectId) {
      return forwards.get(keyFor(ownerId, projectId)) ?? null;
    },
  });
}
