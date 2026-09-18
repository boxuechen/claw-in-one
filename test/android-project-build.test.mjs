import assert from "node:assert/strict";
import fs from "node:fs/promises";
import os from "node:os";
import path from "node:path";
import test from "node:test";
import {
  createAndroidProjectBuildTool,
} from "../product-plugins/android-developer-bridge/project-build/tool.mjs";
import { createProjectBuildAuthorizationBroker } from "../product-plugins/android-developer-bridge/project-build/authorization.mjs";
import {
  ANDROID_PROJECT_BUILD_AUTHORIZATION_NONCE,
  ANDROID_PROJECT_BUILD_TOOL,
  parseAndroidProjectBuildParams,
} from "../product-plugins/android-developer-bridge/project-build/tool-protocol.mjs";

const ADMISSION_KEY = Symbol.for(
  "io.github.boxuechen.clawinone.project-workspaces.admission.v2",
);
const ARTIFACT = "android/app/build/outputs/apk/debug/app-debug.apk";

async function fixture() {
  const root = await fs.mkdtemp(path.join(os.tmpdir(), "clawinone-project-build-"));
  const project = path.join(root, "ReactNativeLab");
  const builder = path.join(root, "profiles", "react-native", "active", "build-project.mjs");
  await fs.mkdir(path.join(project, ".claw-in-one"), { recursive: true });
  await fs.mkdir(path.dirname(builder), { recursive: true });
  await fs.writeFile(builder, "#!/usr/bin/env node\n");
  await fs.writeFile(
    path.join(project, ".claw-in-one", "android-project.v1.json"),
    `${JSON.stringify({
      schemaVersion: 1,
      profileId: "react-native-android-v1",
      profileGeneration: 1,
      projectDirectory: project,
      applicationId: "dev.clawinone.reactnativelab",
      buildCommand: `${builder} --project-dir .`,
      artifact: ARTIFACT,
    })}\n`,
  );
  return { root, project, builder };
}

test("android_project_build accepts no agent-controlled command or path", () => {
  assert.deepEqual(parseAndroidProjectBuildParams({}), { authorizationNonce: undefined });
  assert.throws(() => parseAndroidProjectBuildParams(undefined), /does not accept parameters/);
  assert.throws(() => parseAndroidProjectBuildParams({ command: "./gradlew" }), /does not accept parameters/);
});

test("android_project_build admits only the active Project run and returns a relative fresh APK", async () => {
  const { root, project, builder } = await fixture();
  const calls = [];
  const admission = {projectId: "project-1", projectRoot: await fs.realpath(project), leaseGeneration: 1};
  globalThis[ADMISSION_KEY] = { admitProjectMutation: (_identity, capability) => capability === "android_kotlin" ? admission : null };
  try {
    const host = new AbortController();
    const approvalBroker = createProjectBuildAuthorizationBroker({
      randomUUID: () => "12345678-1234-4123-8123-123456789abc",
    });
    const context = {
      agentId: "main",
      sessionKey: "agent:main:react-native",
      sessionId: "session-1",
      workspaceDir: project,
    };
    const tool = createAndroidProjectBuildTool(
      context,
      {
        approvalBroker,
        profileBuilder: builder,
        async runBuilder(receivedBuilder, receivedProject, { signal }) {
          calls.push({ receivedBuilder, receivedProject, signal });
          const artifact = path.join(receivedProject, ARTIFACT);
          await fs.mkdir(path.dirname(artifact), { recursive: true });
          await fs.writeFile(artifact, "apk");
          return {
            status: "built",
            profileId: "react-native-android-v1",
            projectDirectory: receivedProject,
            artifact,
          };
        },
      },
    );
    assert.equal(tool.name, ANDROID_PROJECT_BUILD_TOOL);
    const signal = new AbortController().signal;
    const authorized = approvalBroker.beforeToolCall(
      { toolName: ANDROID_PROJECT_BUILD_TOOL, toolCallId: "tool-1", params: {} },
      {
        ...context,
        runId: "run-1",
        toolCallId: "tool-1",
        abortSignal: host.signal,
      },
    );
    assert.equal(authorized.block, undefined);
    assert.equal(typeof authorized.params[ANDROID_PROJECT_BUILD_AUTHORIZATION_NONCE], "string");
    const result = await tool.execute("tool-1", authorized.params, signal);
    assert.deepEqual(result.details, {
      protocolVersion: 1,
      status: "built",
      profileId: "react-native-android-v1",
      profileGeneration: 1,
      applicationId: "dev.clawinone.reactnativelab",
      apkPath: ARTIFACT,
    });
    assert.equal(calls.length, 1);
    assert.equal(calls[0].receivedBuilder, builder);
    assert.equal(calls[0].receivedProject, await fs.realpath(project));
    assert(calls[0].signal instanceof AbortSignal);
    assert.equal(calls[0].signal.aborted, false);

    await assert.rejects(tool.execute("tool-1", authorized.params, signal), /not authorized/);
    globalThis[ADMISSION_KEY] = { admitProjectMutation: () => null };
    assert.equal(
      approvalBroker.beforeToolCall(
        { toolName: ANDROID_PROJECT_BUILD_TOOL, toolCallId: "tool-2", params: {} },
        { ...context, runId: "run-1", toolCallId: "tool-2", abortSignal: host.signal },
      ).block,
      true,
    );
    approvalBroker.close();
  } finally {
    delete globalThis[ADMISSION_KEY];
    await fs.rm(root, { recursive: true, force: true });
  }
});

test("android_project_build rejects tampered profile metadata and artifact identity", async () => {
  const { root, project, builder } = await fixture();
  const admission = {projectId: "project-1", projectRoot: await fs.realpath(project), leaseGeneration: 1};
  globalThis[ADMISSION_KEY] = { admitProjectMutation: (_identity, capability) => capability === "android_kotlin" ? admission : null };
  try {
    const approvalBroker = createProjectBuildAuthorizationBroker();
    const context = {
      agentId: "main",
      sessionKey: "agent:main:react-native",
      sessionId: "session-1",
      workspaceDir: project,
    };
    const authorize = (toolCallId) =>
      approvalBroker.beforeToolCall(
        { toolName: ANDROID_PROJECT_BUILD_TOOL, toolCallId, params: {} },
        {
          ...context,
          runId: "run-1",
          toolCallId,
          abortSignal: new AbortController().signal,
        },
      ).params;
    const metadataFile = path.join(project, ".claw-in-one", "android-project.v1.json");
    const metadata = JSON.parse(await fs.readFile(metadataFile, "utf8"));
    await fs.writeFile(metadataFile, `${JSON.stringify({ ...metadata, buildCommand: "./gradlew" })}\n`);
    const tool = createAndroidProjectBuildTool(
      context,
      {
        approvalBroker,
        profileBuilder: builder,
        runBuilder: async () => assert.fail("tampered metadata must not run"),
      },
    );
    await assert.rejects(
      tool.execute("tool-1", authorize("tool-1"), new AbortController().signal),
      /not bound to the qualified/,
    );

    await fs.writeFile(metadataFile, `${JSON.stringify(metadata)}\n`);
    const wrongArtifact = createAndroidProjectBuildTool(
      context,
      {
        approvalBroker,
        profileBuilder: builder,
        async runBuilder() {
          const artifact = path.join(project, ARTIFACT);
          await fs.mkdir(path.dirname(artifact), { recursive: true });
          await fs.writeFile(artifact, "apk");
          return {
            status: "built",
            profileId: "react-native-android-v1",
            projectDirectory: project,
            artifact: path.join(project, "other.apk"),
          };
        },
      },
    );
    await assert.rejects(
      wrongArtifact.execute("tool-2", authorize("tool-2"), new AbortController().signal),
      /unexpected artifact/,
    );
    approvalBroker.close();
  } finally {
    delete globalThis[ADMISSION_KEY];
    await fs.rm(root, { recursive: true, force: true });
  }
});

test("android_project_build is absent from sandboxed or rootless Chats", () => {
  assert.equal(createAndroidProjectBuildTool({}), null);
  assert.equal(createAndroidProjectBuildTool({ workspaceDir: "/workspace", sandboxed: true }), null);
});
