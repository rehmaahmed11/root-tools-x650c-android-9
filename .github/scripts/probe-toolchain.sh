#!/usr/bin/env bash
# ============================================================================
# Toolchain probe - the first link of the CI tool chain.
#
# It detects what is actually available on the runner and publishes the
# findings so every later step (tests, build, verify, report) can consume them
# instead of probing the environment again:
#   - $GITHUB_OUTPUT   java_version / gradle_version / sdk_dir / build_tools
#   - .github-ci-logs/toolchain.json
#   - $GITHUB_STEP_SUMMARY  a short "environment" block
# ============================================================================
set -uo pipefail

cd "$(dirname "${BASH_SOURCE[0]}")/../.."

LOG_DIR="${CI_LOG_DIR:-.github-ci-logs}"
mkdir -p "$LOG_DIR"

SDK_DIR="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-/usr/local/lib/android/sdk}}"
GRADLE_VERSION="unknown"
BUILD_TOOLS="unknown"
PLATFORM="unknown"

json_escape() { printf '%s' "$1" | sed -e 's/\\/\\\\/g' -e 's/"/\\"/g' | tr -d '\r'; }

# $GITHUB_OUTPUT values are plain text: they must not contain newlines and must
# not be JSON escaped.
flat() { printf '%s' "$1" | tr '\r\n' '  ' | sed -e 's/[[:space:]]\{1,\}/ /g' -e 's/^ //' -e 's/ $//'; }

# --- Java ------------------------------------------------------------------
if command -v java >/dev/null 2>&1; then
  # Strip the quotes that `java -version` prints so the value stays a simple
  # single-line string in the JSON and in $GITHUB_OUTPUT.
  JAVA_VERSION="$(java -version 2>&1 | head -1 | tr -d '"' | tr -d '\r')"
else
  JAVA_VERSION="not found"
fi
if [ -n "${JAVA_HOME:-}" ] && [ -x "$JAVA_HOME/bin/java" ]; then
  JAVA_HOME_PATH="$JAVA_HOME"
elif command -v java >/dev/null 2>&1; then
  JAVA_HOME_PATH="$(dirname "$(dirname "$(readlink -f "$(command -v java)")")")"
else
  JAVA_HOME_PATH=""
fi

# --- Gradle ----------------------------------------------------------------
if [ -x "$HOME/gradle-dist/gradle-${CI_GRADLE_VERSION:-8.9}/bin/gradle" ]; then
  GRADLE_VERSION="cached ${CI_GRADLE_VERSION}"
elif [ -x ./gradlew ]; then
  GRADLE_VERSION="wrapper"
elif command -v gradle >/dev/null 2>&1; then
  GRADLE_VERSION="$(gradle --version 2>/dev/null | sed -n 's/^Gradle \([0-9][0-9.]*\).*/\1/p' | head -1)"
  GRADLE_VERSION="${GRADLE_VERSION:-unknown}"
else
  GRADLE_VERSION="to be downloaded (${CI_GRADLE_VERSION:-8.9})"
fi

# --- Android SDK -----------------------------------------------------------
if [ -d "$SDK_DIR" ]; then
  BUILD_TOOLS="$(ls -1 "$SDK_DIR/build-tools" 2>/dev/null | sort -V | tail -1)"
  PLATFORM="$(ls -1 "$SDK_DIR/platforms" 2>/dev/null | sort -V | tail -1)"
fi
[ -n "$BUILD_TOOLS" ] || BUILD_TOOLS="none"
[ -n "$PLATFORM" ] || PLATFORM="none"

{
  printf '{\n'
  printf '  "javaVersion": "%s",\n' "$(json_escape "$JAVA_VERSION")"
  printf '  "javaHome": "%s",\n' "$(json_escape "$JAVA_HOME_PATH")"
  printf '  "gradleVersion": "%s",\n' "$(json_escape "$GRADLE_VERSION")"
  printf '  "sdkDir": "%s",\n' "$(json_escape "$SDK_DIR")"
  printf '  "buildTools": "%s",\n' "$(json_escape "$BUILD_TOOLS")"
  printf '  "platform": "%s"\n' "$(json_escape "$PLATFORM")"
  printf '}\n'
} > "$LOG_DIR/toolchain.json"

if [ -n "${GITHUB_OUTPUT:-}" ]; then
  {
    printf 'java_version=%s\n' "$(flat "$JAVA_VERSION")"
    printf 'gradle_version=%s\n' "$(flat "$GRADLE_VERSION")"
    printf 'sdk_dir=%s\n' "$(flat "$SDK_DIR")"
    printf 'build_tools=%s\n' "$(flat "$BUILD_TOOLS")"
    printf 'android_platform=%s\n' "$(flat "$PLATFORM")"
  } >> "$GITHUB_OUTPUT"
fi

if [ -n "${GITHUB_STEP_SUMMARY:-}" ]; then
  {
    printf '### Environment\n\n'
    printf '| Item | Value |\n|---|---|\n'
    printf '| Java | %s |\n' "$JAVA_VERSION"
    printf '| Gradle | %s |\n' "$GRADLE_VERSION"
    printf '| Android SDK | %s |\n' "$SDK_DIR"
    printf '| Build tools | %s |\n' "$BUILD_TOOLS"
    printf '| Platform | %s |\n\n' "$PLATFORM"
  } >> "$GITHUB_STEP_SUMMARY"
fi

echo "java:    $JAVA_VERSION"
echo "gradle:  $GRADLE_VERSION"
echo "sdk:     $SDK_DIR"
echo "tools:   $BUILD_TOOLS"
echo "platform:$PLATFORM"
