export const ANDROID_APP_TOOL = "android_app";
export const ANDROID_APP_TOOL_PROTOCOL_VERSION = 2;
export const ANDROID_APP_AUTHORIZATION_NONCE = "androidAppAuthorizationNonce";

const OPERATIONS = Object.freeze([
  "inspect_apk",
  "install_apk",
  "place_vscreen_workload",
  "await_vscreen_ready",
  "capture_screen",
  "read_logs",
]);
const OPERATION_KEYS = Object.freeze({
  inspect_apk: ["operation", "apkPath"],
  install_apk: ["operation", "artifactId", ANDROID_APP_AUTHORIZATION_NONCE],
  place_vscreen_workload: ["operation", "artifactId"],
  await_vscreen_ready: ["operation", "artifactId", "workloadId"],
  capture_screen: ["operation", "artifactId"],
  read_logs: ["operation", "artifactId", "maxLines"],
});
const ARTIFACT_ID_PATTERN = /^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;

export const ANDROID_APP_TOOL_PARAMETERS = Object.freeze({
  type: "object",
  additionalProperties: false,
  required: ["operation"],
  properties: {
    operation: {
      type: "string",
      enum: OPERATIONS,
      description: "Inspect an APK, install it, request or await VScreen, capture it, or read scoped logs.",
    },
    apkPath: {
      type: "string",
      minLength: 1,
      maxLength: 4096,
      description: "Workspace-relative APK path. Used only by inspect_apk.",
    },
    artifactId: {
      type: "string",
      description: "Short-lived artifactId returned by inspect_apk.",
    },
    workloadId: {
      type: "string",
      description: "Short-lived workloadId returned by place_vscreen_workload. Used only by await_vscreen_ready.",
    },
    maxLines: {
      type: "integer",
      minimum: 1,
      maximum: 200,
      description: "Maximum recent package-scoped log lines. Defaults to 200.",
    },
    [ANDROID_APP_AUTHORIZATION_NONCE]: {
      type: "string",
      description: "Internal. Injected by host authorization; never set this manually.",
    },
  },
});

function requireRecord(value) {
  if (value === null || typeof value !== "object" || Array.isArray(value)) {
    throw new Error("android_app parameters must be an object");
  }
  return value;
}

function requireExactKeys(record, operation) {
  const allowed = OPERATION_KEYS[operation];
  for (const key of Object.keys(record)) {
    if (!allowed.includes(key)) {
      throw new Error(`android_app does not accept ${key} for ${operation}`);
    }
  }
}

function requireArtifactId(record) {
  const value = record.artifactId;
  if (typeof value !== "string" || !ARTIFACT_ID_PATTERN.test(value)) {
    throw new Error(`android_app requires an artifactId for ${record.operation}`);
  }
  return value.toLowerCase();
}

export function parseAndroidAppToolParams(value) {
  const record = requireRecord(value);
  const operation = record.operation;
  if (typeof operation !== "string" || !OPERATIONS.includes(operation)) {
    throw new Error("android_app requires a supported operation");
  }
  requireExactKeys(record, operation);
  if (operation === "inspect_apk") {
    if (
      typeof record.apkPath !== "string" ||
      record.apkPath.trim().length === 0 ||
      record.apkPath.length > 4096 ||
      record.apkPath.includes("\0")
    ) {
      throw new Error("android_app requires a workspace-relative apkPath for inspect_apk");
    }
    return { operation, apkPath: record.apkPath.trim() };
  }
  const artifactId = requireArtifactId(record);
  if (operation === "await_vscreen_ready") {
    if (typeof record.workloadId !== "string" || !ARTIFACT_ID_PATTERN.test(record.workloadId)) {
      throw new Error("android_app requires a workloadId for await_vscreen_ready");
    }
    return { operation, artifactId, workloadId: record.workloadId.toLowerCase() };
  }
  if (operation === "install_apk") {
    const authorizationNonce = record[ANDROID_APP_AUTHORIZATION_NONCE];
    if (authorizationNonce !== undefined && typeof authorizationNonce !== "string") {
      throw new Error("android_app authorization nonce is invalid");
    }
    return { operation, artifactId, authorizationNonce };
  }
  if (operation === "read_logs") {
    const maxLines = record.maxLines ?? 200;
    if (!Number.isSafeInteger(maxLines) || maxLines < 1 || maxLines > 200) {
      throw new Error("android_app maxLines must be an integer in [1, 200]");
    }
    return { operation, artifactId, maxLines };
  }
  return { operation, artifactId };
}
