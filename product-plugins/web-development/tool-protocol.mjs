export const WEB_PROJECT_TOOL_PROTOCOL_VERSION = 1;
export const WEB_PROJECT_AUTHORIZATION_NONCE = "webProjectAuthorizationNonce";
export const WEB_PROJECT_TOOLS = Object.freeze({
  build: "web_project_build",
  serve: "web_project_serve",
  stop: "web_project_stop",
});

export const WEB_PROJECT_PARAMETERS = Object.freeze({
  type: "object",
  additionalProperties: false,
  properties: {
    [WEB_PROJECT_AUTHORIZATION_NONCE]: {
      type: "string",
      description: "Internal. Injected by host authorization; never set this manually.",
    },
  },
});

export function parseWebProjectParams(value) {
  if (
    !value || typeof value !== "object" || Array.isArray(value) ||
    Object.keys(value).some(key => key !== WEB_PROJECT_AUTHORIZATION_NONCE) ||
    (value[WEB_PROJECT_AUTHORIZATION_NONCE] !== undefined &&
      typeof value[WEB_PROJECT_AUTHORIZATION_NONCE] !== "string")
  ) throw new Error("Web Project tools do not accept parameters");
  return Object.freeze({authorizationNonce: value[WEB_PROJECT_AUTHORIZATION_NONCE]});
}
