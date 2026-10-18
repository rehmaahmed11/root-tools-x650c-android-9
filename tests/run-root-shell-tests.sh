#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
classes="$(mktemp -d)"
trap 'rm -rf "$classes"' EXIT
javac --release 8 -d "$classes" \
  app/src/main/java/com/example/usermanager/RootShell.java \
  tests/com/example/usermanager/RootShellTest.java
java -cp "$classes" com.example.usermanager.RootShellTest
