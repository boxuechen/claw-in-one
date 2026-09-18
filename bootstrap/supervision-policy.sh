#!/bin/bash

claw_in_one_restart_allowed() {
  local restart_count=$1
  (( restart_count <= 5 ))
}

claw_in_one_restart_delay() {
  local restart_count=$1
  local delay=$((1 << (restart_count - 1)))
  if (( delay > 16 )); then
    delay=16
  fi
  printf '%s\n' "$delay"
}
