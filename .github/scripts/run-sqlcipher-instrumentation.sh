#!/usr/bin/env bash
set -euo pipefail

REPORT_DIR="app/build/reports/sqlcipher-instrumentation"
mkdir -p "$REPORT_DIR"
trap 'adb logcat -d -t 4000 > "$REPORT_DIR/logcat.txt" 2>/dev/null || true' EXIT

/tmp/gradle-8.9/bin/gradle --no-daemon \
  :app:assembleDebug \
  :app:assembleDebugAndroidTest \
  --stacktrace

adb wait-for-device
for attempt in $(seq 1 60); do
  if [ "$(adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = "1" ]; then
    break
  fi
  sleep 2
done
test "$(adb shell getprop sys.boot_completed | tr -d '\r')" = "1"

adb install --no-streaming -r -t app/build/outputs/apk/debug/app-debug.apk
adb install --no-streaming -r -t app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk

set +e
adb shell am instrument -w -r \
  com.hazem.dailystore.test/androidx.test.runner.AndroidJUnitRunner \
  2>&1 | tee "$REPORT_DIR/instrumentation.txt"
instrumentation_status=${PIPESTATUS[0]}
set -e

if [ "$instrumentation_status" -ne 0 ]; then
  echo "Instrumentation command failed with exit code $instrumentation_status"
  exit "$instrumentation_status"
fi
if ! grep -Eq 'OK \([1-9][0-9]* tests?\)' "$REPORT_DIR/instrumentation.txt"; then
  echo "Instrumentation did not report a passing test count."
  exit 1
fi
