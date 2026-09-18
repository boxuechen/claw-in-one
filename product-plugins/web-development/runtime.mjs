import path from "node:path";
import {createWebProjectAuthorizationBroker} from "./authorization.mjs";
import {
  WEB_DEVELOPMENT_PLUGIN_ID,
  WEB_PROJECT_RESULT_METHOD,
  createWebProjectService,
  parseOpenResultParams,
} from "./project-service.mjs";
import {WebProjectResultStore} from "./result-store.mjs";
import {
  WEB_PROJECT_PARAMETERS,
  WEB_PROJECT_TOOL_PROTOCOL_VERSION,
  WEB_PROJECT_TOOLS,
  parseWebProjectParams,
} from "./tool-protocol.mjs";

const PROCESS_RUNTIME_KEY = Symbol.for("io.github.boxuechen.clawinone.web-development.process-runtimes.v1");
const runtimes = globalThis[PROCESS_RUNTIME_KEY] instanceof Map ? globalThis[PROCESS_RUNTIME_KEY] : new Map();
globalThis[PROCESS_RUNTIME_KEY] = runtimes;

function runtimeKey(api, dependencies) {
  if (dependencies.runtimeKey) return `explicit:${dependencies.runtimeKey}`;
  const resolve = api?.runtime?.state?.resolveStateDir;
  return typeof resolve === "function" ? `state:${path.resolve(resolve(process.env))}` : null;
}

function processRuntime(api, dependencies) {
  const key = runtimeKey(api, dependencies);
  if (key && runtimes.has(key) && !runtimes.get(key).authorization.closed) return runtimes.get(key);
  const runtime = {
    authorization: dependencies.authorization ?? createWebProjectAuthorizationBroker(dependencies),
    service: dependencies.service ?? createWebProjectService(dependencies),
    results: dependencies.results ?? new WebProjectResultStore(dependencies),
  };
  if (key) runtimes.set(key, runtime);
  return runtime;
}

function result(payload) {
  return {content: [{type: "text", text: JSON.stringify(payload)}], details: payload};
}

function createTool(context, runtime, toolName) {
  const labels = {
    [WEB_PROJECT_TOOLS.build]: ["Build Web Project", "Build the current Project with the qualified Web Development profile."],
    [WEB_PROJECT_TOOLS.serve]: ["Serve Web Project", "Start or replace the current Project production server and publish its task-owned Android reverse result."],
    [WEB_PROJECT_TOOLS.stop]: ["Stop Web Project", "Stop only the current Project server and matching Android reverse mapping."],
  };
  if (context?.sandboxed === true || !labels[toolName]) return null;
  return {
    name: toolName,
    label: labels[toolName][0],
    description: `${labels[toolName][1]} Accepts no path, command, host or port.`,
    parameters: WEB_PROJECT_PARAMETERS,
    async execute(toolCallId, rawParams, signal) {
      const params = parseWebProjectParams(rawParams);
      const authorization = runtime.authorization.consumeAuthorization(params.authorizationNonce, {
        toolName,
        toolCallId: toolCallId?.trim(),
        context,
      });
      return authorization.run(signal, async (authorizedSignal, admission) => {
        if (toolName === WEB_PROJECT_TOOLS.build) {
          const built = await runtime.service.build(admission, authorizedSignal);
          return result({
            protocolVersion: WEB_PROJECT_TOOL_PROTOCOL_VERSION,
            operation: "build",
            status: "built",
            projectId: admission.projectId,
            profileId: built.metadata.profileId,
            profileGeneration: built.metadata.profileGeneration,
            artifact: built.metadata.artifact,
          });
        }
        if (toolName === WEB_PROJECT_TOOLS.serve) {
          const ready = await runtime.service.serve(admission, authorizedSignal);
          return result({
            protocolVersion: WEB_PROJECT_TOOL_PROTOCOL_VERSION,
            operation: "serve",
            status: "ready",
            resultId: ready.resultId,
            projectId: ready.projectId,
            generation: ready.generation,
            targetId: ready.targetId,
            url: ready.url,
            appName: ready.appName,
          });
        }
        const stopped = await runtime.service.stop(admission);
        return result({protocolVersion: WEB_PROJECT_TOOL_PROTOCOL_VERSION, operation: "stop", ...stopped});
      });
    },
  };
}

export function registerWebDevelopmentPlugin(api, dependencies = {}) {
  const runtime = processRuntime(api, dependencies);
  api.registerService({
    id: WEB_DEVELOPMENT_PLUGIN_ID,
    async start() {},
    async stop() {
      await runtime.service.close();
      runtime.authorization.close();
    },
  });
  for (const name of Object.values(WEB_PROJECT_TOOLS)) {
    api.registerTool(context => createTool(context, runtime, name), {name});
  }
  api.on("before_tool_call", (event, context) => {
    const decision = runtime.authorization.beforeToolCall(event, context);
    if (decision?.block !== true) runtime.results.claimToolCall(event, context);
    return decision;
  }, {matcher: Object.values(WEB_PROJECT_TOOLS)});
  api.on("agent_end", (event, context) => {
    runtime.authorization.endRun(event, context);
    runtime.results.clearRun(event?.runId ?? context?.runId);
  });
  api.agent.events.registerAgentEventSubscription({
    id: "web-project-results",
    description: "Publish exact ready Web Project results to ClawInOne clients",
    streams: ["tool", "lifecycle"],
    async handle(event) {
      if (event.stream === "lifecycle") {
        if (["end", "error"].includes(event.data?.phase)) runtime.results.clearRun(event.runId);
        return;
      }
      const publication = runtime.results.eventFromToolResult(event);
      if (!publication) return;
      const emitted = api.agent.events.emitAgentEvent(publication);
      if (emitted?.emitted !== true) api.logger.warn("Web Project result publisher is unavailable");
    },
  });
  api.registerGatewayMethod(WEB_PROJECT_RESULT_METHOD, async ({params, respond}) => {
    try { respond(true, runtime.service.openResult(parseOpenResultParams(params))); }
    catch (error) { respond(false, undefined, {code: "WEB_PROJECT_RESULT_STALE", message: error instanceof Error ? error.message : "Web Project result is unavailable"}); }
  }, {scope: "operator.read", profileAccess: "required"});
  return runtime;
}
