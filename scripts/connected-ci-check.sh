#!/bin/sh
set -eu
task_adb="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-/usr/local/lib/android/sdk}}/platform-tools/adb"
test -x "$task_adb"
"$task_adb" shell cat /proc/meminfo | python3 -c '
import json, re, sys
match = re.search(r"^MemTotal:\s+(\d+)\s+kB", sys.stdin.read(), re.M)
print(json.dumps({"device_memtotal_kib": int(match[1]) if match else None}))
'

# Gradle clears per-test device buffers. Keep a private, temporary runner-only
# buffer until the command ends, then emit fixed categories and delete it.
# Neither this buffer nor free-form device messages are uploaded.
task_crash_log=$(mktemp)
"$task_adb" logcat -b all -v brief > "$task_crash_log" 2>/dev/null &
task_log_pid=$!
finish() {
    task_exit=$?
    kill "$task_log_pid" 2>/dev/null || true
    wait "$task_log_pid" 2>/dev/null || true
    if [ "$task_exit" -ne 0 ]; then
        # Legacy low-memory kills can appear only in the kernel ring buffer.
        # It stays in this private temporary file and is never uploaded.
        "$task_adb" shell dmesg >> "$task_crash_log" 2>/dev/null || true
        "$task_adb" shell cat /proc/meminfo >> "$task_crash_log" 2>/dev/null || true
        python3 scripts/connected-crash-summary.py < "$task_crash_log" || true
    fi
    rm -f "$task_crash_log"
    exit "$task_exit"
}
trap finish EXIT
make smoke-connected GRADLE_RUNNER=wrapper
