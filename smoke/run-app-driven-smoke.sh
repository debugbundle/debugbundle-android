#!/usr/bin/env bash

set -euo pipefail

usage() {
  echo "Usage: sh ./smoke/run-app-driven-smoke.sh [--published <version>]" >&2
}

repo_root="$(cd "$(dirname "$0")/.." && pwd)"
smoke_dir="$repo_root/smoke/android-consumer"
local_repo="$(mktemp -d "${TMPDIR:-/tmp}/debugbundle-android-smoke.XXXXXX")"
published_version=""

cleanup() {
  rm -rf "$local_repo"
}

trap cleanup EXIT

while [[ $# -gt 0 ]]; do
  case "$1" in
    --published)
      shift
      if [[ $# -eq 0 ]]; then
        usage
        exit 1
      fi
      published_version="$1"
      shift
      ;;
    *)
      usage
      exit 1
      ;;
  esac
done

if [[ -n "$published_version" ]]; then
  version="$published_version"
  extra_props=()
else
  version="$(awk -F= '$1 == "VERSION_NAME" { print $2; exit }' "$repo_root/gradle.properties")"
  if [[ -z "$version" ]]; then
    echo "Unable to resolve VERSION_NAME from gradle.properties." >&2
    exit 1
  fi
  sh "$repo_root/scripts/with-android-sdk.sh" \
    "$repo_root/gradlew" \
    --no-daemon \
    -PVERSION_NAME="$version" \
    -PdebugbundlePublishRepo="$local_repo" \
    publishAllPublicationsToSmokeRepository
  extra_props=(-PdebugbundleRepoUrl="$local_repo")
fi

if [[ -n "$published_version" ]]; then
  python3 "$repo_root/scripts/check-artifact-licenses.py" --version "$version"
else
  python3 "$repo_root/scripts/check-artifact-licenses.py" --version "$version" --repository "$local_repo"
fi

sh "$repo_root/scripts/with-android-sdk.sh" \
  "$repo_root/gradlew" \
  --no-daemon \
  -p "$smoke_dir" \
  -PdebugbundleVersion="$version" \
  "${extra_props[@]}" \
  test
