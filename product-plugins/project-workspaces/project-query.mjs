import fs from "node:fs/promises";
import { constants } from "node:fs";
import path from "node:path";

export const PROJECT_QUERY_TOOL = "project_query";
const SKIP = new Set([".git", ".gradle", "build", "node_modules"]);
const MAX_VISITED = 2000;
const MAX_FILE_BYTES = 64 * 1024;
const MAX_SEARCH_BYTES = 1024 * 1024;

function inside(root, file) {
  const relative = path.relative(root, file);
  return relative === "" || (relative !== ".." && !relative.startsWith(`..${path.sep}`) && !path.isAbsolute(relative));
}

// Resolve every component without following workspace symlinks. Search also checks
// the opened file identity; on Linux the descriptor must still be inside the root.
async function checkedPath(root, requested) {
  const file = path.resolve(root, requested);
  if (!inside(root, file)) throw new Error("Project queries must stay inside the current workspace");
  let current = root;
  for (const part of path.relative(root, file).split(path.sep).filter(Boolean)) {
    current = path.join(current, part);
    if ((await fs.lstat(current)).isSymbolicLink()) throw new Error("Project queries do not follow symbolic links");
  }
  if (!inside(root, await fs.realpath(file))) throw new Error("Project query path changed outside the workspace");
  return file;
}

export function createProjectQueryTool(context, expectedWorkspace) {
  const workspace = context?.fsPolicy?.root?.trim() || context?.workspaceDir?.trim();
  if (
    !workspace ||
    !expectedWorkspace ||
    path.resolve(workspace) !== path.resolve(expectedWorkspace) ||
    context?.sandboxed === true
  ) {
    return null;
  }
  return {
    name: PROJECT_QUERY_TOOL,
    label: "Explore project",
    description: "Read-only workspace discovery without shell approval. List a directory, find files by literal name substring, or search source text by literal substring. Paths are workspace-relative; no shell, regex, writes or symlink traversal. Use read for file contents. Build/cache folders are skipped during recursive discovery; query them directly when needed.",
    parameters: {
      type: "object", additionalProperties: false,
      properties: {
        operation: { type: "string", enum: ["list", "find", "search"] },
        path: { type: "string", description: "Workspace-relative directory; defaults to ." },
        query: { type: "string", description: "Literal filename substring for find, or text for search" },
        limit: { type: "integer", minimum: 1, maximum: 100 },
      },
      required: ["operation"],
    },
    async execute(_id, params, signal) {
      if (!params || Object.keys(params).some(k => !["operation", "path", "query", "limit"].includes(k)) ||
          !["list", "find", "search"].includes(params.operation) ||
          (params.path !== undefined && (typeof params.path !== "string" || params.path.length > 2048)) ||
          (params.query !== undefined && (typeof params.query !== "string" || params.query.length > 256)) ||
          (params.operation !== "list" && !params.query?.trim()) ||
          (params.limit !== undefined && (!Number.isInteger(params.limit) || params.limit < 1 || params.limit > 100))) {
        throw new Error("Use list, find or search with a directory, a literal query and limit 1–100");
      }
      signal?.throwIfAborted();
      const root = await fs.realpath(workspace);
      const directory = await checkedPath(root, params.path ?? ".");
      const limit = params.limit ?? 50;
      const matches = [];
      const queue = [directory];
      let visited = 0, searchedBytes = 0, truncated = false;
      outer: while (queue.length) {
        signal?.throwIfAborted();
        const next = await checkedPath(root, queue.shift());
        const dir = await fs.opendir(next);
        try {
          // Check the directory again before consuming entries if its path moved.
          await checkedPath(root, next);
          for await (const entry of dir) {
            signal?.throwIfAborted();
            if (++visited > MAX_VISITED) { truncated = true; break outer; }
            if (entry.isSymbolicLink()) continue;
            const file = path.join(next, entry.name);
            const relative = path.relative(root, file);
            if (params.operation === "list") {
              matches.push({ path: relative, type: entry.isDirectory() ? "directory" : entry.isFile() ? "file" : "other" });
            } else if (entry.isDirectory()) {
              if (!SKIP.has(entry.name)) queue.push(file);
            } else if (entry.isFile()) {
              if (params.operation === "find" && entry.name.includes(params.query)) matches.push({ path: relative });
              if (params.operation === "search") {
                await checkedPath(root, file);
                const before = await fs.lstat(file);
                if (!before.isFile() || before.nlink !== 1 || before.size > MAX_FILE_BYTES) continue;
                if (searchedBytes + before.size > MAX_SEARCH_BYTES) { truncated = true; break outer; }
                const handle = await fs.open(file, constants.O_RDONLY | constants.O_NOFOLLOW | constants.O_NONBLOCK);
                try {
                  const opened = await handle.stat();
                  if (!opened.isFile() || opened.nlink !== 1 || opened.dev !== before.dev || opened.ino !== before.ino) throw new Error("Project file changed during query");
                  if (process.platform === "linux" && !inside(root, await fs.realpath(`/proc/self/fd/${handle.fd}`))) throw new Error("Project file moved outside the workspace");
                  const buffer = Buffer.alloc(MAX_FILE_BYTES + 1);
                  const { bytesRead } = await handle.read(buffer, 0, buffer.length, 0);
                  searchedBytes += bytesRead;
                  if (bytesRead > MAX_FILE_BYTES || buffer.subarray(0, bytesRead).includes(0)) continue;
                  const lines = buffer.subarray(0, bytesRead).toString("utf8").split(/\r?\n/);
                  for (let i = 0; i < lines.length; i++) {
                    if (lines[i].includes(params.query)) matches.push({ path: relative, line: i + 1, text: lines[i].slice(0, 400) });
                    if (matches.length >= limit) { truncated = true; break outer; }
                  }
                } finally { await handle.close(); }
              }
            }
            if (matches.length >= limit) { truncated = true; break outer; }
          }
        } finally { await dir.close().catch(error => { if (error.code !== "ERR_DIR_CLOSED") throw error; }); }
      }
      signal?.throwIfAborted();
      const result = { operation: params.operation, path: path.relative(root, directory) || ".", matches, truncated };
      return { content: [{ type: "text", text: JSON.stringify(result) }], details: result };
    },
  };
}
