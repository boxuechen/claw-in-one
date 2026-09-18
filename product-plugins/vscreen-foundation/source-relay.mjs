import { randomBytes, timingSafeEqual } from "node:crypto";
import net from "node:net";

const RELAY_PROTOCOL_VERSION = 1;
const RELAY_HOST = "127.0.0.1";
const TOKEN_PATTERN = /^[0-9a-f]{64}$/;
const MAX_AUTH_BYTES = 65;
const MAX_INPUT_BYTES = 4096;
const AUTH_TIMEOUT_MS = 5_000;

function relayToken(value) {
  if (typeof value !== "string" || !TOKEN_PATTERN.test(value)) {
    throw new Error("VScreen source relay token is invalid");
  }
  return value;
}

export function parseVScreenSourceRelayDescriptor(value) {
  if (value === null || typeof value !== "object" || Array.isArray(value)) {
    throw new Error("VScreen source relay descriptor is required");
  }
  if (
    value.protocolVersion !== RELAY_PROTOCOL_VERSION ||
    value.host !== RELAY_HOST ||
    !Number.isSafeInteger(value.port) ||
    value.port < 1024 ||
    value.port > 65535
  ) {
    throw new Error("VScreen source relay descriptor is invalid");
  }
  return Object.freeze({
    protocolVersion: RELAY_PROTOCOL_VERSION,
    host: RELAY_HOST,
    port: value.port,
    token: relayToken(value.token),
  });
}

function sameToken(left, right) {
  const a = Buffer.from(left, "ascii");
  const b = Buffer.from(right, "ascii");
  return a.length === b.length && timingSafeEqual(a, b);
}

export async function createVScreenSourceRelay(video, options = {}) {
  if (typeof video?.pipe !== "function" || typeof video?.unpipe !== "function") {
    throw new Error("VScreen source relay requires a readable video stream");
  }
  const token = relayToken((options.createToken ?? (() => randomBytes(32).toString("hex")))());
  let activeSocket = null;
  let claimed = false;
  let closed = false;
  let resolveClosed;
  const closedPromise = new Promise((resolve) => {
    resolveClosed = resolve;
  });
  const server = net.createServer((socket) => {
    if (closed || claimed) {
      socket.destroy();
      return;
    }
    let pending = Buffer.alloc(0);
    let inputPending = Buffer.alloc(0);
    const authTimer = setTimeout(() => socket.destroy(), options.authTimeoutMs ?? AUTH_TIMEOUT_MS);
    const fail = () => {
      clearTimeout(authTimer);
      socket.destroy();
    };
    const authenticate = (chunk) => {
      pending = Buffer.concat([pending, Buffer.from(chunk)]);
      if (pending.length > MAX_AUTH_BYTES) return fail();
      const newline = pending.indexOf(0x0a);
      if (newline < 0) return;
      const supplied = pending.subarray(0, newline).toString("ascii");
      if (newline !== 64 || !sameToken(token, supplied)) return fail();
      clearTimeout(authTimer);
      socket.removeListener("data", authenticate);
      claimed = true;
      activeSocket = socket;
      server.close();
      socket.write("OK\n");
      video.pipe(socket);
      const readInput = (inputChunk) => {
        inputPending = Buffer.concat([inputPending, Buffer.from(inputChunk)]);
        if (inputPending.length > MAX_INPUT_BYTES) return socket.destroy();
        for (;;) {
          const separator = inputPending.indexOf(0x0a);
          if (separator < 0) return;
          const line = inputPending.subarray(0, separator);
          inputPending = inputPending.subarray(separator + 1);
          if (line.length === 0) continue;
          try {
            const event = JSON.parse(line.toString("utf8"));
            if (typeof options.onInput !== "function" || options.onInput(event) === false) {
              socket.destroy();
              return;
            }
          } catch {
            socket.destroy();
            return;
          }
        }
      };
      socket.on("data", readInput);
      const remainder = pending.subarray(newline + 1);
      pending = Buffer.alloc(0);
      if (remainder.length) readInput(remainder);
      socket.once("close", () => {
        video.unpipe(socket);
        socket.removeListener("data", readInput);
        activeSocket = null;
        resolveClosed({ reason: "consumer_closed" });
      });
      socket.once("error", () => {});
    };
    socket.on("data", authenticate);
    socket.once("error", fail);
  });
  await new Promise((resolve, reject) => {
    server.once("error", reject);
    server.listen(0, RELAY_HOST, () => {
      server.removeListener("error", reject);
      resolve();
    });
  });
  server.unref();
  const address = server.address();
  if (!address || typeof address === "string") {
    server.close();
    throw new Error("VScreen source relay address is unavailable");
  }
  const claimTimer = setTimeout(() => {
    if (!claimed) {
      server.close();
      resolveClosed({ reason: "claim_timeout" });
    }
  }, options.claimTimeoutMs ?? AUTH_TIMEOUT_MS);
  const close = async (reason = "closed") => {
    if (closed) return closedPromise;
    closed = true;
    clearTimeout(claimTimer);
    video.unpipe(activeSocket ?? undefined);
    activeSocket?.destroy();
    activeSocket = null;
    server.close();
    resolveClosed({ reason });
    return closedPromise;
  };
  closedPromise.finally(() => clearTimeout(claimTimer));
  return Object.freeze({
    descriptor: Object.freeze({
      protocolVersion: RELAY_PROTOCOL_VERSION,
      host: RELAY_HOST,
      port: address.port,
      token,
    }),
    closed: closedPromise,
    close,
  });
}

export async function connectVScreenSourceRelay(rawDescriptor, options = {}) {
  const descriptor = parseVScreenSourceRelayDescriptor(rawDescriptor);
  const socket = net.createConnection({ host: descriptor.host, port: descriptor.port });
  let settled = false;
  const video = await new Promise((resolve, reject) => {
    let pending = Buffer.alloc(0);
    const finish = (callback) => {
      if (settled) return;
      settled = true;
      clearTimeout(timer);
      socket.removeListener("error", fail);
      socket.removeListener("close", closedBeforeAuth);
      socket.removeListener("data", readAck);
      callback();
    };
    const fail = (error) => finish(() => reject(error));
    const closedBeforeAuth = () => fail(new Error("VScreen source relay closed before authorization"));
    const readAck = (chunk) => {
      pending = Buffer.concat([pending, Buffer.from(chunk)]);
      if (pending.length < 3) return;
      if (pending.subarray(0, 3).toString("ascii") !== "OK\n") {
        fail(new Error("VScreen source relay authorization failed"));
        return;
      }
      const remainder = pending.subarray(3);
      finish(() => {
        socket.pause();
        if (remainder.length) socket.unshift(remainder);
        resolve(socket);
      });
    };
    const timer = setTimeout(() => {
      socket.destroy();
      fail(new Error("VScreen source relay authorization timed out"));
    }, options.timeoutMs ?? AUTH_TIMEOUT_MS);
    socket.once("error", fail);
    socket.once("close", closedBeforeAuth);
    socket.on("data", readAck);
    socket.once("connect", () => socket.write(`${descriptor.token}\n`));
  });
  let resolveClosed;
  const closed = new Promise((resolve) => {
    resolveClosed = resolve;
  });
  video.once("close", () => resolveClosed({ reason: "relay_closed" }));
  video.once("error", () => resolveClosed({ reason: "relay_failed" }));
  video.on("error", () => {});
  return Object.freeze({
    video,
    closed,
    input(event) {
      if (video.destroyed) return false;
      const line = `${JSON.stringify(event)}\n`;
      if (Buffer.byteLength(line) > MAX_INPUT_BYTES) return false;
      return video.write(line);
    },
    close() {
      video.destroy();
    },
  });
}
