import { spawn } from "node:child_process";
import { constants } from "node:fs";
import fs from "node:fs/promises";
import path from "node:path";
import {
  ANDROID_PROJECT_BUILD_PARAMETERS,
  ANDROID_PROJECT_BUILD_PROTOCOL_VERSION,
  ANDROID_PROJECT_BUILD_TOOL,
  parseAndroidProjectBuildParams,
} from "./tool-protocol.mjs";

const PROFILE_ID = "react-native-android-v1";
const PROFILE_GENERATION = 1;
const PROFILE_BUILDER =
  "/home/droid/.local/share/claw-in-one/development-profiles/react-native/active/build-project.mjs";
const PROFILE_ARTIFACT = "android/app/build/outputs/apk/debug/app-debug.apk";
const MAX_METADATA_BYTES = 64 * 1024;
const MAX_BUILD_OUTPUT_BYTES = 4 * 1024 * 1024;
const MAX_BUILD_MS = 20 * 60 * 1000;

function workspaceRootFor(context) {
  return context?.fsPolicy?.root?.trim() || context?.workspaceDir?.trim() || null;
}

function inside(root, file) {
  const relative = path.relative(root, file);
  return relative === "" ||
    (relative !== ".." && !relative.startsWith(`..${path.sep}`) && !path.isAbsolute(relative));
}

async function readProjectMetadata(workspaceRoot) {
  const rootMetadata = await fs.lstat(workspaceRoot).catch(() => null);
  if (!rootMetadata?.isDirectory() || rootMetadata.isSymbolicLink()) {
    throw new Error("Android Project build requires a real Project workspace directory");
  }
  const root = await fs.realpath(workspaceRoot);
  const metadataDirectory = path.join(root, ".claw-in-one");
  const directoryMetadata = await fs.lstat(metadataDirectory).catch(() => null);
  if (!directoryMetadata?.isDirectory() || directoryMetadata.isSymbolicLink()) {
    throw new Error("Android Project metadata directory is unavailable");
  }
  const metadataFile = path.join(metadataDirectory, "android-project.v1.json");
  const before = await fs.lstat(metadataFile).catch(() => null);
  if (!before?.isFile() || before.isSymbolicLink() || before.nlink !== 1 || before.size > MAX_METADATA_BYTES) {
    throw new Error("Android Project metadata is unavailable or unsafe");
  }
  const handle = await fs.open(metadataFile, constants.O_RDONLY | constants.O_NOFOLLOW);
  let content;
  try {
    const opened = await handle.stat();
    if (!opened.isFile() || opened.nlink !== 1 || opened.dev !== before.dev || opened.ino !== before.ino) {
      throw new Error("Android Project metadata changed while it was opened");
    }
    content = await handle.readFile({ encoding: "utf8" });
  } finally {
    await handle.close();
  }
  let metadata;
  try {
    metadata = JSON.parse(content);
  } catch {
    throw new Error("Android Project metadata is invalid");
  }
  return { root, metadata };
}

async function assertQualifiedReactNativeProject(root, metadata, builder) {
  const expectedBuildCommand = `${builder} --project-dir .`;
  const recordedProjectRoot =
    typeof metadata?.projectDirectory === "string"
      ? await fs.realpath(metadata.projectDirectory).catch(() => null)
      : null;
  if (
    metadata?.schemaVersion !== 1 ||
    metadata.profileId !== PROFILE_ID ||
    metadata.profileGeneration !== PROFILE_GENERATION ||
    recordedProjectRoot !== root ||
    typeof metadata.applicationId !== "string" ||
    !metadata.applicationId.trim() ||
    metadata.buildCommand !== expectedBuildCommand ||
    metadata.artifact !== PROFILE_ARTIFACT
  ) {
    throw new Error("Current Project is not bound to the qualified React Native Android profile");
  }
}

function killProcessGroup(child) {
  if (!child.pid) return;
  try {
    if (process.platform === "win32") child.kill("SIGTERM");
    else process.kill(-child.pid, "SIGTERM");
  } catch (error) {
    if (error?.code !== "ESRCH") child.kill("SIGTERM");
  }
}

function outputTail(value, max = 12 * 1024) {
  return value.length <= max ? value : value.slice(value.length - max);
}

export async function runQualifiedProfileBuilder(builder, projectRoot, options = {}) {
  const signal = options.signal;
  signal?.throwIfAborted();
  const child = spawn(builder, ["--project-dir", projectRoot], {
    cwd: projectRoot,
    env: process.env,
    detached: process.platform !== "win32",
    stdio: ["ignore", "pipe", "pipe"],
  });
  let stdout = "";
  let stderr = "";
  let outputBytes = 0;
  let settled = false;
  const timeout = setTimeout(() => killProcessGroup(child), options.timeoutMs ?? MAX_BUILD_MS);
  const onAbort = () => killProcessGroup(child);
  signal?.addEventListener("abort", onAbort, { once: true });
  try {
    const result = await new Promise((resolve, reject) => {
      const collect = (target, chunk) => {
        outputBytes += chunk.length;
        if (outputBytes > MAX_BUILD_OUTPUT_BYTES) {
          killProcessGroup(child);
          reject(new Error("Android Project build output exceeded its bounded limit"));
          return target;
        }
        return target + chunk.toString("utf8");
      };
      child.stdout.on("data", (chunk) => { stdout = collect(stdout, chunk); });
      child.stderr.on("data", (chunk) => { stderr = collect(stderr, chunk); });
      child.once("error", reject);
      child.once("close", (code, childSignal) => resolve({ code, signal: childSignal }));
    });
    settled = true;
    signal?.throwIfAborted();
    if (result.code !== 0) {
      const detail = outputTail(stderr.trim() || stdout.trim());
      throw new Error(`Qualified Android Project build failed${detail ? `:\n${detail}` : ""}`);
    }
    const lines = stdout.trim().split(/\r?\n/u).filter(Boolean);
    let payload;
    try {
      payload = JSON.parse(lines.at(-1));
    } catch {
      throw new Error("Qualified Android Project builder returned an invalid result");
    }
    return payload;
  } finally {
    clearTimeout(timeout);
    signal?.removeEventListener("abort", onAbort);
    if (!settled && signal?.aborted) killProcessGroup(child);
  }
}

async function assertBuiltArtifact(root, metadata, payload) {
  const artifact = path.resolve(root, metadata.artifact);
  if (
    payload?.status !== "built" ||
    payload.profileId !== PROFILE_ID ||
    path.resolve(payload.projectDirectory ?? "") !== root ||
    path.resolve(payload.artifact ?? "") !== artifact ||
    !inside(root, artifact)
  ) {
    throw new Error("Qualified Android Project builder returned an unexpected artifact");
  }
  const artifactMetadata = await fs.lstat(artifact).catch(() => null);
  if (!artifactMetadata?.isFile() || artifactMetadata.isSymbolicLink() || artifactMetadata.nlink !== 1) {
    throw new Error("Qualified Android Project build did not produce a safe APK");
  }
  if (await fs.realpath(artifact) !== artifact) {
    throw new Error("Qualified Android Project APK moved outside its recorded path");
  }
  return metadata.artifact;
}

function toolResult(payload) {
  return { content: [{ type: "text", text: JSON.stringify(payload) }], details: payload };
}

export function createAndroidProjectBuildTool(context, dependencies = {}) {
  const workspaceRoot = workspaceRootFor(context);
  if (!workspaceRoot || context?.sandboxed === true) return null;
  const builder = dependencies.profileBuilder ?? PROFILE_BUILDER;
  const runBuilder = dependencies.runBuilder ?? runQualifiedProfileBuilder;
  const approvalBroker = dependencies.approvalBroker;
  return {
    name: ANDROID_PROJECT_BUILD_TOOL,
    label: "Build Android Project",
    description:
      "Build the current Project with its qualified immutable Android development profile. Accepts no command or path. It validates the active Project run and profile metadata, returns the fresh workspace-relative APK path, and never installs, opens, or presents the App.",
    parameters: ANDROID_PROJECT_BUILD_PARAMETERS,
    async execute(toolCallId, rawParams, signal) {
      const params = parseAndroidProjectBuildParams(rawParams);
      signal?.throwIfAborted();
      if (typeof toolCallId !== "string" || !toolCallId.trim() || !approvalBroker) {
        throw new Error("android_project_build authorization policy is unavailable");
      }
      const authorization = approvalBroker.consumeAuthorization(params.authorizationNonce, {
        toolCallId: toolCallId.trim(),
        context,
      });
      return authorization.run(signal, async (authorizedSignal) => {
        const { root, metadata } = await readProjectMetadata(workspaceRoot);
        await assertQualifiedReactNativeProject(root, metadata, builder);
        const payload = await runBuilder(builder, root, { signal: authorizedSignal });
        authorizedSignal.throwIfAborted();
        const apkPath = await assertBuiltArtifact(root, metadata, payload);
        return toolResult({
          protocolVersion: ANDROID_PROJECT_BUILD_PROTOCOL_VERSION,
          status: "built",
          profileId: metadata.profileId,
          profileGeneration: metadata.profileGeneration,
          applicationId: metadata.applicationId,
          apkPath,
        });
      });
    },
  };
}
