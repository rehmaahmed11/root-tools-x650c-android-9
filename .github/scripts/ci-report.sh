#!/usr/bin/env bash
# ============================================================================
# CI report - the last link of the CI tool chain.
#
# Every workflow run ends with a report, no matter whether the build passed or
# failed.  The report exists so that the next iteration can be planned from
# facts instead of guesswork:
#
#   .github-ci-logs/ci-report.md    human readable, same content as the job summary
#   .github-ci-logs/ci-report.json  machine readable (one object, easy to parse)
#   .github-ci-logs/ci-report.txt   plain text, no markup
#
# The script is deliberately defensive: it never fails the job, and it still
# produces a useful report when the build died before writing any state.
# ============================================================================
set -uo pipefail

cd "$(dirname "${BASH_SOURCE[0]}")/../.."

LOG_DIR="${CI_LOG_DIR:-.github-ci-logs}"
case "$LOG_DIR" in
  /*) : ;;
  *) LOG_DIR="$PWD/$LOG_DIR" ;;
esac
mkdir -p "$LOG_DIR"

# ---------------------------------------------------------------------------
# Inputs (all optional - the report must work with whatever exists)
# ---------------------------------------------------------------------------
APK_PATH="${APK_PATH:-app/build/outputs/apk/debug/app-debug.apk}"
APK_SHA256="${APK_SHA256:-}"
APK_SIZE_BYTES="${APK_SIZE_BYTES:-0}"
BUILD_MODE="${BUILD_MODE:-unknown}"
BUILD_STATUS="${BUILD_STATUS:-unknown}"
TEST_STATUS="${TEST_STATUS:-unknown}"
TEST_PASSED="${TEST_PASSED:-0}"
TEST_FAILED="${TEST_FAILED:-0}"
TEST_FAILURE="${TEST_FAILURE:-}"
TEST_DURATION="${TEST_DURATION:-0}"
JAVA_VERSION="${JAVA_VERSION:-unknown}"
GRADLE_VERSION="${GRADLE_VERSION:-unknown}"
SDK_DIR="${SDK_DIR:-unknown}"
BUILD_TOOLS="${BUILD_TOOLS:-unknown}"
RUN_ID="${RUN_ID:-${GITHUB_RUN_ID:-unknown}}"
RUN_NUMBER="${RUN_NUMBER:-${GITHUB_RUN_NUMBER:-unknown}}"
RUN_URL="${RUN_URL:-}"
if [ -z "$RUN_URL" ]; then
  if [ -n "${GITHUB_REPOSITORY:-}" ]; then
    RUN_URL="${GITHUB_SERVER_URL:-https://github.com}/${GITHUB_REPOSITORY}/actions/runs/${RUN_ID}"
  else
    RUN_URL="(no run url available)"
  fi
fi
COMMIT_SHA="${COMMIT_SHA:-${GITHUB_SHA:-unknown}}"
EVENT="${EVENT:-${GITHUB_EVENT_NAME:-unknown}}"
WORKFLOW="${WORKFLOW:-${GITHUB_WORKFLOW:-unknown}}"
REF_NAME="${REF_NAME:-${GITHUB_REF_NAME:-unknown}}"
ACTOR="${ACTOR:-${GITHUB_ACTOR:-unknown}}"
OVERALL="${OVERALL_STATUS:-unknown}"

json_escape() { printf '%s' "$1" | sed -e 's/\\/\\\\/g' -e 's/"/\\"/g' -e 's/\r//g' | awk 'BEGIN{ORS=""}{if(NR>1)print "\\n";print}'; }

# Reads a (possibly dotted) key from a JSON file.
# python3 is used when available because it is exact; the sed fallback covers
# the simple "one field per line" layout the writers produce.
json_get() { # file dotted.key
  local file="$1" key="$2"
  [ -f "$file" ] || return 0
  if command -v python3 >/dev/null 2>&1; then
    python3 - "$file" "$key" 2>/dev/null <<'PY'
import json, sys
try:
    with open(sys.argv[1]) as handle:
        data = json.load(handle)
except Exception:
    sys.exit(0)
value = data
for part in sys.argv[2].split('.'):
    if not isinstance(value, dict) or part not in value:
        sys.exit(0)
    value = value[part]
if isinstance(value, bool):
    value = str(value).lower()
elif isinstance(value, (dict, list)):
    value = json.dumps(value)
print(value)
PY
    return 0
  fi
  local leaf="${key##*.}"
  sed -n "s/.*\"$leaf\"[[:space:]]*:[[:space:]]*\"\(\([^\"\\\\]\|\\\\.\)*\)\".*/\1/p" "$file" | head -1 \
    | sed -e 's/\\"/"/g' -e 's/\\\\/\\/g'
}

read_json_field() { json_get "$1" "$2"; }

read_json_number() { # file field
  local value
  value="$(json_get "$1" "$2")"
  case "$value" in
    ''|*[!0-9]*) : ;;
    *) printf '%s' "$value" ;;
  esac
}

# Pull in whatever the earlier steps recorded.
[ -f "$LOG_DIR/tests.json" ] && {
  [ "$TEST_STATUS" = "unknown" ] && TEST_STATUS="$(read_json_field "$LOG_DIR/tests.json" status)"
  [ "$TEST_PASSED" = "0" ] && TEST_PASSED="$(read_json_number "$LOG_DIR/tests.json" passed)"
  [ "$TEST_FAILED" = "0" ] && TEST_FAILED="$(read_json_number "$LOG_DIR/tests.json" failed)"
  [ -z "$TEST_FAILURE" ] && TEST_FAILURE="$(read_json_field "$LOG_DIR/tests.json" failure)"
  [ "$TEST_DURATION" = "0" ] && TEST_DURATION="$(read_json_number "$LOG_DIR/tests.json" durationSeconds)"
}
[ -f "$LOG_DIR/build-result.json" ] && {
  [ "$BUILD_MODE" = "unknown" ] && BUILD_MODE="$(read_json_field "$LOG_DIR/build-result.json" buildMode)"
  [ "$BUILD_STATUS" = "unknown" ] && BUILD_STATUS="$(read_json_field "$LOG_DIR/build-result.json" status)"
  [ -z "$APK_SHA256" ] && APK_SHA256="$(read_json_field "$LOG_DIR/build-result.json" apkSha256)"
  [ "$APK_SIZE_BYTES" = "0" ] && APK_SIZE_BYTES="$(read_json_number "$LOG_DIR/build-result.json" apkSizeBytes)"
}
[ -f "$LOG_DIR/toolchain.json" ] && {
  [ "$JAVA_VERSION" = "unknown" ] && JAVA_VERSION="$(read_json_field "$LOG_DIR/toolchain.json" javaVersion)"
  [ "$GRADLE_VERSION" = "unknown" ] && GRADLE_VERSION="$(read_json_field "$LOG_DIR/toolchain.json" gradleVersion)"
  [ "$SDK_DIR" = "unknown" ] && SDK_DIR="$(read_json_field "$LOG_DIR/toolchain.json" sdkDir)"
  [ "$BUILD_TOOLS" = "unknown" ] && BUILD_TOOLS="$(read_json_field "$LOG_DIR/toolchain.json" buildTools)"
}

# Derive the overall verdict from the individual stages.
VERDICT="passed"
FAILED_STAGE_LIST=()
[ "$TEST_STATUS" != "passed" ] && [ "$TEST_STATUS" != "unknown" ] && {
  VERDICT="failed"; FAILED_STAGE_LIST+=("host-tests"); }
[ "$BUILD_STATUS" != "succeeded" ] && [ "$BUILD_STATUS" != "unknown" ] && {
  VERDICT="failed"; FAILED_STAGE_LIST+=("apk-build"); }
[ -z "$APK_SHA256" ] && {
  VERDICT="failed"; FAILED_STAGE_LIST+=("apk-artifact"); }
case "$OVERALL" in
  failure|cancelled|timed_out) VERDICT="failed"; FAILED_STAGE_LIST+=("workflow") ;;
esac
FAILED_STAGES="$(printf '%s, ' "${FAILED_STAGE_LIST[@]:-none}")"
FAILED_STAGES="${FAILED_STAGES%, }"
[ -z "$FAILED_STAGES" ] && FAILED_STAGES="none"

# ---------------------------------------------------------------------------
# Evidence extraction: pull the most useful lines out of the logs
# ---------------------------------------------------------------------------
tail_of() { # file lines
  [ -f "$1" ] || { echo "(no $1 produced)"; return 0; }
  tail -n "${2:-25}" "$1"
}

test_evidence=""
if [ -f "$LOG_DIR/tests.log" ]; then
  test_evidence="$(grep -E '^(PASS|FAIL):|Exception|AssertionError|at com\.example' "$LOG_DIR/tests.log" | head -40)"
fi
[ -z "$test_evidence" ] && test_evidence="$(tail_of "$LOG_DIR/tests.log" 20)"

build_evidence=""
for candidate in "$LOG_DIR/gradle-attempt-3.log" "$LOG_DIR/gradle-attempt-2.log" \
                 "$LOG_DIR/gradle-attempt-1.log" "$LOG_DIR/manual-build.log"; do
  if [ -f "$candidate" ]; then
    build_evidence="$(grep -E 'FAILURE|error:|ERROR|Caused by|Exception|What went wrong|Execution failed' "$candidate" | head -30)"
    [ -n "$build_evidence" ] && break
    build_evidence="$(tail -20 "$candidate")"
  fi
done
[ -z "$build_evidence" ] && build_evidence="(no build error evidence was captured)"

sdk_evidence=""
for candidate in "$LOG_DIR"/sdkmanager-*.log; do
  [ -f "$candidate" ] || continue
  sdk_evidence="$(tail -20 "$candidate")"
  break
done
[ -z "$sdk_evidence" ] && sdk_evidence="(no sdkmanager log was captured)"

download_evidence=""
for candidate in "$LOG_DIR/gradle-download.log"; do
  [ -f "$candidate" ] && download_evidence="$(tail -15 "$candidate")"
done
[ -z "$download_evidence" ] && download_evidence="(no Gradle download log was captured)"

# ---------------------------------------------------------------------------
# Advice: what the next iteration should look at
# ---------------------------------------------------------------------------
advice=""
case "$TEST_STATUS" in
  failed)
    advice="$advice
- The host-side root-shell regression tests failed. The failing assertion is the
  first \`FAIL:\` line in \`tests.log\`. Compare the fake \`su\` fixtures in
  \`tests/com/example/usermanager/RootShellTest.java\` with the invocation
  protocol used by \`app/src/main/java/com/example/usermanager/RootShell.java\`;
  a mismatch between them is what broke CI in the previous run."
    ;;
  timed-out)
    advice="$advice
- The test suite timed out. A fake \`su\` that blocks on stdin without closing it
  is the usual cause: the production code must close the stdin pipe when it uses
  the legacy protocol."
    ;;
  compile-failed)
    advice="$advice
- The test sources did not compile. Check \`javac.log\` in the diagnostics
  artifact for the compiler error."
    ;;
esac
case "$BUILD_STATUS" in
  failed)
    advice="$advice
- The APK build exhausted every fallback layer. The \`Build evidence\` block
  below shows the last Gradle/manual-build error."
    ;;
esac
if [ -z "$APK_SHA256" ]; then
  advice="$advice
- No APK was produced. Inspect \`summary.txt\` in the diagnostics artifact: it
  records which layer (Gradle vs. raw SDK tools) was reached last."
fi
[ -z "$advice" ] && advice="
- Nothing failed. The pipeline is healthy; the APK artifact
  \`root-tools-debug-apk\` can be downloaded from this run."

# ---------------------------------------------------------------------------
# Markdown report
# ---------------------------------------------------------------------------
MD="$LOG_DIR/ci-report.md"
{
  printf '# CI report - %s\n\n' "$WORKFLOW"
  printf '| | |\n|---|---|\n'
  printf '| Verdict | **%s** |\n' "$VERDICT"
  printf '| Failed stages | %s |\n' "$FAILED_STAGES"
  printf '| Run | [#%s](%s) |\n' "$RUN_NUMBER" "$RUN_URL"
  printf '| Commit | `%s` |\n' "$COMMIT_SHA"
  printf '| Branch / event | `%s` / %s |\n' "$REF_NAME" "$EVENT"
  printf '| Triggered by | %s |\n\n' "$ACTOR"

  printf '## Tool chain\n\n'
  printf '| Stage | Tool | Result |\n|---|---|---|\n'
  printf '| 1. Environment | %s | %s |\n' "$JAVA_VERSION" "$SDK_DIR"
  printf '| 2. Unit tests | host-side root-shell suite | %s (%s passed, %s failed, %ss) |\n' \
    "$TEST_STATUS" "$TEST_PASSED" "$TEST_FAILED" "$TEST_DURATION"
  printf '| 3. APK build | %s | %s |\n' "$GRADLE_VERSION" "$BUILD_MODE"
  printf '| 4. Verification | %s | %s |\n' "$APK_PATH" "${APK_SHA256:-no artifact}"
  printf '| 5. Report | ci-report.sh | %s |\n\n' "$VERDICT"

  printf '## Artifact\n\n'
  printf '| Item | Value |\n|---|---|\n'
  printf '| Path | `%s` |\n' "$APK_PATH"
  printf '| Size | %s bytes |\n' "$APK_SIZE_BYTES"
  printf '| SHA-256 | `%s` |\n' "${APK_SHA256:-none}"
  printf '| Build tools | %s |\n\n' "$BUILD_TOOLS"

  printf '## Test evidence\n\n```\n%s\n```\n\n' "$test_evidence"
  printf '## Build evidence\n\n```\n%s\n```\n\n' "$build_evidence"
  printf '## SDK evidence\n\n```\n%s\n```\n\n' "$sdk_evidence"
  printf '## Download evidence\n\n```\n%s\n```\n\n' "$download_evidence"
  printf '## What to do next\n\n%s\n' "$advice"
} > "$MD"

# ---------------------------------------------------------------------------
# JSON report
# ---------------------------------------------------------------------------
JSON="$LOG_DIR/ci-report.json"
{
  printf '{\n'
  printf '  "runId": "%s",\n' "$(json_escape "$RUN_ID")"
  printf '  "runNumber": "%s",\n' "$(json_escape "$RUN_NUMBER")"
  printf '  "runUrl": "%s",\n' "$(json_escape "$RUN_URL")"
  printf '  "commit": "%s",\n' "$(json_escape "$COMMIT_SHA")"
  printf '  "ref": "%s",\n' "$(json_escape "$REF_NAME")"
  printf '  "event": "%s",\n' "$(json_escape "$EVENT")"
  printf '  "actor": "%s",\n' "$(json_escape "$ACTOR")"
  printf '  "workflow": "%s",\n' "$(json_escape "$WORKFLOW")"
  printf '  "verdict": "%s",\n' "$(json_escape "$VERDICT")"
  printf '  "failedStages": "%s",\n' "$(json_escape "$FAILED_STAGES")"
  printf '  "overallStatus": "%s",\n' "$(json_escape "$OVERALL")"
  printf '  "toolChain": {\n'
  printf '    "java": "%s",\n' "$(json_escape "$JAVA_VERSION")"
  printf '    "gradle": "%s",\n' "$(json_escape "$GRADLE_VERSION")"
  printf '    "sdk": "%s",\n' "$(json_escape "$SDK_DIR")"
  printf '    "buildTools": "%s"\n' "$(json_escape "$BUILD_TOOLS")"
  printf '  },\n'
  printf '  "tests": {\n'
  printf '    "status": "%s",\n' "$(json_escape "$TEST_STATUS")"
  printf '    "passed": %s,\n' "${TEST_PASSED:-0}"
  printf '    "failed": %s,\n' "${TEST_FAILED:-0}"
  printf '    "durationSeconds": %s,\n' "${TEST_DURATION:-0}"
  printf '    "failure": "%s"\n' "$(json_escape "$TEST_FAILURE")"
  printf '  },\n'
  printf '  "build": {\n'
  printf '    "status": "%s",\n' "$(json_escape "$BUILD_STATUS")"
  printf '    "mode": "%s"\n' "$(json_escape "$BUILD_MODE")"
  printf '  },\n'
  printf '  "apk": {\n'
  printf '    "path": "%s",\n' "$(json_escape "$APK_PATH")"
  printf '    "sha256": "%s",\n' "$(json_escape "$APK_SHA256")"
  printf '    "sizeBytes": %s\n' "${APK_SIZE_BYTES:-0}"
  printf '  },\n'
  printf '  "advice": "%s"\n' "$(json_escape "$advice")"
  printf '}\n'
} > "$JSON"

# ---------------------------------------------------------------------------
# Plain text report
# ---------------------------------------------------------------------------
TXT="$LOG_DIR/ci-report.txt"
{
  printf 'CI report - %s\n' "$WORKFLOW"
  printf 'verdict:        %s\n' "$VERDICT"
  printf 'failed stages:  %s\n' "$FAILED_STAGES"
  printf 'run:            #%s %s\n' "$RUN_NUMBER" "$RUN_URL"
  printf 'commit:         %s\n' "$COMMIT_SHA"
  printf 'java:           %s\n' "$JAVA_VERSION"
  printf 'gradle:         %s\n' "$GRADLE_VERSION"
  printf 'sdk:            %s\n' "$SDK_DIR"
  printf 'tests:          %s (%s passed / %s failed / %ss)\n' \
    "$TEST_STATUS" "$TEST_PASSED" "$TEST_FAILED" "$TEST_DURATION"
  printf 'build:          %s (%s)\n' "$BUILD_STATUS" "$BUILD_MODE"
  printf 'apk:            %s\n' "$APK_PATH"
  printf 'apk sha256:     %s\n' "${APK_SHA256:-none}"
  printf 'apk size:       %s bytes\n' "$APK_SIZE_BYTES"
  printf '\nWhat to do next:\n%s\n' "$advice"
} > "$TXT"

# ---------------------------------------------------------------------------
# Job summary
# ---------------------------------------------------------------------------
if [ -n "${GITHUB_STEP_SUMMARY:-}" ]; then
  cat "$MD" >> "$GITHUB_STEP_SUMMARY"
fi

echo "CI report written: $MD, $JSON, $TXT"
echo "verdict: $VERDICT ($FAILED_STAGES)"
exit 0
