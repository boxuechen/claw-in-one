import { createHash } from "node:crypto";

function digest(parts) {
  const hash = createHash("sha256");
  for (const part of parts) hash.update(part).update("\0");
  return hash.digest("hex");
}

export function androidAppOwnerFor(context) {
  const sessionKey = context?.sessionKey?.trim();
  if (!sessionKey) return null;
  return {
    ownerKey: digest([sessionKey, context?.sessionId?.trim() ?? ""]),
  };
}

export function sameDeviceBinding(left, right) {
  return left.generation === right.generation && left.target.id === right.target.id;
}

export function assertDeviceBinding(expected, actual) {
  if (!sameDeviceBinding(expected, actual)) {
    throw new Error("The verified Android phone connection changed; inspect the APK again");
  }
}
