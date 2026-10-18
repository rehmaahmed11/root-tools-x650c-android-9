#!/usr/bin/env bash
# ============================================================================
# APK verification - the last link of the CI build chain.
#
# It proves that the artifact produced by the build step is a real, non-empty
# Android package and publishes the facts the report step needs:
#   - $GITHUB_OUTPUT  apk_sha256 / apk_size_bytes / apk_entries
#   - $GITHUB_STEP_SUMMARY
# ============================================================================
set -uo pipefail

cd "$(dirname "${BASH_SOURCE[0]}")/../.."

EXPECTED_APK="${EXPECTED_APK:-app/build/outputs/apk/debug/app-debug.apk}"
LOG_DIR="${CI_LOG_DIR:-.github-ci-logs}"
mkdir -p "$LOG_DIR"

if [ ! -s "$EXPECTED_APK" ]; then
  echo "::error::Expected APK not found or empty: $EXPECTED_APK"
  {
    printf 'apk_sha256=\n'
    printf 'apk_size_bytes=0\n'
    printf 'apk_entries=0\n'
    printf 'apk_verified=false\n'
  } >> "${GITHUB_OUTPUT:-/dev/null}"
  exit 1
fi

sha256="$(sha256sum "$EXPECTED_APK" | cut -d' ' -f1)"
size="$(wc -c < "$EXPECTED_APK" | tr -d ' ')"
entries="$(unzip -l "$EXPECTED_APK" 2>/dev/null | tail -1 | awk '{print $2}')"
[ -n "$entries" ] || entries=0

# A debug APK must at least carry a manifest and compiled code.
if ! unzip -l "$EXPECTED_APK" 2>/dev/null | grep -q 'AndroidManifest.xml'; then
  echo "::error::$EXPECTED_APK does not contain AndroidManifest.xml"
  {
    printf 'apk_sha256=%s\n' "$sha256"
    printf 'apk_size_bytes=%s\n' "$size"
    printf 'apk_entries=%s\n' "$entries"
    printf 'apk_verified=false\n'
  } >> "${GITHUB_OUTPUT:-/dev/null}"
  exit 1
fi

{
  printf 'apk_sha256=%s\n' "$sha256"
  printf 'apk_size_bytes=%s\n' "$size"
  printf 'apk_entries=%s\n' "$entries"
  printf 'apk_verified=true\n'
} >> "${GITHUB_OUTPUT:-/dev/null}"

if [ -n "${GITHUB_STEP_SUMMARY:-}" ]; then
  {
    printf '### APK\n\n'
    printf '| Item | Value |\n|---|---|\n'
    printf '| Path | `%s` |\n' "$EXPECTED_APK"
    printf '| Size | %s bytes |\n' "$size"
    printf '| Entries | %s |\n' "$entries"
    printf '| SHA-256 | `%s` |\n\n' "$sha256"
  } >> "$GITHUB_STEP_SUMMARY"
fi

ls -lh "$EXPECTED_APK"
echo "sha256: $sha256"
echo "size:   $size bytes"
echo "entries:$entries"
