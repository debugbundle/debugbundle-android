#!/bin/sh
set -eu

ANDROID_SDK_ROOT="${ANDROID_SDK_ROOT:-$(pwd)/.android-sdk}"
ANDROID_USER_HOME="${ANDROID_USER_HOME:-$(pwd)/.android-home}"
CMDLINE_TOOLS_VERSION="${CMDLINE_TOOLS_VERSION:-13114758}"
CMDLINE_TOOLS_ZIP="commandlinetools-linux-${CMDLINE_TOOLS_VERSION}_latest.zip"
CMDLINE_TOOLS_URL="https://dl.google.com/android/repository/${CMDLINE_TOOLS_ZIP}"
SDKMANAGER="${ANDROID_SDK_ROOT}/cmdline-tools/latest/bin/sdkmanager"

mkdir -p "${ANDROID_SDK_ROOT}" "${ANDROID_USER_HOME}"

if [ ! -x "${SDKMANAGER}" ]; then
    rm -rf "${ANDROID_SDK_ROOT}/cmdline-tools"
    mkdir -p "${ANDROID_SDK_ROOT}/cmdline-tools"
    curl -fsSL "${CMDLINE_TOOLS_URL}" -o "/tmp/${CMDLINE_TOOLS_ZIP}"
    unzip -q "/tmp/${CMDLINE_TOOLS_ZIP}" -d "${ANDROID_SDK_ROOT}/cmdline-tools"
    mv "${ANDROID_SDK_ROOT}/cmdline-tools/cmdline-tools" "${ANDROID_SDK_ROOT}/cmdline-tools/latest"
fi

yes | "${SDKMANAGER}" --sdk_root="${ANDROID_SDK_ROOT}" --licenses >/dev/null
"${SDKMANAGER}" --sdk_root="${ANDROID_SDK_ROOT}" \
    "platform-tools" \
    "platforms;android-36" \
    "build-tools;36.0.0" >/dev/null

exec "$@"
