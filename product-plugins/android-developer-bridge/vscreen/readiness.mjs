import { randomUUID } from "node:crypto";
import { sameDeviceBinding } from "../app-delivery/tool-security.mjs";

const ENTRY_TTL_MS = 10 * 60 * 1000;
const WAIT_TIMEOUT_MS = 45_000;
const MAX_ENTRIES = 64;

function delay(ms, signal) {
  return new Promise((resolve, reject) => {
    const finish = () => {
      signal?.removeEventListener("abort", abort);
      resolve();
    };
    const timer = setTimeout(finish, ms);
    const abort = () => {
      clearTimeout(timer);
      signal?.removeEventListener("abort", abort);
      reject(signal.reason ?? new Error("VScreen readiness wait was cancelled"));
    };
    signal?.addEventListener("abort", abort, { once: true });
    if (signal?.aborted) abort();
  });
}

function unavailable(reason, message) {
  return { status: "vscreen_unavailable", reason, message };
}

export class AndroidVScreenReadinessStore {
  constructor(options = {}) {
    this.now = options.now ?? Date.now;
    this.createId = options.randomUUID ?? randomUUID;
    this.sleep = options.sleep ?? delay;
    this.ttlMs = options.ttlMs ?? ENTRY_TTL_MS;
    this.waitTimeoutMs = options.waitTimeoutMs ?? WAIT_TIMEOUT_MS;
    this.entries = new Map();
  }

  issue({ ownerKey, artifactId, packageName, component, artifact, binding, requestedAtMs }) {
    this.prune();
    const workloadId = this.createId().toLowerCase();
    if (!/^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/.test(workloadId)) {
      throw new Error("Android VScreen workload identity is unavailable");
    }
    this.entries.set(workloadId, {
      workloadId,
      ownerKey,
      artifactId,
      packageName,
      component,
      artifact: { ...artifact },
      binding,
      requestedAtMs,
      expiresAtMs: this.now() + this.ttlMs,
      vscreen: null,
      firstFrameAtMs: null,
      closed: null,
    });
    while (this.entries.size > MAX_ENTRIES) this.entries.delete(this.entries.keys().next().value);
    return workloadId;
  }

  attachVScreen({ workloadId, targetPackage, binding, sourceHandle, display, initialForegroundSamples }) {
    const entry = this.#entry(workloadId);
    if (entry.packageName !== targetPackage || !sameDeviceBinding(entry.binding, binding)) {
      throw new Error("Android VScreen workload no longer matches its verified artifact or phone");
    }
    if (entry.vscreen) {
      if (entry.vscreen.sourceHandle !== sourceHandle) throw new Error("Android VScreen workload is already attached");
      return;
    }
    entry.vscreen = {
      sourceHandle,
      display: { ...display },
      initialForegroundSamples,
      attachedAtMs: this.now(),
    };
  }

  attachVScreenIfPresent(params) {
    if (!this.entries.has(params.workloadId)) return false;
    this.attachVScreen(params);
    return true;
  }

  frameReady({ workloadId, sourceHandle }) {
    const entry = this.#entry(workloadId);
    if (!entry.vscreen || entry.vscreen.sourceHandle !== sourceHandle || entry.closed) {
      throw new Error("Android VScreen frame does not match the active workload");
    }
    entry.firstFrameAtMs ??= this.now();
    return { status: "recorded", workloadId, sourceHandle };
  }

  frameReadyIfPresent(params) {
    if (!this.entries.has(params.workloadId)) {
      return { status: "recorded", workloadId: params.workloadId, sourceHandle: params.sourceHandle };
    }
    return this.frameReady(params);
  }

  close({ workloadId, sourceHandle, reason }) {
    const entry = this.entries.get(workloadId);
    if (!entry?.vscreen || entry.vscreen.sourceHandle !== sourceHandle) return;
    entry.closed ??= { reason, atMs: this.now() };
  }

  async awaitFrame({ ownerKey, artifactId, workloadId, signal }) {
    const deadline = this.now() + this.waitTimeoutMs;
    for (;;) {
      const entry = this.entries.get(workloadId);
      if (!entry || entry.expiresAtMs <= this.now()) {
        return unavailable("workload_expired", "VScreen did not retain this workload request");
      }
      if (entry.ownerKey !== ownerKey || entry.artifactId !== artifactId) {
        throw new Error("Android VScreen workload is missing, expired, or belongs to another Chat or artifact");
      }
      if (entry.firstFrameAtMs !== null) return { status: "frame_ready", entry };
      if (entry.closed) {
        return unavailable(entry.closed.reason ?? "vscreen_closed", "VScreen ended before its first frame was shown");
      }
      if (this.now() >= deadline) {
        return unavailable("first_frame_timeout", "VScreen did not show a first frame in time");
      }
      await this.sleep(100, signal);
    }
  }

  prune() {
    const now = this.now();
    for (const [workloadId, entry] of this.entries) {
      if (entry.expiresAtMs <= now) this.entries.delete(workloadId);
    }
  }

  clearAll() {
    this.entries.clear();
  }

  #entry(workloadId) {
    this.prune();
    const entry = this.entries.get(workloadId);
    if (!entry) throw new Error("Android VScreen workload is missing or expired");
    return entry;
  }
}
