#!/usr/bin/env bash
set -euo pipefail

REPORT_DIR="app/build/reports/sqlcipher-instrumentation"
mkdir -p "$REPORT_DIR"
trap 'adb logcat -d -t 6000 > "$REPORT_DIR/logcat.txt" 2>/dev/null || true' EXIT

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

run_instrumentation_class() {
  local test_class="$1"
  local report_file="$2"
  echo "Running isolated instrumentation class: $test_class"

  set +e
  adb shell am instrument -w -r -e class "$test_class" \
    com.hazem.dailystore.test/androidx.test.runner.AndroidJUnitRunner \
    2>&1 | tee "$REPORT_DIR/$report_file"
  local instrumentation_status=${PIPESTATUS[0]}
  set -e

  if [ "$instrumentation_status" -ne 0 ]; then
    echo "Instrumentation class $test_class failed with exit code $instrumentation_status"
    return "$instrumentation_status"
  fi
  if ! grep -Eq 'OK \([1-9][0-9]* tests?\)' "$REPORT_DIR/$report_file"; then
    echo "Instrumentation class $test_class did not report a passing test count."
    return 1
  fi
}

# Run each suite in its own instrumentation process. This limits accumulated
# test-process state and makes a failure attributable to one class.
run_instrumentation_class \
  "com.hazem.dailystore.data.DatabaseKeyVaultInstrumentedTest" \
  "database-key-vault.txt"
run_instrumentation_class \
  "com.hazem.dailystore.data.DatabaseMigrationInstrumentedTest" \
  "database-migration.txt"
run_instrumentation_class \
  "com.hazem.dailystore.data.SqlCipherDatabaseMigratorInstrumentedTest" \
  "sqlcipher-database-migrator.txt"
run_instrumentation_class \
  "com.hazem.dailystore.data.SqlCipherCompatibilityInstrumentedTest" \
  "sqlcipher-compatibility.txt"
run_instrumentation_class \
  "com.hazem.dailystore.data.SqlCipherBackupRestoreInstrumentedTest" \
  "sqlcipher-backup-restore.txt"

echo "All SQLCipher Android instrumentation classes passed."
