#!/bin/bash
set -Eeuo pipefail

script_dir=$(
  CDPATH= cd -- "$(dirname -- "$0")"
  pwd
)
shared_dir=${CLAW_IN_ONE_SHARED_DIR:-$script_dir}
export CLAW_IN_ONE_SHARED_DIR="$shared_dir"
: "${CLAW_IN_ONE_CONFIG_FILE:?Missing one-time Bootstrap configuration}"

printf 'ClawInOne bootstrap reached Debian.\n'
exec bash "$shared_dir/install-supervisor.sh"
