#!/bin/bash
set -Eeuo pipefail

repo_root=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
requested=
groups=
list_only=false

usage() {
  printf '%s\n' 'Usage: bash scripts/check.sh [--scope SCOPE ...] [--list]' \
    'Scopes: docs, tooling, android, android-use, vscreen, web, supervisor, terminal, all (default).' \
    'Scopes can be combined; shared checks run once. --list executes no checks.'
}
while [[ $# -gt 0 ]]; do
  case "$1" in
    --scope)
      [[ $# -ge 2 ]] || { usage >&2; exit 2; }
      case "$2" in docs|tooling|android|android-use|vscreen|web|supervisor|terminal|all) ;; *) usage >&2; exit 2 ;; esac
      requested="$requested $2"; shift 2 ;;
    --list) list_only=true; shift ;;
    --help|-h) usage; exit 0 ;;
    *) usage >&2; exit 2 ;;
  esac
done
[[ -n "$requested" ]] || requested=' all'
add_group() {
  case " $groups " in *" $1 "*) ;; *) groups="${groups:+$groups }$1" ;; esac
}
for scope in $requested; do
  case "$scope" in
    all) for group in tooling docs supervisor bootstrap vscreen android-use web terminal android; do add_group "$group"; done ;;
    supervisor|android-use|vscreen|web) add_group "$scope"; add_group bootstrap ;;
    *) add_group "$scope" ;;
  esac
done

run() {
  local cwd=$1
  shift
  printf '  (%s)' "${cwd#"$repo_root"}"
  printf ' %q' "$@"
  printf '\n'
  if [[ "$list_only" == false ]]; then (cd "$cwd" && "$@"); fi
}
run_group() {
  case "$1" in
    tooling) run "$repo_root" node --test test/android-i18n.test.mjs test/android-kotlin-profile.test.mjs test/android-native-profile.test.mjs test/development-skill-contract.test.mjs test/flutter-profile.test.mjs test/godot-android-profile.test.mjs test/react-native-profile.test.mjs test/check-runner.test.mjs test/docs-links.test.mjs test/verification-candidate.test.mjs test/verification-device.test.mjs test/verification-smoke.test.mjs ;;
    docs) run "$repo_root" node scripts/check-docs.mjs; run "$repo_root" git diff --check ;;
    supervisor)
      run "$repo_root/supervisor" cargo fmt --all -- --check
      run "$repo_root/supervisor" cargo clippy --locked --all-targets -- -D warnings
      run "$repo_root/supervisor" cargo test --locked
      run "$repo_root/supervisor" cargo build --locked --release --target aarch64-unknown-linux-musl
      ;;
    bootstrap)
      run "$repo_root" node --test test/openclaw-release-contract.test.mjs
      run "$repo_root" bash test/bootstrap-contract.sh
      ;;
    vscreen) run "$repo_root" node test/vscreen-foundation-plugin.mjs ;;
    android-use)
      run "$repo_root" node --test test/project-workspaces-plugin.mjs
      run "$repo_root" node test/android-use-plugin.mjs
      run "$repo_root" node test/android-developer-bridge-plugin.mjs
      ;;
    web)
      run "$repo_root" node --test test/web-development-profile.test.mjs test/web-development-plugin.test.mjs test/android-device-reverse-port.test.mjs
      run "$repo_root" node scripts/android-i18n.mjs check
      run "$repo_root/apps/android" ./gradlew :app:ktlintCheck
      run "$repo_root/apps/android" ./gradlew :app:testThirdPartyDebugUnitTest --tests ai.openclaw.app.webdelivery.WebProjectResultsTest
      ;;
    terminal) run "$repo_root" node test/terminal-mobile.mjs ;;
    android)
      run "$repo_root" node scripts/android-i18n.mjs check
      run "$repo_root/apps/android" ./gradlew \
        :android-use-fixture:ktlintCheck :android-use-fixture:testDebugUnitTest \
        :android-use-fixture:lintDebug :android-use-fixture:assembleDebug \
        :app:ktlintCheck :app:testThirdPartyDebugUnitTest \
        :app:lintThirdPartyDebug :app:assembleThirdPartyDebug
      ;;
  esac
}

started=$SECONDS
completed=
active=
group_started=$SECONDS
report() {
  local code=$?
  trap - EXIT
  if [[ -n "$active" ]]; then
    printf '\nFAIL %s (%ss, exit %s)\n' "$active" "$((SECONDS - group_started))" "$code"
  fi
  for group in $groups; do
    case " $completed " in *" $group "*) continue ;; esac
    [[ "$group" == "$active" ]] || printf 'NOT RUN %s\n' "$group"
  done
  printf 'Check run finished in %ss (exit %s).\n' "$((SECONDS - started))" "$code"
  exit "$code"
}
if [[ "$list_only" == false ]]; then trap report EXIT; fi
for group in $groups; do
  printf '\n%s\n' "CHECK $group"
  active=$group
  group_started=$SECONDS
  run_group "$group"
  if [[ "$list_only" == false ]]; then printf 'PASS %s (%ss)\n' "$group" "$((SECONDS - group_started))"; fi
  completed="$completed $group"
  active=
done
if [[ "$list_only" == true ]]; then printf '\nPlan only: NOT RUN (no checks executed).\n'; fi
