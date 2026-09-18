import { isIP } from "node:net";

export const ANDROID_DEVICE_PROTOCOL_VERSION = 1;
export const ANDROID_DEVICE_BRIDGE_SERVICE_ID = "claw-in-one-android-device-bridge";
export const ANDROID_DEVICE_METHODS = Object.freeze({
  status: "claw.androidDevice.status",
  pair: "claw.androidDevice.pair",
  connect: "claw.androidDevice.connect",
  verifyReconnect: "claw.androidDevice.verifyReconnect",
  forget: "claw.androidDevice.forget",
});

export const AndroidDeviceErrorCode = Object.freeze({
  invalidRequest: "ANDROID_DEVICE_INVALID_REQUEST",
  unavailable: "ANDROID_DEVICE_UNAVAILABLE",
  adbNotInstalled: "ANDROID_DEVICE_ADB_NOT_INSTALLED",
  adbCommandFailed: "ANDROID_DEVICE_ADB_COMMAND_FAILED",
  busy: "ANDROID_DEVICE_BUSY",
  alreadyPaired: "ANDROID_DEVICE_ALREADY_PAIRED",
  notPaired: "ANDROID_DEVICE_NOT_PAIRED",
  pairFailed: "ANDROID_DEVICE_PAIR_FAILED",
  challengeUnavailable: "ANDROID_DEVICE_CHALLENGE_UNAVAILABLE",
  samePhoneNotFound: "ANDROID_DEVICE_SAME_PHONE_NOT_FOUND",
  samePhoneAmbiguous: "ANDROID_DEVICE_SAME_PHONE_AMBIGUOUS",
  phoneOffline: "ANDROID_DEVICE_PHONE_OFFLINE",
  stateInvalid: "ANDROID_DEVICE_STATE_INVALID",
  vscreenUnavailable: "ANDROID_VSCREEN_UNAVAILABLE",
  vscreenUnauthorized: "ANDROID_VSCREEN_UNAUTHORIZED",
});

const HEX_ID_PATTERN = /^[0-9a-f]{32}$/;
const PAIRING_CODE_PATTERN = /^\d{6}$/;
const LOCAL_HOST_LABEL_PATTERN = /^[A-Za-z0-9_](?:[A-Za-z0-9_-]{0,61}[A-Za-z0-9_])?$/;

export class AndroidDeviceError extends Error {
  constructor(code, message, options = {}) {
    super(message);
    this.name = "AndroidDeviceError";
    this.code = code;
    this.retryable = options.retryable === true;
  }
}

function requireRecord(value) {
  if (value === null || typeof value !== "object" || Array.isArray(value)) {
    throw new AndroidDeviceError(AndroidDeviceErrorCode.invalidRequest, "Request parameters must be an object");
  }
  return value;
}

function requireExactKeys(record, allowedKeys) {
  for (const key of Object.keys(record)) {
    if (!allowedKeys.includes(key)) {
      throw new AndroidDeviceError(AndroidDeviceErrorCode.invalidRequest, `Unexpected request field: ${key}`);
    }
  }
}

function requireProtocolVersion(record) {
  if (record.protocolVersion !== ANDROID_DEVICE_PROTOCOL_VERSION) {
    throw new AndroidDeviceError(
      AndroidDeviceErrorCode.invalidRequest,
      `protocolVersion must be ${ANDROID_DEVICE_PROTOCOL_VERSION}`,
    );
  }
}

function isPrivateIpv4(host) {
  const octets = host.split(".").map(Number);
  return (
    octets[0] === 10 ||
    (octets[0] === 172 && octets[1] >= 16 && octets[1] <= 31) ||
    (octets[0] === 192 && octets[1] === 168) ||
    (octets[0] === 169 && octets[1] === 254)
  );
}

function isPrivateIpv6(host) {
  const normalized = host.toLowerCase();
  return (
    normalized.startsWith("fe8") ||
    normalized.startsWith("fe9") ||
    normalized.startsWith("fea") ||
    normalized.startsWith("feb") ||
    normalized.startsWith("fc") ||
    normalized.startsWith("fd")
  );
}

function isLocalHostname(host) {
  const labels = host.split(".");
  return (
    labels.length >= 2 &&
    labels.at(-1)?.toLowerCase() === "local" &&
    labels.every((label) => label.length <= 63 && LOCAL_HOST_LABEL_PATTERN.test(label))
  );
}

export function normalizeAdbEndpoint(value) {
  if (typeof value !== "string") {
    throw new AndroidDeviceError(AndroidDeviceErrorCode.invalidRequest, "endpoint is required");
  }
  const endpoint = value.trim();
  if (endpoint.length === 0 || endpoint.length > 300 || /\s/.test(endpoint)) {
    throw new AndroidDeviceError(AndroidDeviceErrorCode.invalidRequest, "endpoint must be a local host and port");
  }

  let host;
  let portText;
  if (endpoint.startsWith("[")) {
    const closing = endpoint.indexOf("]:");
    if (closing < 0) {
      throw new AndroidDeviceError(AndroidDeviceErrorCode.invalidRequest, "endpoint must include a port");
    }
    host = endpoint.slice(1, closing);
    portText = endpoint.slice(closing + 2);
  } else {
    const separator = endpoint.lastIndexOf(":");
    if (separator <= 0 || endpoint.indexOf(":") !== separator) {
      throw new AndroidDeviceError(AndroidDeviceErrorCode.invalidRequest, "endpoint must include a port");
    }
    host = endpoint.slice(0, separator);
    portText = endpoint.slice(separator + 1);
  }

  const port = Number(portText);
  if (!Number.isSafeInteger(port) || port < 1024 || port > 65535) {
    throw new AndroidDeviceError(AndroidDeviceErrorCode.invalidRequest, "endpoint port must be between 1024 and 65535");
  }

  const ipVersion = isIP(host);
  const localAddress =
    (ipVersion === 4 && isPrivateIpv4(host)) ||
    (ipVersion === 6 && isPrivateIpv6(host)) ||
    (ipVersion === 0 && isLocalHostname(host));
  if (!localAddress) {
    throw new AndroidDeviceError(
      AndroidDeviceErrorCode.invalidRequest,
      "endpoint must use a private, link-local, or .local address",
    );
  }
  return ipVersion === 6 ? `[${host.toLowerCase()}]:${port}` : `${host.toLowerCase()}:${port}`;
}

export function parseStatusParams(value) {
  const record = requireRecord(value);
  requireExactKeys(record, ["protocolVersion"]);
  requireProtocolVersion(record);
  return {};
}

export function parsePairParams(value) {
  const record = requireRecord(value);
  requireExactKeys(record, ["protocolVersion", "endpoint", "pairingCode", "challengeId"]);
  requireProtocolVersion(record);
  if (typeof record.pairingCode !== "string" || !PAIRING_CODE_PATTERN.test(record.pairingCode)) {
    throw new AndroidDeviceError(AndroidDeviceErrorCode.invalidRequest, "pairingCode must contain exactly six digits");
  }
  if (typeof record.challengeId !== "string" || !HEX_ID_PATTERN.test(record.challengeId)) {
    throw new AndroidDeviceError(AndroidDeviceErrorCode.invalidRequest, "challengeId must be 32 lowercase hexadecimal characters");
  }
  return {
    endpoint: normalizeAdbEndpoint(record.endpoint),
    pairingCode: record.pairingCode,
    challengeId: record.challengeId,
  };
}

export function parseConnectParams(value) {
  const record = requireRecord(value);
  requireExactKeys(record, ["protocolVersion", "endpoint"]);
  requireProtocolVersion(record);
  return {
    endpoint: record.endpoint === undefined ? null : normalizeAdbEndpoint(record.endpoint),
  };
}

export function parseVerifyReconnectParams(value) {
  const record = requireRecord(value);
  requireExactKeys(record, ["protocolVersion", "verificationId", "endpoint"]);
  requireProtocolVersion(record);
  if (typeof record.verificationId !== "string" || !HEX_ID_PATTERN.test(record.verificationId)) {
    throw new AndroidDeviceError(
      AndroidDeviceErrorCode.invalidRequest,
      "verificationId must be 32 lowercase hexadecimal characters",
    );
  }
  return {
    verificationId: record.verificationId,
    endpoint: record.endpoint === undefined ? null : normalizeAdbEndpoint(record.endpoint),
  };
}

export function parseForgetParams(value) {
  const record = requireRecord(value);
  requireExactKeys(record, ["protocolVersion"]);
  requireProtocolVersion(record);
  return {};
}

export function gatewayErrorFor(error) {
  const normalized =
    error instanceof AndroidDeviceError
      ? error
      : new AndroidDeviceError(AndroidDeviceErrorCode.unavailable, "Android development connection is unavailable", {
          retryable: true,
        });
  const invalid = normalized.code === AndroidDeviceErrorCode.invalidRequest;
  return {
    code: invalid ? "INVALID_REQUEST" : "UNAVAILABLE",
    message: normalized.message,
    retryable: normalized.retryable,
    details: { code: normalized.code },
  };
}
