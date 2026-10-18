#!/usr/bin/env bash
# ============================================================================
# Bulletproof APK build with layered fallbacks.
#
# Strategy (every layer is independent, later layers need less network):
#   1. JDK:      use JAVA_HOME, else any JDK 17 already on the runner.
#   2. Android:  use the SDK preinstalled on GitHub runners; only if pieces
#                are missing, install them via sdkmanager with retries.
#   3. Gradle:   cached dir -> repo wrapper -> compat preinstalled gradle ->
#                download 8.9 from 3 mirrors with retries + zip integrity
#                verification.
#   4. Build:    3 gradle attempts (daemon reset + dependency refresh between
#                attempts).
#   5. Fallback: if Gradle is unusable for ANY reason, assemble + sign the
#                same APK with raw SDK tools (aapt2/javac/d8/zipalign/
#                apksigner). This path needs NO network at all on GitHub
#                runners because the SDK is preinstalled.
#   6. Verify:   the script only exits 0 if app/build/outputs/apk/debug/
#                app-debug.apk exists and is non-empty. Logs for diagnostics
#                are kept in .github-ci-logs/.
#
# The script also publishes its findings so the later CI steps can chain on
# them: $GITHUB_OUTPUT (build_mode / apk_path / apk_sha256 / apk_size) and
# .github-ci-logs/build-result.json.
# ============================================================================
set -u -o pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$REPO_ROOT"

CI_GRADLE_VERSION="${CI_GRADLE_VERSION:-8.9}"
EXPECTED_APK="${EXPECTED_APK:-app/build/outputs/apk/debug/app-debug.apk}"
LOG_DIR="${CI_LOG_DIR:-.github-ci-logs}"
case "$LOG_DIR" in
  /*) : ;;
  *) LOG_DIR="$REPO_ROOT/$LOG_DIR" ;;
esac
GRADLE_DIST_DIR="$HOME/gradle-dist"
SUMMARY_FILE="$LOG_DIR/summary.txt"
RESULT_FILE="$LOG_DIR/build-result.json"
BUILD_MODE="unknown"
BUILD_STATUS="failed"
BUILD_STARTED="$(date +%s)"

mkdir -p "$LOG_DIR" "$GRADLE_DIST_DIR"
: > "$SUMMARY_FILE"

log()  { printf '\033[1;32m[build]\033[0m %s\n' "$*" | tee -a "$SUMMARY_FILE"; }
warn() { printf '\033[1;33m[warn ]\033[0m %s\n' "$*" | tee -a "$SUMMARY_FILE" >&2; }
fail() { printf '\033[1;31m[fail ]\033[0m %s\n' "$*" | tee -a "$SUMMARY_FILE" >&2; }
note() { printf '%s\n' "$*" >> "$SUMMARY_FILE"; }

summary() { # append to $GITHUB_STEP_SUMMARY when present
  [ -n "${GITHUB_STEP_SUMMARY:-}" ] && cat "$SUMMARY_FILE" >> "$GITHUB_STEP_SUMMARY" || true
}

json_escape() { printf '%s' "$1" | sed -e 's/\\/\\\\/g' -e 's/"/\\"/g' | tr -d '\r'; }

# $GITHUB_OUTPUT values are plain text: no newlines, no JSON escaping.
flat() { printf '%s' "$1" | tr '\r\n' '  ' | sed -e 's/[[:space:]]\{1,\}/ /g' -e 's/^ //' -e 's/ $//'; }

publish_result() { # status -> writes build-result.json and $GITHUB_OUTPUT
  local status="$1"
  local sha="" size="" apk_path=""
  if [ -s "$EXPECTED_APK" ]; then
    apk_path="$EXPECTED_APK"
    sha="$(sha256sum "$EXPECTED_APK" | cut -d' ' -f1)"
    size="$(wc -c < "$EXPECTED_APK" | tr -d ' ')"
  fi
  local duration=$(( $(date +%s) - BUILD_STARTED ))
  {
    printf '{\n'
    printf '  "status": "%s",\n' "$(json_escape "$status")"
    printf '  "buildMode": "%s",\n' "$(json_escape "$BUILD_MODE")"
    printf '  "apkPath": "%s",\n' "$(json_escape "$apk_path")"
    printf '  "apkSha256": "%s",\n' "$(json_escape "$sha")"
    printf '  "apkSizeBytes": %s,\n' "${size:-0}"
    printf '  "gradleVersion": "%s",\n' "$(json_escape "${CI_GRADLE_VERSION:-8.9}")"
    printf '  "durationSeconds": %s\n' "$duration"
    printf '}\n'
  } > "$RESULT_FILE"
  if [ -n "${GITHUB_OUTPUT:-}" ]; then
    {
      printf 'build_status=%s\n' "$status"
      printf 'build_mode=%s\n' "$(flat "$BUILD_MODE")"
      printf 'apk_path=%s\n' "$(flat "$apk_path")"
      printf 'apk_sha256=%s\n' "$sha"
      printf 'apk_size_bytes=%s\n' "${size:-0}"
      printf 'build_duration_seconds=%s\n' "$duration"
    } >> "$GITHUB_OUTPUT"
  fi
}

# ---------------------------------------------------------------------------
# Phase 1: JDK
# ---------------------------------------------------------------------------
pick_jdk() {
  if [ -n "${JAVA_HOME:-}" ] && [ -x "$JAVA_HOME/bin/java" ]; then
    export PATH="$JAVA_HOME/bin:$PATH"
  fi
  if java -version 2>&1 | grep -q 'version "17'; then
    log "JDK 17 active: $(java -version 2>&1 | head -1)"
    return 0
  fi
  local cand
  for cand in "${JAVA_HOME_17_X64:-}" /usr/lib/jvm/*17*; do
    [ -n "$cand" ] && [ -x "$cand/bin/java" ] || continue
    export JAVA_HOME="$cand"
    export PATH="$cand/bin:$PATH"
    log "Using fallback JDK 17 at $cand"
    return 0
  done
  warn "No JDK 17 found; using system default ($(java -version 2>&1 | head -1 || echo 'none'))"
  command -v java >/dev/null 2>&1
}

# ---------------------------------------------------------------------------
# Phase 2: Android SDK (prefer the preinstalled one; network only if needed)
# ---------------------------------------------------------------------------
install_cmdline_tools() { # only for exotic runners without any SDK
  local sdk="$1" zip="$LOG_DIR/cmdline-tools.zip" attempt url
  for url in \
    "https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip" \
    "https://dl.google.com/android/repository/commandlinetools-linux-13114758_latest.zip"; do
    for attempt in 1 2 3 4; do
      log "Installing Android cmdline-tools (attempt $attempt): $url"
      if curl -fL --connect-timeout 20 --max-time 600 --retry 2 -o "$zip" "$url" \
        && unzip -t "$zip" >/dev/null 2>&1; then
        mkdir -p "$sdk/cmdline-tools"
        rm -rf "$sdk/cmdline-tools/latest"
        unzip -q -o "$zip" -d "$sdk/cmdline-tools"
        mv "$sdk/cmdline-tools/cmdline-tools" "$sdk/cmdline-tools/latest"
        return 0
      fi
      sleep $((attempt * 5))
    done
  done
  return 1
}

sdkmanager_bin() {
  local sdk="$1"
  if [ -n "${ANDROID_HOME:-}" ] && command -v sdkmanager >/dev/null 2>&1; then
    command -v sdkmanager
  else
    ls -1 "$sdk"/cmdline-tools/*/bin/sdkmanager 2>/dev/null | sort -V | tail -1
  fi
}

install_sdk_packages() { # platforms/build-tools when the image lacks them
  local sdk="$1" mgr attempt
  mgr="$(sdkmanager_bin "$sdk")"
  if [ -z "$mgr" ]; then
    install_cmdline_tools "$sdk" || { warn "Cannot provision sdkmanager"; return 1; }
    mgr="$(sdkmanager_bin "$sdk")"
    [ -n "$mgr" ] || return 1
  fi
  for attempt in 1 2 3 4 5; do
    log "Installing SDK packages (attempt $attempt): platforms;android-35 build-tools;35.0.0"
    if yes | "$mgr" --sdk_root="$sdk" "platforms;android-35" "build-tools;35.0.0" \
        > "$LOG_DIR/sdkmanager-$attempt.log" 2>&1; then
      return 0
    fi
    sleep $((attempt * 10))
  done
  warn "sdkmanager install failed after 5 attempts"
  return 1
}

setup_sdk() {
  local sdk="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-/usr/local/lib/android/sdk}}"
  if [ ! -d "$sdk" ]; then
    warn "No preinstalled Android SDK at $sdk; provisioning from scratch"
    install_cmdline_tools "$sdk" || { fail "SDK provisioning failed"; return 1; }
  fi
  export ANDROID_HOME="$sdk"
  export ANDROID_SDK_ROOT="$sdk"
  log "Android SDK: $sdk"

  if [ ! -d "$sdk/platforms/android-35" ] || [ ! -d "$sdk/build-tools/35.0.0" ]; then
    install_sdk_packages "$sdk" || warn "Continuing with whatever SDK pieces exist"
  else
    log "Preinstalled SDK already contains android-35 + build-tools 35.0.0 (no downloads needed)"
  fi
  note "sdk: $sdk"
  note "platforms: $(ls -1 "$sdk/platforms" 2>/dev/null | tr '\n' ' ')"
  note "build-tools: $(ls -1 "$sdk/build-tools" 2>/dev/null | tr '\n' ' ')"
  return 0
}

# ---------------------------------------------------------------------------
# Phase 3: Gradle (cache -> wrapper -> preinstalled -> 3 download mirrors)
# ---------------------------------------------------------------------------
gradle_from_dist_dir() {
  local bin="$GRADLE_DIST_DIR/gradle-$CI_GRADLE_VERSION/bin/gradle"
  [ -x "$bin" ] && { GRADLE_CMD="$bin"; return 0; }
  return 1
}

gradle_from_wrapper() {
  [ -x ./gradlew ] || return 1
  GRADLE_CMD="$PWD/gradlew"
  return 0
}

gradle_from_path() { # accept preinstalled Gradle only if compatible with AGP 8.6.x
  command -v gradle >/dev/null 2>&1 || return 1
  local v
  v="$(gradle --version 2>/dev/null | sed -n 's/^Gradle \([0-9][0-9.]*\).*/\1/p' | head -1)"
  [ -n "$v" ] || return 1
  local major minor
  major="${v%%.*}"
  minor="$(echo "$v" | cut -d. -f2)"
  if [ "$major" -eq 8 ] && [ "${minor:-0}" -ge 7 ]; then
    GRADLE_CMD="$(command -v gradle)"
    log "Using compatible preinstalled Gradle $v"
    return 0
  fi
  return 1
}

gradle_download() {
  local zip="$LOG_DIR/gradle-$CI_GRADLE_VERSION-bin.zip" url attempt ok
  for url in \
    "https://services.gradle.org/distributions/gradle-$CI_GRADLE_VERSION-bin.zip" \
    "https://downloads.gradle-dn.com/distributions/gradle-$CI_GRADLE_VERSION-bin.zip" \
    "https://github.com/gradle/gradle-distributions/releases/download/v$CI_GRADLE_VERSION.0/gradle-$CI_GRADLE_VERSION-bin.zip"; do
    for attempt in 1 2 3; do
      log "Downloading Gradle $CI_GRADLE_VERSION (attempt $attempt): $url"
      rm -f "$zip"
      if curl -fL --connect-timeout 20 --max-time 900 --retry 2 --retry-delay 5 \
           -o "$zip" "$url" 2>>"$LOG_DIR/gradle-download.log" \
         && unzip -t "$zip" >/dev/null 2>&1; then
        ok=1; break 2
      fi
      sleep $((attempt * 5))
    done
  done
  [ -n "${ok:-}" ] || { warn "All Gradle mirrors failed"; return 1; }
  rm -rf "$GRADLE_DIST_DIR/gradle-$CI_GRADLE_VERSION"
  unzip -q -o "$zip" -d "$GRADLE_DIST_DIR"
  gradle_from_dist_dir
}

provision_gradle() {
  if gradle_from_dist_dir; then log "Gradle from cached dist dir"
  elif gradle_from_wrapper; then log "Gradle from repository wrapper"
  elif gradle_from_path; then : # message already logged
  elif gradle_download; then log "Gradle downloaded and verified"
  else
    fail "Every Gradle provisioning strategy failed"
    return 1
  fi
  note "gradle: $GRADLE_CMD"
  return 0
}

# ---------------------------------------------------------------------------
# Phase 4: Gradle build, 3 attempts
# ---------------------------------------------------------------------------
gradle_build() {
  local attempt args rc
  for attempt in 1 2 3; do
    args=""
    if [ "$attempt" -ge 2 ]; then
      warn "Resetting Gradle daemons before attempt $attempt"
      ("$GRADLE_CMD" --stop >/dev/null 2>&1 || true)
      pkill -f GradleDaemon 2>/dev/null || true
      args="--no-daemon"
    fi
    if [ "$attempt" -eq 2 ]; then args="$args --refresh-dependencies"; fi
    log "Gradle build attempt $attempt/3 $args"
    if timeout 1500 "$GRADLE_CMD" :app:assembleDebug $args --stacktrace \
        2>&1 | tee "$LOG_DIR/gradle-attempt-$attempt.log"; then
      log "Gradle build succeeded on attempt $attempt"
      BUILD_MODE="gradle (attempt $attempt)"
      return 0
    fi
    rc=$?
    warn "Gradle attempt $attempt failed (exit $rc)"
    sleep $((attempt * 10))
  done
  return 1
}

# ---------------------------------------------------------------------------
# Phase 5: manual APK assembly from raw SDK tools (no Gradle, no network)
# ---------------------------------------------------------------------------
manual_build_apk() {
  local sdk="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-/usr/local/lib/android/sdk}}" bt aj pkg work out
  log "Falling back to raw SDK build (aapt2 + javac + d8 + apksigner)"

  bt="$sdk/build-tools/35.0.0"
  [ -x "$bt/aapt2" ] || bt="$(ls -1d "$sdk"/build-tools/* 2>/dev/null | sort -V | tail -1)"
  [ -n "$bt" ] && [ -x "$bt/aapt2" ] || { fail "manual: no usable build-tools"; return 1; }
  log "manual: build-tools at $bt"

  aj="$sdk/platforms/android-35/android.jar"
  [ -f "$aj" ] || aj="$(ls -1 "$sdk"/platforms/android-*/android.jar 2>/dev/null | sort -V | tail -1)"
  [ -n "$aj" ] && [ -f "$aj" ] || { fail "manual: no android.jar platform"; return 1; }
  log "manual: platform jar $aj"

  pkg="$(sed -n "s/.*namespace *['\"]\([^'\"]*\)['\"].*/\1/p" app/build.gradle | head -1)"
  [ -n "$pkg" ] || pkg="com.example.usermanager"
  log "manual: package $pkg"

  work="$REPO_ROOT/.manual-build"
  rm -rf "$work"; mkdir -p "$work/gen" "$work/classes" "$work/dex"
  local javac_sources
  javac_sources="$(find "$work/gen" -name '*.java' 2>/dev/null; find app/src/main/java -name '*.java' 2>/dev/null)"
  if [ -z "$javac_sources" ]; then
    fail "manual: no Java sources found to compile"
    return 1
  fi
  {
    set -x
    # Manifest without a package attribute gets one injected for aapt2.
    if grep -q 'package=' app/src/main/AndroidManifest.xml; then
      cp app/src/main/AndroidManifest.xml "$work/AndroidManifest.xml"
    else
      sed "s/<manifest /<manifest package=\"$pkg\" /" \
        app/src/main/AndroidManifest.xml > "$work/AndroidManifest.xml"
    fi

    "$bt/aapt2" compile --dir app/src/main/res -o "$work/res.zip" &&
    "$bt/aapt2" link -o "$work/unsigned.apk" -I "$aj" \
      --manifest "$work/AndroidManifest.xml" --java "$work/gen" \
      --min-sdk-version 28 --target-sdk-version 28 \
      --version-code 2 --version-name 1.0.1 \
      --auto-add-overlay "$work/res.zip" &&

    # shellcheck disable=SC2086
    javac -encoding UTF-8 -classpath "$aj" -source 17 -target 17 \
      -d "$work/classes" $javac_sources &&

    "$bt/d8" --release --lib "$aj" --min-api 28 \
      --output "$work/dex" $(find "$work/classes" -name '*.class') &&

    cp "$work/unsigned.apk" "$work/app-debug.apk" &&
    (cd "$work/dex" && zip -q "$work/app-debug.apk" classes.dex) &&
    "$bt/zipalign" -f 4 "$work/app-debug.apk" "$work/aligned.apk" &&

    keytool -genkeypair -keystore "$work/debug.keystore" \
      -alias androiddebugkey -keyalg RSA -keysize 2048 -validity 10000 \
      -storepass android -keypass android \
      -dname "CN=Android Debug,O=Android,C=US" &&

    "$bt/apksigner" sign --ks "$work/debug.keystore" \
      --ks-key-alias androiddebugkey --ks-pass pass:android --key-pass pass:android \
      --out "$work/app-debug-signed.apk" "$work/aligned.apk" &&
    "$bt/apksigner" verify "$work/app-debug-signed.apk"
    set +x
  } > "$LOG_DIR/manual-build.log" 2>&1
  if [ $? -ne 0 ]; then
    fail "manual build failed; see manual-build.log"
    tail -40 "$LOG_DIR/manual-build.log" >&2 || true
    return 1
  fi

  out="$REPO_ROOT/$EXPECTED_APK"
  mkdir -p "$(dirname "$out")"
  cp "$work/app-debug-signed.apk" "$out"
  BUILD_MODE="manual SDK tools fallback"
  log "manual build produced $out"
  return 0
}

# ---------------------------------------------------------------------------
# Phase 6: verify artifact
# ---------------------------------------------------------------------------
verify_apk() {
  if [ -s "$EXPECTED_APK" ]; then
    log "APK verified: $EXPECTED_APK"
    log "size: $(du -h "$EXPECTED_APK" | cut -f1)  sha256: $(sha256sum "$EXPECTED_APK" | cut -d' ' -f1)"
    log "build mode: $BUILD_MODE"
    return 0
  fi
  fail "Expected APK not found: $EXPECTED_APK"
  return 1
}

# ---------------------------------------------------------------------------
main() {
  log "Root Tools bulletproof build starting in $REPO_ROOT"
  # A stale APK from an earlier step must never be mistaken for a fresh build.
  if [ -e "$EXPECTED_APK" ]; then
    warn "Removing the pre-existing $EXPECTED_APK so only a fresh build can satisfy it"
    rm -f "$EXPECTED_APK"
  fi
  pick_jdk || warn "Proceeding with default JDK"
  setup_sdk || warn "SDK setup incomplete; fallback paths will try to cope"

  if provision_gradle && gradle_build && [ -s "$EXPECTED_APK" ]; then
    :
  else
    warn "Gradle path exhausted; invoking manual fallback"
    manual_build_apk || true
  fi

  if verify_apk; then
    BUILD_STATUS="succeeded"
    publish_result "$BUILD_STATUS"
    summary
    exit 0
  fi
  publish_result "$BUILD_STATUS"
  summary
  fail "All build layers failed. Diagnostics were kept in $LOG_DIR"
  exit 1
}

main "$@"
