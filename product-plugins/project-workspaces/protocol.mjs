export const PROJECT_WORKSPACE_PLUGIN_ID = "claw-in-one-project-workspaces";

export const PROJECT_WORKSPACE_METHODS = Object.freeze({
  create: "claw.projects.create",
  read: "claw.projects.read",
  catalog: "claw.projects.catalog",
  capabilities: "claw.projects.capabilities",
  runLeaseStatus: "claw.projects.run-lease.status",
});

export const ProjectWorkspaceErrorCode = Object.freeze({
  invalidRequest: "invalid_request",
  invalidName: "invalid_name",
  nameConflict: "name_conflict",
  intentConflict: "intent_conflict",
  invalidRoot: "invalid_root",
  unavailable: "unavailable",
});

export class ProjectWorkspaceError extends Error {
  constructor(code, message, options = {}) {
    super(message);
    this.name = "ProjectWorkspaceError";
    this.code = code;
    this.details = options.details;
    this.retryable = options.retryable === true;
  }
}

const OPAQUE_ID = /^[A-Za-z0-9][A-Za-z0-9._:-]{7,127}$/u;
const CONTROL_OR_SEPARATOR = /[\u0000-\u001f\u007f/\\]/u;
export const PROJECT_NAME_MAX_CODE_POINTS = 80;

function requiredOpaqueId(value, field) {
  if (typeof value !== "string" || !OPAQUE_ID.test(value.trim())) {
    throw new ProjectWorkspaceError(
      ProjectWorkspaceErrorCode.invalidRequest,
      `${field} must be an opaque identifier`,
    );
  }
  return value.trim();
}

function requiredProjectId(value) {
  if (typeof value !== "string" || value.trim().length === 0) {
    throw new ProjectWorkspaceError(
      ProjectWorkspaceErrorCode.invalidRequest,
      "projectId must be a non-empty string",
    );
  }
  return value.trim();
}

export function normalizeProjectDisplayName(value) {
  if (typeof value !== "string") {
    throw new ProjectWorkspaceError(ProjectWorkspaceErrorCode.invalidName, "Project name is required");
  }
  const normalized = value.trim().normalize("NFC");
  if (
    normalized.length === 0 ||
    [...normalized].length > PROJECT_NAME_MAX_CODE_POINTS ||
    CONTROL_OR_SEPARATOR.test(normalized)
  ) {
    throw new ProjectWorkspaceError(
      ProjectWorkspaceErrorCode.invalidName,
      "Project name must be 1-80 characters and cannot contain control characters or path separators",
    );
  }
  return normalized;
}

export function projectNameKey(value) {
  return normalizeProjectDisplayName(value).toLocaleLowerCase("und");
}

export function allocateDefaultProjectName(existingNames) {
  const keys = new Set([...existingNames].map(projectNameKey));
  for (let suffix = 1; suffix < Number.MAX_SAFE_INTEGER; suffix += 1) {
    const candidate = suffix === 1 ? "New Project" : `New Project ${suffix}`;
    if (!keys.has(projectNameKey(candidate))) return candidate;
  }
  throw new ProjectWorkspaceError(
    ProjectWorkspaceErrorCode.unavailable,
    "No default Project name is available",
    { retryable: true },
  );
}

export function parseCreateProjectParams(value) {
  const params = value && typeof value === "object" && !Array.isArray(value) ? value : null;
  if (!params || !["default", "custom"].includes(params.naming)) {
    throw new ProjectWorkspaceError(
      ProjectWorkspaceErrorCode.invalidRequest,
      "Project creation requires default or custom naming",
    );
  }
  const allowed = new Set(["intentId", "nameRevision", "naming", "requestedName"]);
  if (Object.keys(params).some((field) => !allowed.has(field))) {
    throw new ProjectWorkspaceError(
      ProjectWorkspaceErrorCode.invalidRequest,
      "Project creation contains an unsupported field",
    );
  }
  return Object.freeze({
    intentId: requiredOpaqueId(params.intentId, "intentId"),
    nameRevision: requiredOpaqueId(params.nameRevision, "nameRevision"),
    naming: params.naming,
    requestedName: normalizeProjectDisplayName(params.requestedName),
  });
}

export function parseReadProjectParams(value) {
  const params = value && typeof value === "object" && !Array.isArray(value) ? value : null;
  if (!params) {
    throw new ProjectWorkspaceError(ProjectWorkspaceErrorCode.invalidRequest, "Project readback requires params");
  }
  return Object.freeze({ intentId: requiredOpaqueId(params.intentId, "intentId") });
}

export function parseProjectRunLeaseStatusParams(value) {
  const params = value && typeof value === "object" && !Array.isArray(value) ? value : null;
  if (!params) {
    throw new ProjectWorkspaceError(ProjectWorkspaceErrorCode.invalidRequest, "Project run lease status requires params");
  }
  return Object.freeze({ projectId: requiredProjectId(params.projectId) });
}

export function parseCapabilitySnapshotParams(value) {
  const params = value && typeof value === "object" && !Array.isArray(value) ? value : null;
  if (!params || Object.keys(params).length !== 0) {
    throw new ProjectWorkspaceError(
      ProjectWorkspaceErrorCode.invalidRequest,
      "Capability snapshot requires empty params",
    );
  }
  return Object.freeze({});
}

export function gatewayErrorForProject(error) {
  const known = error instanceof ProjectWorkspaceError;
  return {
    code: known ? error.code : ProjectWorkspaceErrorCode.unavailable,
    message: known ? error.message : "Project workspace operation failed",
    ...(known && error.details !== undefined ? { details: error.details } : {}),
    retryable: known ? error.retryable : true,
  };
}
