#!/usr/bin/env bash
set -euo pipefail

PACKAGE="io.github.boxuechen.clawinone.debug"
ACTIVITY="ai.openclaw.app.MainActivity"
DEVICE_SERIAL=""
OUTPUT_DIR="/tmp/claw-in-one-product-shell-$(date +%Y%m%d-%H%M%S)"
CAPTURE_DELAY_SECONDS="3"
THEME="dark"
AVAILABLE_SCENES=(blank-chat chat drawer settings local-environment)
SCENES=("${AVAILABLE_SCENES[@]}")

usage() {
  cat <<'EOF'
Usage:
  ./scripts/capture-product-shell.sh [options]

Captures the deterministic ClawInOne product-shell states from a connected
Android device. The app must already be installed.

Options:
  --device <serial>       adb device serial
  --output <directory>    output directory (default: /tmp/claw-in-one-product-shell-<timestamp>)
  --package <package>     installed package (default: io.github.boxuechen.clawinone.debug)
  --delay <seconds>       settle delay before each capture (default: 3)
  --theme <theme>         dark, light, or system (default: dark)
  --scene <scene>         capture one scene instead of the default set
  -h, --help              show help
EOF
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --device)
      DEVICE_SERIAL="${2:-}"
      shift 2
      ;;
    --output)
      OUTPUT_DIR="${2:-}"
      shift 2
      ;;
    --package)
      PACKAGE="${2:-}"
      shift 2
      ;;
    --delay)
      CAPTURE_DELAY_SECONDS="${2:-}"
      shift 2
      ;;
    --theme)
      THEME="${2:-}"
      shift 2
      ;;
    --scene)
      SCENES=("${2:-}")
      shift 2
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    *)
      echo "Unknown arg: $1" >&2
      usage >&2
      exit 2
      ;;
  esac
done

case "$THEME" in
  dark|light|system) ;;
  *)
    echo "Invalid theme: $THEME (expected dark, light, or system)" >&2
    exit 2
    ;;
esac

for scene in "${SCENES[@]}"; do
  case "$scene" in
    blank-chat|chat|drawer|settings|local-environment) ;;
    *)
      echo "Invalid scene: $scene" >&2
      echo "Expected one of: ${AVAILABLE_SCENES[*]}" >&2
      exit 2
      ;;
  esac
done

if ! command -v adb >/dev/null 2>&1; then
  echo "adb required but missing." >&2
  exit 1
fi

adb_cmd() {
  if [[ -n "$DEVICE_SERIAL" ]]; then
    adb -s "$DEVICE_SERIAL" "$@"
  else
    adb "$@"
  fi
}

device_count="$(adb devices | awk 'NR > 1 && $2 == "device" { count += 1 } END { print count + 0 }')"
if [[ -z "$DEVICE_SERIAL" && "$device_count" -ne 1 ]]; then
  echo "Expected exactly one authorized device; pass --device <serial>." >&2
  adb devices -l >&2
  exit 1
fi

if [[ -z "$OUTPUT_DIR" || "$OUTPUT_DIR" == "/" ]]; then
  echo "Refusing invalid output directory: $OUTPUT_DIR" >&2
  exit 2
fi

mkdir -p "$OUTPUT_DIR"

{
  echo "serial=$(adb_cmd get-serialno | tr -d '\r')"
  echo "model=$(adb_cmd shell getprop ro.product.model | tr -d '\r')"
  echo "release=$(adb_cmd shell getprop ro.build.version.release | tr -d '\r')"
  echo "sdk=$(adb_cmd shell getprop ro.build.version.sdk | tr -d '\r')"
  echo "display=$(adb_cmd shell wm size | tr -d '\r' | paste -sd ';' -)"
  echo "density=$(adb_cmd shell wm density | tr -d '\r' | paste -sd ';' -)"
  echo "theme=$THEME"
} >"$OUTPUT_DIR/device.txt"

component="$PACKAGE/$ACTIVITY"

# Warm the process once so the first baseline does not capture the cold-start
# splash instead of the requested production shell state.
adb_cmd shell am force-stop "$PACKAGE" >/dev/null
adb_cmd shell am start \
  -W \
  -n "$component" \
  --ez openclaw.screenshotMode true \
  --es openclaw.screenshotScene home \
  --es openclaw.screenshotTheme "$THEME" >/dev/null
sleep "$CAPTURE_DELAY_SECONDS"

for scene in "${SCENES[@]}"; do
  adb_cmd shell am force-stop "$PACKAGE" >/dev/null
  adb_cmd shell am start \
    -W \
    -n "$component" \
    --ez openclaw.screenshotMode true \
    --es openclaw.screenshotScene "$scene" \
    --es openclaw.screenshotTheme "$THEME" >/dev/null
  sleep "$CAPTURE_DELAY_SECONDS"
  adb_cmd exec-out screencap -p >"$OUTPUT_DIR/$scene.png"
done

echo "screenshots=$OUTPUT_DIR"
