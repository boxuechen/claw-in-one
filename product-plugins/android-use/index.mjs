import { registerAndroidUsePlugin } from "./runtime.mjs";
import { appendAssistantMirrorMessageByIdentity } from "openclaw/plugin-sdk/session-transcript-runtime";

export default {
  id: "claw-in-one-android-use",
  name: "ClawInOne Android Use",
  description: "Controls the approved foreground Android app through the paired phone.",
  register(api) {
    registerAndroidUsePlugin(api, { publishControlNotice: appendAssistantMirrorMessageByIdentity });
  },
};
