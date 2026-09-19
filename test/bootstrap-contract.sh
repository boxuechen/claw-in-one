#!/bin/bash
set -euo pipefail

repo_root=$(
  CDPATH= cd -- "$(dirname -- "$0")/.."
  pwd
)
bootstrap_dir="$repo_root/bootstrap"

for script in "$bootstrap_dir"/*.sh; do
  bash -n "$script"
done

# shellcheck disable=SC1091
source "$bootstrap_dir/supervision-policy.sh"
# shellcheck disable=SC1091
source "$bootstrap_dir/gateway-config-cache.sh"

test_root=$(mktemp -d)
supervisor_test_pid=
cleanup() {
  if [[ -n "$supervisor_test_pid" ]]; then
    kill -TERM "$supervisor_test_pid" 2>/dev/null || true
    wait "$supervisor_test_pid" 2>/dev/null || true
  fi
  rm -rf -- "$test_root"
}
trap cleanup EXIT

# Unchanged Gateway config takes the fast path; any external edit or release
# identity change requires reconciliation again.
cache_config="$test_root/openclaw.json"
cache_stamp="$test_root/product-config.stamp"
printf '{"gateway":{"mode":"local"}}\n' > "$cache_config"
if gateway_config_cache_is_current "$cache_config" "$cache_stamp" 1 2026.8.2; then
  printf 'A missing Gateway config stamp was accepted.\n' >&2
  exit 1
fi
gateway_config_cache_commit "$cache_config" "$cache_stamp" 1 2026.8.2
gateway_config_cache_is_current "$cache_config" "$cache_stamp" 1 2026.8.2
printf '{"gateway":{"mode":"local"},"userEdit":true}\n' > "$cache_config"
if gateway_config_cache_is_current "$cache_config" "$cache_stamp" 1 2026.8.2; then
  printf 'A changed Gateway config incorrectly took the fast path.\n' >&2
  exit 1
fi
gateway_config_cache_commit "$cache_config" "$cache_stamp" 1 2026.8.2
if gateway_config_cache_is_current "$cache_config" "$cache_stamp" 1 2026.8.3; then
  printf 'A different OpenClaw release incorrectly reused the config stamp.\n' >&2
  exit 1
fi
expected_delays=(1 2 4 8 16)
for index in "${!expected_delays[@]}"; do
  restart_count=$((index + 1))
  [[ $(claw_in_one_restart_delay "$restart_count") == "${expected_delays[$index]}" ]]
  claw_in_one_restart_allowed "$restart_count"
done
if claw_in_one_restart_allowed 6; then
  printf 'The crash-loop guard accepted a sixth restart.\n' >&2
  exit 1
fi
[[ $(claw_in_one_restart_delay 6) == 16 ]]

# systemd --user owns the Rust Supervisor before OpenClaw exists.
grep -Fq 'emit_handoff_event supervisor_ready "$supervisor_ready_payload"' "$bootstrap_dir/install-supervisor.sh"
grep -Fq 'claw-in-one-supervisor" serve --config' "$bootstrap_dir/install-supervisor.sh"
grep -Fq 'systemctl --user restart "$supervisor_service_name"' "$bootstrap_dir/install-supervisor.sh"
grep -Fq 'CLAW_IN_ONE_SUPERVISOR_BINARY_SHA256' "$bootstrap_dir/install-supervisor.sh"
grep -Fq 'CLAW_IN_ONE_SUPERVISOR_PROTOCOL_VERSION:-} == 10' "$bootstrap_dir/install-supervisor.sh"
grep -Fq 'gateway-config-cache.sh' "$bootstrap_dir/install-supervisor.sh"
grep -Fq 'reconcile-plugin-allowlist.mjs' "$bootstrap_dir/install-supervisor.sh"
grep -Fq 'reconcile-plugin-paths.mjs' "$bootstrap_dir/install-supervisor.sh"
grep -Fq 'install-bundled-product-plugins.mjs' "$bootstrap_dir/install-supervisor.sh"
grep -Fq 'run_openclaw doctor --fix --non-interactive' "$bootstrap_dir/start-gateway.sh"
grep -Fq 'gateway_config_revision=7' "$bootstrap_dir/start-gateway.sh"
grep -Fq 'android-use/runtime.mjs' "$bootstrap_dir/install-supervisor.sh"
grep -Fq 'android-use/tool-authorization.mjs' "$bootstrap_dir/install-supervisor.sh"
grep -Fq 'product-skills/android-use/SKILL.md' "$bootstrap_dir/install-supervisor.sh"
grep -Fq 'vscreen-foundation/display-service.mjs' "$bootstrap_dir/install-supervisor.sh"
grep -Fq 'vscreen-foundation/workload-registry.mjs' "$bootstrap_dir/install-supervisor.sh"
grep -Fq 'vscreen-foundation/remote-producer.mjs' "$bootstrap_dir/install-supervisor.sh"
grep -Fq 'vscreen-foundation/source-relay.mjs' "$bootstrap_dir/install-supervisor.sh"
grep -Fq 'vscreen-foundation/websocket.mjs' "$bootstrap_dir/install-supervisor.sh"
grep -Fq 'android-developer-bridge/bridge/device-bridge.mjs' "$bootstrap_dir/install-supervisor.sh"
grep -Fq 'android-developer-bridge/app-delivery/device-service.mjs' "$bootstrap_dir/install-supervisor.sh"
grep -Fq 'android-developer-bridge/app-delivery/vscreen-assignment.mjs' "$bootstrap_dir/install-supervisor.sh"
grep -Fq 'android-developer-bridge/project-build/tool.mjs' "$bootstrap_dir/install-supervisor.sh"
grep -Fq 'android-developer-bridge/project-build/tool-protocol.mjs' "$bootstrap_dir/install-supervisor.sh"
grep -Fq 'android-developer-bridge/project-build/authorization.mjs' "$bootstrap_dir/install-supervisor.sh"
grep -Fq 'android-developer-bridge/bridge/reverse-port.mjs' "$bootstrap_dir/install-supervisor.sh"
grep -Fq 'android-developer-bridge/vscreen/device-producer.mjs' "$bootstrap_dir/install-supervisor.sh"
grep -Fq 'android-developer-bridge/vscreen/producer.mjs' "$bootstrap_dir/install-supervisor.sh"
grep -Fq 'android-developer-bridge/vscreen/rpc.mjs' "$bootstrap_dir/install-supervisor.sh"
grep -Fq 'android-developer-bridge/vscreen/helper.mjs' "$bootstrap_dir/install-supervisor.sh"
grep -Fq 'android-developer-bridge/vscreen/readiness.mjs' "$bootstrap_dir/install-supervisor.sh"
if grep -Eq 'android-developer-bridge/(preview-|vscreen/(agent-event|protocol|session|websocket))' \
  "$bootstrap_dir/install-supervisor.sh"; then
  printf 'Android Developer Bridge must not package VScreen Foundation ownership or Preview aliases.\n' >&2
  exit 1
fi
grep -Fq 'project-workspaces/capability-readiness.mjs' "$bootstrap_dir/install-supervisor.sh"
grep -Fq 'project-workspaces/project-query.mjs' "$bootstrap_dir/install-supervisor.sh"
if grep -Fq 'android-developer-bridge/project-query.mjs' "$bootstrap_dir/install-supervisor.sh"; then
  printf 'Android Developer Bridge must not package the Project Workspaces query tool.\n' >&2
  exit 1
fi
grep -Fq 'android-kotlin-compose-v1/new-project.mjs' "$bootstrap_dir/install-supervisor.sh"
grep -Fq 'android-native-vulkan-v1/new-project.mjs' "$bootstrap_dir/install-supervisor.sh"
grep -Fq 'flutter-android-v1/new-project.mjs' "$bootstrap_dir/install-supervisor.sh"
grep -Fq 'godot-android-v1/new-project.mjs' "$bootstrap_dir/install-supervisor.sh"
grep -Fq 'react-native-android-v1/new-project.mjs' "$bootstrap_dir/install-supervisor.sh"
grep -Fq 'react-native-android-v1/build-project.mjs' "$bootstrap_dir/install-supervisor.sh"
grep -Fq 'web-development-v1/build-project.mjs' "$bootstrap_dir/install-supervisor.sh"
grep -Fq 'web-development-v1/serve-project.mjs' "$bootstrap_dir/install-supervisor.sh"
grep -Fq 'web-development/project-service.mjs' "$bootstrap_dir/install-supervisor.sh"
grep -Fq 'web-development/skills/web-development/SKILL.md' "$bootstrap_dir/install-supervisor.sh"
grep -Fq 'android-developer-bridge/skills/android-native-development/SKILL.md' \
  "$bootstrap_dir/install-supervisor.sh"
grep -Fq 'android-developer-bridge/skills/flutter-development/SKILL.md' \
  "$bootstrap_dir/install-supervisor.sh"
grep -Fq 'android-developer-bridge/skills/godot-android-development/SKILL.md' \
  "$bootstrap_dir/install-supervisor.sh"
grep -Fq 'android-developer-bridge/skills/react-native-development/SKILL.md' \
  "$bootstrap_dir/install-supervisor.sh"
grep -Fq 'Do not inspect or source' \
  "$repo_root/product-plugins/android-developer-bridge/skills/react-native-development/SKILL.md"
grep -Fq '/home/droid/.local/share/claw-in-one/development-profiles/react-native/active/new-project.mjs' \
  "$repo_root/product-plugins/android-developer-bridge/skills/react-native-development/SKILL.md"
grep -Fq 'Do not substitute generic ADB, shell commands or VScreen pointer input' \
  "$repo_root/product-skills/android-use/SKILL.md"
grep -Fq '"generation": 2' "$bootstrap_dir/flutter-android-v1/release.json"
grep -Fq '"godotVersion": "4.7.2"' "$bootstrap_dir/godot-android-v1/release.json"
grep -Fq 'custom_template/debug="__ANDROID_DEBUG_TEMPLATE__"' \
  "$bootstrap_dir/godot-android-v1/template/export_presets.cfg"
grep -Fq 'Preserve an existing canonical `.git`' \
  "$repo_root/product-plugins/android-developer-bridge/skills/flutter-development/SKILL.md"
grep -Fq 'Do not inspect or source `capabilities.env`' \
  "$repo_root/product-plugins/android-developer-bridge/skills/android-development/SKILL.md"
grep -Fq '/home/droid/.local/share/claw-in-one/development-profiles/android-kotlin/active/new-project.mjs' \
  "$repo_root/product-plugins/android-developer-bridge/skills/android-development/SKILL.md"
grep -Fq 'stable identity in `.claw-in-one/android-project.v1.json`' \
  "$repo_root/product-plugins/android-developer-bridge/skills/android-development/SKILL.md"
if grep -Fq '$CLAW_IN_ONE_ANDROID_NEW_PROJECT --project-dir' \
  "$repo_root/product-plugins/android-developer-bridge/skills/android-development/SKILL.md"; then
  printf 'Android development Skill must use the bindable Supervisor materializer entrypoint.\n' >&2
  exit 1
fi
if grep -Fq 'Source `$HOME/.local/share/claw-in-one/capabilities.env`' \
  "$repo_root/product-plugins/android-developer-bridge/skills/android-development/SKILL.md"; then
  printf 'Android development Skill must not ask the Agent to read capabilities.env.\n' >&2
  exit 1
fi

# The standalone Supervisor accepts only authenticated, typed commands and
# produces an authenticated status without Node.js or OpenClaw being installed.
supervisor_id=aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa
supervisor_secret=bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb
test_shared_root="$test_root/shared/Download/ClawInOne"
test_control_dir="$test_shared_root/supervisor-$supervisor_id"
test_command_file="$test_control_dir/supervisor.command"
test_status_file="$test_control_dir/supervisor.status"
test_state_dir="$test_root/state/claw-in-one"
mkdir -p "$test_control_dir" "$test_state_dir" "$test_root/home" "$test_root/install"
: > "$test_command_file"
: > "$test_status_file"
(
  cd "$repo_root/supervisor"
  cargo build --locked >/dev/null
)
test_supervisor="$repo_root/supervisor/target/debug/claw-in-one-supervisor"
{
  printf "CLAW_IN_ONE_SUPERVISOR_PROTOCOL_VERSION='10'\n"
  printf "CLAW_IN_ONE_SUPERVISOR_ID='%s'\n" "$supervisor_id"
  printf "CLAW_IN_ONE_SUPERVISOR_SECRET='%s'\n" "$supervisor_secret"
  printf "CLAW_IN_ONE_SUPERVISOR_COMMAND_FILE='%s'\n" "$test_command_file"
  printf "CLAW_IN_ONE_SUPERVISOR_STATUS_FILE='%s'\n" "$test_status_file"
  printf "CLAW_IN_ONE_SHARED_DIR='%s'\n" "$bootstrap_dir"
  printf "CLAW_IN_ONE_OPENCLAW_VERSION='2026.9.4'\n"
  printf "CLAW_IN_ONE_NODE_VERSION='24.7.0'\n"
  printf "CLAW_IN_ONE_NODE_URL='https://example.invalid/node.tar.gz'\n"
  printf "CLAW_IN_ONE_NODE_SIZE_BYTES='1'\n"
  printf "CLAW_IN_ONE_NODE_SHA256='%064d'\n" 0
  printf "CLAW_IN_ONE_OPENCLAW_URL='https://example.invalid/openclaw.tgz'\n"
  printf "CLAW_IN_ONE_OPENCLAW_SIZE_BYTES='1'\n"
  printf "CLAW_IN_ONE_OPENCLAW_INTEGRITY='sha512-fixture'\n"
  printf "CLAW_IN_ONE_OPENCLAW_SIGNATURE='fixture'\n"
  printf "CLAW_IN_ONE_NPM_KEY_ID='SHA256:fixture'\n"
  printf "CLAW_IN_ONE_NPM_PUBLIC_KEY='fixture'\n"
  printf "CLAW_IN_ONE_DEBIAN_SERIES='13'\n"
  printf "CLAW_IN_ONE_PACKAGE_SET_GENERATION='1'\n"
  printf "CLAW_IN_ONE_GENERAL_NODE_VERSION='22.22.0'\n"
  printf "CLAW_IN_ONE_GENERAL_NODE_NPM_VERSION='10.9.4'\n"
  printf "CLAW_IN_ONE_GENERAL_NODE_URL='https://nodejs.org/dist/v22.22.0/node-v22.22.0-linux-arm64.tar.xz'\n"
  printf "CLAW_IN_ONE_GENERAL_NODE_SIZE_BYTES='29977428'\n"
  printf "CLAW_IN_ONE_GENERAL_NODE_SHA256='1bf1eb9ee63ffc4e5d324c0b9b62cf4a289f44332dfef9607cea1a0d9596ba6f'\n"
  printf "CLAW_IN_ONE_SCRCPY_SERVER_VERSION='4.1'\n"
  printf "CLAW_IN_ONE_SCRCPY_SERVER_URL='https://github.com/Genymobile/scrcpy/releases/download/v4.1/scrcpy-server-v4.1'\n"
  printf "CLAW_IN_ONE_SCRCPY_SERVER_SIZE_BYTES='733706'\n"
  printf "CLAW_IN_ONE_SCRCPY_SERVER_SHA256='deacb991ed2509715160ffdc7907e47b4160eb30d1566217e9047fd5b8850cae'\n"
  printf "CLAW_IN_ONE_ANDROID_COMMAND_TOOLS_VERSION='16111833'\n"
  printf "CLAW_IN_ONE_ANDROID_COMMAND_TOOLS_URL='https://dl.google.com/android/repository/commandlinetools-linux-16111833_latest.zip'\n"
  printf "CLAW_IN_ONE_ANDROID_COMMAND_TOOLS_SIZE_BYTES='181052239'\n"
  printf "CLAW_IN_ONE_ANDROID_COMMAND_TOOLS_SHA256='0877a1d048fe4a24efe2eff536ca4223f7adeb58648bb81909d33c446918cfa8'\n"
  printf "CLAW_IN_ONE_ANDROID_SDK_CHANNEL='3'\n"
  printf "CLAW_IN_ONE_ANDROID_PLATFORM='37.0'\n"
  printf "CLAW_IN_ONE_ANDROID_BUILD_TOOLS='37.0.0'\n"
  printf "CLAW_IN_ONE_ANDROID_GRADLE_VERSION='9.7.1'\n"
  printf "CLAW_IN_ONE_ANDROID_GRADLE_URL='https://services.gradle.org/distributions/gradle-9.7.1-bin.zip'\n"
  printf "CLAW_IN_ONE_ANDROID_GRADLE_SIZE_BYTES='151433392'\n"
  printf "CLAW_IN_ONE_ANDROID_GRADLE_SHA256='acd53f1edaf02f1a8ff99879f8a34b302661a057d9b063ae9e35b552f804d20a'\n"
  printf "CLAW_IN_ONE_ANDROID_NATIVE_NDK='29.0.14206865'\n"
  printf "CLAW_IN_ONE_ANDROID_NATIVE_CMAKE='3.22.1'\n"
  printf "CLAW_IN_ONE_FLUTTER_VERSION='3.47.4'\n"
  printf "CLAW_IN_ONE_FLUTTER_DART_VERSION='3.13.3'\n"
  printf "CLAW_IN_ONE_FLUTTER_FRAMEWORK_REVISION='9584c6713b324636289d067944a46fd6b49df14b'\n"
  printf "CLAW_IN_ONE_FLUTTER_ENGINE_REVISION='06a2e2a110089dff50fe635cffd2a61e1b24fbcd'\n"
  printf "CLAW_IN_ONE_FLUTTER_URL='https://storage.googleapis.com/flutter_infra_release/releases/stable/linux/flutter_linux_3.47.4-stable.tar.xz'\n"
  printf "CLAW_IN_ONE_FLUTTER_SIZE_BYTES='1576174568'\n"
  printf "CLAW_IN_ONE_FLUTTER_SHA256='5b45f0ceda99b9bebdc873e7e69f6450aeb4c30f454b505e2e62fc9255a907d3'\n"
  printf "CLAW_IN_ONE_FLUTTER_ANDROID_PLATFORM='36'\n"
  printf "CLAW_IN_ONE_FLUTTER_ANDROID_NDK='28.2.13676358'\n"
  printf "CLAW_IN_ONE_GODOT_VERSION='4.7.2'\n"
  printf "CLAW_IN_ONE_GODOT_BUILD='stable.official.ed1daf0bf'\n"
  printf "CLAW_IN_ONE_GODOT_ENGINE_URL='https://github.com/godotengine/godot-builds/releases/download/4.7.2-stable/Godot_v4.7.2-stable_linux.arm64.zip'\n"
  printf "CLAW_IN_ONE_GODOT_ENGINE_SIZE_BYTES='77008699'\n"
  printf "CLAW_IN_ONE_GODOT_ENGINE_SHA256='5dd0d86405cf7e8adf79fb6377b38ba682a2846cb378ffe5364f38c01ad29b9d'\n"
  printf "CLAW_IN_ONE_GODOT_EXPORT_TEMPLATES_URL='https://github.com/godotengine/godot-builds/releases/download/4.7.2-stable/Godot_v4.7.2-stable_export_templates.tpz'\n"
  printf "CLAW_IN_ONE_GODOT_EXPORT_TEMPLATES_SIZE_BYTES='1281349702'\n"
  printf "CLAW_IN_ONE_GODOT_EXPORT_TEMPLATES_SHA256='f298490b8d44d934be425a5a65a51bf15f422428b229a06a6e11d9ffea248011'\n"
  printf "CLAW_IN_ONE_GODOT_ANDROID_DEBUG_TEMPLATE_SHA256='59e0073f0fa59f552684a861d8f4266b0b34b488a140d6657eac7dd70d1a0a0d'\n"
  printf "CLAW_IN_ONE_REACT_NATIVE_VERSION='0.87.1'\n"
  printf "CLAW_IN_ONE_REACT_NATIVE_REACT_VERSION='19.2.3'\n"
  printf "CLAW_IN_ONE_REACT_NATIVE_COMMUNITY_CLI_VERSION='20.2.0'\n"
  printf "CLAW_IN_ONE_REACT_NATIVE_GRADLE_VERSION='9.4.1'\n"
  printf "CLAW_IN_ONE_REACT_NATIVE_GRADLE_URL='https://services.gradle.org/distributions/gradle-9.4.1-bin.zip'\n"
  printf "CLAW_IN_ONE_REACT_NATIVE_GRADLE_SIZE_BYTES='137878901'\n"
  printf "CLAW_IN_ONE_REACT_NATIVE_GRADLE_SHA256='2ab2958f2a1e51120c326cad6f385153bb11ee93b3c216c5fccebfdfbb7ec6cb'\n"
  printf "CLAW_IN_ONE_REACT_NATIVE_ANDROID_GRADLE_PLUGIN='9.2.1'\n"
  printf "CLAW_IN_ONE_REACT_NATIVE_ANDROID_PLATFORM='37.0'\n"
  printf "CLAW_IN_ONE_REACT_NATIVE_ANDROID_BUILD_TOOLS='37.0.0'\n"
  printf "CLAW_IN_ONE_REACT_NATIVE_ANDROID_NDK='27.1.12297006'\n"
  printf "CLAW_IN_ONE_REACT_NATIVE_ANDROID_CMAKE='3.22.1'\n"
  printf "CLAW_IN_ONE_REACT_NATIVE_HERMES_COMPILER_VERSION='250829098.0.17'\n"
  printf "CLAW_IN_ONE_REACT_NATIVE_HERMES_COMPILER_SHA256='925ddf1bc2f17578e8af5df391c9d84dbd98441540fb43257aac3ef75c54b749'\n"
  printf "CLAW_IN_ONE_REACT_NATIVE_PACKAGE_LOCK_SHA256='d744de5615820ff5edf7b6f1713ee09d22762f63223d1dc8131244a142fa510c'\n"
  printf "CLAW_IN_ONE_WEB_REACT_VERSION='19.3.0'\n"
  printf "CLAW_IN_ONE_WEB_REACT_DOM_VERSION='19.3.0'\n"
  printf "CLAW_IN_ONE_WEB_VITE_VERSION='8.3.0'\n"
  printf "CLAW_IN_ONE_WEB_TYPESCRIPT_VERSION='7.0.2'\n"
  printf "CLAW_IN_ONE_WEB_PACKAGE_LOCK_SHA256='13ec679d2f1972709f000107ef5b8e967c169cacbb76dd02b3f26f6ae007db21'\n"
} > "$test_state_dir/supervisor.env"
HOME="$test_root/home" \
XDG_STATE_HOME="$test_root/state" \
CLAW_IN_ONE_INSTALL_ROOT="$test_root/install" \
  "$test_supervisor" serve --config "$test_state_dir/supervisor.env" > "$test_root/supervisor.log" 2>&1 &
supervisor_test_pid=$!
for ((attempt = 0; attempt < 100; attempt += 1)); do
  [[ -s "$test_status_file" ]] && break
  sleep 0.05
done
[[ -s "$test_status_file" ]]

command_timestamp=$(date +%s)
command_canonical=$(printf '%s\n%s\n%s\n%s\n%s\n%s' \
  10 "$supervisor_id" 1 "$command_timestamp" probe -)
command_hmac=$(printf '%s' "$command_canonical" |
  openssl dgst -sha256 -mac HMAC -macopt "hexkey:$supervisor_secret" -binary |
  od -An -tx1 | tr -d ' \n')
printf '10|%s|1|%s|probe|-|%s\n' \
  "$supervisor_id" "$command_timestamp" "$command_hmac" > "$test_command_file"
for ((attempt = 0; attempt < 100; attempt += 1)); do
  if grep -Eq "^10\|$supervisor_id\|[0-9a-f]{32}\|[0-9]+\|1\|" "$test_status_file"; then
    break
  fi
  sleep 0.05
done
if ! grep -Eq "^10\|$supervisor_id\|[0-9a-f]{32}\|[0-9]+\|1\|[0-9]+\|capabilities_required\|-\|-\|0\|-\|-\|-\|-\|-\|-\|-\|-\|[0-9a-f]{64}$" \
  "$test_status_file"; then
  printf 'The Supervisor did not accept a valid signed probe.\n' >&2
  tail -80 "$test_root/supervisor.log" >&2
  exit 1
fi

# A newer command with an invalid MAC is ignored rather than executed.
printf '10|%s|2|%s|apply_capabilities|cccccccccccccccccccccccccccccccc:android_kotlin|%064d\n' \
  "$supervisor_id" "$command_timestamp" 0 > "$test_command_file"
sleep 0.2
grep -Eq "^10\|$supervisor_id\|[0-9a-f]{32}\|[0-9]+\|1\|" "$test_status_file"
kill -TERM "$supervisor_test_pid"
wait "$supervisor_test_pid" || true
supervisor_test_pid=

# Gateway process ownership remains bounded and never deletes another process.
grep -Fq '[[ "$gateway_started_here" == true ]]' "$bootstrap_dir/start-gateway.sh"
grep -Fq 'gateway_pid_attempt < 300' "$bootstrap_dir/start-gateway.sh"
grep -Fq 'if [[ -z "$gateway_pid" && "$gateway_lock_held" != true ]]; then' \
  "$bootstrap_dir/start-gateway.sh"
grep -Fq 'merge_config_array tools.deny mobile_ui add true' "$bootstrap_dir/start-gateway.sh"
grep -Fq 'merge_config_array gateway.nodes.commands.allow claw.android_use add true' \
  "$bootstrap_dir/start-gateway.sh"
grep -Fq 'reconcile_product_plugin_paths' "$bootstrap_dir/start-gateway.sh"
grep -Fq 'reconcile_bundled_plugin_allowlist' "$bootstrap_dir/start-gateway.sh"
grep -Fq 'plugins.entries.claw-in-one-android-use.hooks.allowConversationAccess true' \
  "$bootstrap_dir/start-gateway.sh"
grep -Fq 'plugins.entries.claw-in-one-vscreen-foundation.enabled true' \
  "$bootstrap_dir/start-gateway.sh"
grep -Fq 'plugins.entries.claw-in-one-android-developer-bridge.enabled true' \
  "$bootstrap_dir/start-gateway.sh"
grep -Fq 'plugins.entries.claw-in-one-android-developer-bridge.config' \
  "$bootstrap_dir/start-gateway.sh"
grep -Fq 'plugins.entries.claw-in-one-android-developer-bridge.hooks.allowConversationAccess true' \
  "$bootstrap_dir/start-gateway.sh"
grep -Fq 'plugins.entries.claw-in-one-web-development.enabled true' \
  "$bootstrap_dir/start-gateway.sh"
grep -Fq 'plugins.entries.claw-in-one-web-development.hooks.allowConversationAccess true' \
  "$bootstrap_dir/start-gateway.sh"
if grep -Fq 'before_prompt_build' \
  "$repo_root/product-plugins/android-developer-bridge/runtime.mjs"; then
  printf 'Android Developer Bridge must not inject stack-specific guidance into every Chat.\n' >&2
  exit 1
fi
grep -Fq 'plugins.entries.claw-in-one-project-workspaces.enabled true' \
  "$bootstrap_dir/start-gateway.sh"
grep -Fq 'plugins.entries.claw-in-one-project-workspaces.hooks.allowConversationAccess true' \
  "$bootstrap_dir/start-gateway.sh"
if grep -Fq '"operator.talk.secrets"' "$bootstrap_dir/start-gateway.sh"; then
  printf 'Android bootstrap must not request the removed Talk scope.\n' >&2
  exit 1
fi
for operator_scope in \
  operator.admin \
  operator.approvals \
  operator.pairing \
  operator.questions \
  operator.read \
  operator.write; do
  grep -Fq "\"$operator_scope\"" "$bootstrap_dir/start-gateway.sh"
done

# ClawInOne product Plugins are host-bundled, never external path overrides.
# Stale product paths are removed while unrelated user paths remain unchanged.
reconciled_paths=$(node "$bootstrap_dir/reconcile-plugin-paths.mjs" \
  '["/opt/user/plugin","/opt/claw/dev/android-use","/opt/claw/supervisor/releases/bootstrap-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa/android-developer-bridge","/opt/claw/supervisor/current/android-use","/opt/claw/supervisor/current/retired-plugin","/opt/claw/dev/vscreen-foundation","/opt/claw/dev/web-development"]' \
  '/opt/claw' \
  '/opt/claw/supervisor/current')
[[ "$reconciled_paths" == \
  '["/opt/user/plugin"]' ]]

# The signed Supervisor release replaces only exact product-owned bundled slots,
# removes stale files inside those slots and preserves unrelated bundled Plugins.
overlay_root="$test_root/product-overlay"
mkdir -p "$overlay_root/extensions/android-use" "$overlay_root/extensions/unrelated"
printf 'stale\n' > "$overlay_root/extensions/android-use/stale.txt"
printf 'keep\n' > "$overlay_root/extensions/unrelated/keep.txt"
node "$bootstrap_dir/install-bundled-product-plugins.mjs" \
  "$overlay_root/extensions" \
  "$repo_root/product-plugins"
for plugin in android-use vscreen-foundation android-developer-bridge web-development project-workspaces; do
  diff -qr "$repo_root/product-plugins/$plugin" "$overlay_root/extensions/$plugin"
done
[[ ! -e "$overlay_root/extensions/android-use/stale.txt" ]]
[[ $(cat "$overlay_root/extensions/unrelated/keep.txt") == keep ]]
node "$bootstrap_dir/install-bundled-product-plugins.mjs" \
  "$overlay_root/extensions" \
  "$repo_root/product-plugins"

# A pinned 9.4 release owns its bundled plugin eligibility. Existing user
# allowlist entries are preserved and duplicate bundled ids are collapsed.
bundled_extensions="$test_root/bundled-extensions"
mkdir -p "$bundled_extensions/anthropic" "$bundled_extensions/openai" "$bundled_extensions/no-manifest"
printf '{"id":"anthropic"}\n' > "$bundled_extensions/anthropic/openclaw.plugin.json"
printf '{"id":"openai"}\n' > "$bundled_extensions/openai/openclaw.plugin.json"
reconciled_allowlist=$(node "$bootstrap_dir/reconcile-plugin-allowlist.mjs" \
  '["user-plugin","anthropic"]' \
  "$bundled_extensions")
[[ "$reconciled_allowlist" == '["user-plugin","anthropic","openai"]' ]]

printf 'Bootstrap contracts passed.\n'
