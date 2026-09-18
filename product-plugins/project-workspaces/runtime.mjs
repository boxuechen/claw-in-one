import { Buffer } from "node:buffer";
import path from "node:path";
import { createSupervisorCapabilityReadiness } from "./capability-readiness.mjs";
import { createProjectQueryTool, PROJECT_QUERY_TOOL } from "./project-query.mjs";
import { createProjectWorkspaceService } from "./project-service.mjs";
import {
  gatewayErrorForProject,
  parseCreateProjectParams,
  parseCapabilitySnapshotParams,
  parseProjectRunLeaseStatusParams,
  parseReadProjectParams,
  PROJECT_WORKSPACE_METHODS,
} from "./protocol.mjs";

export const PROJECT_WORKSPACE_ADMISSION_KEY = Symbol.for(
  "io.github.boxuechen.clawinone.project-workspaces.admission.v2",
);
const PROJECT_WORKSPACE_COORDINATOR_KEY = Symbol.for(
  "io.github.boxuechen.clawinone.project-workspaces.coordinator.v2",
);

function createCoordinator() {
  return { service: null, runLeases: new Map(), runLeaseGeneration: 0 };
}

function sharedCoordinator() {
  const current = globalThis[PROJECT_WORKSPACE_COORDINATOR_KEY];
  if (current) return current;
  const created = createCoordinator();
  globalThis[PROJECT_WORKSPACE_COORDINATOR_KEY] = created;
  return created;
}

function decodeOutput(value) {
  if (typeof value === "string") return value;
  if (value instanceof Uint8Array) return Buffer.from(value).toString("utf8");
  return "";
}

function parseGatewayCliOutput(result) {
  const stdout = decodeOutput(result?.stdout).trim();
  if (result?.code !== 0 || !stdout) throw new Error("OpenClaw Project command failed");
  const parsed = JSON.parse(stdout);
  if (parsed?.ok === false) throw new Error(parsed.error?.message || "OpenClaw Project command failed");
  return parsed;
}

function errorMessage(error) {
  return error instanceof Error ? error.message : String(error);
}

function coreProjectAdapter(api, dependencies) {
  const run = dependencies.runCommand ?? api.runtime.system.runCommandWithTimeout;
  async function call(method, params) {
    const result = await run(
      ["openclaw", "gateway", "call", method, "--params", JSON.stringify(params), "--json", "--timeout", "15000"],
      { timeoutMs: 20_000, maxOutputBytes: 512 * 1024 },
    );
    return parseGatewayCliOutput(result);
  }
  return {
    runCommand: async (argv, options) => {
      const result = await run(argv, { timeoutMs: 15_000, maxOutputBytes: 256 * 1024, ...options });
      if (result?.code !== 0) throw new Error(`Command failed: ${argv[0]}`);
    },
    listProjects: dependencies.listProjects ?? (() => call("projects.list", {})),
    registerProject:
      dependencies.registerProject ??
      ((repoRoot, displayName) => call("projects.register", { path: repoRoot, name: displayName })),
  };
}

function gatewayHandler(parse, invoke) {
  return async ({ params, respond }) => {
    try {
      respond(true, await invoke(parse(params)));
    } catch (error) {
      respond(false, undefined, gatewayErrorForProject(error));
    }
  };
}

function sessionEntryForContext(api, context) {
  if (!context?.agentId || !context?.sessionKey || !context?.sessionId) return null;
  const storePath = api.runtime.agent.session.resolveStorePath(api.config?.session?.store, {
    agentId: context.agentId,
  });
  const entry = api.runtime.agent.session.getSessionEntry({
    agentId: context.agentId,
    sessionKey: context.sessionKey,
    storePath,
    readConsistency: "latest",
  });
  return entry?.sessionId === context.sessionId ? entry : null;
}

function projectIdForSession(api, context) {
  return sessionEntryForContext(api, context)?.projectId?.trim() || null;
}

function projectSessionForContext(api, context) {
  const entry = sessionEntryForContext(api, context);
  const root = entry?.sessionRoot?.trim();
  const projectId = entry?.projectId?.trim();
  if (entry?.permissionMode !== "workspace" || !projectId || !root) return null;
  return {projectId, root};
}

function projectRootForTool(api, context) {
  const session = projectSessionForContext(api, context);
  if (!session) return null;
  const effectiveRoot =
    context?.fsPolicy?.root?.trim() || context?.workspaceDir?.trim() || context?.projectRoot?.trim();
  return effectiveRoot && path.resolve(effectiveRoot) === path.resolve(session.root) ? session.root : null;
}

export function registerProjectWorkspacesPlugin(api, dependencies = {}) {
  const adapters = coreProjectAdapter(api, dependencies);
  const coordinator = dependencies.coordinator ?? sharedCoordinator();
  const service =
    dependencies.service ??
    coordinator.service ??
    (coordinator.service =
      createProjectWorkspaceService({
        stateDir: dependencies.stateDir ?? api.runtime.state.resolveStateDir(process.env) + "/claw-in-one-projects",
        ...(dependencies.projectsRoot ? { projectsRoot: dependencies.projectsRoot } : {}),
        ...(dependencies.randomUUID ? { randomUUID: dependencies.randomUUID } : {}),
        ...adapters,
      }));
  const runLeases = coordinator.runLeases;
  const capabilityReadiness =
    dependencies.capabilityReadiness ?? createSupervisorCapabilityReadiness();

  const admission = Object.freeze({
    admitProjectMutation(context, requiredCapability) {
      const session = projectSessionForContext(api, context);
      const projectId = session?.projectId ?? null;
      const binding = projectId ? service.binding(projectId) : null;
      const lease = projectId ? runLeases.get(projectId) : null;
      const capabilities = capabilityReadiness.current();
      if (
          capabilities &&
          capabilities.readyCapabilities.includes(requiredCapability) &&
          binding?.registered &&
          session &&
          lease?.sessionId === context?.sessionId &&
          lease?.runId === context?.runId
      ) {
        return Object.freeze({
          projectId,
          projectRoot: path.resolve(session.root),
          leaseGeneration: lease.generation,
        });
      }
      return null;
    },
  });
  globalThis[PROJECT_WORKSPACE_ADMISSION_KEY] = admission;

  api.registerTool(
    (context) => createProjectQueryTool(context, projectRootForTool(api, context)),
    { name: PROJECT_QUERY_TOOL },
  );

  api.registerGatewayMethod(
    PROJECT_WORKSPACE_METHODS.create,
    gatewayHandler(parseCreateProjectParams, (params) => {
      capabilityReadiness.requireCurrent();
      return service.create(params);
    }),
    { scope: "operator.admin", profileAccess: "required" },
  );
  api.registerGatewayMethod(
    PROJECT_WORKSPACE_METHODS.read,
    gatewayHandler(parseReadProjectParams, ({ intentId }) => service.read(intentId)),
    { scope: "operator.read", profileAccess: "required" },
  );
  api.registerGatewayMethod(
    PROJECT_WORKSPACE_METHODS.catalog,
    gatewayHandler(() => ({}), service.catalog),
    { scope: "operator.read", profileAccess: "required" },
  );
  api.registerGatewayMethod(
    PROJECT_WORKSPACE_METHODS.capabilities,
    gatewayHandler(parseCapabilitySnapshotParams, () => capabilityReadiness.requireCurrent()),
    { scope: "operator.read", profileAccess: "required" },
  );
  api.registerGatewayMethod(
    PROJECT_WORKSPACE_METHODS.runLeaseStatus,
    gatewayHandler(
      parseProjectRunLeaseStatusParams,
      ({ projectId }) => ({ lease: runLeases.get(projectId) ?? null }),
    ),
    { scope: "operator.read", profileAccess: "required" },
  );

  api.on("before_agent_run", async (_event, context) => {
    const projectId = projectIdForSession(api, context);
    if (!projectId) return undefined;
    let bound;
    try {
      bound = await service.bindSession(projectId, context.sessionKey, context.sessionId);
    } catch (error) {
      api.logger.warn(`Project Session binding failed for ${projectId}: ${errorMessage(error)}`);
      throw error;
    }
    if (!bound) {
      api.logger.warn(`Project Session binding is unavailable for ${projectId}`);
      return { outcome: "block", reason: "Project Session binding is unavailable" };
    }
    const existing = runLeases.get(projectId);
    if (existing && (existing.sessionId !== context.sessionId || existing.runId !== context.runId)) {
      return {
        outcome: "block",
        reason: "This project is active in another Chat",
      };
    }
    coordinator.runLeaseGeneration += 1;
    runLeases.set(projectId, {
      projectId,
      agentId: context.agentId,
      sessionKey: context.sessionKey,
      sessionId: context.sessionId,
      runId: context.runId,
      generation: coordinator.runLeaseGeneration,
    });
    return undefined;
  });
  api.on("agent_end", (_event, context) => {
    for (const [projectId, lease] of runLeases) {
      if (
        lease.agentId === context?.agentId &&
        lease.sessionKey === context?.sessionKey &&
        lease.sessionId === context?.sessionId &&
        lease.runId === context?.runId
      ) {
        runLeases.delete(projectId);
      }
    }
  });

  return Object.freeze({ service, admission, runLeases, capabilityReadiness });
}
