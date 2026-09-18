import { createHash, randomBytes } from "node:crypto";
import fs from "node:fs/promises";
import net from "node:net";
import os from "node:os";
import path from "node:path";
import { AndroidDeviceError, AndroidDeviceErrorCode } from "../bridge/protocol.mjs";

export const SCRCPY_SERVER_VERSION = "4.1";
export const SCRCPY_SERVER_SIZE_BYTES = 733706;
export const SCRCPY_SERVER_SHA256 = "deacb991ed2509715160ffdc7907e47b4160eb30d1566217e9047fd5b8850cae";

const ANDROID_VSCREEN_WIDTH = 720;
const ANDROID_VSCREEN_HEIGHT = 1560;
const ANDROID_VSCREEN_DPI = 320;
const DEVICE_HELPER_PATH = "/data/local/tmp/claw-in-one-scrcpy-server-v4.1.jar";
const HELPER_START_TIMEOUT_MS = 15000;
const MAX_HELPER_LOG_BYTES = 64 * 1024;

/**
 * VScreen keeps Android's virtual-display decorations so the stock Home activity
 * remains available. Native fullscreen presentation hides the host system bars.
 */
export function scrcpyVScreenServerArguments(adbSerial, scidHex) {
  return [
    "-s",
    adbSerial,
    "shell",
    `CLASSPATH=${DEVICE_HELPER_PATH}`,
    "app_process",
    "/",
    "com.genymobile.scrcpy.Server",
    SCRCPY_SERVER_VERSION,
    `scid=${scidHex}`,
    "log_level=info",
    "audio=false",
    "control=true",
    "tunnel_forward=true",
    `new_display=${ANDROID_VSCREEN_WIDTH}x${ANDROID_VSCREEN_HEIGHT}/${ANDROID_VSCREEN_DPI}`,
    "vd_system_decorations=true",
    "video_codec=h264",
    "video_bit_rate=4000000",
    "max_fps=30",
    "capture_orientation=@",
    "send_device_meta=false",
    "send_dummy_byte=false",
    "clipboard_autosync=false",
    "power_on=false",
    "cleanup=true",
  ];
}

function helperError(message, retryable = true) {
  return new AndroidDeviceError(AndroidDeviceErrorCode.vscreenUnavailable, message, { retryable });
}

async function digestFile(file) {
  const content = await fs.readFile(file);
  return createHash("sha256").update(content).digest("hex");
}

async function validQualifiedHelper(file, release) {
  try {
    const info = await fs.lstat(file);
    return (
      info.isFile() &&
      !info.isSymbolicLink() &&
      info.size === release.sizeBytes &&
      (await digestFile(file)) === release.sha256
    );
  } catch {
    return false;
  }
}

export async function requireQualifiedScrcpyServer(options) {
  const source = options.source;
  const release =
    options.release ??
    {
      sizeBytes: SCRCPY_SERVER_SIZE_BYTES,
      sha256: SCRCPY_SERVER_SHA256,
    };
  if (await validQualifiedHelper(source, release)) return source;
  throw helperError("The Supervisor-qualified VScreen runtime asset is missing or invalid", false);
}

function connectLocalSocket(socketPath, options = {}) {
  const timeoutMs = options.timeoutMs ?? HELPER_START_TIMEOUT_MS;
  const retryMs = options.retryMs ?? 100;
  const startedAt = Date.now();
  return new Promise((resolve, reject) => {
    const attempt = () => {
      const socket = net.createConnection({ path: socketPath });
      const fail = () => {
        socket.destroy();
        if (Date.now() - startedAt >= timeoutMs) {
          reject(helperError("The Android VScreen helper transport did not become ready"));
          return;
        }
        setTimeout(attempt, retryMs);
      };
      socket.once("error", fail);
      socket.once("connect", () => {
        socket.removeListener("error", fail);
        socket.on("error", () => {});
        socket.setNoDelay(true);
        resolve(socket);
      });
    };
    attempt();
  });
}

function waitForDisplay(readDisplayId, processClosed, timeoutMs = HELPER_START_TIMEOUT_MS) {
  return new Promise((resolve, reject) => {
    let settled = false;
    const finish = (callback) => {
      if (settled) return;
      settled = true;
      clearInterval(poll);
      clearTimeout(timeout);
      callback();
    };
    const inspect = () => {
      const displayId = readDisplayId();
      if (Number.isSafeInteger(displayId) && displayId > 0) finish(() => resolve(displayId));
    };
    const poll = setInterval(inspect, 25);
    const timeout = setTimeout(
      () => finish(() => reject(helperError("Android did not create the VScreen display"))),
      timeoutMs,
    );
    processClosed.then(
      () => finish(() => reject(helperError("The Android VScreen helper exited during startup"))),
      () => finish(() => reject(helperError("ADB could not start the Android VScreen helper"))),
    );
    inspect();
  });
}

async function waitForDeviceSocket(options, socketName, process) {
  const startedAt = Date.now();
  while (Date.now() - startedAt < HELPER_START_TIMEOUT_MS) {
    if (process.exitCode !== null || process.signalCode !== null) {
      throw helperError("The Android VScreen helper exited during startup");
    }
    const sockets = await options.runCommand(
      ["-s", options.adbSerial, "shell", "cat", "/proc/net/unix"],
      { allowFailure: true, timeoutMs: 5000 },
    );
    if (sockets.code === 0 && sockets.stdout.includes(`@${socketName}`)) return;
    await new Promise((resolve) => setTimeout(resolve, 50));
  }
  throw helperError("The Android VScreen helper transport did not become ready");
}

export async function startScrcpyVScreenHelper(options) {
  const helperPath = await requireQualifiedScrcpyServer({ source: options.helperPath });
  const scid = (options.scid ?? randomBytes(4).readUInt32BE(0)) & 0x7fffffff;
  const scidHex = scid.toString(16).padStart(8, "0");
  const socketName = `scrcpy_${scidHex}`;
  let socketDirectory = null;
  let socketPath = null;
  let forwardEndpoint = null;
  let process = null;
  let video = null;
  let control = null;
  let displayId = null;
  let helperLog = "";
  let closeStarted = false;
  let processClosed = null;
  let resolveClosed;
  const closed = new Promise((resolve) => {
    resolveClosed = resolve;
  });

  const close = async (reason = "closed") => {
    if (closeStarted) return closed;
    closeStarted = true;
    video?.destroy();
    control?.destroy();
    if (process && process.exitCode === null && process.signalCode === null) process.kill("SIGTERM");
    if (processClosed) {
      await Promise.race([
        processClosed.catch(() => {}),
        new Promise((resolve) => setTimeout(resolve, 3000)),
      ]);
    }
    if (forwardEndpoint !== null) {
      await options.runCommand(
        ["-s", options.adbSerial, "forward", "--remove", forwardEndpoint],
        { allowFailure: true, timeoutMs: 5000 },
      ).catch(() => {});
    }
    if (socketDirectory) await fs.rm(socketDirectory, { recursive: true, force: true }).catch(() => {});
    await options.runCommand(
      ["-s", options.adbSerial, "shell", "rm", "-f", DEVICE_HELPER_PATH],
      { allowFailure: true, timeoutMs: 5000 },
    ).catch(() => {});
    resolveClosed({ reason });
    return closed;
  };

  try {
    await options.runCommand(["-s", options.adbSerial, "push", helperPath, DEVICE_HELPER_PATH], {
      timeoutMs: 30000,
    });
    // This transport stays inside Debian. A private Unix socket avoids advertising
    // an ephemeral TCP port to Android Terminal's port-forwarding consent UI.
    socketDirectory = await fs.mkdtemp(path.join(os.tmpdir(), "claw-vscreen-"));
    socketPath = path.join(socketDirectory, "stream.sock");
    if (Buffer.byteLength(socketPath) > 103) throw helperError("The private VScreen socket path is too long", false);
    forwardEndpoint = `localfilesystem:${socketPath}`;
    await options.runCommand(
      ["-s", options.adbSerial, "forward", forwardEndpoint, `localabstract:${socketName}`],
      { timeoutMs: 10000 },
    );
    process = options.spawnCommand(scrcpyVScreenServerArguments(options.adbSerial, scidHex));

    const appendLog = (chunk) => {
      helperLog = (helperLog + chunk.toString("utf8")).slice(-MAX_HELPER_LOG_BYTES);
      const match = helperLog.match(/New display: \d+x\d+\/\d+ \(id=(\d+)\)/);
      if (match) displayId = Number(match[1]);
    };
    process.stdout?.on("data", appendLog);
    process.stderr?.on("data", appendLog);
    processClosed = new Promise((resolve, reject) => {
      process.once("error", () => reject(helperError("ADB could not start the Android VScreen helper")));
      process.once("close", (code, signal) => {
        resolve({ code, signal });
        resolveClosed({ reason: "helper_exit", code, signal });
      });
    });
    processClosed.catch(() => {});

    await waitForDeviceSocket(options, socketName, process);
    video = await connectLocalSocket(socketPath, options);
    control = await connectLocalSocket(socketPath, options);
    // scrcpy may publish clipboard/device messages on the control channel. Drain
    // them so producer-owned pointer writes never encounter unbounded readback.
    control.on("data", () => {});
    const createdDisplayId = await waitForDisplay(() => displayId, processClosed);
    return {
      display: {
        id: createdDisplayId,
        width: ANDROID_VSCREEN_WIDTH,
        height: ANDROID_VSCREEN_HEIGHT,
        dpi: ANDROID_VSCREEN_DPI,
        rotation: 0,
      },
      codec: "h264",
      video,
      control,
      closed,
      close,
    };
  } catch (error) {
    await close();
    throw error instanceof AndroidDeviceError
      ? error
      : helperError("The Android VScreen helper could not be started");
  }
}
