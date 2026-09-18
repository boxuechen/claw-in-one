import { createHash, randomBytes, randomUUID, timingSafeEqual } from "node:crypto";
import {
  VSCREEN_PROTOCOL_VERSION,
  VSCREEN_STREAM_ROUTE,
  VScreenError,
  VScreenErrorCode,
} from "./protocol.mjs";

const TOKEN_LIFETIME_MS = 60_000;

function tokenDigest(token) {
  return createHash("sha256").update("claw-in-one-vscreen-token\0").update(token).digest();
}

function sameDigest(left, right) {
  return Buffer.isBuffer(left) &&
    Buffer.isBuffer(right) &&
    left.length === right.length &&
    timingSafeEqual(left, right);
}

function unavailable(extra = {}) {
  return { protocolVersion: VSCREEN_PROTOCOL_VERSION, status: "unavailable", ...extra };
}

function publicSession(session, includeToken = false) {
  if (!session) return unavailable();
  const common = {
    protocolVersion: VSCREEN_PROTOCOL_VERSION,
    status: session.claimed ? "streaming" : "ready",
    kind: session.kind,
    attachmentId: session.id,
    producerId: session.producer.id,
    targetRef: session.targetRef,
    streamPath: VSCREEN_STREAM_ROUTE,
    codec: session.source.codec,
    display: { ...session.source.display },
    capabilities: [...session.capabilities],
    ...(includeToken && !session.claimed && session.token
      ? { token: session.token, expiresAtMs: session.expiresAtMs }
      : {}),
  };
  if (session.kind === "probe") return { ...common, probeId: session.probeId };
  return {
    ...common,
    targetGeneration: session.targetGeneration,
    sourceGeneration: session.sourceGeneration,
    foreground: session.foreground,
    ...(session.workload
      ? {
          workload: {
            generation: session.workload.generation,
            requestId: session.workload.requestId,
          },
        }
      : {}),
  };
}

function validateSource(source, { probe = false } = {}) {
  if (!source || typeof source !== "object") {
    throw new VScreenError(VScreenErrorCode.unavailable, "VScreen producer returned no source");
  }
  if (
    typeof source.targetRef !== "string" ||
    source.targetRef.length === 0 ||
    source.targetRef.length > 512
  ) {
    throw new VScreenError(
      VScreenErrorCode.unavailable,
      "VScreen producer returned an invalid target reference",
    );
  }
  if (
    typeof source.sourceHandle !== "string" ||
    source.sourceHandle.length === 0 ||
    source.sourceHandle.length > 512
  ) {
    throw new VScreenError(
      VScreenErrorCode.unavailable,
      "VScreen producer returned an invalid source handle",
    );
  }
  if (typeof source.codec !== "string" || source.codec.length === 0 || source.codec.length > 64) {
    throw new VScreenError(VScreenErrorCode.unavailable, "VScreen producer returned an invalid codec");
  }
  const display = source.display;
  if (
    !display ||
    !Number.isSafeInteger(display.id) ||
    display.id <= 0 ||
    !Number.isSafeInteger(display.width) ||
    display.width < 1 ||
    display.width > 4096 ||
    !Number.isSafeInteger(display.height) ||
    display.height < 1 ||
    display.height > 4096 ||
    !Number.isSafeInteger(display.dpi) ||
    display.dpi < 72 ||
    display.dpi > 960
  ) {
    throw new VScreenError(
      VScreenErrorCode.unavailable,
      "VScreen producer returned invalid display geometry",
    );
  }
  if (!source.video || typeof source.video.on !== "function") {
    throw new VScreenError(VScreenErrorCode.unavailable, "VScreen producer returned no video stream");
  }
  if (!source.closed || typeof source.closed.then !== "function") {
    throw new VScreenError(VScreenErrorCode.unavailable, "VScreen producer returned no close signal");
  }
  const capabilities = Array.isArray(source.capabilities)
    ? [...new Set(source.capabilities.filter((value) => typeof value === "string" && value))]
    : ["video/h264"];
  if (probe && capabilities.includes("pointer-v1")) {
    throw new VScreenError(
      VScreenErrorCode.unavailable,
      "The onboarding probe cannot publish input capability",
    );
  }
  if (!probe && capabilities.includes("pointer-v1") && typeof source.input !== "function") {
    throw new VScreenError(
      VScreenErrorCode.unavailable,
      "VScreen producer returned no pointer input sink",
    );
  }
  return { source, capabilities: Object.freeze(capabilities) };
}

export class VScreenDisplayService {
  constructor(options) {
    this.registry = options.registry;
    this.workloads = options.workloads;
    this.now = options.now ?? Date.now;
    this.createId = options.randomUUID ?? randomUUID;
    this.createToken = options.createToken ?? (() => randomBytes(32).toString("hex"));
    this.setTimer = options.setTimer ?? setTimeout;
    this.clearTimer = options.clearTimer ?? clearTimeout;
    this.tokenLifetimeMs = options.tokenLifetimeMs ?? TOKEN_LIFETIME_MS;
    this.session = null;
    this.operation = null;
    this.nextTargetGeneration = 1;
    this.nextSourceGeneration = 1;
    this.nextWorkloadGeneration = 1;
  }

  async status(ownerKey) {
    const session = this.session;
    if (!session || session.ownerKey !== ownerKey) return unavailable();
    this.#ensureAuthorization(session);
    return publicSession(session, true);
  }

  async ensure(ownerKey, params) {
    return await this.#exclusive(async () => {
      const existing = this.session;
      if (existing) {
        this.#requireDisplay(existing, ownerKey, params.producerId);
        if (!existing.claimed && !existing.viewerWasClaimed) {
          this.#ensureAuthorization(existing);
          return publicSession(existing, true);
        }
        // A client that has ever attached cannot safely resume this H.264 byte
        // stream from an arbitrary offset. A later ensure represents a fresh App
        // process, whether or not its old WebSocket close arrived first.
        await this.#retire(existing, "viewer_replaced");
      }
      const producer = this.#producer(params.producerId);
      const validated = validateSource(await producer.producer.ensureTarget({ ownerKey }));
      const session = this.#publishDisplay({
        ownerKey,
        producer,
        source: validated.source,
        capabilities: validated.capabilities,
        foreground: "home",
      });
      return publicSession(session, true);
    });
  }

  async close(ownerKey, params) {
    return await this.#exclusive(async () => {
      const session = this.session;
      if (!session) {
        return {
          protocolVersion: VSCREEN_PROTOCOL_VERSION,
          status: "closed",
          attachmentId: params.attachmentId,
          targetGeneration: params.targetGeneration,
          sourceGeneration: params.sourceGeneration,
        };
      }
      if (
        session.kind !== "display" ||
        session.ownerKey !== ownerKey ||
        session.id !== params.attachmentId ||
        session.targetGeneration !== params.targetGeneration ||
        session.sourceGeneration !== params.sourceGeneration
      ) {
        throw new VScreenError(
          VScreenErrorCode.unauthorized,
          "VScreen close identity did not match",
        );
      }
      await this.#retire(session, "user_closed");
      return {
        protocolVersion: VSCREEN_PROTOCOL_VERSION,
        status: "closed",
        attachmentId: params.attachmentId,
        targetGeneration: params.targetGeneration,
        sourceGeneration: params.sourceGeneration,
      };
    });
  }

  async placeWorkload(ownerKey, params) {
    return await this.#exclusive(async () => {
      const origin = this.workloads?.resolve(params.workloadRequestId);
      if (
        !origin ||
        origin.producerId !== params.producerId ||
        origin.expiresAtMs <= this.now()
      ) {
        throw new VScreenError(
          VScreenErrorCode.unauthorized,
          "VScreen workload is missing, expired, or no longer belongs to its origin",
        );
      }
      let session = this.session;
      if (session) {
        this.#requireDisplay(session, ownerKey, params.producerId);
        if (session.workload?.requestId === params.workloadRequestId) {
          this.#ensureAuthorization(session);
          return publicSession(session, true);
        }
      }
      const producer = session?.producer ?? this.#producer(params.producerId);
      const source = await producer.producer.placeWorkload({
        requestId: params.workloadRequestId,
        origin,
        ownerKey,
        source: session?.source ?? null,
      });
      if (!session) {
        const validated = validateSource(source);
        session = this.#publishDisplay({
          ownerKey,
          producer,
          source: validated.source,
          capabilities: validated.capabilities,
          foreground: "workload",
        });
      } else if (
        source !== session.source ||
        source.targetRef !== session.targetRef ||
        source.sourceHandle !== session.source.sourceHandle
      ) {
        throw new VScreenError(
          VScreenErrorCode.unavailable,
          "VScreen producer replaced the display source during a workload mutation",
        );
      }
      session.workload = Object.freeze({
        generation: this.nextWorkloadGeneration++,
        requestId: params.workloadRequestId,
        origin,
        presented: false,
      });
      session.foreground = "workload";
      this.#ensureAuthorization(session);
      return publicSession(session, true);
    });
  }

  async framePresented(ownerKey, params) {
    const session = this.session;
    if (
      !session ||
      session.kind !== "display" ||
      session.ownerKey !== ownerKey ||
      session.id !== params.attachmentId
    ) {
      throw new VScreenError(
        VScreenErrorCode.unauthorized,
        "VScreen frame identity did not match",
      );
    }
    if (params.workloadRequestId !== undefined) {
      if (session.workload?.requestId !== params.workloadRequestId) {
        throw new VScreenError(
          VScreenErrorCode.unauthorized,
          "VScreen workload frame identity did not match",
        );
      }
      if (!session.workload.presented) {
        await session.producer.producer.verifyPresented({
          attachmentId: session.id,
          requestId: session.workload.requestId,
          ownerKey,
          targetRef: session.targetRef,
          source: session.source,
        });
        session.workload = Object.freeze({ ...session.workload, presented: true });
      }
    } else {
      session.homePresented = true;
    }
    return {
      protocolVersion: VSCREEN_PROTOCOL_VERSION,
      status: "recorded",
      attachmentId: session.id,
      ...(params.workloadRequestId
        ? { workloadRequestId: params.workloadRequestId }
        : { foreground: session.foreground }),
    };
  }

  async probeStatus(ownerKey, params) {
    const session = this.session;
    if (
      !session ||
      session.ownerKey !== ownerKey ||
      session.kind !== "probe" ||
      session.probeId !== params.probeId
    ) {
      return unavailable({ probeId: params.probeId });
    }
    this.#ensureAuthorization(session);
    return publicSession(session, true);
  }

  async startProbe(ownerKey, params) {
    return await this.#exclusive(async () => {
      const existing = this.session;
      if (existing) {
        if (
          existing.kind === "probe" &&
          existing.ownerKey === ownerKey &&
          existing.producer.id === params.producerId &&
          existing.probeId === params.probeId
        ) {
          this.#ensureAuthorization(existing);
          return publicSession(existing, true);
        }
        throw new VScreenError(
          VScreenErrorCode.busy,
          "VScreen is already active; onboarding probes are isolated",
          { retryable: true },
        );
      }
      const producer = this.#producer(params.producerId);
      const validated = validateSource(
        await producer.producer.prepareProbe({ ownerKey, probeId: params.probeId }),
        { probe: true },
      );
      const session = this.#publishProbe({
        ownerKey,
        producer,
        probeId: params.probeId,
        source: validated.source,
        capabilities: validated.capabilities,
      });
      return publicSession(session, true);
    });
  }

  async finishProbe(ownerKey, params) {
    return await this.#exclusive(async () => {
      const session = this.session;
      if (!session) return unavailable({ probeId: params.probeId });
      if (
        session.kind !== "probe" ||
        session.ownerKey !== ownerKey ||
        session.probeId !== params.probeId ||
        session.id !== params.attachmentId
      ) {
        throw new VScreenError(
          VScreenErrorCode.unauthorized,
          "VScreen probe identity did not match",
        );
      }
      await this.#retire(session, "probe_finished");
      return unavailable({ probeId: params.probeId });
    });
  }

  claim(token) {
    const session = this.session;
    if (
      !session ||
      session.claimed ||
      this.now() >= session.expiresAtMs ||
      typeof token !== "string" ||
      !/^[0-9a-f]{64}$/.test(token) ||
      !sameDigest(session.tokenDigest, tokenDigest(token))
    ) {
      throw new VScreenError(
        VScreenErrorCode.unauthorized,
        "VScreen stream authorization failed",
      );
    }
    session.claimed = true;
    session.viewerWasClaimed = true;
    session.token = null;
    session.tokenDigest = null;
    this.clearTimer(session.timer);
    session.timer = null;
    return {
      attachmentId: session.id,
      codec: session.source.codec,
      display: { ...session.source.display },
      capabilities: [...session.capabilities],
      video: session.source.video,
      input: (event) => this.#input(session, event),
      close: () => this.#releaseViewer(session),
    };
  }

  async closeAll(reason = "foundation_stopped") {
    const session = this.session;
    if (session) await this.#retire(session, reason);
  }

  #producer(producerId) {
    const producer = this.registry.resolve(producerId);
    if (!producer) {
      throw new VScreenError(
        VScreenErrorCode.unavailable,
        `The VScreen producer ${producerId} is not registered`,
        { retryable: true },
      );
    }
    return producer;
  }

  #requireDisplay(session, ownerKey, producerId) {
    if (session.kind !== "display") {
      throw new VScreenError(VScreenErrorCode.busy, "The onboarding probe is active", {
        retryable: true,
      });
    }
    if (session.ownerKey !== ownerKey) {
      throw new VScreenError(VScreenErrorCode.busy, "Another VScreen owner is active", {
        retryable: true,
      });
    }
    if (session.producer.id !== producerId) {
      throw new VScreenError(
        VScreenErrorCode.busy,
        "The active VScreen uses another producer",
        { retryable: true },
      );
    }
  }

  #publishDisplay(fields) {
    const session = {
      ...fields,
      kind: "display",
      id: this.createId(),
      targetRef: fields.source.targetRef,
      targetGeneration: this.nextTargetGeneration++,
      sourceGeneration: this.nextSourceGeneration++,
      workload: null,
      homePresented: false,
      claimed: false,
      viewerWasClaimed: false,
      token: null,
      tokenDigest: null,
      expiresAtMs: 0,
      timer: null,
      lastInputSequence: -1,
      activePointers: new Set(),
    };
    this.session = session;
    this.#watchSource(session);
    this.#ensureAuthorization(session);
    return session;
  }

  #publishProbe(fields) {
    const session = {
      ...fields,
      kind: "probe",
      id: this.createId(),
      targetRef: fields.source.targetRef,
      claimed: false,
      viewerWasClaimed: false,
      token: null,
      tokenDigest: null,
      expiresAtMs: 0,
      timer: null,
      lastInputSequence: -1,
      activePointers: new Set(),
    };
    this.session = session;
    this.#watchSource(session);
    this.#ensureAuthorization(session);
    return session;
  }

  #watchSource(session) {
    session.source.closed.then(
      () => {
        if (this.session === session) void this.#retire(session, "producer_closed");
      },
      () => {
        if (this.session === session) void this.#retire(session, "producer_failed");
      },
    );
  }

  #ensureAuthorization(session) {
    if (session.claimed || (session.token && this.now() < session.expiresAtMs)) return;
    if (session.timer !== null) this.clearTimer(session.timer);
    const token = this.createToken();
    if (!/^[0-9a-f]{64}$/.test(token)) {
      void this.#retire(session, "authorization_unavailable");
      throw new VScreenError(
        VScreenErrorCode.unavailable,
        "VScreen stream authorization is unavailable",
      );
    }
    session.token = token;
    session.tokenDigest = tokenDigest(token);
    session.expiresAtMs = this.now() + this.tokenLifetimeMs;
    session.timer = this.setTimer(() => {
      if (this.session !== session || session.claimed) return;
      session.token = null;
      session.tokenDigest = null;
      session.expiresAtMs = 0;
      session.timer = null;
    }, this.tokenLifetimeMs);
  }

  #releaseViewer(session) {
    if (this.session !== session || !session.claimed) return Promise.resolve();
    this.#cancelPointers(session);
    session.claimed = false;
    session.source.video.pause();
    this.#ensureAuthorization(session);
    return Promise.resolve();
  }

  async #retire(session, reason) {
    this.#cancelPointers(session);
    if (session.timer !== null) this.clearTimer(session.timer);
    session.timer = null;
    session.token = null;
    session.tokenDigest = null;
    if (this.session === session) this.session = null;
    await session.producer.producer.closeTarget(session.source, reason).catch(() => {});
  }

  #input(session, event) {
    if (
      this.session !== session ||
      !session.claimed ||
      !session.capabilities.includes("pointer-v1") ||
      !event ||
      typeof event !== "object" ||
      Array.isArray(event) ||
      Object.keys(event).length !== 8 ||
      event.type !== "pointer" ||
      event.attachmentId !== session.id ||
      !Number.isSafeInteger(event.sequence) ||
      event.sequence <= session.lastInputSequence ||
      !Number.isSafeInteger(event.pointerId) ||
      event.pointerId < 0 ||
      !["down", "move", "up", "cancel"].includes(event.phase) ||
      !Number.isFinite(event.normalizedX) ||
      event.normalizedX < 0 ||
      event.normalizedX > 1 ||
      !Number.isFinite(event.normalizedY) ||
      event.normalizedY < 0 ||
      event.normalizedY > 1 ||
      !Number.isFinite(event.pressure) ||
      event.pressure < 0 ||
      event.pressure > 1
    ) return false;
    const active = session.activePointers.has(event.pointerId);
    if ((event.phase === "down" && active) || (event.phase !== "down" && !active)) return false;
    if (session.source.input(event) === false) return false;
    session.lastInputSequence = event.sequence;
    if (event.phase === "down") session.activePointers.add(event.pointerId);
    else if (event.phase === "up" || event.phase === "cancel") session.activePointers.delete(event.pointerId);
    return true;
  }

  #cancelPointers(session) {
    if (!session.capabilities.includes("pointer-v1") || typeof session.source.input !== "function") return;
    for (const pointerId of session.activePointers) {
      session.lastInputSequence += 1;
      try {
        session.source.input({
          type: "pointer",
          attachmentId: session.id,
          sequence: session.lastInputSequence,
          pointerId,
          phase: "cancel",
          normalizedX: 0,
          normalizedY: 0,
          pressure: 0,
        });
      } catch {}
    }
    session.activePointers.clear();
  }

  async #exclusive(run) {
    if (this.operation) {
      throw new VScreenError(VScreenErrorCode.busy, "Another VScreen mutation is active", {
        retryable: true,
      });
    }
    this.operation = run();
    try {
      return await this.operation;
    } finally {
      this.operation = null;
    }
  }
}
