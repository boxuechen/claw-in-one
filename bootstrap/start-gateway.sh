#!/bin/bash
set -euo pipefail

if ! declare -F report_status >/dev/null; then
  printf 'start-gateway.sh must be sourced by the ClawInOne Supervisor.\n' >&2
  return 30
fi

# shellcheck disable=SC1090
source "$CLAW_IN_ONE_SHARED_DIR/supervision-policy.sh"
# shellcheck disable=SC1090
source "$CLAW_IN_ONE_SHARED_DIR/gateway-config-cache.sh"

install_root=${CLAW_IN_ONE_INSTALL_ROOT:-"$HOME/.local/share/claw-in-one"}
runtime_env="$install_root/runtime.env"
capabilities_env="$install_root/capabilities.env"
gateway_port=${CLAW_IN_ONE_GATEWAY_PORT:-18789}
gateway_dir="$install_root/gateway"
gateway_log="$install_root/logs/gateway.log"
gateway_pid_file="$gateway_dir/gateway.pid"
gateway_token_file="$gateway_dir/gateway.token"
gateway_lock_file="$gateway_dir/supervisor.lock"
gateway_state_file="$gateway_dir/supervisor.state"
gateway_config_stamp_file="$gateway_dir/product-config.stamp"
gateway_config_revision=7
gateway_started_here=false
gateway_lock_held=false
gateway_shutdown_requested=false
gateway_restart_count=0
gateway_started_at=0
gateway_pid=

write_gateway_state() {
  if [[ "$gateway_lock_held" != true ]]; then
    return 0
  fi
  local status=$1
  local last_exit=${2:-}
  local temporary="$gateway_state_file.next.$$"
  {
    printf 'status=%s\n' "$status"
    printf 'pid=%s\n' "$gateway_pid"
    printf 'restart_count=%s\n' "$gateway_restart_count"
    printf 'last_exit=%s\n' "$last_exit"
    printf 'updated_at=%s\n' "$(date +%s)"
  } > "$temporary"
  chmod 600 "$temporary"
  mv -f "$temporary" "$gateway_state_file"
}

claw_in_one_stop_gateway() {
  gateway_shutdown_requested=true
  write_gateway_state stopping
  if [[ "$gateway_started_here" == true && -n "$gateway_pid" && -d "/proc/$gateway_pid" ]]; then
    kill -TERM "$gateway_pid" 2>/dev/null || true
    wait "$gateway_pid" 2>/dev/null || true
  fi
  if
    [[ "$gateway_started_here" == true ]] &&
      [[ -n "$gateway_pid" && -r "$gateway_pid_file" ]] &&
      [[ $(tr -cd '0-9' < "$gateway_pid_file") == "$gateway_pid" ]]
  then
    rm -f "$gateway_pid_file"
  fi
  gateway_pid=
  write_gateway_state stopped
}

claw_in_one_wait_gateway() {
  if [[ "$gateway_lock_held" != true ]]; then
    return 0
  fi
  local exit_code
  local uptime
  local restart_delay
  while true; do
    exit_code=0
    if [[ "$gateway_started_here" == true ]]; then
      wait "$gateway_pid" || exit_code=$?
    else
      while [[ -d "/proc/$gateway_pid" && "$gateway_shutdown_requested" != true ]]; do
        sleep 2
      done
      exit_code=1
    fi
    if [[ -r "$gateway_pid_file" && $(tr -cd '0-9' < "$gateway_pid_file") == "$gateway_pid" ]]; then
      rm -f "$gateway_pid_file"
    fi
    if [[ "$gateway_shutdown_requested" == true ]]; then
      gateway_pid=
      write_gateway_state stopped "$exit_code"
      return 0
    fi

    uptime=$((SECONDS - gateway_started_at))
    if (( uptime >= 60 )); then
      gateway_restart_count=0
    fi
    gateway_restart_count=$((gateway_restart_count + 1))
    write_gateway_state crashed "$exit_code"
    if ! claw_in_one_restart_allowed "$gateway_restart_count"; then
      printf 'Gateway restart limit reached; see %s.\n' "$gateway_log" >&2
      write_gateway_state failed "$exit_code"
      if (( exit_code == 0 )); then
        exit_code=1
      fi
      return "$exit_code"
    fi

    restart_delay=$(claw_in_one_restart_delay "$gateway_restart_count")
    sleep "$restart_delay"
    report_status gateway_starting
    start_gateway_process
    if wait_for_gateway_probe healthz 30; then
      report_status gateway_healthy
    fi
    if probe_gateway healthz && wait_for_gateway_probe readyz 30; then
      write_gateway_state running
      report_status gateway_ready
    else
      kill -TERM "$gateway_pid" 2>/dev/null || true
    fi
  done
}

[[ -r "$runtime_env" ]] || {
  printf 'OpenClaw runtime environment is missing: %s\n' "$runtime_env" >&2
  return 31
}
# shellcheck disable=SC1090
source "$runtime_env"
if [[ -r "$capabilities_env" ]]; then
  # shellcheck disable=SC1090
  source "$capabilities_env"
fi
openclaw_bin="$install_root/current/bin/openclaw"
node_bin="$install_root/runtimes/node-v$CLAW_IN_ONE_NODE_VERSION-linux-arm64/bin/node"
[[ -x "$openclaw_bin" && -x "$node_bin" ]] || {
  printf 'The pinned OpenClaw runtime is incomplete.\n' >&2
  return 32
}

mkdir -p "$gateway_dir" "$install_root/logs"
chmod 700 "$gateway_dir"
if ! command -v flock >/dev/null 2>&1; then
  printf 'Gateway supervision requires flock from util-linux.\n' >&2
  return 38
fi
exec 9>"$gateway_lock_file"
if flock -n 9; then
  gateway_lock_held=true
fi

if [[ ! -s "$gateway_token_file" ]]; then
  "$node_bin" - "$gateway_token_file" <<'NODE'
const crypto = require("node:crypto");
const fs = require("node:fs");

const destination = process.argv[2];
let handle;
try {
  handle = fs.openSync(destination, "wx", 0o600);
  fs.writeFileSync(handle, `${crypto.randomBytes(32).toString("hex")}\n`, "utf8");
  fs.fsyncSync(handle);
} catch (error) {
  if (error?.code !== "EEXIST") throw error;
} finally {
  if (handle !== undefined) fs.closeSync(handle);
}
NODE
fi
gateway_token=$(tr -d '\r\n' < "$gateway_token_file")
[[ "$gateway_token" =~ ^[0-9a-f]{64}$ ]] || {
  printf 'The persisted Gateway token is invalid.\n' >&2
  return 33
}
chmod 600 "$gateway_token_file"

run_openclaw() {
  OPENCLAW_GATEWAY_TOKEN="$gateway_token" \
    OPENCLAW_SUPERVISOR_MODE=external \
    PATH="$install_root/runtimes/node-v$CLAW_IN_ONE_NODE_VERSION-linux-arm64/bin:$PATH" \
    "$openclaw_bin" "$@"
}

merge_config_array() {
  local path=$1
  local value=$2
  local mode=$3
  local create_if_missing=$4
  local current
  local updated
  if ! current=$(run_openclaw config get "$path" --json 2>/dev/null); then
    if [[ "$create_if_missing" != true ]]; then
      return 0
    fi
    current='[]'
  fi
  updated=$(
    "$node_bin" -e '
const current = JSON.parse(process.argv[1]);
if (!Array.isArray(current) || !current.every((entry) => typeof entry === "string")) {
  throw new Error("expected a string array");
}
const value = process.argv[2];
const mode = process.argv[3];
const next = mode === "remove" ? current.filter((entry) => entry !== value) : [...new Set([...current, value])];
process.stdout.write(JSON.stringify(next));
' "$current" "$value" "$mode"
  )
  run_openclaw config set "$path" "$updated" --strict-json >> "$gateway_log" 2>&1
}

reconcile_product_plugin_paths() {
  local current
  local updated
  if ! current=$(run_openclaw config get plugins.load.paths --json 2>/dev/null); then
    current='[]'
  fi
  updated=$(
    "$node_bin" \
      "$CLAW_IN_ONE_SHARED_DIR/reconcile-plugin-paths.mjs" \
      "$current" \
      "$install_root" \
      "$CLAW_IN_ONE_SHARED_DIR"
  )
  run_openclaw config set plugins.load.paths "$updated" --strict-json >> "$gateway_log" 2>&1
}

reconcile_bundled_plugin_allowlist() {
  local current
  local updated
  if ! current=$(run_openclaw config get plugins.allow --json 2>/dev/null); then
    current='[]'
  fi
  updated=$(
    "$node_bin" \
      "$CLAW_IN_ONE_SHARED_DIR/reconcile-plugin-allowlist.mjs" \
      "$current" \
      "$install_root/current/lib/node_modules/openclaw/dist/extensions"
  )
  run_openclaw config set plugins.allow "$updated" --strict-json >> "$gateway_log" 2>&1
}

report_status gateway_configuring
if [[ "$gateway_lock_held" == true ]]; then
  # ClawInOne ships these signed first-party Plugins as part of its OpenClaw
  # host distribution. Install them into the pinned runtime's bundled tree
  # before any OpenClaw process loads Plugin code; the npm package remains the
  # platform baseline and only these exact product-owned slots are replaced.
  "$node_bin" \
    "$CLAW_IN_ONE_SHARED_DIR/install-bundled-product-plugins.mjs" \
    "$install_root/current/lib/node_modules/openclaw/dist/extensions" \
    "$CLAW_IN_ONE_SHARED_DIR" >> "$gateway_log" 2>&1
  openclaw_config_file=${OPENCLAW_CONFIG_PATH:-${OPENCLAW_STATE_DIR:-"$HOME/.openclaw"}/openclaw.json}
  # Product Plugins are host-bundled, never external path overrides. Reconcile
  # on every cold Gateway start so stale release/dev paths cannot take priority.
  reconcile_product_plugin_paths
  if ! gateway_config_cache_is_current "$openclaw_config_file" "$gateway_config_stamp_file" "$gateway_config_revision" "$CLAW_IN_ONE_OPENCLAW_VERSION"; then
    # Package replacement intentionally does not mutate operator state. The
    # pinned release's supported, non-interactive Doctor pass owns one-way
    # schema/config migration before any Gateway process can open that state.
    run_openclaw doctor --fix --non-interactive >> "$gateway_log" 2>&1
    run_openclaw config set gateway.mode local >> "$gateway_log" 2>&1
    run_openclaw config set gateway.bind lan >> "$gateway_log" 2>&1
    run_openclaw config set gateway.port "$gateway_port" --strict-json >> "$gateway_log" 2>&1
    run_openclaw config set gateway.auth.mode token >> "$gateway_log" 2>&1
    run_openclaw config set gateway.tls.enabled true --strict-json >> "$gateway_log" 2>&1
    run_openclaw config set gateway.tls.autoGenerate true --strict-json >> "$gateway_log" 2>&1
    # Keep the pinned release's bundled plugins eligible after the product
    # overlay; user deny entries still take precedence.
    reconcile_bundled_plugin_allowlist
    # Product Skills are ordinary OpenClaw extra-directory Skills. Keeping them
    # outside Plugins gives every Agent one shared default while allowing a
    # managed or workspace Skill with the same name to override it.
    merge_config_array skills.load.extraDirs "$CLAW_IN_ONE_SHARED_DIR/product-skills" add true
    merge_config_array plugins.allow claw-in-one-android-use add false
    merge_config_array plugins.deny claw-in-one-android-use remove false
    run_openclaw config set plugins.entries.claw-in-one-android-use.enabled true --strict-json >> "$gateway_log" 2>&1
    # Successful run completion releases control while retaining VScreen. Keep
    # lifecycle-hook conversation access explicit in product configuration.
    run_openclaw config set plugins.entries.claw-in-one-android-use.hooks.allowConversationAccess true --strict-json >> "$gateway_log" 2>&1
    merge_config_array plugins.allow claw-in-one-vscreen-foundation add false
    merge_config_array plugins.deny claw-in-one-vscreen-foundation remove false
    run_openclaw config set plugins.entries.claw-in-one-vscreen-foundation.enabled true --strict-json >> "$gateway_log" 2>&1
    merge_config_array plugins.allow claw-in-one-android-developer-bridge add false
    merge_config_array plugins.deny claw-in-one-android-developer-bridge remove false
    run_openclaw config set plugins.entries.claw-in-one-android-developer-bridge.enabled true --strict-json >> "$gateway_log" 2>&1
    # Only the Supervisor-owned Android AVF runtime may start the private ADB
    # server. Loading this Plugin in a desktop development Gateway stays inert.
    run_openclaw config set plugins.entries.claw-in-one-android-developer-bridge.config '{"runtime":"android-avf"}' --strict-json >> "$gateway_log" 2>&1
    # App Delivery needs conversation lifecycle access to retire exact run-bound receipts
    # and pending authorizations. Skill availability does not require prompt mutation.
    run_openclaw config set plugins.entries.claw-in-one-android-developer-bridge.hooks.allowConversationAccess true --strict-json >> "$gateway_log" 2>&1
    merge_config_array plugins.allow claw-in-one-web-development add false
    merge_config_array plugins.deny claw-in-one-web-development remove false
    run_openclaw config set plugins.entries.claw-in-one-web-development.enabled true --strict-json >> "$gateway_log" 2>&1
    # Web servers survive ordinary task completion; hooks only retire exact pending authorizations.
    run_openclaw config set plugins.entries.claw-in-one-web-development.hooks.allowConversationAccess true --strict-json >> "$gateway_log" 2>&1
    merge_config_array plugins.allow claw-in-one-project-workspaces add false
    merge_config_array plugins.deny claw-in-one-project-workspaces remove false
    run_openclaw config set plugins.entries.claw-in-one-project-workspaces.enabled true --strict-json >> "$gateway_log" 2>&1
    # Project writer ownership is enforced at before_agent_run and released at agent_end.
    run_openclaw config set plugins.entries.claw-in-one-project-workspaces.hooks.allowConversationAccess true --strict-json >> "$gateway_log" 2>&1
    merge_config_array tools.deny mobile_ui add true
    merge_config_array gateway.nodes.commands.allow claw.android_use add true
    gateway_config_cache_commit "$openclaw_config_file" "$gateway_config_stamp_file" "$gateway_config_revision" "$CLAW_IN_ONE_OPENCLAW_VERSION"
  fi
fi

load_running_gateway_pid() {
  local candidate_pid
  local gateway_cmdline
  gateway_pid=
  [[ -r "$gateway_pid_file" ]] || return 1
  candidate_pid=$(tr -cd '0-9' < "$gateway_pid_file")
  if [[ -n "$candidate_pid" && -d "/proc/$candidate_pid" ]]; then
    gateway_cmdline=$(tr '\0' ' ' < "/proc/$candidate_pid/cmdline" 2>/dev/null || true)
    if
      [[ "$gateway_cmdline" != *"$openclaw_bin"* || "$gateway_cmdline" != *"gateway run"* ]] &&
        [[ "${gateway_cmdline%% *}" != "openclaw-gateway" ]]
    then
      return 34
    fi
    gateway_pid=$candidate_pid
    return 0
  fi
  if [[ "$gateway_lock_held" == true ]]; then
    rm -f "$gateway_pid_file"
  fi
  return 1
}

gateway_pid_status=0
if load_running_gateway_pid; then
  gateway_pid_status=0
else
  gateway_pid_status=$?
fi

if [[ -z "$gateway_pid" && "$gateway_lock_held" != true ]]; then
  # The Supervisor may have just started the Gateway worker. Its process owns the
  # lock before the child has published a stable process identity, so wait for
  # that ownership handoff instead of racing it or starting a second Gateway.
  # Reconciliation invokes the pinned OpenClaw CLI several times and may take
  # minutes on a low-memory ARM64 phone after Debian has restarted.
  for ((gateway_pid_attempt = 0; gateway_pid_attempt < 300; gateway_pid_attempt += 1)); do
    sleep 1
    gateway_pid_status=0
    if load_running_gateway_pid; then
      break
    else
      gateway_pid_status=$?
    fi
  done
fi

if [[ "$gateway_pid_status" == 34 ]]; then
  printf 'Gateway PID file does not identify the ClawInOne runtime.\n' >&2
  return 34
fi

if [[ -z "$gateway_pid" && "$gateway_lock_held" != true ]]; then
  printf 'Another Supervisor owns the Gateway, but its PID is not available.\n' >&2
  return 39
fi

start_gateway_process() {
  OPENCLAW_GATEWAY_TOKEN="$gateway_token" \
    OPENCLAW_SUPERVISOR_MODE=external \
    PATH="$install_root/runtimes/node-v$CLAW_IN_ONE_NODE_VERSION-linux-arm64/bin:$PATH" \
    "$openclaw_bin" gateway run --port "$gateway_port" --bind lan --auth token --ws-log compact \
      9>&- >> "$gateway_log" 2>&1 &
  gateway_pid=$!
  gateway_started_here=true
  gateway_started_at=$SECONDS
  local gateway_pid_temporary="$gateway_pid_file.next.$$"
  printf '%s\n' "$gateway_pid" > "$gateway_pid_temporary"
  chmod 600 "$gateway_pid_temporary"
  mv -f "$gateway_pid_temporary" "$gateway_pid_file"
  write_gateway_state starting
}

report_status gateway_starting
if [[ -z "$gateway_pid" ]]; then
  start_gateway_process
else
  gateway_started_at=$SECONDS
fi

probe_gateway() {
  local path=$1
  local url="https://127.0.0.1:$gateway_port/$path"
  if command -v curl >/dev/null 2>&1; then
    curl -kfsS --max-time 2 -o /dev/null "$url"
  elif command -v wget >/dev/null 2>&1; then
    wget -q --no-check-certificate --timeout=2 -O /dev/null "$url"
  else
    printf 'Gateway health checks require curl or wget.\n' >&2
    return 1
  fi
}

wait_for_gateway_probe() {
  local path=$1
  local attempts=${2:-180}
  local attempt
  for ((attempt = 0; attempt < attempts; attempt += 1)); do
    if [[ ! -d "/proc/$gateway_pid" ]]; then
      printf 'Gateway exited before /%s became ready.\n' "$path" >&2
      return 1
    fi
    if probe_gateway "$path"; then
      return 0
    fi
    sleep 1
  done
  printf 'Gateway /%s probe timed out.\n' "$path" >&2
  return 1
}

wait_for_gateway_probe healthz
report_status gateway_healthy
wait_for_gateway_probe readyz
write_gateway_state running

if [[ ${CLAW_IN_ONE_PAIRING_REQUIRED:-true} != true ]]; then
  report_status gateway_ready
  return 0
fi

pairing_json=
if ! pairing_json=$(run_openclaw qr --json --no-ascii 2>> "$gateway_log"); then
  pairing_reason=$("$node_bin" - 4<<<"$pairing_json" <<'NODE'
const fs = require("node:fs");

let reason = "OpenClaw qr command failed without a reason";
try {
  const result = JSON.parse(fs.readFileSync(4, "utf8"));
  if (typeof result.reason === "string" && result.reason.trim()) {
    reason = result.reason.trim();
  }
} catch {}
process.stdout.write(reason.replace(/[\r\n\t]+/g, " ").slice(0, 240));
NODE
  )
  printf 'Could not create the mobile pairing code: %s\n' "$pairing_reason" >&2
  return 35
fi
pairing_values=$("$node_bin" - 4<<<"$pairing_json" <<'NODE'
const fs = require("node:fs");

const result = JSON.parse(fs.readFileSync(4, "utf8"));
if (
  typeof result.gatewayUrl !== "string" ||
  !result.gatewayUrl.startsWith("wss://") ||
  typeof result.setupCode !== "string" ||
  !/^[A-Za-z0-9_-]+$/.test(result.setupCode) ||
  result.access !== "full" ||
  result.accessDowngraded === true ||
  result.urlSource !== "gateway.bind=lan"
) {
  process.stderr.write("OpenClaw returned an invalid mobile pairing response.\n");
  process.exit(35);
}
const payload = JSON.parse(Buffer.from(result.setupCode, "base64url").toString("utf8"));
if (
  payload.url !== result.gatewayUrl ||
  typeof payload.tlsFingerprint !== "string" ||
  !/^[0-9a-fA-F:]{64,95}$/.test(payload.tlsFingerprint)
) {
  process.stderr.write("OpenClaw returned an invalid mobile pairing payload.\n");
  process.exit(36);
}
process.stdout.write(`${result.gatewayUrl}\t${result.setupCode}`);
NODE
)
IFS=$'\t' read -r CLAW_IN_ONE_GATEWAY_URL CLAW_IN_ONE_GATEWAY_SETUP_CODE <<< "$pairing_values"
[[ -n "$CLAW_IN_ONE_GATEWAY_URL" && "$CLAW_IN_ONE_GATEWAY_SETUP_CODE" =~ ^[A-Za-z0-9_-]+$ ]] || {
  printf 'OpenClaw returned an invalid mobile setup code.\n' >&2
  return 37
}

control_ui_bootstrap_sdk="$install_root/current/lib/node_modules/openclaw/dist/plugin-sdk/device-bootstrap.js"
[[ -r "$control_ui_bootstrap_sdk" ]] || {
  printf 'The pinned OpenClaw Control UI bootstrap SDK is missing.\n' >&2
  return 38
}
CLAW_IN_ONE_CONTROL_UI_BOOTSTRAP_TOKEN=$(
  "$node_bin" --input-type=module - "$control_ui_bootstrap_sdk" 4>&1 >/dev/null <<'NODE'
import fs from "node:fs";
import { pathToFileURL } from "node:url";

const sdk = await import(pathToFileURL(process.argv[2]).href);
const issued = await sdk.issueDeviceBootstrapToken({
  profile: {
    roles: ["operator"],
    scopes: [
      "operator.admin",
      "operator.approvals",
      "operator.pairing",
      "operator.questions",
      "operator.read",
      "operator.write",
    ],
    purpose: "control-ui-owner",
  },
});
if (typeof issued.token !== "string" || !/^[A-Za-z0-9_-]{32,256}$/.test(issued.token)) {
  throw new Error("OpenClaw returned an invalid Control UI bootstrap token.");
}
fs.writeFileSync(4, issued.token);
NODE
)
[[ "$CLAW_IN_ONE_CONTROL_UI_BOOTSTRAP_TOKEN" =~ ^[A-Za-z0-9_-]{32,256}$ ]] || {
  printf 'OpenClaw returned an invalid Control UI bootstrap token.\n' >&2
  return 39
}
export CLAW_IN_ONE_GATEWAY_URL CLAW_IN_ONE_GATEWAY_SETUP_CODE CLAW_IN_ONE_CONTROL_UI_BOOTSTRAP_TOKEN
report_status gateway_ready "$CLAW_IN_ONE_GATEWAY_SETUP_CODE"
