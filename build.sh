#!/usr/bin/env bash
# Builds a signed Feed Fence APK without Gradle or the Android SDK manager.
# Tools: aapt, dalvik-exchange (dx), zipalign, apksigner (Ubuntu packages) + an API 34 android.jar.
set -euo pipefail
cd "$(dirname "$0")"

VERSION_CODE=${VERSION_CODE:-1}
VERSION_NAME=${VERSION_NAME:-1.0}
ANDROID_JAR=${ANDROID_JAR:-/tmp/sdk/android-34.jar}
KEYSTORE=${KEYSTORE:-$HOME/feedfence-release.jks}
KS_PASS=${KS_PASS:?set KS_PASS to the keystore password}

rm -rf build && mkdir -p build/gen build/classes build/dex

echo "› resources"
aapt package -f -m \
  -J build/gen -M AndroidManifest.xml -S res -I "$ANDROID_JAR" \
  --min-sdk-version 26 --target-sdk-version 34 \
  --version-code "$VERSION_CODE" --version-name "$VERSION_NAME" \
  -F build/app.unsigned.apk

echo "› java"
javac -nowarn -Xlint:-options -source 8 -target 8 -encoding UTF-8 \
  -bootclasspath "$ANDROID_JAR" -classpath "$ANDROID_JAR" \
  -d build/classes $(find src build/gen -name '*.java')

echo "› dex"
dalvik-exchange --dex --min-sdk-version=26 --output=build/dex/classes.dex build/classes

echo "› package"
(cd build/dex && zip -q -u ../app.unsigned.apk classes.dex)
zipalign -f -p 4 build/app.unsigned.apk build/app.aligned.apk

if [ ! -f "$KEYSTORE" ]; then
  echo "› creating signing key (keep this file: updates must be signed with the same key)"
  keytool -genkeypair -keystore "$KEYSTORE" -storepass "$KS_PASS" -keypass "$KS_PASS" \
    -alias feedfence -keyalg RSA -keysize 2048 -validity 10000 \
    -dname "CN=Feed Fence, O=Personal" >/dev/null 2>&1
fi

echo "› sign"
apksigner sign --ks "$KEYSTORE" --ks-pass "pass:$KS_PASS" --ks-key-alias feedfence \
  --out "build/FeedFence-$VERSION_NAME.apk" build/app.aligned.apk
apksigner verify --print-certs "build/FeedFence-$VERSION_NAME.apk" | head -3
ls -la "build/FeedFence-$VERSION_NAME.apk"
