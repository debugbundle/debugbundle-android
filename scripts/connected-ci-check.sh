#!/bin/sh
set -eu
task_adb="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-/usr/local/lib/android/sdk}}/platform-tools/adb"
test -x "$task_adb"

# Only allowlisted crash types/signals leave the disposable runner; raw device
# logs and exception messages are never retained or uploaded.
trap 'task_exit=$?; if [ "$task_exit" -ne 0 ]; then "$task_adb" logcat -b all -d 2>/dev/null | python3 scripts/connected-crash-summary.py || true; fi; exit "$task_exit"' EXIT
make smoke-connected GRADLE_RUNNER=wrapper
