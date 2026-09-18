import os from "node:os";
import path from "node:path";
import { startScrcpyVScreenHelper } from "./helper.mjs";
import { AndroidDeviceError, AndroidDeviceErrorCode } from "../bridge/protocol.mjs";

const ANDROID_COMPONENT_PATTERN = /^[A-Za-z][A-Za-z0-9_.]*\/(?:\.?[A-Za-z][A-Za-z0-9_.$]*)$/;
const ANDROID_PACKAGE_PATTERN = /^[A-Za-z][A-Za-z0-9_]*(?:\.[A-Za-z0-9_]+)+$/;
const PACKAGE_IN_COMPONENT_PATTERN = /^([A-Za-z][A-Za-z0-9_]*(?:\.[A-Za-z0-9_]+)+)\//;
const RESUMED_ACTIVITY_PATTERN = new RegExp(
  `(?:topResumedActivity|mResumedActivity)[=:]\\s*ActivityRecord\\{[^\\n]*?\\su\\d+\\s+([A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z0-9_]+)+)\\/[^\\s}]+`,
);
const FOREGROUND_SAMPLE_LIMIT = 25;
const FOREGROUND_SAMPLE_INTERVAL_MS = 200;

export function parseVScreenTopPackage(output) {
  if (typeof output !== "string") return null;
  return output.match(RESUMED_ACTIVITY_PATTERN)?.[1] ?? null;
}

function delay(ms) {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

/** The Android/scrcpy producer behind the neutral VScreen Foundation contract. */
export class AndroidVScreenDeviceProducer {
  constructor(options) {
    if (!options?.bridge) throw new TypeError("Android VScreen producer requires a Device Bridge");
    this.bridge = options.bridge;
    this.installRoot =
      options.installRoot ??
      process.env.CLAW_IN_ONE_INSTALL_ROOT ??
      path.join(os.homedir(), ".local/share/claw-in-one");
    this.helperPath = options.helperPath ?? path.join(
      this.installRoot,
      "toolchains/scrcpy/current/scrcpy-server",
    );
    this.startHelper = options.startVScreenHelper ?? startScrcpyVScreenHelper;
    this.sleep = options.sleep ?? delay;
    this.activeHelper = null;
    this.probeHelpers = new Set();
    this.unsubscribe = this.bridge.onBindingInvalidated((reason) => this.stop(reason));
  }

  async stop(reason = "producer_stopped") {
    const helpers = [this.activeHelper, ...this.probeHelpers].filter(Boolean);
    this.activeHelper = null;
    this.probeHelpers.clear();
    await Promise.all(helpers.map((helper) => helper.close(reason).catch(() => {})));
  }

  close() {
    this.unsubscribe?.();
    this.unsubscribe = null;
    return this.stop("producer_closed");
  }

  async ensureTarget({ expectedGeneration }) {
    this.#requireGeneration(expectedGeneration, "target");
    return await this.bridge.withDevice("vscreen_start", async (device) => {
      this.#assertGeneration(device, expectedGeneration);
      const helper = this.activeHelper ?? await this.#startHelper(device);
      try {
        await this.#showHome(device, helper);
        if (this.activeHelper !== helper) {
          this.activeHelper = helper;
          helper.closed.finally(() => {
            if (this.activeHelper === helper) this.activeHelper = null;
          });
        }
        return helper;
      } catch (error) {
        if (this.activeHelper === helper) this.activeHelper = null;
        await helper.close("home_unavailable").catch(() => {});
        throw error;
      }
    });
  }

  async startProbe({ expectedGeneration }) {
    this.#requireGeneration(expectedGeneration, "probe");
    return await this.bridge.withDevice("vscreen_probe", async (device) => {
      this.#assertGeneration(device, expectedGeneration);
      const helper = await this.#startHelper(device);
      try {
        const homeComponent = await this.#resolveSecondaryHome(device);
        await this.#startOnDisplay(device, helper.display.id, homeComponent);
        this.probeHelpers.add(helper);
        helper.closed.finally(() => this.probeHelpers.delete(helper));
        return helper;
      } catch (error) {
        await helper.close("probe_unavailable").catch(() => {});
        throw error;
      }
    });
  }

  async placeWorkload(params) {
    if (
      !params ||
      typeof params.expectedGeneration !== "string" ||
      !ANDROID_PACKAGE_PATTERN.test(params.packageName ?? "") ||
      !Number.isSafeInteger(params.displayId) ||
      params.displayId <= 0
    ) {
      throw new AndroidDeviceError(AndroidDeviceErrorCode.invalidRequest, "Invalid VScreen workload request");
    }
    return await this.bridge.withDevice("vscreen_workload", async (device) => {
      if (
        device.binding.generation !== params.expectedGeneration ||
        this.activeHelper?.display.id !== params.displayId
      ) {
        throw new AndroidDeviceError(AndroidDeviceErrorCode.busy, "The VScreen target changed", {
          retryable: true,
        });
      }
      const component = await this.#resolveLauncher(device, params.packageName);
      await this.#startOnDisplay(device, params.displayId, component);
      const observed = await this.#waitForDisplayTopPackage(
        device,
        params.displayId,
        params.packageName,
      );
      if (observed.packageName !== params.packageName) {
        throw new AndroidDeviceError(
          AndroidDeviceErrorCode.vscreenUnavailable,
          "The expected app did not become active on the VScreen display",
        );
      }
      return {
        displayId: params.displayId,
        component,
        observedPackage: observed.packageName,
        observedSamples: observed.samples,
      };
    });
  }

  async #startHelper(device) {
    return await this.startHelper({
      adbSerial: device.adbSerial,
      helperPath: this.helperPath,
      runCommand: device.run,
      spawnCommand: device.spawn,
    });
  }

  async #showHome(device, helper) {
    const homeComponent = await this.#resolveSecondaryHome(device);
    await this.#startOnDisplay(device, helper.display.id, homeComponent);
    const homePackage = homeComponent.match(PACKAGE_IN_COMPONENT_PATTERN)?.[1] ?? null;
    const observed = await this.#waitForDisplayTopPackage(device, helper.display.id, homePackage);
    if (!homePackage || observed.packageName !== homePackage) {
      throw new AndroidDeviceError(
        AndroidDeviceErrorCode.vscreenUnavailable,
        "Android secondary Home did not become active on the VScreen display",
      );
    }
  }

  #requireGeneration(value, kind) {
    if (typeof value !== "string" || value.length === 0) {
      throw new AndroidDeviceError(AndroidDeviceErrorCode.invalidRequest, `Invalid VScreen ${kind} request`);
    }
  }

  #assertGeneration(device, expectedGeneration) {
    if (device.binding.generation !== expectedGeneration) {
      throw new AndroidDeviceError(AndroidDeviceErrorCode.busy, "The Android device connection changed", {
        retryable: true,
      });
    }
  }

  async #resolveSecondaryHome(device) {
    const resolved = await device.run(
      [
        "-s", device.adbSerial, "shell", "cmd", "package", "resolve-activity", "--brief", "--user", "0",
        "-a", "android.intent.action.MAIN", "-c", "android.intent.category.SECONDARY_HOME",
      ],
      { allowFailure: true, timeoutMs: 10_000 },
    );
    const component = resolved.stdout
      .split(/\r?\n/)
      .map((line) => line.trim())
      .find((line) => ANDROID_COMPONENT_PATTERN.test(line));
    if (resolved.code !== 0 || !component) {
      throw new AndroidDeviceError(
        AndroidDeviceErrorCode.vscreenUnavailable,
        "Android did not resolve a secondary Home activity",
      );
    }
    return component;
  }

  async #resolveLauncher(device, packageName) {
    const resolved = await device.run(
      [
        "-s", device.adbSerial, "shell", "cmd", "package", "resolve-activity", "--brief", "--user", "0",
        "-a", "android.intent.action.MAIN", "-c", "android.intent.category.LAUNCHER", packageName,
      ],
      { allowFailure: true, timeoutMs: 10_000 },
    );
    const component = resolved.stdout
      .split(/\r?\n/)
      .map((line) => line.trim())
      .find((line) => ANDROID_COMPONENT_PATTERN.test(line) && line.startsWith(`${packageName}/`));
    if (resolved.code !== 0 || !component) {
      throw new AndroidDeviceError(
        AndroidDeviceErrorCode.vscreenUnavailable,
        "The requested Android app has no launchable activity",
      );
    }
    return component;
  }

  async #startOnDisplay(device, displayId, component) {
    const started = await device.run(
      ["-s", device.adbSerial, "shell", "am", "start", "--display", String(displayId), "-n", component],
      { allowFailure: true, timeoutMs: 10_000 },
    );
    if (started.code !== 0 || /\b(?:Error|Exception)\b[^\r\n]*:/i.test(`${started.stdout}\n${started.stderr}`)) {
      throw new AndroidDeviceError(
        AndroidDeviceErrorCode.vscreenUnavailable,
        "Android could not start the requested VScreen workload",
      );
    }
  }

  async #readDisplayTopPackage(device, displayId) {
    const command =
      `dumpsys activity -d ${displayId} activities | ` +
      "toybox grep -E 'topResumedActivity|mResumedActivity'";
    const result = await device.run(
      ["-s", device.adbSerial, "shell", command],
      { allowFailure: true, timeoutMs: 5_000 },
    );
    if (result.code === 1 && !result.stdout.trim() && !result.stderr.trim()) return null;
    if (result.code !== 0) {
      throw new AndroidDeviceError(
        AndroidDeviceErrorCode.vscreenUnavailable,
        "Android VScreen foreground state is unavailable",
      );
    }
    return parseVScreenTopPackage(result.stdout);
  }

  async #waitForDisplayTopPackage(device, displayId, expectedPackage) {
    let packageName = null;
    for (let samples = 1; samples <= FOREGROUND_SAMPLE_LIMIT; samples += 1) {
      packageName = await this.#readDisplayTopPackage(device, displayId);
      if (packageName === expectedPackage) return { packageName, samples };
      if (samples < FOREGROUND_SAMPLE_LIMIT) await this.sleep(FOREGROUND_SAMPLE_INTERVAL_MS);
    }
    return { packageName, samples: FOREGROUND_SAMPLE_LIMIT };
  }
}
