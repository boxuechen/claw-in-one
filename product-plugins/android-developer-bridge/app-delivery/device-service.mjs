import { firstOutputLine } from "../bridge/device-bridge.mjs";
import { AndroidDeviceError, AndroidDeviceErrorCode } from "../bridge/protocol.mjs";

const MAX_SCREENSHOT_BYTES = 12 * 1024 * 1024;
const MAX_LOG_BYTES = 64 * 1024;
const ANDROID_PACKAGE_PATTERN = /^[A-Za-z][A-Za-z0-9_]*(?:\.[A-Za-z0-9_]+)+$/;
const APK_SHA256_PATTERN = /^[0-9a-f]{64}$/;

/** APK delivery and package-scoped diagnostics over one trusted Device Bridge. */
export class AndroidAppDelivery {
  constructor({ bridge, now = Date.now }) {
    if (!bridge) throw new TypeError("Android App Delivery requires a Device Bridge");
    this.bridge = bridge;
    this.now = now;
  }

  async deviceBinding() {
    return await this.bridge.deviceBinding();
  }

  async installedPackageState(packageName) {
    this.#validatePackageName(packageName);
    return await this.bridge.withDevice("inspect_package", async (device) => ({
      binding: device.binding,
      package: await this.#readInstalledPackage(device, packageName),
    }));
  }

  async installApk(params) {
    this.#validateInstall(params);
    return await this.bridge.withDevice("install", async (device) => {
      params.signal.throwIfAborted();
      const before = await this.#readInstalledPackage(device, params.packageName);
      await params.validate(device.binding);
      params.signal.throwIfAborted();
      // No awaited I/O is allowed between the final policy check and dispatch.
      params.authorizeDispatch(device.binding);
      let dispatch = null;
      let dispatchError = null;
      try {
        dispatch = await device.run(
          ["-s", device.adbSerial, "install", "-r", params.apkPath],
          { allowFailure: true, timeoutMs: 120_000, signal: params.signal },
        );
      } catch (error) {
        dispatchError = error;
      }
      const after = await this.#readInstalledPackage(device, params.packageName).catch(() => ({
        installed: false,
        versionCode: null,
        sha256: null,
      }));
      const verified =
        after.installed && after.versionCode === params.versionCode && after.sha256 === params.sha256;
      if (verified) {
        return { status: dispatchError ? "verified" : "succeeded", before, installed: after };
      }
      if (dispatch && dispatch.code !== 0) {
        return {
          status: "failed",
          message: firstOutputLine(dispatch.stdout, dispatch.stderr),
          before,
          installed: after,
        };
      }
      return {
        status: "unknown",
        message: dispatchError
          ? "The installation response was lost and readback did not verify the artifact"
          : "Android did not verify the installed artifact",
        before,
        installed: after,
      };
    });
  }

  async prepareInstalledWorkload(params) {
    this.#validateArtifactOperation(params);
    return await this.bridge.withDevice("prepare_workload", async (device) => {
      await params.validate(device.binding);
      await this.#requireInstalledArtifact(device, params);
      const component = await this.#resolveLauncher(device, params.packageName);
      return {
        status: "vscreen_workload_requested",
        packageName: params.packageName,
        component,
        binding: device.binding,
        requestedAtMs: this.now(),
      };
    });
  }

  async verifyPresentedApp(params) {
    this.#validateArtifactOperation(params);
    if (
      typeof params.component !== "string" ||
      !params.component.startsWith(`${params.packageName}/`) ||
      typeof params.expectedGeneration !== "string" ||
      !Number.isFinite(params.requestedAtMs)
    ) {
      throw new AndroidDeviceError(AndroidDeviceErrorCode.invalidRequest, "Invalid Android presentation readiness request");
    }
    return await this.bridge.withDevice("verify_presented_app", async (device) => {
      await params.validate(device.binding);
      if (device.binding.generation !== params.expectedGeneration) {
        throw new AndroidDeviceError(
          AndroidDeviceErrorCode.vscreenUnavailable,
          "The Android phone connection changed during VScreen startup",
        );
      }
      const installed = await this.#requireInstalledArtifact(device, params);
      const component = await this.#resolveLauncher(device, params.packageName);
      if (component !== params.component) {
        throw new AndroidDeviceError(
          AndroidDeviceErrorCode.vscreenUnavailable,
          "The installed app launcher changed during VScreen startup",
        );
      }
      const uid = await this.#packageUid(device, params.packageName);
      const logs = await device.run(
        ["-s", device.adbSerial, "shell", "logcat", "-d", `--uid=${uid}`, "-v", "epoch", "-t", "200"],
        { allowFailure: true, timeoutMs: 30_000 },
      );
      if (logs.code !== 0) {
        throw new AndroidDeviceError(AndroidDeviceErrorCode.vscreenUnavailable, "Android launch logs were unavailable");
      }
      const launchLines = logs.stdout
        .replace(/\0/g, "")
        .replace(/\r/g, "")
        .split("\n")
        .filter((line) => {
          const timestamp = Number(line.match(/^\s*(\d+\.\d+)\s/)?.[1]);
          return Number.isFinite(timestamp) && timestamp * 1000 >= params.requestedAtMs - 1000;
        });
      if (launchLines.some((line) => /FATAL EXCEPTION|Fatal signal \d+|\bam_crash\b|AndroidRuntime.*FATAL/i.test(line))) {
        throw new AndroidDeviceError(
          AndroidDeviceErrorCode.vscreenUnavailable,
          "The app had a fatal crash during VScreen startup",
        );
      }
      return { installed, component, launchLogLines: launchLines.length };
    });
  }

  async captureScreen(params) {
    this.#validateArtifactOperation(params);
    return await this.bridge.withDevice("capture", async (device) => {
      await params.validate(device.binding);
      await this.#requireInstalledArtifact(device, params);
      if (!(await this.#isPackageForeground(device, params.packageName))) {
        throw new AndroidDeviceError(AndroidDeviceErrorCode.adbCommandFailed, "The inspected package is not in the foreground");
      }
      const captured = await device.runBinary(
        ["-s", device.adbSerial, "exec-out", "screencap", "-p"],
        { timeoutMs: 30_000, maxOutputBytes: MAX_SCREENSHOT_BYTES },
      );
      const pngSignature = Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]);
      if (
        captured.code !== 0 ||
        captured.stdout.length < pngSignature.length ||
        !captured.stdout.subarray(0, 8).equals(pngSignature)
      ) {
        throw new AndroidDeviceError(AndroidDeviceErrorCode.adbCommandFailed, "Android did not return a valid PNG screenshot");
      }
      if (!(await this.#isPackageForeground(device, params.packageName))) {
        throw new AndroidDeviceError(AndroidDeviceErrorCode.adbCommandFailed, "The foreground app changed during screenshot capture");
      }
      return { status: "captured", packageName: params.packageName, png: captured.stdout };
    });
  }

  async readPackageLogs(params) {
    this.#validateArtifactOperation(params);
    if (!Number.isSafeInteger(params.maxLines) || params.maxLines < 1 || params.maxLines > 200) {
      throw new AndroidDeviceError(AndroidDeviceErrorCode.invalidRequest, "Invalid Android log line limit");
    }
    return await this.bridge.withDevice("logs", async (device) => {
      await params.validate(device.binding);
      await this.#requireInstalledArtifact(device, params);
      const uid = await this.#packageUid(device, params.packageName);
      const result = await device.run(
        ["-s", device.adbSerial, "shell", "logcat", "-d", `--uid=${uid}`, "-t", String(params.maxLines)],
        { allowFailure: true, timeoutMs: 30_000 },
      );
      if (result.code !== 0) {
        throw new AndroidDeviceError(AndroidDeviceErrorCode.adbCommandFailed, firstOutputLine(result.stdout, result.stderr));
      }
      const encoded = Buffer.from(result.stdout.replace(/\0/g, "").replace(/\r/g, ""), "utf8");
      const truncated = encoded.length > MAX_LOG_BYTES;
      const logs = (truncated ? encoded.subarray(encoded.length - MAX_LOG_BYTES) : encoded).toString("utf8");
      return { status: "read", packageName: params.packageName, maxLines: params.maxLines, truncated, logs };
    });
  }

  #validatePackageName(packageName) {
    if (typeof packageName !== "string" || !ANDROID_PACKAGE_PATTERN.test(packageName)) {
      throw new AndroidDeviceError(AndroidDeviceErrorCode.invalidRequest, "Invalid Android package name");
    }
  }

  #validateArtifactOperation(params) {
    if (
      !params ||
      typeof params.packageName !== "string" ||
      !ANDROID_PACKAGE_PATTERN.test(params.packageName) ||
      typeof params.versionCode !== "string" ||
      !/^\d+$/.test(params.versionCode) ||
      typeof params.sha256 !== "string" ||
      !APK_SHA256_PATTERN.test(params.sha256) ||
      typeof params.validate !== "function"
    ) {
      throw new AndroidDeviceError(AndroidDeviceErrorCode.invalidRequest, "Invalid Android artifact operation");
    }
  }

  #validateInstall(params) {
    this.#validateArtifactOperation(params);
    if (
      typeof params.apkPath !== "string" ||
      typeof params.authorizeDispatch !== "function" ||
      !(params.signal instanceof AbortSignal)
    ) {
      throw new AndroidDeviceError(AndroidDeviceErrorCode.invalidRequest, "Invalid APK installation request");
    }
  }

  async #readInstalledPackage(device, packageName) {
    const paths = await device.run(
      ["-s", device.adbSerial, "shell", "pm", "path", "--user", "0", packageName],
      { allowFailure: true, timeoutMs: 10_000 },
    );
    const basePath = paths.stdout
      .split(/\r?\n/)
      .map((line) => line.trim())
      .filter((line) => line.startsWith("package:"))
      .map((line) => line.slice("package:".length))
      .find((candidate) => candidate.startsWith("/data/app/") && candidate.endsWith("/base.apk"));
    if (paths.code !== 0 || !basePath || /[\r\n\0]/.test(basePath)) {
      return { installed: false, versionCode: null, sha256: null };
    }
    const versions = await device.run(
      ["-s", device.adbSerial, "shell", "pm", "list", "packages", "--show-versioncode", packageName],
      { allowFailure: true, timeoutMs: 10_000 },
    );
    const exactPrefix = `package:${packageName} versionCode:`;
    const versionCode = versions.stdout
      .split(/\r?\n/)
      .map((line) => line.trim())
      .find((line) => line.startsWith(exactPrefix))
      ?.slice(exactPrefix.length)
      .match(/^\d+/)?.[0] ?? null;
    const digest = await device.run(
      ["-s", device.adbSerial, "shell", "sha256sum", basePath],
      { allowFailure: true, timeoutMs: 30_000 },
    );
    const sha256 = digest.code === 0
      ? digest.stdout.trim().match(/^([0-9a-f]{64})(?:\s|$)/i)?.[1]?.toLowerCase() ?? null
      : null;
    return { installed: true, versionCode, sha256 };
  }

  async #requireInstalledArtifact(device, params) {
    const installed = await this.#readInstalledPackage(device, params.packageName);
    if (!installed.installed || installed.versionCode !== params.versionCode || installed.sha256 !== params.sha256) {
      throw new AndroidDeviceError(
        AndroidDeviceErrorCode.adbCommandFailed,
        "The installed package does not match the inspected Android artifact",
      );
    }
    return installed;
  }

  async #resolveLauncher(device, packageName) {
    const resolved = await device.run(
      [
        "-s", device.adbSerial, "shell", "cmd", "package", "resolve-activity", "--brief", "--user", "0",
        "-a", "android.intent.action.MAIN", "-c", "android.intent.category.LAUNCHER", packageName,
      ],
      { allowFailure: true, timeoutMs: 10_000 },
    );
    const componentPattern = new RegExp(`^${packageName.replaceAll(".", "\\.")}\/[A-Za-z0-9_.$]+$`);
    const component = resolved.stdout.split(/\r?\n/).map((line) => line.trim()).find((line) => componentPattern.test(line));
    if (resolved.code !== 0 || !component) {
      throw new AndroidDeviceError(AndroidDeviceErrorCode.adbCommandFailed, "The inspected package has no launcher Activity");
    }
    return component;
  }

  async #packageUid(device, packageName) {
    const listed = await device.run(
      ["-s", device.adbSerial, "shell", "cmd", "package", "list", "packages", "-U", packageName],
      { allowFailure: true, timeoutMs: 10_000 },
    );
    const exactPrefix = `package:${packageName} uid:`;
    const uid = listed.stdout
      .split(/\r?\n/)
      .map((line) => line.trim())
      .find((line) => line.startsWith(exactPrefix))
      ?.slice(exactPrefix.length)
      .match(/^\d+$/)?.[0];
    if (listed.code !== 0 || !uid) {
      throw new AndroidDeviceError(AndroidDeviceErrorCode.adbCommandFailed, "Android did not return the package UID");
    }
    return uid;
  }

  async #isPackageForeground(device, packageName) {
    const result = await device.run(
      ["-s", device.adbSerial, "shell", "dumpsys", "window"],
      { allowFailure: true, timeoutMs: 10_000 },
    );
    if (result.code !== 0) return false;
    const escaped = packageName.replaceAll(".", "\\.");
    const component = new RegExp(`(?:^|\\s)${escaped}\/[A-Za-z0-9_.$]+(?:\\s|}|$)`);
    return result.stdout
      .split(/\r?\n/)
      .filter((line) => /mCurrentFocus|mFocusedApp|topResumedActivity/.test(line))
      .some((line) => component.test(line));
  }
}
