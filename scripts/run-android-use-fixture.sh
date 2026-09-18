#!/bin/bash
set -Eeuo pipefail

repo_root=$(
  CDPATH= cd -- "$(dirname -- "$0")/.."
  pwd
)
android_serial=${ANDROID_SERIAL:?Set ANDROID_SERIAL to the target device serial.}
adb_binary=${ANDROID_ADB:-adb}
fixture_package=io.github.boxuechen.clawinone.fixture
fixture_activity=ai.clawinone.androiduse.fixture.FixtureFormActivity
fixture_apk="$repo_root/apps/android/android-use-fixture/build/outputs/apk/debug/android-use-fixture-debug.apk"

(
  cd "$repo_root/apps/android"
  ./gradlew :android-use-fixture:assembleDebug
)

"$adb_binary" -s "$android_serial" install -r "$fixture_apk"
"$adb_binary" -s "$android_serial" shell am force-stop "$fixture_package"
"$adb_binary" -s "$android_serial" shell am start -W -n "$fixture_package/$fixture_activity"
