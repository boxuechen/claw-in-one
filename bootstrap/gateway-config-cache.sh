#!/bin/bash

# Content-addressed fast path for product-owned Gateway configuration. A user or
# Gateway edit changes the config digest and forces a full non-destructive reconciliation.
gateway_config_cache_is_current() {
  local config_file=$1
  local stamp_file=$2
  local revision=$3
  local openclaw_version=$4
  local config_sha256
  local expected
  [[ -f "$config_file" && ! -L "$stamp_file" && -f "$stamp_file" ]] || return 1
  config_sha256=$(sha256sum "$config_file" | awk '{print $1}')
  [[ "$config_sha256" =~ ^[0-9a-f]{64}$ ]] || return 1
  expected="$revision|$openclaw_version|$config_sha256"
  [[ $(tr -d '\r\n' < "$stamp_file") == "$expected" ]]
}

gateway_config_cache_commit() {
  local config_file=$1
  local stamp_file=$2
  local revision=$3
  local openclaw_version=$4
  local config_sha256
  local temporary
  [[ -f "$config_file" ]] || return 1
  config_sha256=$(sha256sum "$config_file" | awk '{print $1}')
  [[ "$config_sha256" =~ ^[0-9a-f]{64}$ ]] || return 1
  temporary="$stamp_file.next.$$"
  printf '%s|%s|%s\n' "$revision" "$openclaw_version" "$config_sha256" > "$temporary"
  chmod 600 "$temporary"
  mv -f "$temporary" "$stamp_file"
}
