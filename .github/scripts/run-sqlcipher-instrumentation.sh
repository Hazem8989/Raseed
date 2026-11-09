#!/usr/bin/env bash
set -euo pipefail

REPORT_DIR="app/build/reports/sqlcipher-instrumentation"
mkdir -p "$REPORT_DIR"
trap 'adb logcat -d -t 6000 > "$REPORT_DIR/logcat.txt" 2>/dev/null || true' EXIT

SUITE="${1:-all}"

# Emulator boot completion can precede system_server readiness. In particular,
# package/activity binder services may briefly disappear after a slow AVD boot.
wait_for_android_framework() {
  local attempt
  adb wait-for-device
  for attempt in $(seq 1 90); do
    if [ "$(adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = "1" ] &&
       adb shell service check package 2>&1 | grep -q "found" &&
       adb shell service check activity 2>&1 | grep -q "found"; then
      return 0
    fi
    sleep 2
  done
  echo "Android framework services did not become ready (package/activity)." >&2
  adb shell getprop sys.boot_completed >&2 || true
  adb shell service list >&2 || true
  return 1
}

install_apk_with_recovery() {
  local apk="$1"
  local attempt
  local install_log="$REPORT_DIR/install-$(basename "$apk").log"
  for attempt in 1 2 3 4; do
    wait_for_android_framework
    echo "Installing $(basename "$apk") (attempt $attempt/4)"
    if adb install --no-streaming -r -t "$apk" 2>&1 | tee "$install_log"; then
      return 0
    fi
    if ! grep -Eqi "Broken pipe|Can't find service: (package|activity)|device offline|no devices/emulators found|transport.*error" "$install_log"; then
      echo "APK installation failed for a non-transient reason; not retrying." >&2
      return 1
    fi
    echo "Transient emulator/binder failure during APK installation; reconnecting and waiting for Android services."
    adb reconnect || true
    sleep 5
  done
  echo "APK installation did not recover after four attempts: $apk" >&2
  adb logcat -d -v threadtime > "$REPORT_DIR/install-failure-logcat.txt" 2>/dev/null || true
  return 1
}

wait_for_android_framework
install_apk_with_recovery app/build/outputs/apk/debug/app-debug.apk
install_apk_with_recovery app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk

run_instrumentation() {
  local selector="$1"
  local report_file="$2"
  local instrumentation_status=1
  local attempt_log=""
  local attempt
  for attempt in 1 2 3; do
    attempt_log="$REPORT_DIR/${report_file%.txt}-attempt-${attempt}.txt"
    echo "Running isolated instrumentation selector (20-minute limit, attempt $attempt/3): $selector"
    set +e
    timeout --signal=TERM --kill-after=30s 20m adb shell am instrument -w -r -e class "$selector" \
      com.hazem.dailystore.test/androidx.test.runner.AndroidJUnitRunner \
      2>&1 | tee "$attempt_log"
    instrumentation_status=${PIPESTATUS[0]}
    set -e

    if [ "$instrumentation_status" -eq 0 ]; then
      cp "$attempt_log" "$REPORT_DIR/$report_file"
      break
    fi

    # A transient system_server restart can make the instrumentation command
    # throw this framework NPE before the test process starts. Retry only this
    # exact infrastructure signature; real test failures remain immediate failures.
    if grep -Fq "IActivityManager.startInstrumentation" "$attempt_log" &&
       grep -Fq "on a null object reference" "$attempt_log" &&
       [ "$attempt" -lt 3 ]; then
      echo "Android ActivityManager was temporarily unavailable before instrumentation. Waiting for the binder service before retry."
      for recovery_attempt in $(seq 1 30); do
        if adb shell service check activity 2>&1 | grep -q "found"; then
          sleep 3
          break
        fi
        sleep 2
      done
      continue
    fi

    break
  done

  if [ "$instrumentation_status" -ne 0 ]; then
    cp "$attempt_log" "$REPORT_DIR/$report_file"
    if [ "$instrumentation_status" -eq 124 ]; then
      echo "Instrumentation selector $selector exceeded the 20-minute limit; collecting ART thread dumps before cleanup."
      for package_name in com.hazem.dailystore com.hazem.dailystore.test; do
        package_pid="$(adb shell pidof "$package_name" 2>/dev/null | tr -d '\r' || true)"
        if [ -n "$package_pid" ]; then
          adb shell kill -3 $package_pid 2>/dev/null || true
        fi
      done
      adb shell dumpsys activity processes > "$REPORT_DIR/processes-$report_file" 2>&1 || true
      sleep 3
      adb logcat -d -v threadtime > "$REPORT_DIR/timeout-thread-dump-$report_file" 2>/dev/null || true
      echo "Thread dumps and process state saved for $selector."
    else
      echo "Instrumentation selector $selector failed with exit code $instrumentation_status after infrastructure retry policy."
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
  startup_barrier)
    run_instrumentation "com.hazem.dailystore.DatabaseStartupBarrierInstrumentedTest" "database-startup-barrier.txt"
    ;;
  end_to_end)
    run_instrumentation "com.hazem.dailystore.data.SqlCipherBackupRestoreInstrumentedTest#encryptedDatabaseBackupRestoresOnFreshDeviceVaultWithAllDataIntact" "restore-end-to-end.txt"
    ;;
  pipeline)
    run_instrumentation "com.hazem.dailystore.data.SqlCipherBackupRestoreInstrumentedTest#pipelineRejectsWrongPasswordBeforeStaging" "pipeline-wrong-password.txt"
    run_instrumentation "com.hazem.dailystore.data.SqlCipherBackupRestoreInstrumentedTest#pipelineRejectsTamperedSqlCipherPayload" "pipeline-tampered-payload.txt"
    run_instrumentation "com.hazem.dailystore.data.SqlCipherBackupRestoreInstrumentedTest#pipelineRejectsTruncatedBackupPayload" "pipeline-truncated-payload.txt"
    run_instrumentation "com.hazem.dailystore.data.SqlCipherBackupRestoreInstrumentedTest#pipelineRejectsTrailingBackupBytes" "pipeline-trailing-bytes.txt"
    run_instrumentation "com.hazem.dailystore.data.SqlCipherBackupRestoreInstrumentedTest#pipelineStagesAndCommitsValidEncryptedBackup" "pipeline-valid-restore.txt"
    run_instrumentation "com.hazem.dailystore.data.SqlCipherBackupRestoreInstrumentedTest#pipelineRejectsBackupWhileWalIsNonEmpty" "pipeline-wal-rejection.txt"
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
