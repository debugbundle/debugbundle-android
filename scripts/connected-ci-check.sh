#!/bin/sh
set -eu
task_adb="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-/usr/local/lib/android/sdk}}/platform-tools/adb"
test -x "$task_adb"

# Gradle clears per-test device buffers. Keep a private, temporary runner-only
# buffer until the command ends, then emit fixed categories and delete it.
# Neither this buffer nor free-form device messages are uploaded.
task_crash_log=$(mktemp)
"$task_adb" logcat -b all -v brief > "$task_crash_log" 2>/dev/null &
task_log_pid=$!
trap 'task_exit=$?; kill "$task_log_pid" 2>/dev/null || true; wait "$task_log_pid" 2>/dev/null || true; if [ "$task_exit" -ne 0 ]; then python3 scripts/connected-crash-summary.py < "$task_crash_log" || true; fi; rm -f "$task_crash_log"; exit "$task_exit"' EXIT
make smoke-connected GRADLE_RUNNER=wrapper
