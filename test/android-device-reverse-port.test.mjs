import assert from "node:assert/strict";
import test from "node:test";
import {createAndroidDeviceReversePort} from "../product-plugins/android-developer-bridge/bridge/reverse-port.mjs";

test("Android Device Bridge owns exact task reverse mappings and stale cleanup is inert", async () => {
  const calls = [];
  const bridge = {
    async withDevice(purpose, action) {
      calls.push(purpose);
      return action({
        adbSerial: "phone-1",
        binding: {generation: "binding-1", target: {id: "a".repeat(64)}},
        async run(args, options) {
          calls.push({args, options});
          return {code: 0};
        },
      });
    },
  };
  const reverse = createAndroidDeviceReversePort(() => bridge);
  const first = await reverse.publish({
    ownerId: "claw-in-one-web-development",
    projectId: "project-1",
    hostPort: 4173,
    generation: 1,
  });
  const devicePort = Number(new URL(first.deviceUrl).port);
  assert(devicePort >= 38000 && devicePort <= 38999);
  assert.deepEqual(calls[1].args, ["-s", "phone-1", "reverse", `tcp:${devicePort}`, "tcp:4173"]);

  const second = await reverse.publish({
    ownerId: "claw-in-one-web-development",
    projectId: "project-1",
    hostPort: 5173,
    generation: 2,
  });
  assert.equal(second.deviceUrl, first.deviceUrl);
  assert.deepEqual(await reverse.remove({ownerId: "claw-in-one-web-development", projectId: "project-1", generation: 1}), {status: "unchanged"});
  assert.equal(calls.length, 4);
  assert.deepEqual(await reverse.remove({ownerId: "claw-in-one-web-development", projectId: "project-1", generation: 2}), {
    status: "removed",
    cleanup: "complete",
  });
  assert.deepEqual(calls.at(-1).args, ["-s", "phone-1", "reverse", "--remove", `tcp:${devicePort}`]);
  assert.deepEqual(calls.at(-1).options, {allowFailure: true});
  await assert.rejects(
    reverse.remove({ownerId: "bad", projectId: "project-1", generation: 2, port: 9999}),
    /removal is invalid/,
  );
});
