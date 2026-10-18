#!/usr/bin/env bash
# ============================================================================
# Host-side regression tests for the root-shell layer.
#
# The suite is the first link of the CI tool chain: its result is written to
#   - .github-ci-logs/tests.log   (human readable transcript)
#   - .github-ci-logs/tests.json  (machine readable result)
#   - $GITHUB_OUTPUT              (test_status / test_passed / test_failed)
# so that the build, verification and report steps can all consume it.
# ============================================================================
set -uo pipefail

cd "$(dirname "$0")/.."

LOG_DIR="${CI_LOG_DIR:-.github-ci-logs}"
TEST_LOG="$LOG_DIR/tests.log"
TEST_JSON="$LOG_DIR/tests.json"
TEST_TIMEOUT="${TEST_TIMEOUT:-420}"

mkdir -p "$LOG_DIR"

SOURCES=(
  app/src/main/java/com/example/usermanager/RootShell.java
  tests/com/example/usermanager/RootShellTest.java
)

classes="$(mktemp -d)"
trap 'rm -rf "$classes"' EXIT

json_escape() { printf '%s' "$1" | sed -e 's/\\/\\\\/g' -e 's/"/\\"/g' -e 's/\t/\\t/g' | tr -d '\r'; }

# $GITHUB_OUTPUT values are plain text: no newlines, no JSON escaping.
flat() { printf '%s' "$1" | tr '\r\n' '  ' | sed -e 's/[[:space:]]\{1,\}/ /g' -e 's/^ //' -e 's/ $//'; }

write_result() { # status passed failed seconds failure
  local status="$1" passed="$2" failed="$3" seconds="$4" failure="$5"
  {
    printf '{\n'
    printf '  "status": "%s",\n' "$(json_escape "$status")"
    printf '  "passed": %s,\n' "$passed"
    printf '  "failed": %s,\n' "$failed"
    printf '  "durationSeconds": %s,\n' "$seconds"
    printf '  "failure": "%s",\n' "$(json_escape "$failure")"
    printf '  "log": "tests.log"\n'
    printf '}\n'
  } > "$TEST_JSON"
  if [ -n "${GITHUB_OUTPUT:-}" ]; then
    {
      printf 'test_status=%s\n' "$status"
      printf 'test_passed=%s\n' "$passed"
      printf 'test_failed=%s\n' "$failed"
      printf 'test_duration_seconds=%s\n' "$seconds"
      printf 'test_failure=%s\n' "$(flat "$failure")"
    } >> "$GITHUB_OUTPUT"
  fi
}

compile_sources() {
  if command -v javac >/dev/null 2>&1; then
    javac --release 8 -d "$classes" "${SOURCES[@]}" 2>>"$LOG_DIR/javac.log" && return 0
    # Older JDKs do not know --release; fall back to source/target.
    javac -source 8 -target 8 -d "$classes" "${SOURCES[@]}" >>"$LOG_DIR/javac.log" 2>&1 && return 0
    return 1
  fi
  # Optional Eclipse compiler fallback for environments without a full JDK.
  if [ -n "${ECJ_JAR:-}" ] && [ -f "$ECJ_JAR" ] && command -v java >/dev/null 2>&1; then
    java -cp "$ECJ_JAR" org.eclipse.jdt.internal.compiler.batch.Main \
      -source 8 -target 8 -nowarn -d "$classes" "${SOURCES[@]}" 2>>"$LOG_DIR/javac.log" && return 0
  fi
  return 1
}

if ! compile_sources; then
  echo "::error::Could not compile the host-side regression tests (see $LOG_DIR/javac.log)"
  cat "$LOG_DIR/javac.log" 2>/dev/null | tail -30
  write_result "compile-failed" 0 1 0 "the test sources could not be compiled"
  exit 1
fi

started=$(date +%s)
timeout "$TEST_TIMEOUT" java -cp "$classes" com.example.usermanager.RootShellTest > "$TEST_LOG" 2>&1
rc=$?
ended=$(date +%s)
seconds=$((ended - started))

cat "$TEST_LOG"

passed=$(grep -c '^PASS: ' "$TEST_LOG" 2>/dev/null || true)
failed=$(grep -c '^FAIL: ' "$TEST_LOG" 2>/dev/null || true)
passed=${passed:-0}
failed=${failed:-0}
failure=$(grep -m1 '^FAIL: ' "$TEST_LOG" 2>/dev/null | sed 's/^FAIL: //' || true)

if [ "$rc" -eq 124 ]; then
  status="timed-out"
  failure="the test suite exceeded ${TEST_TIMEOUT}s and was terminated"
elif [ "$rc" -ne 0 ] && [ "$failed" -eq 0 ]; then
  status="failed"
  [ -n "$failure" ] || failure="the test runner exited with code $rc"
elif [ "$failed" -gt 0 ]; then
  status="failed"
else
  status="passed"
fi

write_result "$status" "$passed" "$failed" "$seconds" "$failure"

case "$status" in
  passed)
    echo "All $passed root-shell regression tests passed in ${seconds}s."
    exit 0
    ;;
  *)
    echo "::error::Root-shell regression tests did not pass ($status): $failure"
    exit 1
    ;;
esac
