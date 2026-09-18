import { createHash } from "node:crypto";

const WEBSOCKET_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";
const MAX_CLIENT_FRAME_BYTES = 4096;

function writeHttpError(socket, status, message) {
  const body = `${message}\n`;
  socket.end(
    `HTTP/1.1 ${status}\r\nConnection: close\r\nContent-Type: text/plain; charset=utf-8\r\nContent-Length: ${Buffer.byteLength(body)}\r\n\r\n${body}`,
  );
}

function bearerToken(request) {
  const authorization = request.headers?.authorization;
  const match = typeof authorization === "string" ? authorization.match(/^Bearer ([0-9a-f]{64})$/) : null;
  return match?.[1] ?? null;
}

function websocketAccept(key) {
  return createHash("sha1").update(key).update(WEBSOCKET_GUID).digest("base64");
}

function encodeFrame(opcode, payload = Buffer.alloc(0)) {
  const content = Buffer.from(payload);
  let header;
  if (content.length < 126) {
    header = Buffer.from([0x80 | opcode, content.length]);
  } else if (content.length <= 0xffff) {
    header = Buffer.alloc(4);
    header[0] = 0x80 | opcode;
    header[1] = 126;
    header.writeUInt16BE(content.length, 2);
  } else {
    header = Buffer.alloc(10);
    header[0] = 0x80 | opcode;
    header[1] = 127;
    header.writeBigUInt64BE(BigInt(content.length), 2);
  }
  return Buffer.concat([header, content]);
}

function createFrameReader(handlers) {
  let pending = Buffer.alloc(0);
  return (chunk) => {
    pending = Buffer.concat([pending, Buffer.from(chunk)]);
    while (pending.length >= 2) {
      const first = pending[0];
      const second = pending[1];
      const fin = (first & 0x80) !== 0;
      const opcode = first & 0x0f;
      const masked = (second & 0x80) !== 0;
      let length = second & 0x7f;
      let offset = 2;
      if (!fin || !masked || opcode === 0 || (first & 0x70) !== 0) return handlers.protocolError();
      if (length === 126) {
        if (pending.length < 4) return;
        length = pending.readUInt16BE(2);
        offset = 4;
      } else if (length === 127) {
        if (pending.length < 10) return;
        const wideLength = pending.readBigUInt64BE(2);
        if (wideLength > BigInt(MAX_CLIENT_FRAME_BYTES)) return handlers.protocolError();
        length = Number(wideLength);
        offset = 10;
      }
      if (length > MAX_CLIENT_FRAME_BYTES) return handlers.protocolError();
      if (pending.length < offset + 4 + length) return;
      const mask = pending.subarray(offset, offset + 4);
      const payload = Buffer.from(pending.subarray(offset + 4, offset + 4 + length));
      for (let index = 0; index < payload.length; index += 1) payload[index] ^= mask[index % 4];
      pending = pending.subarray(offset + 4 + length);
      if (opcode === 0x8) handlers.close();
      else if (opcode === 0x1) handlers.text(payload);
      else if (opcode === 0x9) handlers.ping(payload);
      else if (opcode !== 0xa) return handlers.protocolError();
    }
  };
}

function bridge(socket, claimed, head) {
  let closed = false;
  const close = () => {
    if (closed) return;
    closed = true;
    claimed.video.removeListener("data", onVideo);
    socket.removeListener("drain", onDrain);
    claimed.video.pause();
    if (!socket.destroyed) socket.end(encodeFrame(0x8));
    void claimed.close();
  };
  const onDrain = () => claimed.video.resume();
  const onVideo = (chunk) => {
    if (closed || socket.destroyed) return;
    if (!socket.write(encodeFrame(0x2, chunk))) claimed.video.pause();
  };
  const readFrame = createFrameReader({
    text(payload) {
      try {
        const text = new TextDecoder("utf-8", { fatal: true }).decode(payload);
        if (claimed.input(JSON.parse(text)) !== true) close();
      } catch {
        close();
      }
    },
    ping(payload) {
      if (!closed && !socket.destroyed) socket.write(encodeFrame(0xa, payload));
    },
    close,
    protocolError: close,
  });
  socket.on("data", readFrame);
  socket.once("close", close);
  socket.once("error", close);
  claimed.video.on("data", onVideo);
  claimed.video.once("end", close);
  claimed.video.once("error", close);
  socket.on("drain", onDrain);
  if (head?.length) readFrame(head);
  claimed.video.resume();
}

export function createVScreenWebSocketRoute(resolveVScreen) {
  return {
    handler(_request, response) {
      response.statusCode = 426;
      response.setHeader("Connection", "close");
      response.end("WebSocket upgrade required\n");
      return true;
    },
    handleUpgrade(request, socket, head) {
      const key = request.headers?.["sec-websocket-key"];
      if (
        typeof key !== "string" ||
        !/^[A-Za-z0-9+/]{22}==$/.test(key) ||
        String(request.headers?.upgrade).toLowerCase() !== "websocket" ||
        String(request.headers?.["sec-websocket-version"]) !== "13"
      ) {
        writeHttpError(socket, "400 Bad Request", "Invalid WebSocket upgrade");
        return true;
      }
      const token = bearerToken(request);
      if (!token) {
        writeHttpError(socket, "401 Unauthorized", "VScreen authorization required");
        return true;
      }
      let claimed;
      try {
        claimed = resolveVScreen()?.claim(token);
        if (!claimed) throw new Error("VScreen unavailable");
      } catch {
        writeHttpError(socket, "401 Unauthorized", "VScreen authorization failed");
        return true;
      }
      socket.write(
        "HTTP/1.1 101 Switching Protocols\r\n" +
          "Upgrade: websocket\r\n" +
          "Connection: Upgrade\r\n" +
          `Sec-WebSocket-Accept: ${websocketAccept(key)}\r\n\r\n`,
      );
      bridge(socket, claimed, head);
      return true;
    },
  };
}
