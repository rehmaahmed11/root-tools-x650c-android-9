#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
mkdir -p build/host-tests
sources=(companion/src/dev/guestguard/Snapshot.java companion/src/dev/guestguard/RootClient.java tests/CompanionTest.java)
if [[ -n "${ECJ_JAR:-}" ]]; then
  "${JAVA:-java}" -jar "$ECJ_JAR" -1.8 -proc:none -bootclasspath "${ANDROID_JAR:?}" -d build/host-tests "${sources[@]}"
else
  "${JAVAC:-javac}" -d build/host-tests "${sources[@]}"
fi
"${JAVA:-java}" -cp build/host-tests dev.guestguard.CompanionTest
