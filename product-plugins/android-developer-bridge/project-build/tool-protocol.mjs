export const ANDROID_PROJECT_BUILD_TOOL = "android_project_build";
export const ANDROID_PROJECT_BUILD_PROTOCOL_VERSION = 1;
export const ANDROID_PROJECT_BUILD_AUTHORIZATION_NONCE = "androidProjectBuildAuthorizationNonce";

export const ANDROID_PROJECT_BUILD_PARAMETERS = Object.freeze({
  type: "object",
  additionalProperties: false,
  properties: {
    [ANDROID_PROJECT_BUILD_AUTHORIZATION_NONCE]: {
      type: "string",
      description: "Internal. Injected by host authorization; never set this manually.",
    },
  },
});

export function parseAndroidProjectBuildParams(value) {
  if (
    !value ||
    typeof value !== "object" ||
    Array.isArray(value) ||
    Object.keys(value).some((key) => key !== ANDROID_PROJECT_BUILD_AUTHORIZATION_NONCE) ||
    (value[ANDROID_PROJECT_BUILD_AUTHORIZATION_NONCE] !== undefined &&
      typeof value[ANDROID_PROJECT_BUILD_AUTHORIZATION_NONCE] !== "string")
  ) {
    throw new Error("android_project_build does not accept parameters");
  }
  return Object.freeze({
    authorizationNonce: value[ANDROID_PROJECT_BUILD_AUTHORIZATION_NONCE],
  });
}
