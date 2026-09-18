import { createHash, randomUUID } from "node:crypto";
import { spawn } from "node:child_process";
import { constants as fsConstants } from "node:fs";
import fs from "node:fs/promises";
import path from "node:path";

const MAX_APK_BYTES = 512 * 1024 * 1024;
const MAX_TOOL_OUTPUT_BYTES = 256 * 1024;
const RECEIPT_TTL_MS = 30 * 60 * 1000;
const ANDROID_PACKAGE_PATTERN = /^[A-Za-z][A-Za-z0-9_]*(?:\.[A-Za-z0-9_]+)+$/;
const SHA256_PATTERN = /^[0-9a-f]{64}$/;

function firstLine(value) {
  return value
    .split(/\r?\n/)
    .map((part) => part.trim())
    .find(Boolean)
    ?.slice(0, 240) ?? "Android SDK tool failed";
}

export function createSdkToolRunner(dependencies = {}) {
  const spawnProcess = dependencies.spawnProcess ?? spawn;
  return (command, args, options = {}) =>
    new Promise((resolve, reject) => {
      const child = spawnProcess(command, args, {
        env: process.env,
        stdio: ["ignore", "pipe", "pipe"],
      });
      let stdout = "";
      let stderr = "";
      let settled = false;
      const finish = (callback) => {
        if (settled) return;
        settled = true;
        clearTimeout(timer);
        callback();
      };
      const append = (current, chunk) => {
        const next = current + chunk.toString("utf8");
        if (Buffer.byteLength(next) > MAX_TOOL_OUTPUT_BYTES) {
          child.kill("SIGKILL");
          finish(() => reject(new Error(`${command} returned too much output`)));
        }
        return next;
      };
      const timer = setTimeout(() => {
        child.kill("SIGKILL");
        finish(() => reject(new Error(`${command} timed out`)));
      }, options.timeoutMs ?? 30000);
      child.stdout.on("data", (chunk) => {
        stdout = append(stdout, chunk);
      });
      child.stderr.on("data", (chunk) => {
        stderr = append(stderr, chunk);
      });
      child.on("error", (error) => finish(() => reject(new Error(`${command} could not start`, { cause: error }))));
      child.on("close", (code) =>
        finish(() => {
          if (code === 0) resolve({ stdout, stderr });
          else reject(new Error(firstLine(`${stderr}\n${stdout}`)));
        }),
      );
    });
}

function isInside(root, candidate) {
  const relative = path.relative(root, candidate);
  return relative === "" || (!relative.startsWith(`..${path.sep}`) && relative !== ".." && !path.isAbsolute(relative));
}

function stableFileProjection(info) {
  return {
    dev: info.dev,
    ino: info.ino,
    size: info.size,
    mtimeMs: info.mtimeMs,
    ctimeMs: info.ctimeMs,
  };
}

function sameStableFile(left, right) {
  return Object.keys(left).every((key) => left[key] === right[key]);
}

export function sameApkFingerprint(left, right) {
  return (
    left.workspaceRoot === right.workspaceRoot &&
    left.apkPath === right.apkPath &&
    left.realPath === right.realPath &&
    sameStableFile(left.file, right.file) &&
    left.sha256 === right.sha256 &&
    JSON.stringify(left.metadata) === JSON.stringify(right.metadata)
  );
}

async function hashFile(filePath) {
  const handle = await fs.open(filePath, fsConstants.O_RDONLY | fsConstants.O_NOFOLLOW);
  try {
    const hash = createHash("sha256");
    const buffer = Buffer.allocUnsafe(1024 * 1024);
    let position = 0;
    while (true) {
      const { bytesRead } = await handle.read(buffer, 0, buffer.length, position);
      if (bytesRead === 0) break;
      hash.update(buffer.subarray(0, bytesRead));
      position += bytesRead;
    }
    return hash.digest("hex");
  } finally {
    await handle.close();
  }
}

function quotedField(line, name) {
  return line.match(new RegExp(`(?:^|\\s)${name}='([^']*)'`))?.[1] ?? null;
}

function minimumSdk(lines) {
  // AAPT versions use either label for the same manifest attribute.
  const declarations = lines.filter((line) => /^(?:minSdkVersion|sdkVersion):/.test(line));
  if (declarations.length === 0) return "1";
  const values = declarations.map((line) => line.match(/^(?:minSdkVersion|sdkVersion):'([^']+)'$/)?.[1]);
  if (values.some((value) => !value) || new Set(values).size !== 1) {
    throw new Error("APK minimum SDK metadata is invalid or conflicting");
  }
  return values[0];
}

export function parseApkMetadata(aaptOutput, signerOutput) {
  const lines = aaptOutput.split(/\r?\n/);
  const packageLine = lines.find((line) => line.startsWith("package:"));
  const targetSdkLine = lines.find((line) => line.startsWith("targetSdkVersion:"));
  const packageName = packageLine ? quotedField(packageLine, "name") : null;
  const versionCode = packageLine ? quotedField(packageLine, "versionCode") : null;
  const versionName = packageLine ? quotedField(packageLine, "versionName") : null;
  const minSdk = minimumSdk(lines);
  const targetSdk = targetSdkLine?.match(/^targetSdkVersion:'([^']+)'/)?.[1] ?? minSdk;
  const signerSha256 = [...signerOutput.matchAll(/certificate SHA-256 digest:\s*([0-9a-f]{64})/gi)]
    .map((match) => match[1].toLowerCase())
    .filter((digest, index, values) => values.indexOf(digest) === index)
    .sort();
  if (
    !packageName ||
    !ANDROID_PACKAGE_PATTERN.test(packageName) ||
    !versionCode ||
    !/^\d+$/.test(versionCode) ||
    versionName === null ||
    !minSdk ||
    !targetSdk ||
    signerSha256.length === 0 ||
    !signerSha256.every((digest) => SHA256_PATTERN.test(digest))
  ) {
    throw new Error("APK metadata is incomplete or invalid");
  }
  return { packageName, versionCode, versionName, minSdk, targetSdk, signerSha256 };
}

export async function inspectApkFingerprint(params) {
  if (typeof params.workspaceRoot !== "string" || !path.isAbsolute(params.workspaceRoot)) {
    throw new Error("android_app requires an active local workspace root");
  }
  if (path.isAbsolute(params.apkPath)) {
    throw new Error("android_app apkPath must be relative to the active workspace");
  }
  const workspaceRoot = await fs.realpath(params.workspaceRoot);
  const requestedPath = path.resolve(workspaceRoot, params.apkPath);
  if (!isInside(workspaceRoot, requestedPath)) {
    throw new Error("android_app apkPath escapes the active workspace");
  }
  const requestedInfo = await fs.lstat(requestedPath);
  if (!requestedInfo.isFile() || requestedInfo.isSymbolicLink()) {
    throw new Error("android_app apkPath must name a regular APK file");
  }
  const realPath = await fs.realpath(requestedPath);
  if (!isInside(workspaceRoot, realPath) || path.extname(realPath).toLowerCase() !== ".apk") {
    throw new Error("android_app apkPath must name an APK inside the active workspace");
  }
  const before = stableFileProjection(await fs.stat(realPath));
  if (before.size <= 0 || before.size > MAX_APK_BYTES) {
    throw new Error("android_app APK must be between 1 byte and 512 MiB");
  }
  const runTool = params.runTool ?? createSdkToolRunner();
  const sha256 = await hashFile(realPath);
  const aapt = await runTool("aapt2", ["dump", "badging", realPath]);
  const signer = await runTool("apksigner", ["verify", "--print-certs", realPath]);
  const after = stableFileProjection(await fs.stat(realPath));
  if (!sameStableFile(before, after)) {
    throw new Error("APK changed while it was being inspected");
  }
  return {
    workspaceRoot,
    apkPath: path.relative(workspaceRoot, realPath),
    realPath,
    file: after,
    sha256,
    metadata: parseApkMetadata(aapt.stdout, `${signer.stdout}\n${signer.stderr}`),
  };
}

export function createArtifactReceiptStore(dependencies = {}) {
  const now = dependencies.now ?? Date.now;
  const createId = dependencies.randomUUID ?? randomUUID;
  const receipts = new Map();

  function get(ownerKey, artifactId) {
    const receipt = receipts.get(ownerKey) ?? null;
    if (!receipt) return null;
    if (now() >= receipt.expiresAtMs) {
      receipts.delete(ownerKey);
      return null;
    }
    return receipt.artifactId === artifactId ? receipt : null;
  }

  return {
    issue(owner, fingerprint, deviceBinding) {
      const issuedAtMs = now();
      const receipt = {
        artifactId: createId().toLowerCase(),
        ownerKey: owner.ownerKey,
        issuedAtMs,
        expiresAtMs: issuedAtMs + RECEIPT_TTL_MS,
        fingerprint,
        deviceBinding: structuredClone(deviceBinding),
      };
      receipts.set(owner.ownerKey, receipt);
      return receipt;
    },
    get,
    clear(ownerKey) {
      receipts.delete(ownerKey);
    },
    clearAll() {
      receipts.clear();
    },
  };
}

export function projectArtifactReceipt(receipt) {
  return {
    artifactId: receipt.artifactId,
    expiresAtMs: receipt.expiresAtMs,
    apkPath: receipt.fingerprint.apkPath,
    sha256: receipt.fingerprint.sha256,
    ...receipt.fingerprint.metadata,
    target: {
      id: receipt.deviceBinding.target.id,
      model: receipt.deviceBinding.target.model,
      androidApi: receipt.deviceBinding.target.androidApi,
    },
  };
}
