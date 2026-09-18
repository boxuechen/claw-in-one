import assert from "node:assert/strict";
import { createHash } from "node:crypto";
import { EventEmitter } from "node:events";
import fs from "node:fs/promises";
import os from "node:os";
import path from "node:path";
import { PassThrough } from "node:stream";
import {
  AndroidDeviceBridge,
  createAdbBinaryCommandRunner,
  createAdbCommandRunner,
} from "../product-plugins/android-developer-bridge/bridge/device-bridge.mjs";
import { AndroidAppDelivery } from "../product-plugins/android-developer-bridge/app-delivery/device-service.mjs";
import {
  AndroidVScreenDeviceProducer,
  parseVScreenTopPackage,
} from "../product-plugins/android-developer-bridge/vscreen/device-producer.mjs";
import {
  createArtifactReceiptStore,
  inspectApkFingerprint,
  parseApkMetadata,
  projectArtifactReceipt,
} from "../product-plugins/android-developer-bridge/app-delivery/artifact-service.mjs";
import { createInstallApprovalBroker } from "../product-plugins/android-developer-bridge/app-delivery/install-approval.mjs";
import {
  ANDROID_APP_INSTALLED_EVENT_STREAM,
  AndroidInstalledAppEventStore,
} from "../product-plugins/android-developer-bridge/app-delivery/install-result.mjs";
import { AndroidAppVScreenAssignmentStore } from "../product-plugins/android-developer-bridge/app-delivery/vscreen-assignment.mjs";
import {
  ANDROID_DEVICE_METHODS,
  AndroidDeviceErrorCode,
  normalizeAdbEndpoint,
  parseConnectParams,
  parseForgetParams,
  parsePairParams,
  parseStatusParams,
  parseVerifyReconnectParams,
} from "../product-plugins/android-developer-bridge/bridge/protocol.mjs";
import {
  SCRCPY_SERVER_SHA256,
  SCRCPY_SERVER_SIZE_BYTES,
  requireQualifiedScrcpyServer,
  scrcpyVScreenServerArguments,
} from "../product-plugins/android-developer-bridge/vscreen/helper.mjs";
import {
  ANDROID_VSCREEN_PRODUCER_ID,
  createAndroidVScreenProducer,
  serializeScrcpyPointerEvent,
} from "../product-plugins/android-developer-bridge/vscreen/producer.mjs";
import { AndroidVScreenReadinessStore } from "../product-plugins/android-developer-bridge/vscreen/readiness.mjs";
import {
  VSCREEN_INTERNAL_METHODS,
  VSCREEN_PROTOCOL_VERSION,
} from "../product-plugins/vscreen-foundation/protocol.mjs";
import {
  ANDROID_VSCREEN_PRODUCER_METHODS,
  VSCREEN_FOUNDATION_PLUGIN_ID,
  createAndroidVScreenRpcAdapter,
  parseVScreenCloseParams,
  parseVScreenEnsureParams,
  parseVScreenPresentedParams,
  parseVScreenProbeParams,
  parseVScreenWorkloadParams,
} from "../product-plugins/android-developer-bridge/vscreen/rpc.mjs";
import {
  ANDROID_APP_AUTHORIZATION_NONCE,
  ANDROID_APP_TOOL,
  ANDROID_APP_TOOL_PROTOCOL_VERSION,
  parseAndroidAppToolParams,
} from "../product-plugins/android-developer-bridge/app-delivery/tool-protocol.mjs";
import {
  createAndroidAppTool,
  createInstallMutationStore,
} from "../product-plugins/android-developer-bridge/app-delivery/tool.mjs";
import { ANDROID_PROJECT_BUILD_TOOL } from "../product-plugins/android-developer-bridge/project-build/tool-protocol.mjs";
import { registerAndroidDeveloperBridgePlugin } from "../product-plugins/android-developer-bridge/runtime.mjs";
import "./android-app-authorization.mjs";

const PROTOCOL_VERSION = 1;
const CHALLENGE_ID = "1".repeat(32);
const CHALLENGE = "2".repeat(64);
const RECONNECT_VERIFICATION_ID = "3".repeat(32);
const PAIRING_ENDPOINT = "10.0.0.2:41001";
const CONNECT_ENDPOINT = "10.0.0.2:42002";
const PAIRING_CODE = "123456";
const MDNS_TRANSPORT = "adb-phone-fixture._adb-tls-connect._tcp";
const ROTATED_TRANSPORT = "adb-phone-rotated._adb-tls-connect._tcp";
const DEVICE_SERIAL = "PHONE_SERIAL_FIXTURE";
const ARTIFACT_ID = "12345678-1234-4123-8123-123456789abc";
const WORKLOAD_ID = "92345678-1234-4123-8123-123456789abc";
const APK_SIGNER = "a".repeat(64);
const APK_CONTENT = "signed-apk-fixture";
const APK_SHA256 = createHash("sha256").update(APK_CONTENT).digest("hex");
const PROJECT_WORKSPACE_ADMISSION_KEY = Symbol.for(
  "io.github.boxuechen.clawinone.project-workspaces.admission.v2",
);

{
  const arguments_ = scrcpyVScreenServerArguments("device-fixture", "0123abcd");
  assert.deepEqual(arguments_.slice(0, 3), ["-s", "device-fixture", "shell"]);
  assert.equal(arguments_.includes("vd_system_decorations=true"), true);
  assert.equal(arguments_.some((argument) => argument === "vd_system_decorations=false"), false);
  assert.equal(arguments_.includes("new_display=720x1560/320"), true);
  assert.equal(arguments_.includes("scid=0123abcd"), true);
}

{
  const encoded = serializeScrcpyPointerEvent({
    type: "pointer",
    attachmentId: ARTIFACT_ID,
    sequence: 1,
    pointerId: 7,
    phase: "down",
    normalizedX: 0.5,
    normalizedY: 1,
    pressure: 1,
  }, { width: 720, height: 1560 });
  assert.equal(encoded.length, 32);
  assert.equal(encoded[0], 2);
  assert.equal(encoded[1], 0);
  assert.equal(encoded.readBigUInt64BE(2), 7n);
  assert.equal(encoded.readUInt32BE(10), 360);
  assert.equal(encoded.readUInt32BE(14), 1559);
  assert.equal(encoded.readUInt16BE(18), 720);
  assert.equal(encoded.readUInt16BE(20), 1560);
  assert.equal(encoded.readUInt16BE(22), 0xffff);
}

function deferred() {
  let resolve;
  const promise = new Promise((done) => {
    resolve = done;
  });
  return { promise, resolve };
}

const standardSession = {
  resolveStorePath: () => "/fixture/session-store",
  getSessionEntry: () => ({ sessionId: "session-1", permissionMode: "guarded" }),
  runWithWorkAdmission: async ({ signal }, run) => run(signal),
};
const hostContext = (toolCallId) => ({
  agentId: "main", sessionKey: "agent:main:main", sessionId: "session-1",
  runId: "run-1", toolCallId, abortSignal: new AbortController().signal,
});

async function temporaryDirectory(name) {
  return await fs.mkdtemp(path.join(os.tmpdir(), `claw-in-one-${name}-`));
}

function fakeAdb(challenge = CHALLENGE) {
  const calls = [];
  let connected = [];
  let catValue = challenge;
  let displayTopPackages = ["com.android.launcher3"];

  return {
    calls,
    setConnected(next) {
      connected = [...next];
    },
    setCatValue(next) {
      catValue = next;
    },
    setDisplayTopPackages(next) {
      displayTopPackages = [...next];
    },
    async run(args, options = {}) {
      calls.push({ args: [...args], options: { ...options } });
      if (args[0] === "kill-server" || args[0] === "start-server") {
        return { code: 0, stdout: "", stderr: "" };
      }
      if (args[0] === "pair") {
        assert.equal(args.includes(PAIRING_CODE), false);
        assert.equal(options.input, `${PAIRING_CODE}\n`);
        connected = ["unrelated-device:5555", "10.0.0.2:43003", MDNS_TRANSPORT];
        return { code: 0, stdout: "Successfully paired", stderr: "" };
      }
      if (args[0] === "connect") {
        connected = [ROTATED_TRANSPORT];
        return { code: 0, stdout: `connected to ${args[1]}`, stderr: "" };
      }
      if (args[0] === "disconnect") {
        connected = connected.filter((serial) => serial !== args[1]);
        return { code: 0, stdout: "", stderr: "" };
      }
      if (args[0] === "devices") {
        return {
          code: 0,
          stdout: `List of devices attached\n${connected.map((serial) => `${serial}\tdevice product:fixture`).join("\n")}\n`,
          stderr: "",
        };
      }
      if (args[0] === "-s" && args[2] === "shell" && args[3] === "cat") {
        return {
          code: 0,
          stdout: args[1] === "unrelated-device:5555" ? `${"9".repeat(64)}\n` : `${catValue}\n`,
          stderr: "",
        };
      }
      if (args[0] === "-s" && args[2] === "shell" && args[3] === "getprop") {
        const unrelated = args[1] === "unrelated-device:5555";
        const values = {
          "ro.serialno": unrelated ? "OTHER_DEVICE" : DEVICE_SERIAL,
          "ro.product.device": unrelated ? "other" : "shiba",
          "ro.product.model": unrelated ? "Other" : "Pixel 8",
          "ro.build.version.sdk": unrelated ? "36" : "37",
        };
        return { code: 0, stdout: `${values[args[4]] ?? ""}\n`, stderr: "" };
      }
      if (
        args[0] === "-s" &&
        args.slice(2).join(" ").includes("cmd package resolve-activity") &&
        args.includes("android.intent.category.SECONDARY_HOME")
      ) {
        return { code: 0, stdout: "com.android.launcher3/.SecondaryDisplayLauncher\n", stderr: "" };
      }
      if (
        args[0] === "-s" &&
        args.slice(2).join(" ").includes("cmd package resolve-activity") &&
        args.includes("android.intent.category.LAUNCHER")
      ) {
        const packageName = args.at(-1);
        return { code: 0, stdout: `${packageName}/.MainActivity\n`, stderr: "" };
      }
      if (args[0] === "-s" && args[2] === "shell" && args[3] === "am" && args[4] === "start") {
        return { code: 0, stdout: "Starting: Intent\n", stderr: "" };
      }
      if (args[0] === "-s" && args[2] === "shell" && args[3]?.startsWith("dumpsys activity -d ")) {
        const packageName = displayTopPackages.length > 1
          ? displayTopPackages.shift()
          : displayTopPackages[0];
        return {
          code: packageName ? 0 : 1,
          stdout: packageName
            ? `topResumedActivity=ActivityRecord{1 u0 ${packageName}/.Launcher t9}\n`
            : "",
          stderr: "",
        };
      }
      throw new Error(`Unexpected fake ADB command: ${args.join(" ")}`);
    },
  };
}

{
  const root = await temporaryDirectory("vscreen-home-readiness");
  const adb = fakeAdb();
  adb.setDisplayTopPackages([null, "com.android.launcher3"]);
  let closeCalls = 0;
  const helper = {
    display: { id: 42, width: 720, height: 1560, dpi: 320, rotation: 0 },
    codec: "h264",
    video: new PassThrough(),
    control: new PassThrough(),
    closed: new Promise(() => {}),
    async close() { closeCalls += 1; },
  };
  const bridge = new AndroidDeviceBridge(serviceOptions(root, adb));
  const deviceProducer = new AndroidVScreenDeviceProducer({
    bridge,
    installRoot: root,
    startVScreenHelper: async () => helper,
    sleep: async () => {},
  });
  assert.equal(
    deviceProducer.helperPath,
    path.join(root, "toolchains/scrcpy/current/scrcpy-server"),
  );
  try {
    await bridge.start();
    await writeChallenge(root);
    await bridge.pair({
      endpoint: PAIRING_ENDPOINT,
      pairingCode: PAIRING_CODE,
      challengeId: CHALLENGE_ID,
    });
    const binding = await bridge.deviceBinding();
    assert.equal(
      await deviceProducer.ensureTarget({ expectedGeneration: binding.generation }),
      helper,
    );
    assert.equal(
      await deviceProducer.ensureTarget({ expectedGeneration: binding.generation }),
      helper,
      "recovery ensure reuses the healthy display after explicitly returning it Home",
    );
    assert.equal(
      adb.calls.filter(({ args }) => args[2] === "shell" && args[3]?.startsWith("dumpsys activity -d ")).length,
      3,
      "VScreen waits for Home and verifies it again when recovering a retained target",
    );
    const homeStarts = adb.calls.filter(
      ({ args }) => args[2] === "shell" && args[3] === "am" && args[4] === "start",
    );
    assert.equal(
      homeStarts.length,
      2,
      "every ensure establishes secondary Home instead of trusting the previous workload foreground",
    );
    assert.equal(
      homeStarts.every(({ args }) => args.includes("com.android.launcher3/.SecondaryDisplayLauncher")),
      true,
      "VScreen launches the resolved secondary-display component",
    );
    const homeResolutions = adb.calls.filter(
      ({ args }) => args[2] === "shell" && args.slice(3).includes("resolve-activity"),
    );
    assert.equal(homeResolutions.length, 2);
    assert.equal(
      homeResolutions.every(({ args }) => args.includes("android.intent.category.SECONDARY_HOME")),
      true,
      "VScreen resolves Android's dedicated secondary-display Home",
    );
    assert.equal(
      homeResolutions.some(({ args }) => args.includes("android.intent.category.HOME")),
      false,
      "VScreen never falls back to the phone's primary Home",
    );
    adb.setDisplayTopPackages(["com.android.settings"]);
    const placement = await deviceProducer.placeWorkload({
      expectedGeneration: binding.generation,
      displayId: helper.display.id,
      packageName: "com.android.settings",
    });
    assert.equal(placement.component, "com.android.settings/.MainActivity");
    assert.equal(placement.observedPackage, "com.android.settings");
    const appStart = adb.calls
      .filter(({ args }) => args[2] === "shell" && args[3] === "am" && args[4] === "start")
      .at(-1);
    assert.equal(appStart.args.includes("com.android.settings/.MainActivity"), true);
  } finally {
    await deviceProducer.close();
    await bridge.stop();
    assert.equal(closeCalls, 1);
    await fs.rm(root, { recursive: true, force: true });
  }
}

function serviceOptions(root, adb, overrides = {}) {
  return {
    stateDir: path.join(root, "state"),
    challengeSharedRoot: path.join(root, "shared"),
    challengeDeviceRoot: "/sdcard/Download/ClawInOne/developer-bridge",
    serverPort: 5038,
    checkAdb: async () => {},
    runCommand: adb.run,
    sleep: async () => {},
    pairAttempts: 1,
    connectAttempts: 1,
    now: () => 123456,
    ...overrides,
  };
}

async function writeChallenge(root, value = CHALLENGE) {
  const shared = path.join(root, "shared");
  await fs.mkdir(shared, { recursive: true });
  await fs.writeFile(path.join(shared, `challenge-${CHALLENGE_ID}.txt`), `${value}\n`, { mode: 0o600 });
}

function fakeApkTool(command, args) {
  assert.equal(args.at(-1).endsWith("fixture.apk"), true);
  if (command === "aapt2") {
    return {
      stdout: "package: name='io.github.clawinone.fixture' versionCode='7' versionName='0.7'\nsdkVersion:'26'\ntargetSdkVersion:'37'\n",
      stderr: "",
    };
  }
  if (command === "apksigner") {
    return { stdout: `Signer #1 certificate SHA-256 digest: ${APK_SIGNER}\n`, stderr: "" };
  }
  throw new Error(`Unexpected SDK tool: ${command}`);
}

{
  assert.deepEqual(parseAndroidAppToolParams({ operation: "inspect_apk", apkPath: "app/build/fixture.apk" }), {
    operation: "inspect_apk",
    apkPath: "app/build/fixture.apk",
  });
  assert.deepEqual(parseAndroidAppToolParams({ operation: "read_logs", artifactId: ARTIFACT_ID }), {
    operation: "read_logs",
    artifactId: ARTIFACT_ID,
    maxLines: 200,
  });
  assert.throws(() => parseAndroidAppToolParams({ operation: "launch_app", artifactId: ARTIFACT_ID }), /supported operation/);
  assert.throws(
    () => parseAndroidAppToolParams({ operation: "place_vscreen_workload", artifactId: ARTIFACT_ID, packageName: "other" }),
    /does not accept packageName/,
  );
  assert.throws(() => parseAndroidAppToolParams({ operation: "install_apk", artifactId: "bad" }), /artifactId/);
  assert.deepEqual(
    parseAndroidAppToolParams({ operation: "await_vscreen_ready", artifactId: ARTIFACT_ID, workloadId: WORKLOAD_ID }),
    { operation: "await_vscreen_ready", artifactId: ARTIFACT_ID, workloadId: WORKLOAD_ID },
  );
  assert.throws(
    () => parseAndroidAppToolParams({ operation: "read_logs", artifactId: ARTIFACT_ID, maxLines: 201 }),
    /maxLines/,
  );
  assert.deepEqual(
    parseApkMetadata(
      "package: name='io.github.clawinone.fixture' versionCode='7' versionName='0.7'\nsdkVersion:'26'\ntargetSdkVersion:'37'\n",
      `Signer #1 certificate SHA-256 digest: ${APK_SIGNER}`,
    ),
    {
      packageName: "io.github.clawinone.fixture",
      versionCode: "7",
      versionName: "0.7",
      minSdk: "26",
      targetSdk: "37",
      signerSha256: [APK_SIGNER],
    },
  );
  assert.deepEqual(
    parseApkMetadata(
      "package: name='io.github.clawinone.fixture' versionCode='7' versionName='0.7'\ntargetSdkVersion:'37'\n",
      `Signer #1 certificate SHA-256 digest: ${APK_SIGNER}`,
    ),
    {
      packageName: "io.github.clawinone.fixture",
      versionCode: "7",
      versionName: "0.7",
      minSdk: "1",
      targetSdk: "37",
      signerSha256: [APK_SIGNER],
    },
  );
  assert.deepEqual(
    parseApkMetadata(
      "package: name='io.github.clawinone.fixture' versionCode='7' versionName='0.7'\nsdkVersion:'26'\n",
      `Signer #1 certificate SHA-256 digest: ${APK_SIGNER}`,
    ),
    {
      packageName: "io.github.clawinone.fixture",
      versionCode: "7",
      versionName: "0.7",
      minSdk: "26",
      targetSdk: "26",
      signerSha256: [APK_SIGNER],
    },
  );

  // Captured AAPT 37 output uses minSdkVersion, not sdkVersion. Missing target
  // defaults to the declared minimum; it must not turn a real minSdk 23 into 1.
  for (const declaration of ["minSdkVersion:'23'", "sdkVersion:'23'\nminSdkVersion:'23'"]) {
    const metadata = parseApkMetadata(
      `package: name='io.github.clawinone.fixture' versionCode='2' versionName='2.0'\n${declaration}\n`,
      `Signer #1 certificate SHA-256 digest: ${APK_SIGNER}`,
    );
    assert.equal(metadata.minSdk, "23");
    assert.equal(metadata.targetSdk, "23");
  }
  for (const declaration of [
    "minSdkVersion:'23'\nsdkVersion:'26'",
    "minSdkVersion:'23'\nminSdkVersion:'26'",
    "minSdkVersion:''",
    "minSdkVersion:23",
  ]) {
    assert.throws(() => parseApkMetadata(
      `package: name='io.github.clawinone.fixture' versionCode='2' versionName='2.0'\n${declaration}\n`,
      `Signer #1 certificate SHA-256 digest: ${APK_SIGNER}`,
    ), /minimum SDK metadata/);
  }
}

{
  const root = await temporaryDirectory("developer-bridge-artifact");
  try {
    const apk = path.join(root, "fixture.apk");
    await fs.writeFile(apk, APK_CONTENT);
    const fingerprint = await inspectApkFingerprint({
      workspaceRoot: root,
      apkPath: "fixture.apk",
      runTool: fakeApkTool,
    });
    assert.equal(fingerprint.apkPath, "fixture.apk");
    assert.equal(fingerprint.metadata.packageName, "io.github.clawinone.fixture");
    assert.match(fingerprint.sha256, /^[0-9a-f]{64}$/);
    await assert.rejects(
      inspectApkFingerprint({ workspaceRoot: root, apkPath: "../outside.apk", runTool: fakeApkTool }),
      /escapes/,
    );
    const alias = path.join(root, "alias.apk");
    await fs.symlink(apk, alias);
    await assert.rejects(
      inspectApkFingerprint({ workspaceRoot: root, apkPath: "alias.apk", runTool: fakeApkTool }),
      /regular APK/,
    );

    let clock = 1000;
    let nextId = ARTIFACT_ID;
    const store = createArtifactReceiptStore({ now: () => clock, randomUUID: () => nextId });
    const binding = {
      generation: "binding-1",
      target: { id: "target-1", model: "Pixel 8", androidApi: "37" },
    };
    const owner = { ownerKey: "owner-1" };
    const receipt = store.issue(owner, fingerprint, binding);
    assert.deepEqual(projectArtifactReceipt(receipt).target, {
      id: "target-1",
      model: "Pixel 8",
      androidApi: "37",
    });
    assert.equal(store.get("owner-1", ARTIFACT_ID), receipt);
    assert.equal(store.get("owner-2", ARTIFACT_ID), null);
    nextId = "22345678-1234-4123-8123-123456789abc";
    const replacement = store.issue(owner, fingerprint, binding);
    assert.equal(store.get("owner-1", ARTIFACT_ID), null);
    assert.equal(store.get("owner-1", replacement.artifactId), replacement);
    clock += 30 * 60 * 1000;
    assert.equal(store.get("owner-1", replacement.artifactId), null);
  } finally {
    await fs.rm(root, { recursive: true, force: true });
  }
}

{
  const root = await temporaryDirectory("developer-bridge-tool-install");
  try {
    await fs.writeFile(path.join(root, "fixture.apk"), APK_CONTENT);
    const binding = {
      generation: "binding-1",
      target: { id: "target-1", product: "shiba", model: "Pixel 8", androidApi: "37" },
    };
    let installCalls = 0;
    let installStatus = "succeeded";
    let validateCalls = 0;
    let launchCalls = 0;
    let captureCalls = 0;
    let logCalls = 0;
    const service = {
      async deviceBinding() {
        return structuredClone(binding);
      },
      async installedPackageState() {
        return {
          binding: structuredClone(binding),
          package: { installed: false, versionCode: null, sha256: null },
        };
      },
      async installApk(params) {
        await params.validate(structuredClone(binding));
        params.authorizeDispatch(structuredClone(binding));
        validateCalls += 1;
        installCalls += 1;
        assert.equal(params.packageName, "io.github.clawinone.fixture");
        assert.equal(params.versionCode, "7");
        assert.equal(params.sha256, APK_SHA256);
        return {
          status: installStatus,
          before: { installed: false, versionCode: null, sha256: null },
          installed:
            installStatus === "succeeded"
              ? { installed: true, versionCode: "7", sha256: APK_SHA256 }
              : { installed: false, versionCode: null, sha256: null },
        };
      },
      async prepareInstalledWorkload(params) {
        await params.validate(structuredClone(binding));
        launchCalls += 1;
        return {
          status: "vscreen_workload_requested",
          packageName: params.packageName,
          component: `${params.packageName}/.MainActivity`,
          binding: structuredClone(binding),
          requestedAtMs: 1000,
        };
      },
      async verifyPresentedApp(params) {
        await params.validate(structuredClone(binding));
        assert.equal(params.component, "io.github.clawinone.fixture/.MainActivity");
        assert.equal(params.displayId, 42);
        assert.equal(params.expectedGeneration, binding.generation);
        return {
          installed: { installed: true, versionCode: "7", sha256: APK_SHA256 },
          initialForegroundSamples: 1,
          launchLogLines: 4,
        };
      },
      async captureScreen(params) {
        await params.validate(structuredClone(binding));
        captureCalls += 1;
        return {
          status: "captured",
          packageName: params.packageName,
          png: Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a, 0x01]),
        };
      },
      async readPackageLogs(params) {
        await params.validate(structuredClone(binding));
        logCalls += 1;
        return {
          status: "read",
          packageName: params.packageName,
          maxLines: params.maxLines,
          truncated: false,
          logs: "fixture log\n",
        };
      },
    };
    const receiptStore = createArtifactReceiptStore({ randomUUID: () => ARTIFACT_ID });
    const mutationStore = createInstallMutationStore();
    const readinessStore = new AndroidVScreenReadinessStore({
      now: () => 1000,
      randomUUID: () => "92345678-1234-4123-8123-123456789abc",
    });
    const approvalIds = [
      "32345678-1234-4123-8123-123456789abc",
      "42345678-1234-4123-8123-123456789abc",
      "52345678-1234-4123-8123-123456789abc",
      "62345678-1234-4123-8123-123456789abc",
      "72345678-1234-4123-8123-123456789abc",
      "82345678-1234-4123-8123-123456789abc",
    ];
    const approvalBroker = createInstallApprovalBroker({
      session: standardSession,
      resolveDelivery: () => service,
      receiptStore,
      runTool: fakeApkTool,
      randomUUID: () => approvalIds.shift(),
    });
    const tool = createAndroidAppTool(
      () => service,
      {
        sessionKey: "agent:main:main",
        sessionId: "session-1",
        agentId: "main",
        workspaceDir: root,
        fsPolicy: { workspaceOnly: true, root },
      },
      { receiptStore, mutationStore, approvalBroker, readinessStore, runTool: fakeApkTool },
    );
    const inspected = await tool.execute("inspect-call", { operation: "inspect_apk", apkPath: "fixture.apk" });
    assert.equal(inspected.details.status, "inspected");
    assert.equal(inspected.details.artifact.artifactId, ARTIFACT_ID);

    await assert.rejects(
      tool.execute("install-call", { operation: "install_apk", artifactId: ARTIFACT_ID }),
      /not authorized/,
    );
    const approval = await approvalBroker.beforeToolCall(
      {
        toolName: ANDROID_APP_TOOL,
        toolCallId: "install-call",
        params: {
          operation: "install_apk",
          artifactId: ARTIFACT_ID,
          [ANDROID_APP_AUTHORIZATION_NONCE]: "model-supplied-value",
        },
      },
      hostContext("install-call"),
    );
    assert.equal(approval.requireApproval.title, "Install io.github.clawinone.fixture");
    assert.deepEqual(approval.requireApproval.allowedDecisions, ["allow-once", "deny"]);
    assert.equal(approval.requireApproval.description.includes(APK_SHA256), true);
    assert.equal(approval.requireApproval.description.includes(APK_SIGNER), true);
    assert.notEqual(
      approval.params[ANDROID_APP_AUTHORIZATION_NONCE],
      "model-supplied-value",
    );
    approval.requireApproval.onResolution("allow-once");
    const install = await tool.execute("install-call", approval.params);
    assert.equal(install.details.protocolVersion, ANDROID_APP_TOOL_PROTOCOL_VERSION);
    assert.equal(install.details.operation, "install_apk");
    assert.equal(install.details.status, "succeeded");
    assert.equal(install.details.mutationId, "32345678-1234-4123-8123-123456789abc");
    assert.equal(validateCalls, 1);
    assert.equal(installCalls, 1);

    const duplicate = await tool.execute("install-call", approval.params);
    assert.deepEqual(duplicate.details, install.details);
    assert.equal(installCalls, 1);

    const denied = await approvalBroker.beforeToolCall(
      {
        toolName: ANDROID_APP_TOOL,
        toolCallId: "install-denied",
        params: { operation: "install_apk", artifactId: ARTIFACT_ID },
      },
      hostContext("install-denied"),
    );
    denied.requireApproval.onResolution("deny");
    await assert.rejects(tool.execute("install-denied", denied.params), /not authorized/);
    assert.equal(installCalls, 1);

    const unknownApproval = await approvalBroker.beforeToolCall(
      {
        toolName: ANDROID_APP_TOOL,
        toolCallId: "install-unknown",
        params: { operation: "install_apk", artifactId: ARTIFACT_ID },
      },
      hostContext("install-unknown"),
    );
    unknownApproval.requireApproval.onResolution("allow-once");
    installStatus = "unknown";
    const unknown = await tool.execute("install-unknown", unknownApproval.params);
    assert.equal(unknown.details.status, "unknown");
    assert.equal(installCalls, 2);
    const duplicateUnknown = await tool.execute("install-unknown", unknownApproval.params);
    assert.deepEqual(duplicateUnknown.details, unknown.details);
    assert.equal(installCalls, 2);

    const launched = await tool.execute("launch-call", { operation: "place_vscreen_workload", artifactId: ARTIFACT_ID });
    assert.equal(launched.details.status, "vscreen_workload_requested");
    assert.equal(launched.details.workloadId, "92345678-1234-4123-8123-123456789abc");
    assert.equal(launchCalls, 1);
    readinessStore.attachVScreen({
      workloadId: launched.details.workloadId,
      targetPackage: launched.details.packageName,
      binding,
      sourceHandle: "92345678-1234-4123-8123-123456789abd",
      display: { id: 42, width: 720, height: 1560, dpi: 320, rotation: 0 },
      initialForegroundSamples: 1,
    });
    readinessStore.frameReady({
      workloadId: launched.details.workloadId,
      sourceHandle: "92345678-1234-4123-8123-123456789abd",
    });
    const ready = await tool.execute("ready-call", {
      operation: "await_vscreen_ready",
      artifactId: ARTIFACT_ID,
      workloadId: launched.details.workloadId,
    });
    assert.equal(ready.details.status, "vscreen_ready");
    assert.equal(ready.details.evidence.firstFrameAtMs, 1000);
    assert.equal(ready.details.evidence.initialForegroundSamples, 1);
    assert.equal(ready.details.evidence.fatalCrash, false);
    const captured = await tool.execute("capture-call", { operation: "capture_screen", artifactId: ARTIFACT_ID });
    assert.equal(captured.details.status, "captured");
    assert.equal(captured.content[1].type, "image");
    assert.equal(captured.content[1].mimeType, "image/png");
    assert.equal(captureCalls, 1);
    const logs = await tool.execute("logs-call", {
      operation: "read_logs",
      artifactId: ARTIFACT_ID,
      maxLines: 17,
    });
    assert.equal(logs.details.logs, "fixture log\n");
    assert.equal(logs.details.maxLines, 17);
    assert.equal(logCalls, 1);

    const workspaceSession = {
      ...standardSession,
      getSessionEntry: () => ({ sessionId: "session-1", permissionMode: "workspace", projectId: "project-1" }),
    };
    globalThis[PROJECT_WORKSPACE_ADMISSION_KEY] = {
      admitProjectMutation: (_identity, capability) => capability === "android_kotlin" ? {
        projectId: "project-1",
        projectRoot: "/workspace/project-1",
        leaseGeneration: 1,
      } : null,
    };
    const workspaceBroker = createInstallApprovalBroker({
      session: workspaceSession,
      resolveDelivery: () => service,
      receiptStore,
      runTool: fakeApkTool,
      randomUUID: () => "a2345678-1234-4123-8123-123456789abc",
    });
    const workspaceApproval = await workspaceBroker.beforeToolCall(
      {
        toolName: ANDROID_APP_TOOL,
        toolCallId: "workspace-install",
        params: { operation: "install_apk", artifactId: ARTIFACT_ID },
      },
      hostContext("workspace-install"),
    );
    assert.equal(workspaceApproval.requireApproval, undefined);
    assert.equal(typeof workspaceApproval.params[ANDROID_APP_AUTHORIZATION_NONCE], "string");

    globalThis[PROJECT_WORKSPACE_ADMISSION_KEY] = { admitProjectMutation: () => null };
    const rejectedWorkspace = await workspaceBroker.beforeToolCall(
      {
        toolName: ANDROID_APP_TOOL,
        toolCallId: "unrelated-workspace-install",
        params: { operation: "install_apk", artifactId: ARTIFACT_ID },
      },
      hostContext("unrelated-workspace-install"),
    );
    assert.equal(rejectedWorkspace.block, true);
    assert.match(rejectedWorkspace.blockReason, /active ready Project run/);
    workspaceBroker.close();
    delete globalThis[PROJECT_WORKSPACE_ADMISSION_KEY];
  } finally {
    delete globalThis[PROJECT_WORKSPACE_ADMISSION_KEY];
    await fs.rm(root, { recursive: true, force: true });
  }
}

{
  const root = await temporaryDirectory("developer-bridge-device-install");
  const stateDir = path.join(root, "state");
  await fs.mkdir(stateDir, { recursive: true });
  await fs.writeFile(
    path.join(stateDir, "connection.json"),
    `${JSON.stringify({
      version: 1,
      selection: {
        adbSerial: MDNS_TRANSPORT,
        deviceSerial: DEVICE_SERIAL,
        targetId: "f".repeat(64),
        product: "shiba",
        model: "Pixel 8",
        androidApi: "37",
      },
      revokedAtMs: null,
    })}\n`,
  );
  let installed = false;
  let installShouldThrow = false;
  let installCalls = 0;
  let foreground = true;
  let fatalLogs = false;
  const adbCalls = [];
  const runCommand = async (args) => {
    adbCalls.push([...args]);
    if (args[0] === "kill-server" || args[0] === "start-server") return { code: 0, stdout: "", stderr: "" };
    if (args[0] === "devices") {
      return { code: 0, stdout: `List of devices attached\n${MDNS_TRANSPORT}\tdevice product:fixture\n`, stderr: "" };
    }
    if (args[0] === "-s" && args[2] === "shell" && args[3] === "getprop") {
      const values = {
        "ro.serialno": DEVICE_SERIAL,
        "ro.product.device": "shiba",
        "ro.product.model": "Pixel 8",
        "ro.build.version.sdk": "37",
      };
      return { code: 0, stdout: `${values[args[4]] ?? ""}\n`, stderr: "" };
    }
    if (args[0] === "-s" && args[2] === "shell" && args[3] === "pm" && args[4] === "path") {
      return installed
        ? { code: 0, stdout: "package:/data/app/fixture/base.apk\n", stderr: "" }
        : { code: 1, stdout: "", stderr: "package not found" };
    }
    if (args[0] === "-s" && args[2] === "shell" && args[3] === "pm" && args[4] === "list") {
      return { code: 0, stdout: "package:io.github.clawinone.fixture versionCode:7\n", stderr: "" };
    }
    if (args[0] === "-s" && args[2] === "shell" && args[3] === "sha256sum") {
      return { code: 0, stdout: `${APK_SHA256}  /data/app/fixture/base.apk\n`, stderr: "" };
    }
    if (args[0] === "-s" && args[2] === "shell" && args[3] === "cmd" && args[4] === "package" && args[5] === "resolve-activity") {
      return { code: 0, stdout: "io.github.clawinone.fixture/.MainActivity\n", stderr: "" };
    }
    if (args[0] === "-s" && args[2] === "shell" && args[3] === "am" && args[4] === "start") {
      foreground = true;
      return { code: 0, stdout: "Status: ok\n", stderr: "" };
    }
    if (args[0] === "-s" && args[2] === "shell" && args[3] === "dumpsys" && args[4] === "window") {
      assert.deepEqual(args.slice(3), ["dumpsys", "window"]);
      return {
        code: 0,
        stdout: foreground ? "mCurrentFocus=Window{abc u0 io.github.clawinone.fixture/.MainActivity}\n" : "mCurrentFocus=Window{abc u0 com.android.settings/.Settings}\n",
        stderr: "",
      };
    }
    if (args[0] === "-s" && args[2] === "shell" && args[3]?.startsWith("dumpsys activity -d ")) {
      assert.equal(args[3], "dumpsys activity -d 42 activities | toybox grep -E 'topResumedActivity|mResumedActivity'");
      return {
        code: 0,
        stdout: "topResumedActivity=ActivityRecord{1 u0 io.github.clawinone.fixture/.MainActivity t9}\n",
        stderr: "",
      };
    }
    if (args[0] === "-s" && args[2] === "shell" && args[3] === "cmd" && args[4] === "package" && args[5] === "list") {
      return { code: 0, stdout: "package:io.github.clawinone.fixture uid:10123\n", stderr: "" };
    }
    if (args[0] === "-s" && args[2] === "shell" && args[3] === "logcat") {
      if (args.includes("-v")) {
        assert.deepEqual(args.slice(3), ["logcat", "-d", "--uid=10123", "-v", "epoch", "-t", "200"]);
        return {
          code: 0,
          stdout: fatalLogs
            ? "1000.100 10123 10123 E AndroidRuntime: FATAL EXCEPTION: main\n"
            : "1000.100 10123 10123 I fixture: launched\n",
          stderr: "",
        };
      }
      assert.deepEqual(args.slice(3), ["logcat", "-d", "--uid=10123", "-t", "23"]);
      return { code: 0, stdout: "09-06 fixture log\n", stderr: "" };
    }
    if (args[0] === "-s" && args[2] === "install") {
      installCalls += 1;
      if (installShouldThrow) throw new Error("transport timeout");
      installed = true;
      return { code: 0, stdout: "Success\n", stderr: "" };
    }
    throw new Error(`Unexpected device-install ADB command: ${args.join(" ")}`);
  };
  const bridge = new AndroidDeviceBridge({
    stateDir,
    checkAdb: async () => {},
    runCommand,
    async runBinaryCommand(args) {
      assert.deepEqual(args, ["-s", MDNS_TRANSPORT, "exec-out", "screencap", "-p"]);
      return {
        code: 0,
        stdout: Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a, 0x01]),
        stderr: "",
      };
    },
    randomUUID: () => "binding-generation",
    now: () => 1_000_000,
  });
  const delivery = new AndroidAppDelivery({ bridge, now: () => 1_000_000 });
  for (const operation of ["installApk", "prepareInstalledWorkload", "verifyPresentedApp", "ensureTarget", "startProbe"]) {
    assert.equal(typeof bridge[operation], "undefined", `Device Bridge must not own ${operation}`);
  }
  for (const operation of ["pair", "connect", "verifyReconnect", "forget", "ensureTarget"]) {
    assert.equal(typeof delivery[operation], "undefined", `App Delivery must not own ${operation}`);
  }
  try {
    await bridge.start();
    const binding = await bridge.deviceBinding();
    assert.equal(binding.generation, "binding-generation");
    let checkedBinding;
    const result = await delivery.installApk({
      apkPath: "/workspace/fixture.apk",
      packageName: "io.github.clawinone.fixture",
      versionCode: "7",
      sha256: APK_SHA256,
      signal: new AbortController().signal,
      authorizeDispatch(value) { assert.deepEqual(value, binding); },
      async validate(value) {
        checkedBinding = value;
      },
    });
    assert.equal(result.status, "succeeded");
    assert.deepEqual(checkedBinding, binding);
    const installArgs = adbCalls.find((args) => args[0] === "-s" && args[2] === "install");
    assert.deepEqual(installArgs, ["-s", MDNS_TRANSPORT, "install", "-r", "/workspace/fixture.apk"]);

    installed = false;
    installShouldThrow = true;
    const unknown = await delivery.installApk({
      apkPath: "/workspace/fixture.apk",
      packageName: "io.github.clawinone.fixture",
      versionCode: "7",
      sha256: APK_SHA256,
      signal: new AbortController().signal,
      authorizeDispatch(value) { assert.deepEqual(value, binding); },
      async validate() {},
    });
    assert.equal(unknown.status, "unknown");
    assert.equal(installCalls, 2);

    // Cancellation or lost policy after validation must fail before the ADB install command.
    const cancellation = new AbortController();
    const installParams = {
      apkPath: "/workspace/fixture.apk",
      packageName: "io.github.clawinone.fixture",
      versionCode: "7", sha256: APK_SHA256,
      signal: cancellation.signal,
      async validate() {},
      authorizeDispatch(value) { assert.deepEqual(value, binding); },
    };
    await assert.rejects(delivery.installApk({
      ...installParams,
      authorizeDispatch() { throw new Error("policy changed before dispatch"); },
    }), /policy changed/);
    await assert.rejects(delivery.installApk({
      ...installParams,
      async validate() { cancellation.abort(new Error("Chat stopped")); },
    }), /Chat stopped/);
    assert.equal(installCalls, 2);

    installed = true;
    installShouldThrow = false;
    const artifactOperation = {
      packageName: "io.github.clawinone.fixture",
      versionCode: "7",
      sha256: APK_SHA256,
      async validate(value) {
        assert.equal(value.target.id, "f".repeat(64));
      },
    };
    const launched = await delivery.prepareInstalledWorkload(artifactOperation);
    assert.equal(launched.status, "vscreen_workload_requested");
    assert.equal(launched.component, "io.github.clawinone.fixture/.MainActivity");
    assert.equal(adbCalls.some((args) => args[2] === "shell" && args[3] === "am" && args[4] === "start"), false, "place_vscreen_workload must wait for the VScreen workload mutation");
    const readiness = await delivery.verifyPresentedApp({
      ...artifactOperation,
      component: launched.component,
      displayId: 42,
      expectedGeneration: binding.generation,
      requestedAtMs: launched.requestedAtMs,
    });
    assert.equal(readiness.launchLogLines, 1);
    fatalLogs = true;
    await assert.rejects(delivery.verifyPresentedApp({
      ...artifactOperation,
      component: launched.component,
      displayId: 42,
      expectedGeneration: binding.generation,
      requestedAtMs: launched.requestedAtMs,
    }), /fatal crash/);
    fatalLogs = false;
    const captured = await delivery.captureScreen(artifactOperation);
    assert.equal(captured.status, "captured");
    assert.equal(captured.png.subarray(0, 8).toString("hex"), "89504e470d0a1a0a");
    const logs = await delivery.readPackageLogs({ ...artifactOperation, maxLines: 23 });
    assert.equal(logs.logs, "09-06 fixture log\n");
    assert.equal(logs.truncated, false);

    foreground = false;
    await assert.rejects(delivery.captureScreen(artifactOperation), /not in the foreground/);
  } finally {
    await bridge.stop();
    await fs.rm(root, { recursive: true, force: true });
  }
}

{
  assert.deepEqual(parseStatusParams({ protocolVersion: PROTOCOL_VERSION }), {});
  assert.deepEqual(parseForgetParams({ protocolVersion: PROTOCOL_VERSION }), {});
  assert.deepEqual(parseConnectParams({ protocolVersion: PROTOCOL_VERSION }), { endpoint: null });
  assert.deepEqual(
    parseVerifyReconnectParams({
      protocolVersion: PROTOCOL_VERSION,
      verificationId: RECONNECT_VERIFICATION_ID,
    }),
    { endpoint: null, verificationId: RECONNECT_VERIFICATION_ID },
  );
  assert.equal(normalizeAdbEndpoint("[fd00::1]:5555"), "[fd00::1]:5555");
  assert.equal(normalizeAdbEndpoint("phone.local:5555"), "phone.local:5555");
  assert.equal(
    normalizeAdbEndpoint("Android_DEVICE.local:41003"),
    "android_device.local:41003",
  );
  assert.throws(() => normalizeAdbEndpoint("8.8.8.8:5555"), /private/);
  assert.throws(() => normalizeAdbEndpoint("bad..local:5555"), /private/);
  assert.throws(
    () => parsePairParams({ protocolVersion: PROTOCOL_VERSION, endpoint: PAIRING_ENDPOINT, pairingCode: "12345", challengeId: CHALLENGE_ID }),
    /six digits/,
  );
  assert.throws(
    () => parseStatusParams({ protocolVersion: PROTOCOL_VERSION, unexpected: true }),
    /Unexpected request field/,
  );
  assert.throws(() => parseStatusParams({ protocolVersion: 2 }), /protocolVersion/);
  assert.throws(
    () => parseVerifyReconnectParams({ protocolVersion: PROTOCOL_VERSION, verificationId: "bad" }),
    /verificationId/,
  );
  assert.throws(() => new AndroidDeviceBridge({ stateDir: "/tmp/unused", serverPort: 5037 }), /cannot be 5037/);
  assert.equal(new AndroidDeviceBridge({ stateDir: "/tmp/unused" }).adbPath, "/usr/bin/adb");
}

{
  let processSpec;
  let stdinValue;
  const runner = createAdbCommandRunner({
    adbPath: "/fixture/adb",
    serverPort: 5038,
    privateHome: "/fixture/private-home",
    spawnProcess(command, args, options) {
      processSpec = { command, args, options };
      const child = new EventEmitter();
      child.stdout = new PassThrough();
      child.stderr = new PassThrough();
      child.stdin = new EventEmitter();
      child.stdin.end = (value) => {
        stdinValue = value;
        queueMicrotask(() => child.emit("close", 0));
      };
      child.kill = () => {};
      return child;
    },
  });
  await runner(["pair", PAIRING_ENDPOINT], { input: `${PAIRING_CODE}\n` });
  assert.deepEqual(processSpec.args, ["-P", "5038", "pair", PAIRING_ENDPOINT]);
  assert.equal(processSpec.args.includes(PAIRING_CODE), false);
  assert.equal(stdinValue, `${PAIRING_CODE}\n`);
  assert.equal(processSpec.options.env.HOME, "/fixture/private-home");
  assert.equal(processSpec.options.env.ANDROID_USER_HOME, "/fixture/private-home/.android");
  assert.equal(processSpec.options.env.ADB_VENDOR_KEYS, "/fixture/private-home/.android");
  assert.equal(processSpec.options.env.ADB_SERVER_SOCKET, undefined);
  assert.equal(processSpec.options.env.ANDROID_ADB_SERVER_PORT, undefined);
}

{
  const runner = createAdbBinaryCommandRunner({
    adbPath: "/fixture/adb",
    serverPort: 5038,
    privateHome: "/fixture/private-home",
    spawnProcess() {
      const child = new EventEmitter();
      child.stdout = new PassThrough();
      child.stderr = new PassThrough();
      child.kill = () => {};
      queueMicrotask(() => {
        child.stdout.write(Buffer.from([0x89, 0x50, 0x4e, 0x47]));
        child.emit("close", 0);
      });
      return child;
    },
  });
  const result = await runner(["exec-out", "screencap", "-p"]);
  assert.deepEqual(result.stdout, Buffer.from([0x89, 0x50, 0x4e, 0x47]));
}

{
  let spawns = 0;
  let kills = 0;
  let activeChild;
  const runner = createAdbCommandRunner({
    adbPath: "/fixture/adb", serverPort: 5038, privateHome: "/fixture/private-home",
    spawnProcess() {
      spawns += 1;
      const child = new EventEmitter();
      child.stdout = new PassThrough();
      child.stderr = new PassThrough();
      child.stdin = null;
      child.kill = (signal) => {
        assert.equal(signal, "SIGKILL");
        kills += 1;
        // Synchronous close intentionally races rejection; aborted work must not be Success.
        child.emit("close", 0);
      };
      activeChild = child;
      return child;
    },
  });
  const stopped = new AbortController();
  stopped.abort(new Error("stopped before spawn"));
  await assert.rejects(runner(["install"], { signal: stopped.signal }), /before spawn/);
  assert.equal(spawns, 0);
  const running = new AbortController();
  const result = runner(["install"], { signal: running.signal, allowFailure: true });
  const rejected = assert.rejects(result, /stopped after dispatch/);
  running.abort(new Error("stopped after dispatch"));
  await rejected;
  assert.equal(kills, 1);
  assert.equal(spawns, 1);
  activeChild.emit("close", 0);
  running.abort();
  assert.equal(kills, 1);
}

function commandPipeFixture() {
  const children = [];
  const runner = createAdbCommandRunner({
    adbPath: "/fixture/adb", serverPort: 5038, privateHome: "/fixture/private-home",
    spawnProcess(_command, _args, options) {
      const child = new EventEmitter();
      child.options = options;
      child.stdout = new PassThrough();
      child.stderr = new PassThrough();
      child.stdin = options.stdio[0] === "pipe" ? new EventEmitter() : null;
      if (child.stdin) child.stdin.end = (value) => { child.input = value; };
      child.kills = [];
      child.kill = (signal) => {
        child.kills.push(signal);
        // Error settlement must win even if close arrives during cancellation.
        child.emit("close", 0);
      };
      children.push(child);
      return child;
    },
  });
  return { runner, children };
}

{
  const { runner, children } = commandPipeFixture();
  const result = runner(["devices", "-l"]);
  assert.deepEqual(children[0].options.stdio, ["ignore", "pipe", "pipe"]);
  assert.equal(children[0].stdin, null, "read-only probes must not create an unused input pipe");
  children[0].stdout.write("device-list");
  children[0].emit("close", 0);
  assert.deepEqual(await result, { code: 0, stdout: "device-list", stderr: "" });
  assert.equal(children.length, 1);
}

for (const stream of ["stdin", "stdout", "stderr"]) {
  const { runner, children } = commandPipeFixture();
  const result = runner(["pair", PAIRING_ENDPOINT], { input: `${PAIRING_CODE}\n`, allowFailure: true });
  const rejected = assert.rejects(result, (error) => {
    assert.equal(error.code, AndroidDeviceErrorCode.adbCommandFailed);
    assert.equal(error.message, "ADB command stream failed");
    assert.equal(error.retryable, false, "an uncertain dispatched command is not an automatic retry");
    return true;
  });
  const child = children[0];
  assert.equal(child.input, `${PAIRING_CODE}\n`);
  const pipeError = Object.assign(new Error(`write EPIPE ${PAIRING_CODE}`), { code: "EPIPE" });
  assert.doesNotThrow(() => child[stream].emit("error", pipeError));
  await rejected;
  assert.deepEqual(child.kills, ["SIGKILL"]);
  assert.doesNotThrow(() => child[stream].emit("error", pipeError));
  child.emit("close", 0);
  assert.equal(children.length, 1, "a failed pipe never respawns the command");
  assert.equal(child.kills.length, 1);
}

for (const outcome of ["close", "abort", "timeout", "spawn-error", "output-limit"]) {
  const { runner, children } = commandPipeFixture();
  const cancellation = new AbortController();
  const result = runner(["pair", PAIRING_ENDPOINT], {
    input: `${PAIRING_CODE}\n`, signal: cancellation.signal, timeoutMs: outcome === "timeout" ? 1 : 10000,
  });
  const child = children[0];
  if (outcome === "close") {
    child.emit("close", 0);
    assert.equal((await result).code, 0);
  } else {
    const rejected = assert.rejects(result, {
      abort: /cancelled by user/,
      timeout: /timed out/,
      "spawn-error": /not installed/,
      "output-limit": /more data/,
    }[outcome]);
    if (outcome === "abort") cancellation.abort(new Error("cancelled by user"));
    if (outcome === "spawn-error") child.emit("error", { code: "ENOENT" });
    if (outcome === "output-limit") child.stdout.write(Buffer.alloc(256 * 1024 + 1));
    await rejected;
  }
  const kills = child.kills.length;
  for (const stream of ["stdin", "stdout", "stderr"]) {
    assert.doesNotThrow(() => child[stream].emit("error", Object.assign(new Error("late pipe failure"), { code: "EPIPE" })));
  }
  assert.equal(child.kills.length, kills, "late pipe events do not re-cancel a settled process");
  assert.equal(children.length, 1);
}

{
  const { runner, children } = commandPipeFixture();
  const result = runner(["install"], { allowFailure: true });
  children[0].stderr.write("package manager rejected the artifact");
  children[0].emit("close", 1);
  assert.deepEqual(await result, { code: 1, stdout: "", stderr: "package manager rejected the artifact" });
}

{
  const root = await temporaryDirectory("developer-bridge-service");
  const adb = fakeAdb();
  let previewProbeOptions;
  let previewProbeCloseCalls = 0;
  const previewProbeHelper = {
    display: { id: 9, width: 720, height: 1560, dpi: 320, rotation: 0 },
    codec: "h264",
    video: new PassThrough(),
    closed: new Promise(() => {}),
    async close() {
      previewProbeCloseCalls += 1;
    },
  };
  const bridge = new AndroidDeviceBridge(serviceOptions(root, adb));
  const deviceProducer = new AndroidVScreenDeviceProducer({
    bridge,
    async startVScreenHelper(options) {
      previewProbeOptions = options;
      return previewProbeHelper;
    },
  });
  try {
    await bridge.start();
    assert.deepEqual(await bridge.status(), {
      protocolVersion: PROTOCOL_VERSION,
      status: "setup_required",
      paired: false,
      connected: false,
      target: null,
    });

    await writeChallenge(root);
    const paired = await bridge.pair({
      endpoint: PAIRING_ENDPOINT,
      pairingCode: PAIRING_CODE,
      challengeId: CHALLENGE_ID,
    });
    assert.equal(paired.status, "ready");
    assert.equal(paired.target.product, "shiba");
    assert.equal(paired.target.model, "Pixel 8");
    assert.equal(paired.target.androidApi, "37");
    assert.match(paired.target.id, /^[0-9a-f]{64}$/);
    assert.equal(paired.target.id.includes(DEVICE_SERIAL), false);
    assert.equal(paired.verificationId, CHALLENGE_ID);
    await assert.rejects(fs.access(path.join(root, "shared", `challenge-${CHALLENGE_ID}.txt`)));

    const stateFile = path.join(root, "state", "connection.json");
    const stored = await fs.readFile(stateFile, "utf8");
    const storedMode = (await fs.stat(stateFile)).mode & 0o777;
    assert.equal(storedMode, 0o600);
    assert.equal(stored.includes(PAIRING_CODE), false);
    assert.equal(stored.includes(PAIRING_ENDPOINT), false);
    assert.equal(stored.includes(CHALLENGE), false);
    assert.equal(JSON.parse(stored).selection.adbSerial, MDNS_TRANSPORT);

    adb.setConnected([]);
    assert.equal((await bridge.status()).status, "offline");
    const reconnected = await bridge.connect({ endpoint: CONNECT_ENDPOINT });
    assert.equal(reconnected.status, "ready");
    assert.equal(JSON.parse(await fs.readFile(stateFile, "utf8")).selection.adbSerial, ROTATED_TRANSPORT);
    const serverStopsBeforeVerification = adb.calls.filter((call) => call.args[0] === "kill-server").length;
    const verified = await bridge.verifyReconnect({
      endpoint: null,
      verificationId: RECONNECT_VERIFICATION_ID,
    });
    assert.equal(verified.status, "ready");
    assert.equal(verified.verificationId, RECONNECT_VERIFICATION_ID);
    assert.equal((await bridge.status()).verificationId, RECONNECT_VERIFICATION_ID);
    assert.equal(
      adb.calls.filter((call) => call.args[0] === "kill-server").length,
      serverStopsBeforeVerification + 2,
    );
    adb.setConnected([]);
    await assert.rejects(
      bridge.verifyReconnect({ endpoint: null, verificationId: "4".repeat(32) }),
      (error) => error.code === AndroidDeviceErrorCode.phoneOffline,
    );
    const failedVerification = await bridge.status();
    assert.equal(failedVerification.status, "offline");
    assert.equal(failedVerification.verificationId, undefined);

    adb.setConnected([ROTATED_TRANSPORT]);
    const probeBinding = await bridge.deviceBinding();
    assert.equal(
      await deviceProducer.startProbe({ expectedGeneration: probeBinding.generation }),
      previewProbeHelper,
    );
    assert.equal(previewProbeOptions.targetPackage, undefined);
    assert.equal(previewProbeOptions.adbSerial, ROTATED_TRANSPORT);

    const sentinel = path.join(root, "state", "adb-home", ".android", "adbkey");
    await fs.writeFile(sentinel, "private-key-fixture", { mode: 0o600 });
    const forgotten = await bridge.forget();
    assert.equal(forgotten.status, "revoked");
    assert.equal(forgotten.paired, false);
    assert.equal(previewProbeCloseCalls, 1, "Forget closes producer resources before ADB credentials");
    await assert.rejects(fs.access(sentinel));
    assert.equal(JSON.parse(await fs.readFile(stateFile, "utf8")).selection, null);
  } finally {
    await deviceProducer.close();
    await bridge.stop();
    await fs.rm(root, { recursive: true, force: true });
  }
}

{
  const root = await temporaryDirectory("developer-bridge-mismatch");
  const adb = fakeAdb();
  adb.setCatValue("8".repeat(64));
  const service = new AndroidDeviceBridge(serviceOptions(root, adb));
  try {
    await service.start();
    const rejectedKey = path.join(root, "state", "adb-home", ".android", "adbkey");
    await fs.writeFile(rejectedKey, "must-be-revoked", { mode: 0o600 });
    await writeChallenge(root);
    await assert.rejects(
      service.pair({ endpoint: PAIRING_ENDPOINT, pairingCode: PAIRING_CODE, challengeId: CHALLENGE_ID }),
      (error) => error.code === AndroidDeviceErrorCode.samePhoneNotFound,
    );
    assert.equal((await service.status()).paired, false);
    await assert.rejects(fs.access(rejectedKey));
    await assert.rejects(fs.access(path.join(root, "shared", `challenge-${CHALLENGE_ID}.txt`)));
  } finally {
    await service.stop();
    await fs.rm(root, { recursive: true, force: true });
  }
}

{
  const root = await temporaryDirectory("developer-bridge-busy");
  const adb = fakeAdb();
  let releasePair;
  let announcePair;
  const pairStarted = new Promise((resolve) => {
    announcePair = resolve;
  });
  const release = new Promise((resolve) => {
    releasePair = resolve;
  });
  const service = new AndroidDeviceBridge(
    serviceOptions(root, adb, {
      async runCommand(args, options) {
        if (args[0] === "pair") {
          announcePair();
          await release;
        }
        return await adb.run(args, options);
      },
    }),
  );
  try {
    await service.start();
    await writeChallenge(root);
    const pairing = service.pair({
      endpoint: PAIRING_ENDPOINT,
      pairingCode: PAIRING_CODE,
      challengeId: CHALLENGE_ID,
    });
    await pairStarted;
    assert.equal((await service.status()).status, "pairing");
    await assert.rejects(
      service.connect({ endpoint: null }),
      (error) => error.code === AndroidDeviceErrorCode.busy,
    );
    releasePair();
    assert.equal((await pairing).status, "ready");
  } finally {
    releasePair();
    await service.stop();
    await fs.rm(root, { recursive: true, force: true });
  }
}

{
  const root = await temporaryDirectory("developer-bridge-state-recovery");
  const adb = fakeAdb();
  await fs.mkdir(path.join(root, "state"), { recursive: true });
  await fs.writeFile(path.join(root, "state", "connection.json"), "not-json\n");
  const service = new AndroidDeviceBridge(serviceOptions(root, adb));
  try {
    await service.start();
    const broken = await service.status();
    assert.equal(broken.status, "unavailable");
    assert.equal(broken.reasonCode, AndroidDeviceErrorCode.stateInvalid);
    assert.equal((await service.forget()).status, "revoked");
  } finally {
    await service.stop();
    await fs.rm(root, { recursive: true, force: true });
  }
}

{
  const root = await temporaryDirectory("developer-bridge-no-adb");
  const adb = fakeAdb();
  const service = new AndroidDeviceBridge(
    serviceOptions(root, adb, {
      checkAdb: async () => {
        throw new Error("missing");
      },
    }),
  );
  try {
    await service.start();
    const unavailable = await service.status();
    assert.equal(unavailable.status, "unavailable");
    assert.equal(unavailable.reasonCode, AndroidDeviceErrorCode.adbNotInstalled);
  } finally {
    await service.stop();
    await fs.rm(root, { recursive: true, force: true });
  }
}

{
  let clock = 1_000;
  const offers = new AndroidAppVScreenAssignmentStore({
    now: () => clock,
    ttlMs: 100,
    maxClaims: 2,
  });
  const before = (toolCallId, overrides = {}) => ({
    toolName: ANDROID_APP_TOOL,
    params: { operation: "place_vscreen_workload" },
    runId: "run-claim",
    toolCallId,
    ...overrides,
  });
  const context = (toolCallId, overrides = {}) => ({
    sessionKey: "agent:main:claim",
    sessionId: "session-claim",
    runId: "run-claim",
    toolCallId,
    ...overrides,
  });
  const result = (toolCallId, overrides = {}) => ({
    stream: "tool",
    runId: "run-claim",
    sessionKey: "agent:main:claim",
    data: {
      phase: "result",
      name: ANDROID_APP_TOOL,
      toolCallId,
      isError: false,
      result: {
        details: {
          protocolVersion: ANDROID_APP_TOOL_PROTOCOL_VERSION,
          operation: "place_vscreen_workload",
          status: "vscreen_workload_requested",
          workloadId: WORKLOAD_ID,
          packageName: "io.github.boxuechen.fixture",
          component: "io.github.boxuechen.fixture/.MainActivity",
          artifact: { packageName: "io.github.boxuechen.fixture" },
        },
      },
    },
    ...overrides,
  });

  offers.claimToolCall(before("valid-workload"), context("valid-workload"));
  const installedPublication = offers.assignmentFromToolResult(result("valid-workload"));
  assert.equal(installedPublication.producerId, ANDROID_VSCREEN_PRODUCER_ID);
  assert.equal(installedPublication.targetPackage, "io.github.boxuechen.fixture");

  assert.equal(offers.claimToolCall(before("missing"), { ...context("missing"), sessionId: undefined }), false);
  assert.equal(offers.claimToolCall(before("mismatch"), context("mismatch", { runId: "other" })), false);
  assert.equal(offers.claimToolCall(before("foreign"), context("foreign")), true);
  assert.equal(offers.assignmentFromToolResult(result("foreign", { sessionKey: "agent:main:other" })), null);
  assert.equal(offers.assignmentFromToolResult(result("foreign")), null, "a rejected result cannot be replayed");

  assert.equal(offers.claimToolCall(before("ownerless"), context("ownerless")), true);
  assert.equal(offers.assignmentFromToolResult(result("ownerless", { sessionKey: undefined })), null);
  assert.equal(offers.claimToolCall(before("cleared"), context("cleared")), true);
  offers.clearRun("run-claim");
  assert.equal(offers.assignmentFromToolResult(result("cleared")), null);

  assert.equal(offers.claimToolCall(before("expired"), context("expired")), true);
  clock += 101;
  assert.equal(offers.assignmentFromToolResult(result("expired")), null);
  assert.equal(offers.claimToolCall(before("valid"), context("valid")), true);
  assert.equal(offers.assignmentFromToolResult(result("valid"))?.sessionId, "session-claim");
  offers.clearRun("run-claim");
}

{
  let clock = 1_000;
  const installs = new AndroidInstalledAppEventStore({ now: () => clock, ttlMs: 100, maxClaims: 2 });
  const before = (toolCallId) => ({
    toolName: ANDROID_APP_TOOL,
    params: { operation: "install_apk" },
    runId: "run-install",
    toolCallId,
  });
  const context = (toolCallId) => ({
    sessionKey: "agent:main:install",
    sessionId: "session-install",
    runId: "run-install",
    toolCallId,
  });
  const result = (toolCallId, status = "succeeded", installed = true) => ({
    stream: "tool",
    runId: "run-install",
    sessionKey: "agent:main:install",
    data: {
      phase: "result",
      name: ANDROID_APP_TOOL,
      toolCallId,
      isError: false,
      result: {
        details: {
          protocolVersion: ANDROID_APP_TOOL_PROTOCOL_VERSION,
          operation: "install_apk",
          status,
          installed: {
            installed,
            versionCode: installed ? "7" : null,
            sha256: installed ? APK_SHA256 : null,
          },
          artifact: {
            packageName: "io.github.boxuechen.fixture",
            versionCode: "7",
            sha256: APK_SHA256,
          },
        },
      },
    },
  });

  assert.equal(installs.claimToolCall(before("installed"), context("installed")), true);
  const event = installs.eventFromToolResult(result("installed"));
  assert.equal(event.stream, ANDROID_APP_INSTALLED_EVENT_STREAM);
  assert.equal(event.data.phase, "installed");
  assert.equal(event.data.packageName, "io.github.boxuechen.fixture");
  assert.equal(event.data.versionCode, "7");
  assert.equal(event.data.sha256, APK_SHA256);

  installs.claimToolCall(before("verified"), context("verified"));
  assert.equal(installs.eventFromToolResult(result("verified", "verified"))?.data.phase, "installed");

  installs.claimToolCall(before("mismatched"), context("mismatched"));
  const mismatched = result("mismatched");
  mismatched.data.result.details.installed.sha256 = "b".repeat(64);
  assert.equal(installs.eventFromToolResult(mismatched), null);

  installs.claimToolCall(before("unknown"), context("unknown"));
  assert.equal(installs.eventFromToolResult(result("unknown", "unknown", false)), null);
  installs.claimToolCall(before("failed"), context("failed"));
  assert.equal(installs.eventFromToolResult(result("failed", "failed", false)), null);
  installs.claimToolCall(before("expired"), context("expired"));
  clock += 101;
  assert.equal(installs.eventFromToolResult(result("expired")), null);
}

{
  let clock = 10;
  const store = new AndroidVScreenReadinessStore({
    now: () => clock,
    randomUUID: () => WORKLOAD_ID,
    waitTimeoutMs: 200,
    sleep: async (milliseconds) => {
      clock += milliseconds;
    },
  });
  const binding = { generation: "generation", target: { id: "phone" } };
  store.issue({
    ownerKey: "owner",
    artifactId: ARTIFACT_ID,
    packageName: "io.github.boxuechen.fixture",
    component: "io.github.boxuechen.fixture/.MainActivity",
    artifact: { artifactId: ARTIFACT_ID },
    binding,
    requestedAtMs: clock,
  });
  await assert.rejects(
    store.awaitFrame({ ownerKey: "other", artifactId: ARTIFACT_ID, workloadId: WORKLOAD_ID }),
    /another Chat or artifact/,
  );
  const unavailable = await store.awaitFrame({
    ownerKey: "owner",
    artifactId: ARTIFACT_ID,
    workloadId: WORKLOAD_ID,
  });
  assert.equal(unavailable.status, "vscreen_unavailable");
  assert.equal(unavailable.reason, "first_frame_timeout");
}

{
  const ownerKey = "a".repeat(64);
  const probeId = "b".repeat(32);
  assert.deepEqual(
    parseVScreenEnsureParams({
      protocolVersion: VSCREEN_PROTOCOL_VERSION,
      ownerKey,
    }),
    { ownerKey },
  );
  assert.deepEqual(
    parseVScreenWorkloadParams({
      protocolVersion: VSCREEN_PROTOCOL_VERSION,
      workloadRequestId: "request-fixture",
      ownerKey,
      sourceHandle: ARTIFACT_ID,
      targetRef: WORKLOAD_ID,
      targetPackage: "io.github.boxuechen.fixture",
    }),
    {
      workloadRequestId: "request-fixture",
      targetPackage: "io.github.boxuechen.fixture",
      ownerKey,
      sourceHandle: ARTIFACT_ID,
      targetRef: WORKLOAD_ID,
    },
  );
  assert.deepEqual(
    parseVScreenPresentedParams({
      protocolVersion: VSCREEN_PROTOCOL_VERSION,
      sourceHandle: ARTIFACT_ID,
      workloadRequestId: "request-fixture",
      ownerKey,
    }),
    { sourceHandle: ARTIFACT_ID, workloadRequestId: "request-fixture", ownerKey },
  );
  assert.deepEqual(
    parseVScreenCloseParams({
      protocolVersion: VSCREEN_PROTOCOL_VERSION,
      sourceHandle: ARTIFACT_ID,
      reason: "native_stop",
    }),
    { sourceHandle: ARTIFACT_ID, reason: "native_stop" },
  );
  assert.deepEqual(
    parseVScreenProbeParams({ protocolVersion: VSCREEN_PROTOCOL_VERSION, ownerKey, probeId }),
    { ownerKey, probeId },
  );

  const producerCalls = [];
  const sourceClosed = deferred();
  const source = {
    targetRef: WORKLOAD_ID,
    sourceHandle: ARTIFACT_ID,
    codec: "h264",
    display: { id: 42, width: 720, height: 1560, dpi: 320 },
    capabilities: ["video/h264", "pointer-v1"],
    video: new PassThrough(),
    closed: sourceClosed.promise,
  };
  const relay = {
    descriptor: { protocolVersion: 1, host: "127.0.0.1", port: 23456, token: "c".repeat(64) },
    closed: deferred().promise,
    async close(reason) { producerCalls.push(["relay-close", reason]); },
  };
  const adapter = createAndroidVScreenRpcAdapter({
    randomUUID: () => ARTIFACT_ID,
    async createRelay(video) { assert.equal(video, source.video); return relay; },
    producer: {
      async ensureTarget(params) { producerCalls.push(["ensure", params]); return source; },
      async placeWorkload(params) { producerCalls.push(["workload", params]); return source; },
      async verifyPresented(params) { producerCalls.push(["presented", params]); return { status: "ready" }; },
      async closeSource(received, reason) { producerCalls.push(["close", received, reason]); },
      async prepareProbe() { assert.fail("probe is not used in this fixture"); },
    },
  });
  const descriptor = await adapter.ensure({ ownerKey });
  assert.equal(descriptor.sourceHandle, ARTIFACT_ID);
  assert.deepEqual(descriptor.transport, relay.descriptor);
  await adapter.workload({
    workloadRequestId: "request-fixture",
    targetPackage: "io.github.boxuechen.fixture",
    ownerKey,
    sourceHandle: ARTIFACT_ID,
    targetRef: WORKLOAD_ID,
  });
  await assert.rejects(
    adapter.presented({
      sourceHandle: ARTIFACT_ID,
      workloadRequestId: "request-changed",
      ownerKey,
    }),
    /identity changed/,
  );
  assert.deepEqual(
    await adapter.presented({ sourceHandle: ARTIFACT_ID, workloadRequestId: "request-fixture", ownerKey }),
    { status: "ready" },
  );
  await adapter.close({ sourceHandle: ARTIFACT_ID, reason: "native_stop" });
  await adapter.close({ sourceHandle: ARTIFACT_ID, reason: "native_stop" });
  assert.equal(producerCalls.filter(([kind]) => kind === "close").length, 1);
  sourceClosed.resolve();
}

{
  const ownerKey = "a".repeat(64);
  const relayClosed = deferred();
  const sourceClosed = deferred();
  const producerCalls = [];
  const source = {
    targetRef: WORKLOAD_ID,
    sourceHandle: ARTIFACT_ID,
    codec: "h264",
    display: { id: 42, width: 720, height: 1560, dpi: 320 },
    capabilities: ["video/h264", "pointer-v1"],
    video: new PassThrough(),
    closed: sourceClosed.promise,
  };
  const adapter = createAndroidVScreenRpcAdapter({
    randomUUID: () => ARTIFACT_ID,
    async createRelay() {
      return {
        descriptor: { protocolVersion: 1, host: "127.0.0.1", port: 23456, token: "c".repeat(64) },
        closed: relayClosed.promise,
        async close() {},
      };
    },
    producer: {
      async ensureTarget() { return source; },
      async placeWorkload() { return source; },
      async verifyPresented() { return { status: "ready" }; },
      async closeSource(received, reason) { producerCalls.push([received, reason]); },
      async prepareProbe() { assert.fail("probe is not used in this fixture"); },
    },
  });
  await adapter.ensure({ ownerKey });
  relayClosed.resolve();
  await new Promise((resolve) => setImmediate(resolve));
  assert.deepEqual(producerCalls, [[source, "relay_closed"]]);
  await adapter.close({ sourceHandle: ARTIFACT_ID, reason: "native_stop" });
  assert.equal(producerCalls.length, 1);
  sourceClosed.resolve();
}

{
  const registeredMethods = new Map();
  let registeredService;
  const registeredTools = new Map();
  let registeredBeforeToolCall;
  let registeredBuildBeforeToolCall;
  let registeredAgentEventSubscription;
  let registeredHookOptions;
  let registeredBuildHookOptions;
  const calls = [];
  const foundationRequests = [];
  const vscreenRpcCalls = [];
  const warnings = [];
  const installedEvents = [];
  const registeredHookNames = [];
  const assignmentStore = new AndroidAppVScreenAssignmentStore();
  const vscreenProducer = {
    id: ANDROID_VSCREEN_PRODUCER_ID,
    async ensureTarget() {},
    async placeWorkload() {},
    async verifyPresented() {},
    async closeSource() {},
    async prepareProbe() {},
  };
  const vscreenRpc = {
    async ensure(params) { vscreenRpcCalls.push(["ensure", params]); return { status: "ready" }; },
    async workload(params) { vscreenRpcCalls.push(["workload", params]); return { status: "ready" }; },
    async presented(params) { vscreenRpcCalls.push(["presented", params]); return { status: "ready" }; },
    async close(params) { vscreenRpcCalls.push(["close", params]); return { status: "closed" }; },
    async probe(params) { vscreenRpcCalls.push(["probe", params]); return { status: "probing" }; },
    async closeAll() { vscreenRpcCalls.push(["close-all"]); },
  };
  const fakeService = {
    onBindingInvalidated() {},
    async start() {
      calls.push("start");
    },
    async stop() {
      calls.push("stop");
    },
    async status() {
      calls.push("status");
      return { status: "ready" };
    },
    async pair(params) {
      calls.push(["pair", params]);
      return { status: "ready" };
    },
    async connect(params) {
      calls.push(["connect", params]);
      return { status: "ready" };
    },
    async verifyReconnect(params) {
      calls.push(["verify-reconnect", params]);
      return { status: "ready", verificationId: params.verificationId };
    },
    async forget() {
      calls.push("forget");
      return { status: "revoked" };
    },
  };
  registerAndroidDeveloperBridgePlugin(
    {
      runtime: {
        state: { resolveStateDir: () => `/tmp/developer-bridge-registration-${process.pid}` },
        gateway: {
          async request(method, params, options) {
            foundationRequests.push({ method, params, options });
            return { status: "published" };
          },
        },
      },
      agent: {
        events: {
          registerAgentEventSubscription(subscription) {
            registeredAgentEventSubscription = subscription;
          },
          emitAgentEvent(event) {
            installedEvents.push(event);
            return { emitted: true };
          },
        },
      },
      logger: {
        warn(message) {
          warnings.push(message);
        },
      },
      registerService(value) {
        registeredService = value;
      },
      registerGatewayMethod(method, handler, options) {
        registeredMethods.set(method, { handler, options });
      },
      registerTool(factory, options) {
        registeredTools.set(options.name, factory);
      },
      on(name, handler, options) {
        registeredHookNames.push(name);
        if (name === "agent_end") return;
        if (name === "before_tool_call") {
          if (options.matcher[0] === ANDROID_PROJECT_BUILD_TOOL) {
            registeredBuildBeforeToolCall = handler;
            registeredBuildHookOptions = options;
          } else {
            registeredBeforeToolCall = handler;
            registeredHookOptions = options;
          }
          return;
        }
        assert.fail(`Unexpected hook ${name}`);
      },
    },
    {
      bridgeRuntimeEnabled: true,
      createBridge: () => fakeService,
      createDeviceProducer: () => ({ async close() {} }),
      vscreenProducer,
      assignmentStore,
      vscreenRpc,
      approvalBroker: {
        beforeToolCall: async (event) =>
          event.params.operation === "install_apk" && !event.toolCallId
            ? { block: true, blockReason: "fixture" }
            : undefined,
        endRun() {},
        close() {},
        consumeAuthorization() {
          throw new Error("fixture authorization is unavailable");
        },
      },
    },
  );
  assert.deepEqual(registeredHookNames, ["before_tool_call", "before_tool_call", "agent_end"]);
  assert.equal(
    registeredMethods.size,
    Object.keys(ANDROID_DEVICE_METHODS).length + Object.keys(ANDROID_VSCREEN_PRODUCER_METHODS).length,
  );
  assert.deepEqual([...registeredTools.keys()], [ANDROID_APP_TOOL, ANDROID_PROJECT_BUILD_TOOL]);
  assert.deepEqual(registeredHookOptions, { matcher: [ANDROID_APP_TOOL] });
  assert.deepEqual(registeredBuildHookOptions, { matcher: [ANDROID_PROJECT_BUILD_TOOL] });
  assert.equal(
    registeredBuildBeforeToolCall(
      { toolName: ANDROID_PROJECT_BUILD_TOOL, toolCallId: "build-1", params: {} },
      {},
    ).block,
    true,
  );
  const descriptorTool = registeredTools.get(ANDROID_APP_TOOL)({ sessionKey: "main", workspaceDir: "/workspace" });
  assert.equal(descriptorTool.name, ANDROID_APP_TOOL);
  await assert.rejects(
    descriptorTool.execute("before-start", { operation: "inspect_apk", apkPath: "fixture.apk" }),
    /App Delivery is not running/,
  );
  assert.deepEqual(
    await registeredBeforeToolCall(
      { toolName: ANDROID_APP_TOOL, params: { operation: "install_apk" } },
      {},
    ),
    { block: true, blockReason: "fixture" },
  );
  assert.equal(registeredAgentEventSubscription.id, "android-app-delivery");
  assert.deepEqual(registeredAgentEventSubscription.streams, ["tool", "lifecycle"]);
  await registeredBeforeToolCall(
    {
      toolName: ANDROID_APP_TOOL,
      params: { operation: "install_apk" },
      runId: "run-1",
      toolCallId: "install-1",
    },
    hostContext("install-1"),
  );
  await registeredAgentEventSubscription.handle({
    stream: "tool",
    runId: "run-1",
    sessionKey: "agent:main:main",
    data: {
      phase: "result",
      name: ANDROID_APP_TOOL,
      toolCallId: "install-1",
      isError: false,
      result: {
        details: {
          protocolVersion: ANDROID_APP_TOOL_PROTOCOL_VERSION,
          operation: "install_apk",
          status: "succeeded",
          installed: { installed: true, versionCode: "7", sha256: APK_SHA256 },
          artifact: {
            packageName: "io.github.boxuechen.fixture",
            versionCode: "7",
            sha256: APK_SHA256,
          },
        },
      },
    },
  });
  assert.equal(installedEvents.length, 1);
  assert.equal(installedEvents[0].stream, ANDROID_APP_INSTALLED_EVENT_STREAM);
  assert.equal(installedEvents[0].data.executionSessionId, "session-1");
  await registeredBeforeToolCall(
    {
      toolName: ANDROID_APP_TOOL,
      params: { operation: "place_vscreen_workload" },
      runId: "run-1",
      toolCallId: "launch-1",
    },
    hostContext("launch-1"),
  );
  await registeredAgentEventSubscription.handle({
    stream: "tool",
    runId: "run-1",
    sessionKey: "agent:main:main",
    data: {
      phase: "result",
      name: ANDROID_APP_TOOL,
      toolCallId: "launch-1",
      isError: false,
      result: {
        details: {
          protocolVersion: ANDROID_APP_TOOL_PROTOCOL_VERSION,
          operation: "place_vscreen_workload",
          status: "vscreen_workload_requested",
          workloadId: WORKLOAD_ID,
          packageName: "io.github.boxuechen.fixture",
          component: "io.github.boxuechen.fixture/.MainActivity",
          artifact: { packageName: "io.github.boxuechen.fixture" },
        },
      },
    },
  });
  assert.equal(foundationRequests.length, 1);
  assert.equal(foundationRequests[0].method, VSCREEN_INTERNAL_METHODS.assignApp);
  assert.equal(foundationRequests[0].params.protocolVersion, VSCREEN_PROTOCOL_VERSION);
  assert.equal(foundationRequests[0].params.producerId, ANDROID_VSCREEN_PRODUCER_ID);
  assert.equal(foundationRequests[0].params.workloadRequestId, WORKLOAD_ID);
  assert.equal(foundationRequests[0].params.targetPackage, "io.github.boxuechen.fixture");
  assert.equal(foundationRequests[0].params.sessionId, "session-1");
  assert.deepEqual(foundationRequests[0].options, { timeoutMs: 5_000 });
  assert.deepEqual(warnings, []);
  await registeredBeforeToolCall(
    {
      toolName: ANDROID_APP_TOOL,
      params: { operation: "place_vscreen_workload" },
      runId: "run-1",
      toolCallId: "launch-2",
    },
    hostContext("launch-2"),
  );
  await registeredAgentEventSubscription.handle({
    stream: "tool",
    runId: "run-1",
    sessionKey: "agent:main:main",
    data: {
      phase: "result",
      name: ANDROID_APP_TOOL,
      toolCallId: "launch-2",
      isError: false,
      result: { details: { status: "vscreen_workload_requested" } },
    },
  });
  assert.equal(foundationRequests.length, 1);
  assert.deepEqual(registeredMethods.get(ANDROID_DEVICE_METHODS.status).options, {
    scope: "operator.read",
    profileAccess: "required",
  });
  for (const method of [
    ANDROID_DEVICE_METHODS.pair,
    ANDROID_DEVICE_METHODS.connect,
    ANDROID_DEVICE_METHODS.verifyReconnect,
    ANDROID_DEVICE_METHODS.forget,
  ]) {
    assert.deepEqual(registeredMethods.get(method).options, {
      scope: "operator.write",
      profileAccess: "required",
    });
  }
  async function invoke(method, params, pluginRuntimeOwnerId = null) {
    let response;
    await registeredMethods.get(method).handler({
      params,
      client: pluginRuntimeOwnerId ? {
        internal: { syntheticClient: true, pluginRuntimeOwnerId },
      } : {},
      respond(...args) {
        response = args;
      },
    });
    return response;
  }

  const beforeStart = await invoke(ANDROID_DEVICE_METHODS.status, { protocolVersion: PROTOCOL_VERSION });
  assert.equal(beforeStart[0], false);
  assert.equal(beforeStart[2].details.code, AndroidDeviceErrorCode.unavailable);
  await registeredService.start({ stateDir: "/fixture/plugin-state" });
  assert.equal(
    (await invoke(ANDROID_VSCREEN_PRODUCER_METHODS.ensure, {
      protocolVersion: VSCREEN_PROTOCOL_VERSION,
      ownerKey: "a".repeat(64),
    }))[0],
    false,
  );
  assert.deepEqual(
    await invoke(
      ANDROID_VSCREEN_PRODUCER_METHODS.ensure,
      {
        protocolVersion: VSCREEN_PROTOCOL_VERSION,
        ownerKey: "a".repeat(64),
      },
      VSCREEN_FOUNDATION_PLUGIN_ID,
    ),
    [true, { status: "ready" }],
  );
  assert.equal(
    registeredTools.get(ANDROID_APP_TOOL)({ sessionKey: "main", sessionId: "session", workspaceDir: "/workspace" }).name,
    ANDROID_APP_TOOL,
  );
  assert.deepEqual(await invoke(ANDROID_DEVICE_METHODS.status, { protocolVersion: PROTOCOL_VERSION }), [
    true,
    { status: "ready" },
  ]);
  assert.equal(
    (await invoke(ANDROID_DEVICE_METHODS.pair, { protocolVersion: 2 }))[2].code,
    "INVALID_REQUEST",
  );
  const pairResponse = await invoke(ANDROID_DEVICE_METHODS.pair, {
    protocolVersion: PROTOCOL_VERSION,
    endpoint: PAIRING_ENDPOINT,
    pairingCode: PAIRING_CODE,
    challengeId: CHALLENGE_ID,
  });
  assert.equal(pairResponse[0], true);
  assert.equal(calls.some((entry) => Array.isArray(entry) && entry[0] === "pair"), true);
  assert.deepEqual(
    await invoke(ANDROID_DEVICE_METHODS.verifyReconnect, {
      protocolVersion: PROTOCOL_VERSION,
      verificationId: RECONNECT_VERIFICATION_ID,
    }),
    [true, { status: "ready", verificationId: RECONNECT_VERIFICATION_ID }],
  );
  await registeredService.stop();
  assert.equal(calls.at(-1), "stop");
  assert.equal(vscreenRpcCalls.some(([kind]) => kind === "close-all"), true);
}

{
  const root = await temporaryDirectory("developer-bridge-runtime-sharing");
  const stateRoot = path.join(root, "state");
  const workspaceRoot = path.join(root, "workspace");
  await fs.mkdir(workspaceRoot, { recursive: true });
  await fs.writeFile(path.join(workspaceRoot, "fixture.apk"), APK_CONTENT);
  const binding = {
    generation: "shared-binding",
    target: { id: "shared-target", product: "shiba", model: "Pixel 8", androidApi: "37" },
  };
  const stoppedServices = [];
  let nextServiceId = 1;
  const createFakeService = () => {
    const id = nextServiceId++;
    return {
      id,
      onBindingInvalidated() {},
      async start() {},
      async stop() { stoppedServices.push(id); },
      async deviceBinding() {
        return structuredClone(binding);
      },
    };
  };
  let activeLifecycle;
  let replacementLifecycle;
  let discoveryFactory;
  const sharedRuntimeHost = {
    state: { resolveStateDir: () => stateRoot },
    agent: { session: standardSession },
  };
  const apiFor = (capture, pluginConfig = {}) => ({
    pluginConfig,
    agent: {
      events: {
        registerAgentEventSubscription() {},
        emitAgentEvent(event) {
          return { emitted: true, stream: event.stream };
        },
      },
    },
    logger: { warn() {} },
    runtime: new Proxy(sharedRuntimeHost, {}),
    registerService(service) {
      capture.service = service;
    },
    registerGatewayMethod() {},
    registerHttpRoute() {},
    registerTool(factory, options) {
      capture.tools ??= new Map();
      capture.tools.set(options.name, factory);
    },
    on() {},
  });
  try {
    let desktopBridgeCreations = 0;
    const desktop = {};
    registerAndroidDeveloperBridgePlugin(apiFor(desktop), {
      createBridge: () => {
        desktopBridgeCreations += 1;
        throw new Error("desktop Gateway must not create the real Device Bridge");
      },
      createDeviceProducer: () => ({ async close() {} }),
      runTool: fakeApkTool,
    });
    await desktop.service.start({ stateDir: stateRoot });
    await desktop.service.stop();
    assert.equal(desktopBridgeCreations, 0);

    const active = {};
    registerAndroidDeveloperBridgePlugin(apiFor(active, { runtime: "android-avf" }), {
      createBridge: createFakeService,
      createDeviceProducer: () => ({ async close() {} }),
      runTool: fakeApkTool,
    });
    activeLifecycle = active.service;
    await activeLifecycle.start({ stateDir: stateRoot });

    const replacement = {};
    registerAndroidDeveloperBridgePlugin(apiFor(replacement, { runtime: "android-avf" }), {
      createBridge: createFakeService,
      createDeviceProducer: () => ({ async close() {} }),
      runTool: fakeApkTool,
    });
    replacementLifecycle = replacement.service;
    await replacementLifecycle.start({ stateDir: stateRoot });
    await activeLifecycle.stop();
    assert.deepEqual(stoppedServices, [1]);

    const discovery = {};
    registerAndroidDeveloperBridgePlugin(apiFor(discovery, { runtime: "android-avf" }), {
      createBridge: () => {
        throw new Error("discovery must not own the active service");
      },
      createDeviceProducer: () => ({ async close() {} }),
      runTool: fakeApkTool,
    });
    discoveryFactory = discovery.tools.get(ANDROID_APP_TOOL);
    const tool = discoveryFactory({
      sessionKey: "agent:main:main",
      sessionId: "shared-session",
      workspaceDir: workspaceRoot,
    });
    const inspected = await tool.execute("shared-inspect", {
      operation: "inspect_apk",
      apkPath: "fixture.apk",
    });
    assert.equal(inspected.details.status, "inspected");
    assert.equal(inspected.details.artifact.target.id, binding.target.id);
  } finally {
    await replacementLifecycle?.stop();
    await activeLifecycle?.stop();
    assert.deepEqual(stoppedServices, [1, 2]);
    await fs.rm(root, { recursive: true, force: true });
  }
}

{
  assert.equal(SCRCPY_SERVER_SIZE_BYTES, 733706);
  assert.match(SCRCPY_SERVER_SHA256, /^[0-9a-f]{64}$/);
}

{
  const fixture = `Display #25\n  topResumedActivity=ActivityRecord{106517547 u0 io.github.boxuechen.fixture/.MainActivity t470}`;
  assert.equal(parseVScreenTopPackage(fixture), "io.github.boxuechen.fixture");
  assert.equal(parseVScreenTopPackage("Display #25\n  mResumedActivity: ActivityRecord{1 u10 com.example.other/.Home t2}"), "com.example.other");
  assert.equal(parseVScreenTopPackage("Display #25\n  no resumed task"), null);
}

{
  const root = await temporaryDirectory("preview-helper-qualified-asset");
  try {
    const source = path.join(root, "toolchains/scrcpy/current/scrcpy-server");
    const content = Buffer.from("pinned preview helper fixture");
    const release = {
      sizeBytes: content.length,
      sha256: createHash("sha256").update(content).digest("hex"),
    };
    await fs.mkdir(path.dirname(source), { recursive: true });
    await fs.writeFile(source, content);
    assert.equal(
      await requireQualifiedScrcpyServer({ source, release }),
      source,
    );
    await fs.writeFile(source, "corrupt");
    await assert.rejects(
      requireQualifiedScrcpyServer({ source, release }),
      /Supervisor-qualified VScreen runtime asset is missing or invalid/,
    );
    await assert.rejects(
      requireQualifiedScrcpyServer({ source: path.join(root, "missing"), release }),
      /Supervisor-qualified VScreen runtime asset is missing or invalid/,
    );
  } finally {
    await fs.rm(root, { recursive: true, force: true });
  }
}

{
  const helperCloseReasons = [];
  const helpers = [];
  const helper = (displayId) => {
    const value = {
      codec: "h264",
      display: { id: displayId, width: 720, height: 1560, dpi: 320, rotation: 0 },
      video: new PassThrough(),
      control: new PassThrough(),
      closed: new Promise(() => {}),
      async close() {
        helperCloseReasons.push(displayId);
      },
    };
    helpers.push(value);
    return value;
  };
  const readinessCalls = [];
  const offer = { requestId: WORKLOAD_ID, targetPackage: "io.github.boxuechen.fixture" };
  const deviceProducer = {
    async ensureTarget(params) {
      assert.equal(params.expectedGeneration, "producer-binding");
      return helper(51);
    },
    async placeWorkload(params) {
      assert.equal(params.expectedGeneration, "producer-binding");
      assert.equal(params.displayId, 51);
      assert.equal(params.packageName, offer.targetPackage);
      return { observedSamples: 1 };
    },
    async startProbe(params) {
      assert.equal(params.expectedGeneration, "producer-binding");
      return helper(52);
    },
  };
  const producer = createAndroidVScreenProducer({
    resolveBridge: () => ({
      async deviceBinding() {
        return { generation: "producer-binding", target: { id: "phone" } };
      },
    }),
    resolveDeviceProducer: () => deviceProducer,
    readinessStore: {
      attachVScreenIfPresent(value) {
        readinessCalls.push(["attach", value]);
      },
      frameReadyIfPresent(value) {
        readinessCalls.push(["frame", value]);
        return { status: "recorded" };
      },
      close(value) {
        readinessCalls.push(["close", value]);
      },
    },
    randomUUID: (() => {
      const ids = [
        "32345678-1234-4123-8123-123456789abc",
        "42345678-1234-4123-8123-123456789abc",
        "52345678-1234-4123-8123-123456789abc",
        "62345678-1234-4123-8123-123456789abc",
      ];
      return () => ids.shift();
    })(),
  });
  const source = await producer.ensureTarget({ ownerKey: "owner" });
  assert.equal(await producer.ensureTarget({ ownerKey: "owner" }), source, "a healthy display is reused");
  await producer.placeWorkload({ requestId: WORKLOAD_ID, targetPackage: offer.targetPackage, ownerKey: "owner", source });
  assert.equal(source.targetRef, "32345678-1234-4123-8123-123456789abc");
  assert.equal(source.sourceHandle, "42345678-1234-4123-8123-123456789abc");
  assert.equal(readinessCalls[0][0], "attach");
  assert.equal(readinessCalls[0][1].sourceHandle, source.sourceHandle);
  assert.equal(readinessCalls[0][1].initialForegroundSamples, 1);
  await producer.verifyPresented({ requestId: WORKLOAD_ID, targetRef: source.targetRef, source });
  assert.deepEqual(readinessCalls[1], ["frame", { workloadId: WORKLOAD_ID, sourceHandle: source.sourceHandle }]);
  await assert.rejects(
    producer.verifyPresented({ requestId: WORKLOAD_ID, targetRef: "other", source }),
    /identity changed/,
  );
  await producer.closeSource(source, "detached");
  await producer.closeSource(source, "late");
  assert.equal(helperCloseReasons.filter((id) => id === 51).length, 1);
  assert.equal(readinessCalls.filter(([kind]) => kind === "close").length, 1);

  const probe = await producer.prepareProbe({ probeId: RECONNECT_VERIFICATION_ID });
  assert.equal(probe.targetRef, "phone");
  assert.deepEqual(probe.capabilities, ["video/h264"]);
  await producer.closeSource(probe, "probe_finished");
  assert.equal(helperCloseReasons.filter((id) => id === 52).length, 1);
}

console.log("Android Developer Bridge Plugin contracts passed.");
