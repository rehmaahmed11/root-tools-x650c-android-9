#!/usr/bin/env bash
# No Gradle, Maven dependencies, AndroidX, Kotlin or third-party app libraries.
set -euo pipefail
cd "$(dirname "$0")/.."
SDK=${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}
JAVA=${JAVA:-java}
JAVAC=${JAVAC:-javac}
KEYTOOL=${KEYTOOL:-keytool}
if [[ -n "$SDK" && -d "$SDK/build-tools" ]]; then
  BT=$(find "$SDK/build-tools" -mindepth 1 -maxdepth 1 -type d | sort -V | tail -1)
  PLATFORM=$(find "$SDK/platforms" -mindepth 1 -maxdepth 1 -type d | sort -V | tail -1)
  [[ ! -d "$SDK/build-tools/35.0.0" ]] || BT="$SDK/build-tools/35.0.0"
  [[ ! -d "$SDK/platforms/android-35" ]] || PLATFORM="$SDK/platforms/android-35"
  AAPT2=${AAPT2:-$BT/aapt2}; ANDROID_JAR=${ANDROID_JAR:-$PLATFORM/android.jar}
  D8=${D8:-$BT/d8}; APKSIGNER=${APKSIGNER:-$BT/apksigner}
fi
: "${AAPT2:?Set ANDROID_SDK_ROOT or AAPT2}" "${ANDROID_JAR:?Set ANDROID_JAR}"
rm -rf build
mkdir -p build/classes build/dex build/module dist .signing
# Compile the icon before linking. No generated R references are needed in Java.
"$AAPT2" compile --dir companion/res -o build/res.zip
"$AAPT2" link -o build/resources.apk -I "$ANDROID_JAR" --manifest companion/AndroidManifest.xml \
  --min-sdk-version 28 --target-sdk-version 28 build/res.zip
mapfile -t SOURCES < <(find companion/src -name '*.java' | sort)
if [[ -n "${ECJ_JAR:-}" ]]; then
  "$JAVA" -jar "$ECJ_JAR" -1.8 -proc:none -bootclasspath "$ANDROID_JAR" -d build/classes "${SOURCES[@]}"
else
  "$JAVAC" -source 8 -target 8 -bootclasspath "$ANDROID_JAR" -d build/classes "${SOURCES[@]}"
fi
mapfile -t CLASSES < <(find build/classes/dev -name '*.class' | sort)
if [[ -n "${DX_JAR:-}" ]]; then
  "$JAVA" -cp "$DX_JAR" com.android.dx.command.Main --dex --min-sdk-version=28 --output=build/dex/classes.dex build/classes
elif [[ -n "${D8_JAR:-}" ]]; then
  "$JAVA" -cp "$D8_JAR" com.android.tools.r8.D8 --lib "$ANDROID_JAR" --min-api 28 --output build/dex "${CLASSES[@]}"
else
  "${D8:?Set D8 or D8_JAR}" --lib "$ANDROID_JAR" --min-api 28 --output build/dex "${CLASSES[@]}"
fi
cp build/resources.apk build/unsigned.apk
(cd build/dex && zip -q -j ../unsigned.apk ./*.dex)
# Development signing identity is local and intentionally ignored. Never auto-uninstall
# an existing companion on signature mismatch. CI can supply a persistent keystore.
KEYSTORE=${KEYSTORE:-.signing/development.jks}
KEY_ALIAS=${KEY_ALIAS:-guestguard}
KEY_PASS=${KEY_PASS:-android}
if [[ ! -f "$KEYSTORE" ]]; then
  "$KEYTOOL" -genkeypair -keystore "$KEYSTORE" -storepass "$KEY_PASS" -keypass "$KEY_PASS" \
    -alias "$KEY_ALIAS" -keyalg RSA -keysize 2048 -validity 10000 -dname 'CN=Guest Guard Development'
fi
export GG_KEY_PASS="$KEY_PASS"
if [[ -n "${APKSIGNER_JAR:-}" ]]; then SIGN=("$JAVA" -jar "$APKSIGNER_JAR"); else SIGN=("${APKSIGNER:?Set APKSIGNER or APKSIGNER_JAR}"); fi
"${SIGN[@]}" sign --ks "$KEYSTORE" --ks-key-alias "$KEY_ALIAS" --ks-pass env:GG_KEY_PASS --key-pass env:GG_KEY_PASS \
  --min-sdk-version 28 --v1-signing-enabled true --v2-signing-enabled true --v3-signing-enabled true --out build/companion.apk build/unsigned.apk
"${SIGN[@]}" verify --verbose --min-sdk-version 28 build/companion.apk
"$AAPT2" dump badging build/companion.apk > build/apk-badging.txt
cp -R module/. build/module/
cp build/companion.apk build/module/companion.apk
python3 scripts/package.py
