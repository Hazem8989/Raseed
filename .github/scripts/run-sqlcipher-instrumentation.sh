#!/usr/bin/env bash
set -euo pipefail

REPORT_DIR="app/build/reports/sqlcipher-instrumentation"
mkdir -p "$REPORT_DIR"
trap 'adb logcat -d -t 6000 > "$REPORT_DIR/logcat.txt" 2>/dev/null || true' EXIT

SUITE="${1:-all}"
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

run_instrumentation() {
  local selector="$1"
  local report_file="$2"
  echo "Running isolated instrumentation selector (8-minute limit): $selector"
  set +e
  timeout --signal=TERM --kill-after=30s 8m adb shell am instrument -w -r -e class "$selector" \
    com.hazem.dailystore.test/androidx.test.runner.AndroidJUnitRunner \
    2>&1 | tee "$REPORT_DIR/$report_file"
  local instrumentation_status=${PIPESTATUS[0]}
  set -e
  if [ "$instrumentation_status" -ne 0 ]; then
    if [ "$instrumentation_status" -eq 124 ]; then
      echo "Instrumentation selector $selector exceeded the 8-minute limit; see logcat.txt and its selector report."
    else
      echo "Instrumentation selector $selector failed with exit code $instrumentation_status"
    fi
    return "$instrumentation_status"
  fi
  if ! grep -Eq 'OK \([1-9][0-9]* tests?\)' "$REPORT_DIR/$report_file"; then
    echo "Instrumentation selector $selector did not report a passing test count."
    return 1
  fi
}

case "$SUITE" in
  core)
    run_instrumentation "com.hazem.dailystore.data.DatabaseKeyVaultInstrumentedTest" "database-key-vault.txt"
    run_instrumentation "com.hazem.dailystore.data.DatabaseMigrationInstrumentedTest" "database-migration.txt"
    run_instrumentation "com.hazem.dailystore.data.SqlCipherDatabaseMigratorInstrumentedTest" "sqlcipher-database-migrator.txt"
    run_instrumentation "com.hazem.dailystore.data.SqlCipherCompatibilityInstrumentedTest" "sqlcipher-compatibility.txt"
    ;;
  coordinator)
    run_instrumentation "com.hazem.dailystore.data.SqlCipherBackupRestoreInstrumentedTest#restoreCoordinatorWaitsForMissingKeyAndResumesAfterKeyImport" "restore-coordinator.txt"
    run_instrumentation "com.hazem.dailystore.data.SqlCipherSameDeviceRestoreInstrumentedTest" "same-device-restore-crash-recovery.txt"
    ;;
  end_to_end)
    run_instrumentation "com.hazem.dailystore.data.SqlCipherBackupRestoreInstrumentedTest#encryptedDatabaseBackupRestoresOnFreshDeviceVaultWithAllDataIntact" "restore-end-to-end.txt"
    ;;
  pipeline)
    run_instrumentation "com.hazem.dailystore.data.SqlCipherBackupRestoreInstrumentedTest#pipelineRoundTripsAndRejectsWrongPasswordAndTamperedPayload" "backup-pipeline.txt"
    run_instrumentation "com.hazem.dailystore.data.SqlCipherBackupRestoreInstrumentedTest#pipelineRejectsNonEmptyDestinationAndKeyVaultRejectsDifferentKeyButAllowsRetry" "backup-rejection-safety.txt"
    run_instrumentation "com.hazem.dailystore.data.SqlCipherBackupRestoreInstrumentedTest#restoreJournalPersistsMonotonicPhasesWithoutStoringSecrets" "restore-journal-phases.txt"
    run_instrumentation "com.hazem.dailystore.data.SqlCipherBackupRestoreInstrumentedTest#restoreJournalRejectsSkippedOrReversedPhasesAndDifferentPaths" "restore-journal-order.txt"
    run_instrumentation "com.hazem.dailystore.data.SqlCipherBackupRestoreInstrumentedTest#restoreJournalCorruptionFailsClosedAndPreservesDatabaseFiles" "restore-journal-corruption.txt"
    ;;
  *)
    echo "Unknown SQLCipher test suite: $SUITE" >&2
    exit 2
    ;;
esac

echo "SQLCipher Android instrumentation suite '$SUITE' passed."
