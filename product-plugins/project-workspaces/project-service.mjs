import { randomUUID } from "node:crypto";
import fs from "node:fs/promises";
import os from "node:os";
import path from "node:path";
import {
  allocateDefaultProjectName,
  normalizeProjectDisplayName,
  projectNameKey,
  ProjectWorkspaceError,
  ProjectWorkspaceErrorCode,
} from "./protocol.mjs";

const STORE_VERSION = 2;
const DEFAULT_BRANCH = "main";

function plainRecord(value) {
  return value && typeof value === "object" && !Array.isArray(value) ? value : null;
}

function normalizedProject(value) {
  const project = plainRecord(value);
  if (
    typeof project?.id !== "string" ||
    typeof project.displayName !== "string" ||
    typeof project.repoRoot !== "string" ||
    typeof project.source !== "string"
  ) {
    throw new ProjectWorkspaceError(
      ProjectWorkspaceErrorCode.unavailable,
      "OpenClaw returned an invalid Project record",
      { retryable: true },
    );
  }
  return Object.freeze({
    id: project.id,
    displayName: project.displayName,
    repoRoot: path.resolve(project.repoRoot),
    source: project.source,
    ...(typeof project.agentId === "string" ? { agentId: project.agentId } : {}),
  });
}

function normalizedProjectSummary(value) {
  const project = plainRecord(value);
  if (
    typeof project?.id !== "string" ||
    typeof project.displayName !== "string" ||
    typeof project.source !== "string"
  ) {
    throw new ProjectWorkspaceError(
      ProjectWorkspaceErrorCode.unavailable,
      "OpenClaw returned an invalid Project summary",
      { retryable: true },
    );
  }
  return Object.freeze({
    id: project.id,
    displayName: project.displayName,
    source: project.source,
    ...(typeof project.repoRoot === "string" ? { repoRoot: path.resolve(project.repoRoot) } : {}),
    ...(typeof project.agentId === "string" ? { agentId: project.agentId } : {}),
  });
}

function attemptResult(attempt) {
  return Object.freeze({
    intentId: attempt.intentId,
    nameRevision: attempt.nameRevision,
    phase: attempt.phase,
    naming: attempt.naming,
    displayName: attempt.displayName,
    repoRoot: attempt.repoRoot,
    ...(attempt.project ? { project: normalizedProject(attempt.project) } : {}),
  });
}

async function fileExists(file) {
  try {
    await fs.lstat(file);
    return true;
  } catch (error) {
    if (error?.code === "ENOENT") return false;
    throw error;
  }
}

export function createProjectWorkspaceService(options = {}) {
  const stateDir = path.resolve(options.stateDir ?? path.join(os.homedir(), ".openclaw", "claw-in-one-projects"));
  const projectsRoot = path.resolve(options.projectsRoot ?? path.join(os.homedir(), "ClawInOneProjects"));
  const stateFile = path.join(stateDir, "attempts.v2.json");
  const runCommand = options.runCommand;
  const listProjects = options.listProjects;
  const registerProject = options.registerProject;
  const createId = options.randomUUID ?? randomUUID;
  let lane = Promise.resolve();
  let loaded = false;
  let store = { version: STORE_VERSION, revision: 0, attempts: {}, sessionBindings: {} };

  if (typeof runCommand !== "function" || typeof listProjects !== "function" || typeof registerProject !== "function") {
    throw new Error("Project workspace service requires command and OpenClaw Project adapters");
  }

  function serialize(operation) {
    const next = lane.then(operation, operation);
    lane = next.catch(() => {});
    return next;
  }

  async function load(fresh = false) {
    if (loaded && !fresh) return;
    try {
      const parsed = JSON.parse(await fs.readFile(stateFile, "utf8"));
      if (parsed?.version !== STORE_VERSION || !plainRecord(parsed.attempts)) throw new Error("invalid store");
      store = parsed;
      store.sessionBindings = plainRecord(store.sessionBindings) ?? {};
      loaded = true;
    } catch (error) {
      if (error?.code === "ENOENT") {
        store = { version: STORE_VERSION, revision: 0, attempts: {}, sessionBindings: {} };
        loaded = true;
      } else {
        throw new ProjectWorkspaceError(
          ProjectWorkspaceErrorCode.unavailable,
          "Project creation journal is unavailable",
          { retryable: true },
        );
      }
    }
  }

  async function persist() {
    await fs.mkdir(stateDir, { recursive: true, mode: 0o700 });
    const temporary = `${stateFile}.${process.pid}.${createId()}.next`;
    await fs.writeFile(temporary, `${JSON.stringify(store)}\n`, { mode: 0o600 });
    await fs.rename(temporary, stateFile);
  }

  async function canonicalProjects() {
    const result = await listProjects();
    const projects = Array.isArray(result?.projects) ? result.projects : [];
    // projects.list deliberately redacts checkout paths at operator.read. Keep those
    // summaries as canonical registration evidence; the creation journal retains the
    // exact root returned by projects.register and never treats a Workspace row as managed.
    return projects.map(normalizedProjectSummary);
  }

  function matchingAttempt(existing, request) {
    return (
      existing.nameRevision === request.nameRevision &&
      existing.naming === request.naming &&
      existing.requestedName === request.requestedName
    );
  }

  async function reconcile(attempt, projects) {
    if (attempt.project) {
      const registered = projects.find((project) => project.id === attempt.project.id);
      if (!registered) return attempt;
      if (
        projectNameKey(registered.displayName) !== projectNameKey(attempt.displayName) ||
        (registered.repoRoot && registered.repoRoot !== path.resolve(attempt.repoRoot))
      ) {
        throw new ProjectWorkspaceError(ProjectWorkspaceErrorCode.unavailable, "Registered Project identity does not match its reservation");
      }
      return attempt;
    }
    const registered = projects.find((project) => project.repoRoot === path.resolve(attempt.repoRoot));
    if (!registered) return attempt;
    if (projectNameKey(registered.displayName) !== projectNameKey(attempt.displayName)) {
      throw new ProjectWorkspaceError(ProjectWorkspaceErrorCode.unavailable, "Registered Project identity does not match its reservation");
    }
    attempt.phase = "registered";
    attempt.project = { ...registered, repoRoot: path.resolve(attempt.repoRoot) };
    store.revision += 1;
    await persist();
    return attempt;
  }

  async function initializeRepository(attempt) {
    const resolvedRoot = path.resolve(attempt.repoRoot);
    const relative = path.relative(projectsRoot, resolvedRoot);
    if (!relative || relative.startsWith("..") || path.isAbsolute(relative)) {
      throw new ProjectWorkspaceError(ProjectWorkspaceErrorCode.invalidRoot, "Project root escaped its configured directory");
    }
    await fs.mkdir(projectsRoot, { recursive: true, mode: 0o700 });
    if (!(await fileExists(resolvedRoot))) await fs.mkdir(resolvedRoot, { recursive: false, mode: 0o700 });
    const rootStat = await fs.lstat(resolvedRoot);
    if (!rootStat.isDirectory() || rootStat.isSymbolicLink()) {
      throw new ProjectWorkspaceError(ProjectWorkspaceErrorCode.invalidRoot, "Reserved Project root is not a directory");
    }
    const marker = path.join(resolvedRoot, ".git");
    if (!(await fileExists(marker))) {
      await runCommand(["git", "init", "-b", DEFAULT_BRANCH], { cwd: resolvedRoot });
    }
    try {
      await runCommand(["git", "rev-parse", "--verify", "HEAD"], { cwd: resolvedRoot });
    } catch {
      await runCommand(["git", "commit", "--allow-empty", "-m", "Initialize ClawInOne project"], {
        cwd: resolvedRoot,
        env: {
          GIT_AUTHOR_NAME: "ClawInOne",
          GIT_AUTHOR_EMAIL: "clawinone@localhost",
          GIT_COMMITTER_NAME: "ClawInOne",
          GIT_COMMITTER_EMAIL: "clawinone@localhost",
        },
      });
    }
  }

  async function create(request) {
    return serialize(async () => {
      await load(true);
      const previous = store.attempts[request.intentId];
      if (previous && !matchingAttempt(previous, request)) {
        throw new ProjectWorkspaceError(
          ProjectWorkspaceErrorCode.intentConflict,
          "Project intent was already used with different input",
        );
      }
      const projects = await canonicalProjects();
      if (previous) {
        await reconcile(previous, projects);
        if (previous.phase === "registered") return attemptResult(previous);
        await initializeRepository(previous);
        const project = normalizedProject(await registerProject(previous.repoRoot, previous.displayName));
        if (
          project.repoRoot !== path.resolve(previous.repoRoot) ||
          projectNameKey(project.displayName) !== projectNameKey(previous.displayName)
        ) {
          throw new ProjectWorkspaceError(ProjectWorkspaceErrorCode.unavailable, "Registered Project identity does not match its reservation");
        }
        previous.phase = "registered";
        previous.project = project;
        store.revision += 1;
        await persist();
        return attemptResult(previous);
      }

      const reservedAttempts = Object.values(store.attempts).filter((attempt) => attempt.phase !== "failed");
      const existingNames = [
        ...projects.map((project) => project.displayName),
        ...reservedAttempts.map((attempt) => attempt.displayName),
      ];
      const displayName =
        request.naming === "default"
          ? allocateDefaultProjectName(existingNames)
          : normalizeProjectDisplayName(request.requestedName);
      if (request.naming === "custom" && new Set(existingNames.map(projectNameKey)).has(projectNameKey(displayName))) {
        throw new ProjectWorkspaceError(
          ProjectWorkspaceErrorCode.nameConflict,
          "Name already exists. Choose another.",
          { details: { name: displayName } },
        );
      }
      const rootId = createId().toLowerCase();
      if (!/^[a-f0-9-]{16,64}$/u.test(rootId)) {
        throw new ProjectWorkspaceError(ProjectWorkspaceErrorCode.unavailable, "Project root identity is unavailable");
      }
      const attempt = {
        intentId: request.intentId,
        nameRevision: request.nameRevision,
        naming: request.naming,
        requestedName: request.requestedName,
        displayName,
        repoRoot: path.join(projectsRoot, rootId),
        phase: "reserved",
      };
      store.attempts[request.intentId] = attempt;
      store.revision += 1;
      await persist();
      await initializeRepository(attempt);
      attempt.phase = "repository_ready";
      store.revision += 1;
      await persist();
      const project = normalizedProject(await registerProject(attempt.repoRoot, displayName));
      if (
        project.repoRoot !== path.resolve(attempt.repoRoot) ||
        projectNameKey(project.displayName) !== projectNameKey(displayName)
      ) {
        throw new ProjectWorkspaceError(ProjectWorkspaceErrorCode.unavailable, "Registered Project identity does not match its reservation");
      }
      attempt.phase = "registered";
      attempt.project = project;
      store.revision += 1;
      await persist();
      return attemptResult(attempt);
    });
  }

  async function read(intentId) {
    return serialize(async () => {
      await load(true);
      const attempt = store.attempts[intentId];
      if (!attempt) return Object.freeze({ intentId, phase: "missing" });
      await reconcile(attempt, await canonicalProjects());
      return attemptResult(attempt);
    });
  }

  async function catalog() {
    return serialize(async () => {
      await load(true);
      const projects = await canonicalProjects();
      for (const attempt of Object.values(store.attempts)) await reconcile(attempt, projects);
      const managedProjects =
        Object.values(store.attempts)
          .filter((attempt) => attempt.phase === "registered" && attempt.project)
          .map((attempt) => {
            const canonical = projects.find((project) => project.id === attempt.project.id);
            if (!canonical) return null;
            return normalizedProject({
              ...canonical,
              repoRoot: attempt.repoRoot,
            });
          })
          .filter(Boolean);
      return Object.freeze({
        revision: store.revision,
        projects: managedProjects,
        sessionBindings: Object.freeze({ ...store.sessionBindings }),
      });
    });
  }

  async function bindSession(projectId, sessionKey, sessionId) {
    return serialize(async () => {
      await load(true);
      const binding = bindingForProject(projectId);
      if (!binding?.registered) return false;
      const previous = store.sessionBindings[sessionKey];
      if (previous && (previous.projectId !== projectId || previous.sessionId !== sessionId)) return false;
      if (!previous) {
        store.sessionBindings[sessionKey] = { projectId, sessionId };
        store.revision += 1;
        await persist();
      }
      return true;
    });
  }

  function bindingForProject(projectId) {
    const attempt = Object.values(store.attempts).find((candidate) => candidate.project?.id === projectId);
    if (!attempt) return null;
    return Object.freeze({
      projectId,
      repoRoot: attempt.repoRoot,
      registered: attempt.phase === "registered",
    });
  }

  return Object.freeze({ create, read, catalog, bindSession, binding: bindingForProject, projectsRoot, stateFile });
}
