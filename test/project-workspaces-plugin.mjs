import "./project-query.mjs";
import assert from "node:assert/strict";
import { execFile } from "node:child_process";
import fs from "node:fs/promises";
import os from "node:os";
import path from "node:path";
import test from "node:test";
import { promisify } from "node:util";
import { createProjectWorkspaceService } from "../product-plugins/project-workspaces/project-service.mjs";
import { createSupervisorCapabilityReadiness } from "../product-plugins/project-workspaces/capability-readiness.mjs";
import { registerProjectWorkspacesPlugin } from "../product-plugins/project-workspaces/runtime.mjs";
import {
  allocateDefaultProjectName,
  parseCreateProjectParams,
  parseProjectRunLeaseStatusParams,
  ProjectWorkspaceErrorCode,
} from "../product-plugins/project-workspaces/protocol.mjs";

const runFile = promisify(execFile);

function request(overrides = {}) {
  return parseCreateProjectParams({
    intentId: "intent-00000001",
    nameRevision: "revision-000001",
    naming: "default",
    requestedName: "New Project",
    ...overrides,
  });
}

const readyCapabilitySnapshot = {
    revision: "android-kotlin-compose-v1:1|android-native:none|flutter:none|godot-android:none|react-native:none|web-development:none",
    readyCapabilities: ["android_kotlin"],
    profiles: {
      androidKotlin: { profileId: "android-kotlin-compose-v1", generation: 1 },
      androidNative: null,
      flutter: null,
      godotAndroid: null,
      reactNative: null,
      webDevelopment: null,
    },
};
const readyCapability = {
  isReady: () => true,
  current: () => readyCapabilitySnapshot,
  requireCurrent: () => readyCapabilitySnapshot,
};

async function fixture(options = {}) {
  const root = await fs.mkdtemp(path.join(os.tmpdir(), "clawinone-projects-"));
  const registered = [...(options.registered ?? [])];
  let registrationFailure = options.registrationFailure;
  const ids = [
    "11111111-1111-4111-8111-111111111111",
    "22222222-2222-4222-8222-222222222222",
    "33333333-3333-4333-8333-333333333333",
  ];
  const service = createProjectWorkspaceService({
    stateDir: path.join(root, "state"),
    projectsRoot: path.join(root, "projects"),
    randomUUID: () => ids.shift() ?? "44444444-4444-4444-8444-444444444444",
    runCommand: async (argv, commandOptions) => {
      await runFile(argv[0], argv.slice(1), {
        cwd: commandOptions.cwd,
        env: { ...process.env, ...commandOptions.env },
      });
    },
    listProjects: async () => ({ projects: registered }),
    registerProject: async (repoRoot, displayName) => {
      if (registrationFailure) {
        const failure = registrationFailure;
        registrationFailure = null;
        throw failure;
      }
      const project = {
        id: `project-${registered.length + 1}`,
        displayName,
        repoRoot,
        source: "registered",
      };
      registered.push(project);
      return project;
    },
  });
  return { root, service, registered };
}

test("default naming allocates the first canonical suffix", () => {
  assert.equal(allocateDefaultProjectName([" new project ", "NEW PROJECT 2", "Other"]), "New Project 3");
});

test("Project Workspaces exclusively declares the project query tool", async () => {
  const projectManifest = JSON.parse(
    await fs.readFile(new URL("../product-plugins/project-workspaces/openclaw.plugin.json", import.meta.url), "utf8"),
  );
  const androidBridgeManifest = JSON.parse(
    await fs.readFile(new URL("../product-plugins/android-developer-bridge/openclaw.plugin.json", import.meta.url), "utf8"),
  );
  assert.deepEqual(projectManifest.contracts.tools, ["project_query"]);
  assert.deepEqual(androidBridgeManifest.contracts.tools, ["android_app", "android_project_build"]);
});

test("create contract rejects removed client capability assertions", () => {
  assert.throws(
    () => request({ capabilityGeneration: "forged-generation" }),
    (error) => error.code === ProjectWorkspaceErrorCode.invalidRequest,
  );
});

test("run lease status accepts every canonical non-empty OpenClaw Project id", () => {
  assert.deepEqual(parseProjectRunLeaseStatusParams({ projectId: "rnfixed" }), {
    projectId: "rnfixed",
  });
  assert.deepEqual(parseProjectRunLeaseStatusParams({ projectId: "r" }), {
    projectId: "r",
  });
  assert.throws(
    () => parseProjectRunLeaseStatusParams({ projectId: "  " }),
    (error) => error.code === ProjectWorkspaceErrorCode.invalidRequest,
  );
});

test("Supervisor capability readiness validates the exact immutable active profile", async () => {
  const root = await fs.mkdtemp(path.join(os.tmpdir(), "clawinone-readiness-"));
  const profileRoot = path.join(root, "development-profiles", "releases", "android-kotlin-compose-v1-generation-1");
  const sdkRoot = path.join(root, "android-sdk");
  const javaHome = path.join(root, "java");
  await fs.mkdir(profileRoot, { recursive: true });
  await fs.mkdir(sdkRoot, { recursive: true });
  await fs.mkdir(javaHome, { recursive: true });
  const materializer = path.join(profileRoot, "new-project.mjs");
  const signer = "a".repeat(64);
  await fs.writeFile(materializer, "#!/usr/bin/env node\n");
  await fs.chmod(materializer, 0o555);
  await fs.writeFile(path.join(profileRoot, "qualified"), `${signer}\n`);
  await fs.writeFile(
    path.join(profileRoot, "release.json"),
    JSON.stringify({ schemaVersion: 1, profileId: "android-kotlin-compose-v1", generation: 1 }),
  );
  const profilePath = path.join(profileRoot, "profile.json");
  await fs.writeFile(
    profilePath,
    JSON.stringify({
      schemaVersion: 1,
      profileId: "android-kotlin-compose-v1",
      generation: 1,
      status: "ready",
      javaHome,
      androidSdkRoot: sdkRoot,
      materializer,
      debugSignerSha256: signer,
    }),
  );
  await fs.chmod(profilePath, 0o444);
  const readiness = createSupervisorCapabilityReadiness({
    environment: {
      CLAW_IN_ONE_ANDROID_PROFILE: profilePath,
      CLAW_IN_ONE_ANDROID_NEW_PROJECT: materializer,
      ANDROID_SDK_ROOT: sdkRoot,
      JAVA_HOME: javaHome,
    },
  });

  assert.deepEqual(readiness.current(), {
    revision: "android-kotlin-compose-v1:1|android-native:none|flutter:none|godot-android:none|react-native:none|web-development:none",
    readyCapabilities: ["android_kotlin"],
    profiles: {
      androidKotlin: { profileId: "android-kotlin-compose-v1", generation: 1 },
      androidNative: null,
      flutter: null,
      godotAndroid: null,
      reactNative: null,
      webDevelopment: null,
    },
  });
  await fs.writeFile(path.join(profileRoot, "qualified"), `${"b".repeat(64)}\n`);
  assert.equal(readiness.current(), null);
  await fs.writeFile(path.join(profileRoot, "qualified"), `${signer}\n`);
  await fs.chmod(profilePath, 0o644);
  assert.equal(readiness.current(), null);
  await fs.rm(root, { recursive: true, force: true });
});

test("Flutter readiness is advertised only with the exact qualified profile", async () => {
  const root = await fs.mkdtemp(path.join(os.tmpdir(), "clawinone-flutter-readiness-"));
  const androidProfileRoot = path.join(root, "android-kotlin");
  const flutterProfileRoot = path.join(root, "flutter");
  const sdkRoot = path.join(root, "android-sdk");
  const javaHome = path.join(root, "java");
  await Promise.all([
    fs.mkdir(androidProfileRoot, { recursive: true }),
    fs.mkdir(flutterProfileRoot, { recursive: true }),
    fs.mkdir(sdkRoot, { recursive: true }),
    fs.mkdir(javaHome, { recursive: true }),
  ]);
  const signer = "a".repeat(64);
  const androidMaterializer = path.join(androidProfileRoot, "new-project.mjs");
  await fs.writeFile(androidMaterializer, "#!/usr/bin/env node\n", { mode: 0o555 });
  await fs.writeFile(path.join(androidProfileRoot, "qualified"), `${signer}\n`);
  await fs.writeFile(path.join(androidProfileRoot, "release.json"), JSON.stringify({ schemaVersion: 1, profileId: "android-kotlin-compose-v1", generation: 1 }));
  await fs.writeFile(
    path.join(androidProfileRoot, "profile.json"),
    JSON.stringify({
      schemaVersion: 1,
      profileId: "android-kotlin-compose-v1",
      generation: 1,
      status: "ready",
      javaHome,
      androidSdkRoot: sdkRoot,
      materializer: androidMaterializer,
      debugSignerSha256: signer,
    }),
    { mode: 0o444 },
  );

  const flutterMaterializer = path.join(flutterProfileRoot, "new-project.mjs");
  await fs.writeFile(flutterMaterializer, "#!/usr/bin/env node\n", { mode: 0o555 });
  const release = {
    schemaVersion: 1,
    profileId: "flutter-android-v1",
    generation: 2,
    flutterVersion: "3.47.4",
    dartVersion: "3.13.3",
    frameworkRevision: "9584c6713b324636289d067944a46fd6b49df14b",
    engineRevision: "06a2e2a110089dff50fe635cffd2a61e1b24fbcd",
    androidNdk: "28.2.13676358",
  };
  await fs.writeFile(path.join(flutterProfileRoot, "release.json"), JSON.stringify(release));
  await fs.writeFile(path.join(flutterProfileRoot, "qualified"), `${"b".repeat(64)}\n`);
  await fs.writeFile(
    path.join(flutterProfileRoot, "profile.json"),
    JSON.stringify({
      ...release,
      status: "ready",
      targetPlatform: "android-arm64",
      javaHome,
      androidSdkRoot: sdkRoot,
      materializer: flutterMaterializer,
    }),
    { mode: 0o444 },
  );
  const readiness = createSupervisorCapabilityReadiness({
    environment: {
      CLAW_IN_ONE_ANDROID_PROFILE: path.join(androidProfileRoot, "profile.json"),
      CLAW_IN_ONE_ANDROID_NEW_PROJECT: androidMaterializer,
      CLAW_IN_ONE_FLUTTER_PROFILE: path.join(flutterProfileRoot, "profile.json"),
      CLAW_IN_ONE_FLUTTER_NEW_PROJECT: flutterMaterializer,
      ANDROID_SDK_ROOT: sdkRoot,
      JAVA_HOME: javaHome,
    },
  });

  assert.deepEqual(readiness.current().readyCapabilities, ["android_kotlin", "flutter"]);
  const flutterWithoutKotlin = createSupervisorCapabilityReadiness({
    environment: {
      CLAW_IN_ONE_FLUTTER_PROFILE: path.join(flutterProfileRoot, "profile.json"),
      CLAW_IN_ONE_FLUTTER_NEW_PROJECT: flutterMaterializer,
      ANDROID_SDK_ROOT: sdkRoot,
      JAVA_HOME: javaHome,
    },
  });
  assert.deepEqual(flutterWithoutKotlin.current().readyCapabilities, ["flutter"]);
  await fs.chmod(path.join(flutterProfileRoot, "profile.json"), 0o644);
  assert.deepEqual(readiness.current().readyCapabilities, ["android_kotlin"]);
  await fs.rm(root, { recursive: true, force: true });
});

test("Godot Android readiness requires the qualified ARM64 profile and both pinned artifacts", async () => {
  const root = await fs.mkdtemp(path.join(os.tmpdir(), "clawinone-godot-readiness-"));
  const profileRoot = path.join(root, "godot-android");
  const sdkRoot = path.join(root, "android-sdk");
  const javaHome = path.join(root, "java");
  await Promise.all([
    fs.mkdir(profileRoot, { recursive: true }),
    fs.mkdir(sdkRoot, { recursive: true }),
    fs.mkdir(javaHome, { recursive: true }),
  ]);
  const materializer = path.join(profileRoot, "new-project.mjs");
  const godotBinary = path.join(root, "godot");
  const androidDebugTemplate = path.join(root, "android_debug.apk");
  const signer = "c".repeat(64);
  await Promise.all([
    fs.writeFile(materializer, "#!/usr/bin/env node\n", { mode: 0o555 }),
    fs.writeFile(godotBinary, "fixture"),
    fs.writeFile(androidDebugTemplate, "fixture"),
    fs.writeFile(path.join(profileRoot, "qualified"), `${signer}\n`),
  ]);
  const release = {
    schemaVersion: 1,
    profileId: "godot-android-v1",
    generation: 1,
    godotVersion: "4.7.2",
    godotBuild: "stable.official.ed1daf0bf",
    targetPlatform: "android-arm64",
    renderer: "mobile",
    customBuild: false,
  };
  await fs.writeFile(path.join(profileRoot, "release.json"), JSON.stringify(release));
  const profilePath = path.join(profileRoot, "profile.json");
  await fs.writeFile(
    profilePath,
    JSON.stringify({
      ...release,
      status: "ready",
      javaHome,
      androidSdkRoot: sdkRoot,
      godotBinary,
      androidDebugTemplate,
      materializer,
      debugSignerSha256: signer,
    }),
    { mode: 0o444 },
  );
  const readiness = createSupervisorCapabilityReadiness({
    environment: {
      CLAW_IN_ONE_GODOT_ANDROID_PROFILE: profilePath,
      CLAW_IN_ONE_GODOT_ANDROID_NEW_PROJECT: materializer,
      ANDROID_SDK_ROOT: sdkRoot,
      JAVA_HOME: javaHome,
    },
  });

  assert.deepEqual(readiness.current(), {
    revision: "android-kotlin:none|android-native:none|flutter:none|godot-android-v1:1|react-native:none|web-development:none",
    readyCapabilities: ["godot_android"],
    profiles: {
      androidKotlin: null,
      androidNative: null,
      flutter: null,
      godotAndroid: { profileId: "godot-android-v1", generation: 1 },
      reactNative: null,
      webDevelopment: null,
    },
  });
  await fs.rm(androidDebugTemplate);
  assert.equal(readiness.current(), null);
  await fs.rm(root, { recursive: true, force: true });
});

test("React Native readiness requires the exact standalone ARM64 profile and Developer Node paths", async () => {
  const root = await fs.mkdtemp(path.join(os.tmpdir(), "clawinone-react-native-readiness-"));
  const profileRoot = path.join(root, "react-native");
  const sdkRoot = path.join(root, "android-sdk");
  const javaHome = path.join(root, "java");
  const npmCache = path.join(root, "npm-cache");
  await Promise.all([
    fs.mkdir(profileRoot, { recursive: true }),
    fs.mkdir(sdkRoot, { recursive: true }),
    fs.mkdir(javaHome, { recursive: true }),
    fs.mkdir(npmCache, { recursive: true }),
  ]);
  const materializer = path.join(profileRoot, "new-project.mjs");
  const nodeBinary = path.join(root, "node");
  const npmBinary = path.join(root, "npm");
  const signer = "d".repeat(64);
  await Promise.all([
    fs.writeFile(materializer, "#!/usr/bin/env node\n", { mode: 0o555 }),
    fs.writeFile(nodeBinary, "fixture", { mode: 0o555 }),
    fs.writeFile(npmBinary, "fixture", { mode: 0o555 }),
    fs.writeFile(path.join(profileRoot, "qualified"), `${signer}\n`),
  ]);
  const release = {
    schemaVersion: 1,
    profileId: "react-native-android-v1",
    generation: 1,
    reactNativeVersion: "0.87.1",
    reactVersion: "19.2.3",
    nodeVersion: "22.22.0",
    targetPlatform: "android-arm64",
    newArchitecture: true,
    hermes: true,
    metroRequired: false,
  };
  await fs.writeFile(path.join(profileRoot, "release.json"), JSON.stringify(release));
  const profilePath = path.join(profileRoot, "profile.json");
  await fs.writeFile(
    profilePath,
    JSON.stringify({
      ...release,
      status: "ready",
      javaHome,
      androidSdkRoot: sdkRoot,
      nodeBinary,
      npmBinary,
      npmCache,
      materializer,
      debugSignerSha256: signer,
    }),
    { mode: 0o444 },
  );
  const readiness = createSupervisorCapabilityReadiness({
    environment: {
      CLAW_IN_ONE_REACT_NATIVE_PROFILE: profilePath,
      CLAW_IN_ONE_REACT_NATIVE_NEW_PROJECT: materializer,
      ANDROID_SDK_ROOT: sdkRoot,
      JAVA_HOME: javaHome,
    },
  });
  assert.deepEqual(readiness.current(), {
    revision: "android-kotlin:none|android-native:none|flutter:none|godot-android:none|react-native-android-v1:1|web-development:none",
    readyCapabilities: ["react_native"],
    profiles: {
      androidKotlin: null,
      androidNative: null,
      flutter: null,
      godotAndroid: null,
      reactNative: { profileId: "react-native-android-v1", generation: 1 },
      webDevelopment: null,
    },
  });
  await fs.chmod(nodeBinary, 0o000);
  await fs.rm(nodeBinary);
  assert.equal(readiness.current(), null);
  await fs.rm(root, { recursive: true, force: true });
});

test("catalog ignores the root workspace record that has no repository root", async () => {
  const { service } =
    await fixture({
      registered: [
        { id: "workspace:main", displayName: "workspace", source: "workspace", agentId: "main" },
      ],
    });
  assert.deepEqual(await service.catalog(), {
    revision: 0,
    projects: [],
    sessionBindings: {},
  });
});

test("catalog joins a read-redacted Core summary to its durable registered root", async () => {
  const { service, registered } = await fixture();
  const created = await service.create(request());
  registered[0] = {
    id: created.project.id,
    displayName: created.displayName,
    source: "registered",
  };
  assert.deepEqual(await service.catalog(), {
    revision: 3,
    projects: [created.project],
    sessionBindings: {},
  });
});

test("custom names reject separators before filesystem mutation", async () => {
  const { service } = await fixture();
  assert.throws(
    () => request({ naming: "custom", requestedName: "bad/name" }),
    (error) => error.code === ProjectWorkspaceErrorCode.invalidName,
  );
  assert.equal((await fs.readdir(service.projectsRoot).catch(() => [])).length, 0);
});

test("one intent creates one committed Git Project and reuses its result", async () => {
  const { service, registered } = await fixture({
    registered: [
      { id: "existing", displayName: "New Project", repoRoot: "/tmp/existing", source: "registered" },
    ],
  });
  const first = await service.create(request());
  const duplicate = await service.create(request());
  assert.equal(first.displayName, "New Project 2");
  assert.deepEqual(duplicate, first);
  assert.equal(registered.length, 2);
  assert.equal((await runFile("git", ["rev-list", "--count", "HEAD"], { cwd: first.repoRoot })).stdout.trim(), "1");
  await assert.rejects(
    () => service.create(request({ requestedName: "Changed", nameRevision: "revision-000002" })),
    (error) => error.code === ProjectWorkspaceErrorCode.intentConflict,
  );
});

test("custom conflict remains typed and creates no directory", async () => {
  const { service } = await fixture({
    registered: [
      { id: "existing", displayName: "Tiny Memo", repoRoot: "/tmp/existing", source: "registered" },
    ],
  });
  await assert.rejects(
    () => service.create(request({ naming: "custom", requestedName: " tiny memo " })),
    (error) => error.code === ProjectWorkspaceErrorCode.nameConflict,
  );
  assert.equal((await fs.readdir(service.projectsRoot).catch(() => [])).length, 0);
});

test("unknown registration outcome reconciles by canonical root without a second Project", async () => {
  const { service, registered } = await fixture({ registrationFailure: new Error("response lost") });
  await assert.rejects(() => service.create(request()), /response lost/);
  const pending = await service.read("intent-00000001");
  assert.equal(pending.phase, "repository_ready");
  registered.push({
    id: "project-after-loss",
    displayName: pending.displayName,
    repoRoot: pending.repoRoot,
    source: "registered",
  });
  const recovered = await service.read("intent-00000001");
  assert.equal(recovered.phase, "registered");
  assert.equal(recovered.project.id, "project-after-loss");
});

test("a commit interruption resumes inside the reserved root", async () => {
  const root = await fs.mkdtemp(path.join(os.tmpdir(), "clawinone-project-resume-"));
  const registered = [];
  let failCommit = true;
  const service = createProjectWorkspaceService({
    stateDir: path.join(root, "state"),
    projectsRoot: path.join(root, "projects"),
    randomUUID: () => "55555555-5555-4555-8555-555555555555",
    runCommand: async (argv, commandOptions) => {
      if (argv[0] === "git" && argv[1] === "commit" && failCommit) {
        failCommit = false;
        throw new Error("interrupted commit");
      }
      await runFile(argv[0], argv.slice(1), {
        cwd: commandOptions.cwd,
        env: { ...process.env, ...commandOptions.env },
      });
    },
    listProjects: async () => ({ projects: registered }),
    registerProject: async (repoRoot, displayName) => {
      const project = { id: "resumed-project", displayName, repoRoot, source: "registered" };
      registered.push(project);
      return project;
    },
  });
  await assert.rejects(() => service.create(request()), /interrupted commit/);
  const recovered = await service.create(request());
  assert.equal(recovered.project.id, "resumed-project");
  assert.equal((await runFile("git", ["rev-list", "--count", "HEAD"], { cwd: recovered.repoRoot })).stdout.trim(), "1");
  assert.equal(registered.length, 1);
});

test("missing server readiness rejects Project creation and Android App admission before mutation", async () => {
  const methods = new Map();
  let createCalls = 0;
  const owner = {
    agentId: "main",
    sessionKey: "agent:main:project:owner",
    sessionId: "session-owner",
    runId: "run-owner",
  };
  const service = {
    create() { createCalls += 1; },
    read() {},
    catalog() {},
    bindSession() {},
    binding: () => ({ projectId: "project-1", registered: true }),
  };
  const coordinator = {
    service,
    runLeases: new Map([["project-1", { projectId: "project-1", ...owner }]]),
    runLeaseGeneration: 1,
  };
  const capabilityReadiness = createSupervisorCapabilityReadiness({ environment: {} });
  const api = {
    config: {},
    logger: { warn() {} },
    runtime: {
      system: { runCommandWithTimeout() {} },
      state: { resolveStateDir: () => "/tmp" },
      agent: {
        session: {
          resolveStorePath: () => "/tmp/sessions",
          getSessionEntry: () => ({ sessionId: owner.sessionId, projectId: "project-1" }),
        },
      },
    },
    registerTool() {},
    registerGatewayMethod(name, handler) { methods.set(name, handler); },
    on() {},
  };
  const runtime = registerProjectWorkspacesPlugin(api, { coordinator, capabilityReadiness });
  let response;
  await methods.get("claw.projects.create")({
    params: request(),
    respond(ok, value, error) { response = { ok, value, error }; },
  });

  assert.equal(response.ok, false);
  assert.equal(response.error.code, ProjectWorkspaceErrorCode.unavailable);
  assert.equal(createCalls, 0);
  assert.equal(runtime.admission.admitProjectMutation(owner, "android_kotlin"), null);
});

test("runtime admits one active Chat run per Project and releases only the exact run", async () => {
  const hooks = new Map();
  const entries = new Map([
    ["agent:main:project:one", {
      sessionId: "session-1",
      projectId: "project-1",
      permissionMode: "workspace",
      sessionRoot: "/workspace/project-1",
    }],
    ["agent:main:project:two", {
      sessionId: "session-2",
      projectId: "project-1",
      permissionMode: "workspace",
      sessionRoot: "/workspace/project-1",
    }],
  ]);
  const bindings = new Map();
  const service = {
    create() {}, read() {}, catalog() {},
    async bindSession(projectId, sessionKey, sessionId) {
      bindings.set(sessionKey, { projectId, sessionId });
      return true;
    },
    binding: (projectId) =>
      [...bindings.values()].some((binding) => binding.projectId === projectId)
        ? { projectId, registered: true }
        : null,
  };
  const api = {
    config: {},
    logger: { warn() {} },
    runtime: {
      system: { runCommandWithTimeout() {} },
      state: { resolveStateDir: () => "/tmp" },
      agent: {
        session: {
          resolveStorePath: () => "/tmp/sessions",
          getSessionEntry: ({ sessionKey }) => entries.get(sessionKey),
        },
      },
    },
    registerTool() {},
    registerGatewayMethod() {},
    on(name, handler) { hooks.set(name, handler); },
  };
  const runtime = registerProjectWorkspacesPlugin(api, { service, capabilityReadiness: readyCapability });
  const owner = {
    agentId: "main", sessionKey: "agent:main:project:one", sessionId: "session-1", runId: "run-1",
    workspaceDir: "/workspace/project-1",
  };
  assert.equal(await hooks.get("before_agent_run")({}, owner), undefined);
  assert.equal(runtime.admission.admitProjectMutation(owner, "android_kotlin").projectId, "project-1");
  const toolHookContext = {
    agentId: owner.agentId,
    sessionKey: owner.sessionKey,
    sessionId: owner.sessionId,
    runId: owner.runId,
  };
  assert.deepEqual(runtime.admission.admitProjectMutation(toolHookContext, "android_kotlin"), {
    projectId: "project-1",
    projectRoot: "/workspace/project-1",
    leaseGeneration: 1,
  });
  const competing = {
    agentId: "main", sessionKey: "agent:main:project:two", sessionId: "session-2", runId: "run-2",
    workspaceDir: "/workspace/project-1",
  };
  assert.deepEqual(await hooks.get("before_agent_run")({}, competing), {
    outcome: "block",
    reason: "This project is active in another Chat",
  });
  assert.equal(runtime.admission.admitProjectMutation(competing, "android_kotlin"), null);

  hooks.get("agent_end")({}, owner);
  assert.equal(await hooks.get("before_agent_run")({}, { ...owner, runId: "run-3" }), undefined);
  hooks.get("agent_end")({}, owner);
  assert.equal(runtime.admission.admitProjectMutation({ ...owner, runId: "run-3" }, "android_kotlin").projectId, "project-1");
  assert.equal(runtime.admission.admitProjectMutation({ ...owner, runId: "run-3" }, "web_development"), null);
  hooks.get("agent_end")({}, { ...owner, runId: "run-3" });
  assert.equal(runtime.runLeases.size, 0);
});

test("runtime registrations share the Project coordinator used by hooks and Gateway methods", async () => {
  const hooks = new Map();
  const methods = new Map();
  const tools = [];
  const entries = new Map([
    ["agent:main:project:owner", {
      sessionId: "session-owner",
      projectId: "project-1",
      permissionMode: "workspace",
      sessionRoot: "/workspace/project-1",
    }],
  ]);
  const service = {
    create() {}, read() {}, catalog() {},
    async bindSession() { return true; },
    binding() { return { projectId: "project-1", registered: true }; },
  };
  const coordinator = { service, runLeases: new Map(), runLeaseGeneration: 0 };
  function api(registerHooks) {
    return {
      config: {},
      logger: { warn() {} },
      runtime: {
        system: { runCommandWithTimeout() {} },
        state: { resolveStateDir: () => "/tmp" },
        agent: {
          session: {
            resolveStorePath: () => "/tmp/sessions",
            getSessionEntry: ({ sessionKey }) => entries.get(sessionKey),
          },
        },
      },
      registerTool(factory, options) { tools.push({ factory, options }); },
      registerGatewayMethod(name, handler) { methods.set(name, handler); },
      on(name, handler) { if (registerHooks) hooks.set(name, handler); },
    };
  }
  registerProjectWorkspacesPlugin(api(true), { coordinator, capabilityReadiness: readyCapability });
  registerProjectWorkspacesPlugin(api(false), { coordinator, capabilityReadiness: readyCapability });
  const owner = {
    agentId: "main",
    sessionKey: "agent:main:project:owner",
    sessionId: "session-owner",
    runId: "run-owner",
  };
  assert.equal(await hooks.get("before_agent_run")({}, owner), undefined);
  let response;
  await methods.get("claw.projects.run-lease.status")({
    params: { projectId: "project-1" },
    respond(ok, value) { response = { ok, value }; },
  });
  assert.equal(response.ok, true);
  assert.equal(response.value.lease.sessionKey, owner.sessionKey);
  const queryRegistration = tools.find(({ options }) => options.name === "project_query");
  assert(queryRegistration);
  assert.equal(queryRegistration.factory({ ...owner, workspaceDir: "/workspace/other" }), null);
  assert.equal(queryRegistration.factory({ ...owner, workspaceDir: "/workspace/project-1" }).name, "project_query");
  assert.equal(queryRegistration.factory({ ...owner, workspaceDir: "/workspace/project-1", sandboxed: true }), null);
});
