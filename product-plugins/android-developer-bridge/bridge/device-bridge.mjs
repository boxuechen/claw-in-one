import { createHash, randomUUID } from "node:crypto";
import { spawn } from "node:child_process";
import { constants as fsConstants } from "node:fs";
import fs from "node:fs/promises";
import path from "node:path";
import {
  ANDROID_DEVICE_PROTOCOL_VERSION,
  AndroidDeviceError,
  AndroidDeviceErrorCode,
} from "./protocol.mjs";

const STATE_VERSION = 1;
const DEFAULT_ADB_SERVER_PORT = 5038;
const DEFAULT_COMMAND_TIMEOUT_MS = 10000;
const DEFAULT_PAIR_ATTEMPTS = 30;
const DEFAULT_CONNECT_ATTEMPTS = 20;
const MAX_ADB_OUTPUT_BYTES = 256 * 1024;
const MAX_ADB_BINARY_OUTPUT_BYTES = 12 * 1024 * 1024;
const MAX_CONNECTED_DEVICES = 8;
const CHALLENGE_PATTERN = /^[0-9a-f]{64}$/;
const TARGET_ID_PATTERN = /^[0-9a-f]{64}$/;

function adbEnvironment(privateHome) {
  const androidHome = path.join(privateHome, ".android");
  const environment = {
    ...process.env,
    HOME: privateHome,
    ANDROID_USER_HOME: androidHome,
    ADB_VENDOR_KEYS: androidHome,
    ADB_MDNS_AUTO_CONNECT: "adb-tls-connect",
  };
  delete environment.ADB_SERVER_SOCKET;
  delete environment.ANDROID_ADB_SERVER_PORT;
  return environment;
}

function delay(ms) {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

export function firstOutputLine(stdout, stderr) {
  const line = `${stderr}\n${stdout}`
    .split(/\r?\n/)
    .map((part) => part.trim())
    .find(Boolean);
  return line?.slice(0, 240) ?? "ADB command failed";
}

export function createAdbCommandRunner(options) {
  const adbPath = options.adbPath;
  const serverPort = options.serverPort;
  const privateHome = options.privateHome;
  const spawnProcess = options.spawnProcess ?? spawn;
  const environment = adbEnvironment(privateHome);

  return (args, runOptions = {}) =>
    new Promise((resolve, reject) => {
      runOptions.signal?.throwIfAborted();
      const child = spawnProcess(adbPath, ["-P", String(serverPort), ...args], {
        env: environment,
        stdio: [runOptions.input === undefined ? "ignore" : "pipe", "pipe", "pipe"],
      });
      let stdout = "";
      let stderr = "";
      let settled = false;
      const timeoutMs = runOptions.timeoutMs ?? DEFAULT_COMMAND_TIMEOUT_MS;
      const finish = (callback) => {
        if (settled) return;
        settled = true;
        clearTimeout(timer);
        runOptions.signal?.removeEventListener("abort", onAbort);
        callback();
      };
      const onAbort = () => {
        // Stopping this client cannot promise rollback of an Android package-manager mutation.
        finish(() => {
          child.kill("SIGKILL");
          reject(runOptions.signal.reason ?? new Error("ADB command was cancelled"));
        });
      };
      const append = (current, chunk) => {
        if (settled) return current;
        const next = current + chunk.toString("utf8");
        if (Buffer.byteLength(next) > MAX_ADB_OUTPUT_BYTES) {
          finish(() => {
            child.kill("SIGKILL");
            reject(
              new AndroidDeviceError(
                AndroidDeviceErrorCode.adbCommandFailed,
                "ADB returned more data than the Device Bridge limit",
              ),
            );
          });
          return current;
        }
        return next;
      };
      const timer = setTimeout(() => {
        finish(() => {
          child.kill("SIGKILL");
          reject(
            new AndroidDeviceError(AndroidDeviceErrorCode.adbCommandFailed, "ADB command timed out", {
              retryable: true,
            }),
          );
        });
      }, timeoutMs);
      runOptions.signal?.addEventListener("abort", onAbort, { once: true });

      const onStreamError = () => {
        finish(() => {
          // The subprocess may already have dispatched work. Retire this client,
          // not the private ADB server; callers retain their unknown-outcome policy.
          child.kill("SIGKILL");
          reject(new AndroidDeviceError(AndroidDeviceErrorCode.adbCommandFailed, "ADB command stream failed"));
        });
      };
      // ChildProcess does not forward its stdio errors. Keep these handlers for
      // late EPIPE after close/abort too, without changing an already settled result.
      child.stdin?.on("error", onStreamError);
      child.stdout.on("error", onStreamError);
      child.stderr.on("error", onStreamError);
      child.stdout.on("data", (chunk) => {
        stdout = append(stdout, chunk);
      });
      child.stderr.on("data", (chunk) => {
        stderr = append(stderr, chunk);
      });
      child.on("error", (error) => {
        finish(() =>
          reject(
            new AndroidDeviceError(
              error?.code === "ENOENT" ? AndroidDeviceErrorCode.adbNotInstalled : AndroidDeviceErrorCode.adbCommandFailed,
              error?.code === "ENOENT" ? "The qualified ADB runtime is not installed" : "ADB could not be started",
              { retryable: error?.code !== "ENOENT" },
            ),
          ),
        );
      });
      child.on("close", (code) => {
        finish(() => {
          const result = { code: code ?? -1, stdout, stderr };
          if (result.code === 0 || runOptions.allowFailure === true) {
            resolve(result);
            return;
          }
          reject(
            new AndroidDeviceError(AndroidDeviceErrorCode.adbCommandFailed, firstOutputLine(stdout, stderr), {
              retryable: true,
            }),
          );
        });
      });
      if (runOptions.input !== undefined) child.stdin.end(runOptions.input);
      if (runOptions.signal?.aborted) onAbort();
    });
}

export function createAdbBinaryCommandRunner(options) {
  const adbPath = options.adbPath;
  const serverPort = options.serverPort;
  const privateHome = options.privateHome;
  const spawnProcess = options.spawnProcess ?? spawn;
  const environment = adbEnvironment(privateHome);

  return (args, runOptions = {}) =>
    new Promise((resolve, reject) => {
      const child = spawnProcess(adbPath, ["-P", String(serverPort), ...args], {
        env: environment,
        stdio: ["ignore", "pipe", "pipe"],
      });
      const chunks = [];
      let outputBytes = 0;
      let stderr = "";
      let settled = false;
      const finish = (callback) => {
        if (settled) return;
        settled = true;
        clearTimeout(timer);
        callback();
      };
      const timer = setTimeout(() => {
        child.kill("SIGKILL");
        finish(() => reject(new AndroidDeviceError(AndroidDeviceErrorCode.adbCommandFailed, "ADB binary command timed out", { retryable: true })));
      }, runOptions.timeoutMs ?? DEFAULT_COMMAND_TIMEOUT_MS);
      child.stdout.on("data", (chunk) => {
        outputBytes += chunk.length;
        if (outputBytes > (runOptions.maxOutputBytes ?? MAX_ADB_BINARY_OUTPUT_BYTES)) {
          child.kill("SIGKILL");
          finish(() => reject(new AndroidDeviceError(AndroidDeviceErrorCode.adbCommandFailed, "ADB binary output exceeded the Device Bridge limit")));
          return;
        }
        chunks.push(Buffer.from(chunk));
      });
      child.stderr.on("data", (chunk) => {
        stderr += chunk.toString("utf8");
        if (Buffer.byteLength(stderr) > MAX_ADB_OUTPUT_BYTES) {
          child.kill("SIGKILL");
          finish(() => reject(new AndroidDeviceError(AndroidDeviceErrorCode.adbCommandFailed, "ADB returned too much diagnostic output")));
        }
      });
      child.on("error", (error) =>
        finish(() =>
          reject(
            new AndroidDeviceError(
              error?.code === "ENOENT" ? AndroidDeviceErrorCode.adbNotInstalled : AndroidDeviceErrorCode.adbCommandFailed,
              error?.code === "ENOENT" ? "The qualified ADB runtime is not installed" : "ADB could not be started",
              { retryable: error?.code !== "ENOENT" },
            ),
          ),
        ),
      );
      child.on("close", (code) =>
        finish(() => {
          const result = { code: code ?? -1, stdout: Buffer.concat(chunks), stderr };
          if (result.code === 0 || runOptions.allowFailure === true) resolve(result);
          else reject(new AndroidDeviceError(AndroidDeviceErrorCode.adbCommandFailed, firstOutputLine("", stderr), { retryable: true }));
        }),
      );
    });
}

export function createAdbProcessSpawner(options) {
  const spawnProcess = options.spawnProcess ?? spawn;
  const environment = adbEnvironment(options.privateHome);
  return (args) =>
    spawnProcess(options.adbPath, ["-P", String(options.serverPort), ...args], {
      env: environment,
      stdio: ["ignore", "pipe", "pipe"],
    });
}

function parseServerPort(value) {
  if (value === undefined || value === "") return DEFAULT_ADB_SERVER_PORT;
  const port = Number(value);
  if (!Number.isSafeInteger(port) || port < 1024 || port > 65535 || port === 5037) {
    throw new AndroidDeviceError(
      AndroidDeviceErrorCode.stateInvalid,
      "The dedicated ADB server port must be between 1024 and 65535 and cannot be 5037",
    );
  }
  return port;
}

function parseAdbDevices(output) {
  const devices = [];
  for (const line of output.split(/\r?\n/).slice(1)) {
    const [serial, state] = line.trim().split(/\s+/, 2);
    if (!serial || state !== "device") continue;
    devices.push(serial);
  }
  if (devices.length > MAX_CONNECTED_DEVICES) {
    throw new AndroidDeviceError(
      AndroidDeviceErrorCode.samePhoneAmbiguous,
      "Too many ADB devices are connected to select this phone safely",
    );
  }
  return devices;
}

function isRecord(value) {
  return value !== null && typeof value === "object" && !Array.isArray(value);
}

function validateSelection(value) {
  if (
    !isRecord(value) ||
    typeof value.adbSerial !== "string" ||
    value.adbSerial.length === 0 ||
    typeof value.deviceSerial !== "string" ||
    value.deviceSerial.length === 0 ||
    typeof value.targetId !== "string" ||
    !TARGET_ID_PATTERN.test(value.targetId) ||
    typeof value.product !== "string" ||
    typeof value.model !== "string" ||
    typeof value.androidApi !== "string"
  ) {
    throw new AndroidDeviceError(AndroidDeviceErrorCode.stateInvalid, "Stored Android device state is invalid");
  }
  return value;
}

function targetIdFor(deviceSerial) {
  return createHash("sha256").update("claw-in-one-android-device\0").update(deviceSerial).digest("hex");
}

function preferredTransport(candidates) {
  return [...candidates].sort((left, right) => {
    const leftMdns = left.adbSerial.endsWith("._adb-tls-connect._tcp") ? 0 : 1;
    const rightMdns = right.adbSerial.endsWith("._adb-tls-connect._tcp") ? 0 : 1;
    return leftMdns - rightMdns || left.adbSerial.localeCompare(right.adbSerial);
  })[0];
}

export class AndroidDeviceBridge {
  constructor(options) {
    this.stateDir = options.stateDir;
    this.adbPath = options.adbPath ?? "/usr/bin/adb";
    this.serverPort = parseServerPort(options.serverPort ?? process.env.CLAW_IN_ONE_ADB_SERVER_PORT);
    this.privateHome = path.join(this.stateDir, "adb-home");
    this.stateFile = path.join(this.stateDir, "connection.json");
    this.challengeSharedRoot = options.challengeSharedRoot ?? "/mnt/shared/Download/ClawInOne/developer-bridge";
    this.challengeDeviceRoot = options.challengeDeviceRoot ?? "/sdcard/Download/ClawInOne/developer-bridge";
    this.runCommand =
      options.runCommand ??
      createAdbCommandRunner({
        adbPath: this.adbPath,
        serverPort: this.serverPort,
        privateHome: this.privateHome,
      });
    this.runBinaryCommand =
      options.runBinaryCommand ??
      createAdbBinaryCommandRunner({
        adbPath: this.adbPath,
        serverPort: this.serverPort,
        privateHome: this.privateHome,
      });
    this.spawnCommand =
      options.spawnCommand ??
      createAdbProcessSpawner({
        adbPath: this.adbPath,
        serverPort: this.serverPort,
        privateHome: this.privateHome,
      });
    this.checkAdb = options.checkAdb ?? (() => fs.access(this.adbPath, fsConstants.X_OK));
    this.sleep = options.sleep ?? delay;
    this.pairAttempts = options.pairAttempts ?? DEFAULT_PAIR_ATTEMPTS;
    this.connectAttempts = options.connectAttempts ?? DEFAULT_CONNECT_ATTEMPTS;
    this.now = options.now ?? Date.now;
    this.createGeneration = options.randomUUID ?? randomUUID;
    this.bindingGeneration = this.createGeneration();
    this.started = false;
    this.serverReady = false;
    this.operation = null;
    this.selection = null;
    this.revokedAtMs = null;
    this.lastReconnectVerification = null;
    this.stateError = null;
    this.unavailableCode = null;
    this.invalidationListeners = new Set();
  }

  async start() {
    await fs.mkdir(this.stateDir, { recursive: true, mode: 0o700 });
    await fs.chmod(this.stateDir, 0o700);
    await this.#preparePrivateHome();
    this.started = true;
    try {
      await this.#loadState();
    } catch (error) {
      this.stateError = error;
      return;
    }
    try {
      await this.#ensureServer();
    } catch (error) {
      this.unavailableCode = error.code ?? AndroidDeviceErrorCode.unavailable;
    }
  }

  async stop() {
    await this.#notifyInvalidation("device_bridge_stopped");
    if (this.serverReady) {
      await this.runCommand(["kill-server"], { allowFailure: true, timeoutMs: 5000 }).catch(() => {});
    }
    this.serverReady = false;
    this.started = false;
  }

  async status() {
    this.#requireStarted();
    if (this.operation) {
      const operationState = {
        pair: ["pairing", false],
        connect: ["connecting", false],
        verify_reconnect: ["connecting", false],
        forget: ["forgetting", false],
      }[this.operation] ?? ["ready", true];
      return this.#projection(operationState[0], operationState[1]);
    }
    if (this.stateError) {
      return this.#projection("unavailable", false, AndroidDeviceErrorCode.stateInvalid);
    }
    try {
      await this.#ensureServer();
    } catch (error) {
      return this.#projection("unavailable", false, error.code ?? AndroidDeviceErrorCode.unavailable);
    }
    if (!this.selection) {
      return this.#projection(this.revokedAtMs === null ? "setup_required" : "revoked", false);
    }
    let connected;
    try {
      connected = await this.#findStoredPhone();
    } catch (error) {
      return this.#projection("unavailable", false, error.code ?? AndroidDeviceErrorCode.unavailable);
    }
    if (!connected) return this.#projection("offline", false);
    if (connected.adbSerial !== this.selection.adbSerial) {
      this.selection = { ...this.selection, adbSerial: connected.adbSerial };
      await this.#saveState();
    }
    return this.#projection("ready", true);
  }

  async pair(params) {
    return await this.#withMutation("pair", async () => {
      this.#requireHealthyState();
      if (this.selection) {
        throw new AndroidDeviceError(
          AndroidDeviceErrorCode.alreadyPaired,
          "Forget the current development connection before pairing another one",
        );
      }
      await this.#ensureServer();
      const challengePath = this.#sharedChallengePath(params.challengeId);
      let pairingAttempted = false;
      let completed = false;
      try {
        const challenge = await this.#readChallenge(challengePath);
        pairingAttempted = true;
        const pairResult = await this.runCommand(["pair", params.endpoint], {
          input: `${params.pairingCode}\n`,
          timeoutMs: 20000,
        });
        if (!/Successfully paired/i.test(`${pairResult.stdout}\n${pairResult.stderr}`)) {
          throw new AndroidDeviceError(AndroidDeviceErrorCode.pairFailed, "Android rejected the pairing request", {
            retryable: true,
          });
        }
        const candidate = await this.#waitForChallengeMatch(params.challengeId, challenge);
        if (!candidate) {
          throw new AndroidDeviceError(
            AndroidDeviceErrorCode.samePhoneNotFound,
            "The paired ADB device did not match this phone",
            { retryable: true },
          );
        }
        this.selection = {
          ...candidate,
          targetId: targetIdFor(candidate.deviceSerial),
        };
        this.bindingGeneration = this.createGeneration();
        this.revokedAtMs = null;
        await this.#saveState();
        this.lastReconnectVerification = {
          id: params.challengeId,
          targetId: this.selection.targetId,
        };
        completed = true;
        return this.#projection("ready", true);
      } finally {
        await fs.unlink(challengePath).catch(() => {});
        if (pairingAttempted && !completed) {
          this.selection = null;
          this.lastReconnectVerification = null;
          await this.#resetPrivateCredentials();
        }
      }
    });
  }

  async connect(params) {
    return await this.#withMutation("connect", async () => {
      this.#requireHealthyState();
      if (!this.selection) {
        throw new AndroidDeviceError(AndroidDeviceErrorCode.notPaired, "Pair this phone before connecting");
      }
      await this.#ensureServer();
      if (params.endpoint) {
        const result = await this.runCommand(["connect", params.endpoint], { timeoutMs: 15000 });
        if (/failed|unable|cannot/i.test(`${result.stdout}\n${result.stderr}`)) {
          throw new AndroidDeviceError(AndroidDeviceErrorCode.phoneOffline, "ADB could not connect to this phone", {
            retryable: true,
          });
        }
      }
      for (let attempt = 0; attempt < this.connectAttempts; attempt += 1) {
        const candidate = await this.#findStoredPhone();
        if (candidate) {
          if (candidate.adbSerial !== this.selection.adbSerial) {
            this.selection = { ...this.selection, adbSerial: candidate.adbSerial };
            await this.#saveState();
          }
          return this.#projection("ready", true);
        }
        if (attempt + 1 < this.connectAttempts) await this.sleep(500);
      }
      throw new AndroidDeviceError(AndroidDeviceErrorCode.phoneOffline, "The paired phone is offline", {
        retryable: true,
      });
    });
  }

  async verifyReconnect(params) {
    return await this.#withMutation("verify_reconnect", async () => {
      this.#requireHealthyState();
      if (!this.selection) {
        throw new AndroidDeviceError(AndroidDeviceErrorCode.notPaired, "Pair this phone before reconnecting");
      }
      this.lastReconnectVerification = null;
      await this.#stopPrivateServer();
      await this.#ensureServer();
      if (params.endpoint) {
        const result = await this.runCommand(["connect", params.endpoint], { timeoutMs: 15000 });
        if (/failed|unable|cannot/i.test(`${result.stdout}\n${result.stderr}`)) {
          throw new AndroidDeviceError(AndroidDeviceErrorCode.phoneOffline, "ADB could not reconnect to this phone", {
            retryable: true,
          });
        }
      }
      for (let attempt = 0; attempt < this.connectAttempts; attempt += 1) {
        const candidate = await this.#findStoredPhone();
        if (candidate) {
          if (candidate.adbSerial !== this.selection.adbSerial) {
            this.selection = { ...this.selection, adbSerial: candidate.adbSerial };
            await this.#saveState();
          }
          this.lastReconnectVerification = {
            id: params.verificationId,
            targetId: this.selection.targetId,
          };
          return this.#projection("ready", true);
        }
        if (attempt + 1 < this.connectAttempts) await this.sleep(500);
      }
      throw new AndroidDeviceError(AndroidDeviceErrorCode.phoneOffline, "The paired phone did not reconnect", {
        retryable: true,
      });
    });
  }

  async forget() {
    return await this.#withMutation("forget", async () => {
      this.#requireStarted();
      await this.#notifyInvalidation("device_forgotten");
      if (this.serverReady && this.selection) {
        await this.runCommand(["disconnect", this.selection.adbSerial], {
          allowFailure: true,
          timeoutMs: 5000,
        });
      }
      await this.#stopPrivateServer();
      await fs.rm(this.privateHome, { recursive: true, force: true });
      await this.#preparePrivateHome();
      this.selection = null;
      this.lastReconnectVerification = null;
      this.bindingGeneration = this.createGeneration();
      this.revokedAtMs = this.now();
      this.stateError = null;
      await this.#saveState();
      try {
        await this.#ensureServer();
      } catch (error) {
        this.unavailableCode = error.code ?? AndroidDeviceErrorCode.unavailable;
      }
      return this.#projection("revoked", false);
    });
  }

  async deviceBinding() {
    this.#requireHealthyState();
    if (this.operation) {
      throw new AndroidDeviceError(AndroidDeviceErrorCode.busy, "Another Android device operation is active", {
        retryable: true,
      });
    }
    const selection = this.selection;
    if (!selection) {
      throw new AndroidDeviceError(AndroidDeviceErrorCode.notPaired, "Pair this phone before using Android development");
    }
    await this.#ensureServer();
    const connected = await this.#findStoredPhone();
    if (this.operation || this.selection !== selection) {
      throw new AndroidDeviceError(AndroidDeviceErrorCode.busy, "The Android device connection changed", {
        retryable: true,
      });
    }
    if (!connected) {
      throw new AndroidDeviceError(AndroidDeviceErrorCode.phoneOffline, "The paired phone is offline", {
        retryable: true,
      });
    }
    if (connected.adbSerial !== selection.adbSerial) {
      this.selection = { ...selection, adbSerial: connected.adbSerial };
      await this.#saveState();
    }
    return this.#bindingProjection();
  }

  /** Internal trusted port. It is never registered as an Agent or Gateway API. */
  async withDevice(operation, run) {
    if (typeof operation !== "string" || !/^[a-z][a-z0-9_]{0,63}$/.test(operation)) {
      throw new AndroidDeviceError(AndroidDeviceErrorCode.invalidRequest, "Invalid Android device operation");
    }
    if (typeof run !== "function") {
      throw new AndroidDeviceError(AndroidDeviceErrorCode.invalidRequest, "Android device operation is missing");
    }
    return await this.#withConnectedDeviceOperation(operation, ({ adbSerial, binding }) =>
      run(Object.freeze({
        adbSerial,
        binding,
        run: (args, options) => this.runCommand(args, options),
        runBinary: (args, options) => this.runBinaryCommand(args, options),
        spawn: (args) => this.spawnCommand(args),
      })),
    );
  }

  onBindingInvalidated(listener) {
    if (typeof listener !== "function") {
      throw new TypeError("Android device invalidation listener is required");
    }
    this.invalidationListeners.add(listener);
    return () => this.invalidationListeners.delete(listener);
  }

  async #withMutation(operation, run) {
    this.#requireStarted();
    if (this.operation) {
      throw new AndroidDeviceError(AndroidDeviceErrorCode.busy, "Another development-connection operation is active", {
        retryable: true,
      });
    }
    this.operation = operation;
    try {
      return await run();
    } finally {
      this.operation = null;
    }
  }

  async #withConnectedDeviceOperation(operation, run) {
    return await this.#withMutation(operation, async () => {
      this.#requireHealthyState();
      if (!this.selection) {
        throw new AndroidDeviceError(AndroidDeviceErrorCode.notPaired, "Pair this phone before using Android development");
      }
      await this.#ensureServer();
      const connected = await this.#findStoredPhone();
      if (!connected) {
        throw new AndroidDeviceError(AndroidDeviceErrorCode.phoneOffline, "The paired phone is offline", {
          retryable: true,
        });
      }
      if (connected.adbSerial !== this.selection.adbSerial) {
        this.selection = { ...this.selection, adbSerial: connected.adbSerial };
        await this.#saveState();
      }
      return await run({ adbSerial: this.selection.adbSerial, binding: this.#bindingProjection() });
    });
  }

  #requireStarted() {
    if (!this.started) {
      throw new AndroidDeviceError(AndroidDeviceErrorCode.unavailable, "Android Device Bridge is not running", {
        retryable: true,
      });
    }
  }

  #requireHealthyState() {
    this.#requireStarted();
    if (this.stateError) throw this.stateError;
  }

  async #preparePrivateHome() {
    await fs.mkdir(path.join(this.privateHome, ".android"), { recursive: true, mode: 0o700 });
    await fs.chmod(this.privateHome, 0o700);
    await fs.chmod(path.join(this.privateHome, ".android"), 0o700);
  }

  async #ensureServer() {
    if (this.serverReady) return;
    try {
      await this.checkAdb();
    } catch {
      throw new AndroidDeviceError(AndroidDeviceErrorCode.adbNotInstalled, "Android Development tools are not installed");
    }
    await this.runCommand(["kill-server"], { allowFailure: true, timeoutMs: 5000 });
    await this.runCommand(["start-server"], { timeoutMs: 10000 });
    this.serverReady = true;
    this.unavailableCode = null;
  }

  async #stopPrivateServer() {
    if (!this.serverReady) return;
    const stopped = await this.runCommand(["kill-server"], { allowFailure: true, timeoutMs: 5000 });
    if (stopped.code !== 0) {
      throw new AndroidDeviceError(
        AndroidDeviceErrorCode.adbCommandFailed,
        "The private ADB server could not be stopped",
        { retryable: true },
      );
    }
    this.serverReady = false;
  }

  async #resetPrivateCredentials() {
    await this.#stopPrivateServer();
    await fs.rm(this.privateHome, { recursive: true, force: true });
    await this.#preparePrivateHome();
    try {
      await this.#ensureServer();
    } catch (error) {
      this.unavailableCode = error.code ?? AndroidDeviceErrorCode.unavailable;
    }
  }

  async #notifyInvalidation(reason) {
    const listeners = [...this.invalidationListeners];
    await Promise.all(listeners.map((listener) => Promise.resolve(listener(reason)).catch(() => {})));
  }

  async #loadState() {
    let raw;
    try {
      raw = await fs.readFile(this.stateFile, "utf8");
    } catch (error) {
      if (error?.code === "ENOENT") return;
      throw error;
    }
    let parsed;
    try {
      parsed = JSON.parse(raw);
    } catch {
      throw new AndroidDeviceError(AndroidDeviceErrorCode.stateInvalid, "Stored Android device state is invalid");
    }
    if (!isRecord(parsed) || parsed.version !== STATE_VERSION) {
      throw new AndroidDeviceError(AndroidDeviceErrorCode.stateInvalid, "Stored Android device state is invalid");
    }
    this.selection = parsed.selection === null ? null : validateSelection(parsed.selection);
    this.revokedAtMs = Number.isSafeInteger(parsed.revokedAtMs) ? parsed.revokedAtMs : null;
  }

  async #saveState() {
    const temporary = `${this.stateFile}.next-${process.pid}`;
    const payload = `${JSON.stringify({
      version: STATE_VERSION,
      selection: this.selection,
      revokedAtMs: this.revokedAtMs,
    })}\n`;
    await fs.writeFile(temporary, payload, { encoding: "utf8", mode: 0o600, flag: "w" });
    await fs.chmod(temporary, 0o600);
    await fs.rename(temporary, this.stateFile);
  }

  #sharedChallengePath(challengeId) {
    return path.join(this.challengeSharedRoot, `challenge-${challengeId}.txt`);
  }

  #deviceChallengePath(challengeId) {
    return `${this.challengeDeviceRoot}/challenge-${challengeId}.txt`;
  }

  async #readChallenge(challengePath) {
    let info;
    try {
      info = await fs.lstat(challengePath);
    } catch {
      throw new AndroidDeviceError(
        AndroidDeviceErrorCode.challengeUnavailable,
        "The same-phone verification challenge is unavailable",
      );
    }
    if (!info.isFile() || info.isSymbolicLink() || info.size > 128) {
      throw new AndroidDeviceError(
        AndroidDeviceErrorCode.challengeUnavailable,
        "The same-phone verification challenge is invalid",
      );
    }
    const challenge = (await fs.readFile(challengePath, "utf8")).trim();
    if (!CHALLENGE_PATTERN.test(challenge)) {
      throw new AndroidDeviceError(
        AndroidDeviceErrorCode.challengeUnavailable,
        "The same-phone verification challenge is invalid",
      );
    }
    return challenge;
  }

  async #listConnectedSerials() {
    const result = await this.runCommand(["devices", "-l"], { timeoutMs: 5000 });
    return parseAdbDevices(result.stdout);
  }

  async #readProperty(adbSerial, property) {
    const result = await this.runCommand(["-s", adbSerial, "shell", "getprop", property], {
      timeoutMs: 5000,
    });
    return result.stdout.replace(/\r/g, "").trim();
  }

  async #inspectCandidate(adbSerial) {
    const deviceSerial = await this.#readProperty(adbSerial, "ro.serialno");
    if (!deviceSerial) return null;
    return {
      adbSerial,
      deviceSerial,
      product: await this.#readProperty(adbSerial, "ro.product.device"),
      model: await this.#readProperty(adbSerial, "ro.product.model"),
      androidApi: await this.#readProperty(adbSerial, "ro.build.version.sdk"),
    };
  }

  async #findStoredPhone() {
    const matches = [];
    for (const adbSerial of await this.#listConnectedSerials()) {
      try {
        const candidate = await this.#inspectCandidate(adbSerial);
        if (candidate?.deviceSerial === this.selection.deviceSerial) matches.push(candidate);
      } catch {
        // A disconnected or unauthorized transport is not an eligible candidate.
      }
    }
    if (matches.length === 0) return null;
    return preferredTransport(matches);
  }

  #bindingProjection() {
    return {
      generation: this.bindingGeneration,
      target: {
        id: this.selection.targetId,
        product: this.selection.product,
        model: this.selection.model,
        androidApi: this.selection.androidApi,
      },
    };
  }

  async #waitForChallengeMatch(challengeId, challenge) {
    for (let attempt = 0; attempt < this.pairAttempts; attempt += 1) {
      const matches = [];
      for (const adbSerial of await this.#listConnectedSerials()) {
        try {
          const result = await this.runCommand(
            ["-s", adbSerial, "shell", "cat", this.#deviceChallengePath(challengeId)],
            { timeoutMs: 5000 },
          );
          if (result.stdout.replace(/\r/g, "").trim() !== challenge) continue;
          const candidate = await this.#inspectCandidate(adbSerial);
          if (candidate) matches.push(candidate);
        } catch {
          // Only a connected transport that returns the exact challenge can match.
        }
      }
      const identities = new Map();
      for (const candidate of matches) {
        const group = identities.get(candidate.deviceSerial) ?? [];
        group.push(candidate);
        identities.set(candidate.deviceSerial, group);
      }
      if (identities.size > 1) {
        throw new AndroidDeviceError(
          AndroidDeviceErrorCode.samePhoneAmbiguous,
          "More than one ADB device matched the same-phone challenge",
        );
      }
      if (identities.size === 1) return preferredTransport([...identities.values()][0]);
      if (attempt + 1 < this.pairAttempts) await this.sleep(500);
    }
    return null;
  }

  #projection(status, connected, reasonCode = null) {
    return {
      protocolVersion: ANDROID_DEVICE_PROTOCOL_VERSION,
      status,
      paired: this.selection !== null,
      connected,
      ...(reasonCode ? { reasonCode } : {}),
      ...(connected &&
      this.selection &&
      this.lastReconnectVerification?.targetId === this.selection.targetId
        ? { verificationId: this.lastReconnectVerification.id }
        : {}),
      target: this.selection
        ? {
            id: this.selection.targetId,
            product: this.selection.product,
            model: this.selection.model,
            androidApi: this.selection.androidApi,
          }
        : null,
    };
  }
}
