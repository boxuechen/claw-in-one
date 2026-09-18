import assert from "node:assert/strict";
import fs from "node:fs";
import path from "node:path";
import test from "node:test";
import {fileURLToPath} from "node:url";

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");
const skills = [
  ["android-developer-bridge", "android-development", "CLAW_IN_ONE_ANDROID_PROFILE"],
  ["android-developer-bridge", "android-native-development", "CLAW_IN_ONE_ANDROID_NATIVE_PROFILE"],
  ["android-developer-bridge", "flutter-development", "CLAW_IN_ONE_FLUTTER_PROFILE"],
  ["android-developer-bridge", "godot-android-development", "CLAW_IN_ONE_GODOT_ANDROID_PROFILE"],
  ["android-developer-bridge", "react-native-development", "CLAW_IN_ONE_REACT_NATIVE_PROFILE"],
  ["web-development", "web-development", "CLAW_IN_ONE_WEB_PROFILE"],
];

test("each development workflow has one compact profile-gated Skill", () => {
  for (const [plugin, reference, readinessEnv] of skills) {
    const directory = path.join(root, "product-plugins", plugin, "skills", reference);
    const entries = fs.readdirSync(directory);
    assert.deepEqual(entries, ["SKILL.md"], reference);

    const contract = fs.readFileSync(path.join(directory, "SKILL.md"), "utf8");
    assert.match(contract, new RegExp(`^---\\nname: ${reference}\\n`, "u"), reference);
    assert.match(contract, new RegExp(`- ${readinessEnv}\\n`, "u"), reference);
    assert.match(contract, /Work only in the current ClawInOne Project\./u, reference);
    assert.match(contract, /The Supervisor owns the qualified/u, reference);
    assert.match(contract, /VScreen/u, reference);
    assert.match(contract, /Android\s+Use/u, reference);
  }
});

test("VScreen and Android Use remain Tool capabilities without activation Skills", () => {
  for (const plugin of ["vscreen-foundation", "android-use"]) {
    assert.equal(fs.existsSync(path.join(root, "product-plugins", plugin, "skills")), false, plugin);
  }
});
