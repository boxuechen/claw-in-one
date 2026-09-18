import { registerVScreenFoundationPlugin } from "./runtime.mjs";

export default function register(api) {
  registerVScreenFoundationPlugin(api, {
    producerBindings: [
      {
        id: "claw-in-one-android-vscreen-producer",
        pluginId: "claw-in-one-android-developer-bridge",
        assignmentPluginIds: [
          "claw-in-one-android-developer-bridge",
          "claw-in-one-android-use",
        ],
        methods: {
          ensure: "claw.androidVScreen.ensure",
          workload: "claw.androidVScreen.workload",
          presented: "claw.androidVScreen.presented",
          close: "claw.androidVScreen.close",
          probe: "claw.androidVScreen.probe",
        },
      },
    ],
  });
}
