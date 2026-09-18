#!/bin/bash
set -Eeuo pipefail

script_dir=$(
  CDPATH= cd -- "$(dirname -- "${BASH_SOURCE[0]}")"
  pwd
)
source_dir=${CLAW_IN_ONE_SHARED_DIR:-$script_dir}
handoff_config_file=${CLAW_IN_ONE_CONFIG_FILE:-}
install_root=${CLAW_IN_ONE_INSTALL_ROOT:-"$HOME/.local/share/claw-in-one"}
private_state_dir=${XDG_STATE_HOME:-"$HOME/.local/state"}/claw-in-one
private_config_file="$private_state_dir/supervisor.env"
supervisor_root="$install_root/supervisor"
supervisor_current="$supervisor_root/current"
supervisor_service_name=claw-in-one-supervisor.service
handoff_sequence=0
current_stage=bootstrap_executed

if [[ -z "$handoff_config_file" || ! -r "$handoff_config_file" || -L "$handoff_config_file" ]]; then
  printf 'Missing one-time Bootstrap configuration.\n' >&2
  exit 2
fi

# shellcheck disable=SC1090
source "$handoff_config_file"

validate_control_file() {
  local path=$1
  [[ -f "$path" && ! -L "$path" ]]
}

validate_handoff() {
  [[ ${CLAW_IN_ONE_HANDOFF_PROTOCOL_VERSION:-} == 4 ]] || return 1
  [[ ${CLAW_IN_ONE_HANDOFF_REQUEST_ID:-} =~ ^[0-9a-f]{32}$ ]] || return 1
  [[ ${CLAW_IN_ONE_HANDOFF_SECRET:-} =~ ^[0-9a-f]{64}$ ]] || return 1
  [[ ${CLAW_IN_ONE_SUPERVISOR_PROTOCOL_VERSION:-} == 10 ]] || return 1
  [[ ${CLAW_IN_ONE_SUPERVISOR_ID:-} == "$CLAW_IN_ONE_HANDOFF_REQUEST_ID" ]] || return 1
  [[ ${CLAW_IN_ONE_SUPERVISOR_SECRET:-} =~ ^[0-9a-f]{64}$ ]] || return 1
  [[ ${CLAW_IN_ONE_SUPERVISOR_BINARY_SHA256:-} =~ ^[0-9a-f]{64}$ ]] || return 1
  local control_dir="/mnt/shared/Download/ClawInOne/supervisor-$CLAW_IN_ONE_SUPERVISOR_ID"
  [[ ${CLAW_IN_ONE_SUPERVISOR_COMMAND_FILE:-} == "$control_dir/supervisor.command" ]] || return 1
  [[ ${CLAW_IN_ONE_SUPERVISOR_STATUS_FILE:-} == "$control_dir/supervisor.status" ]] || return 1
  validate_control_file "$CLAW_IN_ONE_SUPERVISOR_COMMAND_FILE" || return 1
  validate_control_file "$CLAW_IN_ONE_SUPERVISOR_STATUS_FILE" || return 1
  [[ -r "$CLAW_IN_ONE_SUPERVISOR_COMMAND_FILE" ]] || return 1
  [[ -w "$CLAW_IN_ONE_SUPERVISOR_STATUS_FILE" ]] || return 1
  local events_file="/mnt/shared/Download/ClawInOne/handoff-$CLAW_IN_ONE_HANDOFF_REQUEST_ID/handoff.events"
  [[ ${CLAW_IN_ONE_HANDOFF_EVENTS_FILE:-} == "$events_file" ]] || return 1
  validate_control_file "$CLAW_IN_ONE_HANDOFF_EVENTS_FILE" || return 1
  [[ -w "$CLAW_IN_ONE_HANDOFF_EVENTS_FILE" ]] || return 1
  [[ $source_dir =~ /bootstrap-[0-9a-f]{32}$ ]] || return 1
  command -v openssl >/dev/null 2>&1 || return 1
  command -v sha256sum >/dev/null 2>&1 || return 1
}

emit_handoff_event() {
  local stage=$1
  local payload_json=$2
  local timestamp payload canonical hmac event_line
  [[ "$stage" =~ ^[a-z_]{3,48}$ ]]
  handoff_sequence=$((handoff_sequence + 1))
  timestamp=$(date +%s)
  payload=$(printf '%s' "$payload_json" | openssl base64 -A | tr '+/' '-_' | tr -d '=')
  canonical=$(printf '%s\n%s\n%s\n%s\n%s\n%s' \
    "$CLAW_IN_ONE_HANDOFF_REQUEST_ID" \
    "$CLAW_IN_ONE_HANDOFF_PROTOCOL_VERSION" \
    "$handoff_sequence" \
    "$timestamp" \
    "$stage" \
    "$payload")
  hmac=$(printf '%s' "$canonical" |
    openssl dgst -sha256 -mac HMAC -macopt "hexkey:$CLAW_IN_ONE_HANDOFF_SECRET" -binary |
    od -An -tx1 | tr -d ' \n')
  [[ "$hmac" =~ ^[0-9a-f]{64}$ ]]
  event_line=$(printf \
    '{"requestId":"%s","protocolVersion":%s,"sequence":%s,"timestamp":%s,"stage":"%s","payload":"%s","hmac":"%s"}' \
    "$CLAW_IN_ONE_HANDOFF_REQUEST_ID" \
    "$CLAW_IN_ONE_HANDOFF_PROTOCOL_VERSION" \
    "$handoff_sequence" \
    "$timestamp" \
    "$stage" \
    "$payload" \
    "$hmac")
  printf '%s\n' "$event_line" >> "$CLAW_IN_ONE_HANDOFF_EVENTS_FILE"
}

remove_private_handoff_credentials() {
  local sanitized="$private_config_file.next.$$"
  sed '/^CLAW_IN_ONE_HANDOFF_/d' "$private_config_file" > "$sanitized"
  chmod 600 "$sanitized"
  mv -f "$sanitized" "$private_config_file"
  unset CLAW_IN_ONE_HANDOFF_REQUEST_ID CLAW_IN_ONE_HANDOFF_SECRET CLAW_IN_ONE_HANDOFF_EVENTS_FILE
}

report_failure() {
  local exit_code=$?
  trap - ERR
  emit_handoff_event failed "{\"stage\":\"$current_stage\",\"exitCode\":$exit_code}" || true
  if [[ -f "$private_config_file" && ! -L "$private_config_file" ]]; then
    remove_private_handoff_credentials || true
  fi
  exit "$exit_code"
}
trap report_failure ERR

if ! validate_handoff; then
  printf 'Invalid signed Bootstrap handoff configuration.\n' >&2
  exit 3
fi

emit_handoff_event bootstrap_executed '{}'
current_stage=installing_supervisor
emit_handoff_event installing_supervisor '{}'

supervisor_binary="$source_dir/claw-in-one-supervisor"
[[ -f "$supervisor_binary" && ! -L "$supervisor_binary" ]]
actual_binary_sha256=$(sha256sum "$supervisor_binary" | awk '{print $1}')
[[ "$actual_binary_sha256" == "$CLAW_IN_ONE_SUPERVISOR_BINARY_SHA256" ]]

bundle_name=${source_dir##*/}
supervisor_release="$supervisor_root/releases/$bundle_name"
supervisor_staging="$supervisor_release.staging.$$"
supervisor_assets=(
  claw-in-one-supervisor
  start-gateway.sh
  gateway-config-cache.sh
  install-bundled-product-plugins.mjs
  reconcile-plugin-allowlist.mjs
  reconcile-plugin-paths.mjs
  supervision-policy.sh
  android-use/index.mjs
  android-use/runtime.mjs
  android-use/tool-authorization.mjs
  android-use/openclaw.plugin.json
  android-use/package.json
  product-skills/android-use/SKILL.md
  vscreen-foundation/index.mjs
  vscreen-foundation/runtime.mjs
  vscreen-foundation/protocol.mjs
  vscreen-foundation/workload-registry.mjs
  vscreen-foundation/producer-registry.mjs
  vscreen-foundation/remote-producer.mjs
  vscreen-foundation/display-service.mjs
  vscreen-foundation/source-relay.mjs
  vscreen-foundation/websocket.mjs
  vscreen-foundation/openclaw.plugin.json
  vscreen-foundation/package.json
  android-developer-bridge/index.mjs
  android-developer-bridge/runtime.mjs
  android-developer-bridge/bridge/device-bridge.mjs
  android-developer-bridge/bridge/protocol.mjs
  android-developer-bridge/bridge/reverse-port.mjs
  android-developer-bridge/app-delivery/artifact-service.mjs
  android-developer-bridge/app-delivery/device-service.mjs
  android-developer-bridge/app-delivery/install-approval.mjs
  android-developer-bridge/app-delivery/install-result.mjs
  android-developer-bridge/app-delivery/vscreen-assignment.mjs
  android-developer-bridge/app-delivery/tool-security.mjs
  android-developer-bridge/app-delivery/tool.mjs
  android-developer-bridge/app-delivery/tool-protocol.mjs
  android-developer-bridge/project-build/tool.mjs
  android-developer-bridge/project-build/tool-protocol.mjs
  android-developer-bridge/project-build/authorization.mjs
  android-developer-bridge/vscreen/device-producer.mjs
  android-developer-bridge/vscreen/producer.mjs
  android-developer-bridge/vscreen/rpc.mjs
  android-developer-bridge/vscreen/readiness.mjs
  android-developer-bridge/vscreen/helper.mjs
  android-developer-bridge/openclaw.plugin.json
  android-developer-bridge/package.json
  android-developer-bridge/skills/android-development/SKILL.md
  android-developer-bridge/skills/android-native-development/SKILL.md
  android-developer-bridge/skills/flutter-development/SKILL.md
  android-developer-bridge/skills/godot-android-development/SKILL.md
  android-developer-bridge/skills/react-native-development/SKILL.md
  web-development/index.mjs
  web-development/runtime.mjs
  web-development/authorization.mjs
  web-development/project-service.mjs
  web-development/result-store.mjs
  web-development/tool-protocol.mjs
  web-development/openclaw.plugin.json
  web-development/package.json
  web-development/skills/web-development/SKILL.md
  project-workspaces/index.mjs
  project-workspaces/runtime.mjs
  project-workspaces/capability-readiness.mjs
  project-workspaces/project-query.mjs
  project-workspaces/project-service.mjs
  project-workspaces/protocol.mjs
  project-workspaces/openclaw.plugin.json
  project-workspaces/package.json
  android-kotlin-compose-v1/release.json
  android-kotlin-compose-v1/new-project.mjs
  android-kotlin-compose-v1/template/settings.gradle.kts
  android-kotlin-compose-v1/template/build.gradle.kts
  android-kotlin-compose-v1/template/gradle.properties
  android-kotlin-compose-v1/template/app/build.gradle.kts
  android-kotlin-compose-v1/template/app/src/main/AndroidManifest.xml
  android-kotlin-compose-v1/template/app/src/main/res/values/styles.xml
  android-kotlin-compose-v1/template/app/src/main/java/starter/MainActivity.kt
  android-native-vulkan-v1/release.json
  android-native-vulkan-v1/new-project.mjs
  android-native-vulkan-v1/template/settings.gradle.kts
  android-native-vulkan-v1/template/build.gradle.kts
  android-native-vulkan-v1/template/gradle.properties
  android-native-vulkan-v1/template/app/build.gradle.kts
  android-native-vulkan-v1/template/app/src/main/AndroidManifest.xml
  android-native-vulkan-v1/template/app/src/main/cpp/CMakeLists.txt
  android-native-vulkan-v1/template/app/src/main/cpp/main.c
  flutter-android-v1/release.json
  flutter-android-v1/new-project.mjs
  godot-android-v1/release.json
  godot-android-v1/new-project.mjs
  godot-android-v1/template/project.godot
  godot-android-v1/template/export_presets.cfg
  godot-android-v1/template/main.gd
  godot-android-v1/template/main.tscn
  godot-android-v1/template/icon.svg
  react-native-android-v1/release.json
  react-native-android-v1/new-project.mjs
  react-native-android-v1/build-project.mjs
  react-native-android-v1/template/App.tsx
  react-native-android-v1/template/app.json
  react-native-android-v1/template/babel.config.js
  react-native-android-v1/template/index.js
  react-native-android-v1/template/jest.config.js
  react-native-android-v1/template/metro.config.js
  react-native-android-v1/template/package.json
  react-native-android-v1/template/package-lock.json
  react-native-android-v1/template/tsconfig.json
  react-native-android-v1/template/tests/App.test.tsx
  react-native-android-v1/template/android/build.gradle
  react-native-android-v1/template/android/gradle.properties
  react-native-android-v1/template/android/settings.gradle
  react-native-android-v1/template/android/app/build.gradle
  react-native-android-v1/template/android/app/proguard-rules.pro
  react-native-android-v1/template/android/app/src/main/AndroidManifest.xml
  react-native-android-v1/template/android/app/src/main/java/starter/MainActivity.kt
  react-native-android-v1/template/android/app/src/main/java/starter/MainApplication.kt
  react-native-android-v1/template/android/app/src/main/res/drawable/rn_edit_text_material.xml
  react-native-android-v1/template/android/app/src/main/res/values/strings.xml
  react-native-android-v1/template/android/app/src/main/res/values/styles.xml
  web-development-v1/release.json
  web-development-v1/new-project.mjs
  web-development-v1/build-project.mjs
  web-development-v1/serve-project.mjs
  web-development-v1/template/package.json
  web-development-v1/template/package-lock.json
  web-development-v1/template/index.html
  web-development-v1/template/tsconfig.json
  web-development-v1/template/server.mjs
  web-development-v1/template/src/main.tsx
  web-development-v1/template/src/style.css
)

install -d -m 700 "$supervisor_root/releases" "$private_state_dir"
if [[ -e "$supervisor_release" || -L "$supervisor_release" ]]; then
  [[ -d "$supervisor_release" && ! -L "$supervisor_release" ]]
  for asset in "${supervisor_assets[@]}"; do
    [[ -f "$supervisor_release/$asset" && ! -L "$supervisor_release/$asset" ]]
    cmp -s "$source_dir/$asset" "$supervisor_release/$asset"
  done
else
  rm -rf -- "$supervisor_staging"
  install -d -m 700 "$supervisor_staging"
  for asset in "${supervisor_assets[@]}"; do
    [[ -f "$source_dir/$asset" && ! -L "$source_dir/$asset" ]]
    install -d -m 700 "$(dirname "$supervisor_staging/$asset")"
    install -m 700 "$source_dir/$asset" "$supervisor_staging/$asset"
  done
  mv "$supervisor_staging" "$supervisor_release"
fi

supervisor_next="$supervisor_current.next.$$"
rm -f -- "$supervisor_next"
ln -s "$supervisor_release" "$supervisor_next"
mv -Tf "$supervisor_next" "$supervisor_current"

private_config_staging="$private_config_file.next.$$"
sed '/^CLAW_IN_ONE_SHARED_DIR=/d' "$handoff_config_file" > "$private_config_staging"
[[ "$supervisor_current" != *"'"* ]]
printf "CLAW_IN_ONE_SHARED_DIR='%s'\n" "$supervisor_current" >> "$private_config_staging"
chmod 600 "$private_config_staging"
mv -f "$private_config_staging" "$private_config_file"
rm -f -- "$handoff_config_file"

current_stage=starting_supervisor
emit_handoff_event starting_supervisor '{}'

supervisor_lifecycle_mode=external-foreground
service_dir=${XDG_CONFIG_HOME:-"$HOME/.config"}/systemd/user
service_file="$service_dir/$supervisor_service_name"
service_staging="$service_file.next.$$"

if command -v systemctl >/dev/null 2>&1 && systemctl --user show-environment >/dev/null 2>&1; then
  mkdir -p "$service_dir"
  cat > "$service_staging" <<UNIT
[Unit]
Description=ClawInOne Supervisor
After=default.target

[Service]
Type=simple
ExecStart="$supervisor_current/claw-in-one-supervisor" serve --config "$private_config_file"
Restart=on-failure
RestartSec=5s
TimeoutStopSec=30s
KillMode=control-group

[Install]
WantedBy=default.target
UNIT
  chmod 644 "$service_staging"
  mv -f "$service_staging" "$service_file"
  systemctl --user daemon-reload
  systemctl --user enable "$supervisor_service_name" >/dev/null
  systemctl --user restart "$supervisor_service_name"
  systemctl --user is-active --quiet "$supervisor_service_name"
  supervisor_lifecycle_mode=systemd-user
fi

{
  printf 'mode=%s\n' "$supervisor_lifecycle_mode"
  printf 'unit=%s\n' "$supervisor_service_name"
} > "$supervisor_root/lifecycle.env"
chmod 600 "$supervisor_root/lifecycle.env"

supervisor_ready_payload=$(printf '{"protocolVersion":%s}' "$CLAW_IN_ONE_SUPERVISOR_PROTOCOL_VERSION")
emit_handoff_event supervisor_ready "$supervisor_ready_payload"
remove_private_handoff_credentials
trap - ERR

printf 'ClawInOne local service is ready. You can return to the app.\n'
if [[ "$supervisor_lifecycle_mode" == systemd-user ]]; then
  exit 0
fi
exec "$supervisor_current/claw-in-one-supervisor" serve --config "$private_config_file"
