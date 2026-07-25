#!/usr/bin/env bash
# ---------------------------------------------------------------------------
# build_apk.sh  –  Build BLE Heart Rate APK without AGP / Gradle
# ---------------------------------------------------------------------------
set -euo pipefail

ANDROID_HOME="${ANDROID_SDK_ROOT:-/usr/local/lib/android/sdk}"
BUILD_TOOLS="$ANDROID_HOME/build-tools/35.0.0"
PLATFORM="$ANDROID_HOME/platforms/android-35"
ANDROID_JAR="$PLATFORM/android.jar"

PROJECT_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
APP_SRC="$PROJECT_ROOT/app/src/main"
BUILD_DIR="$PROJECT_ROOT/build/manual"

AAPT2="$BUILD_TOOLS/aapt2"
D8="$BUILD_TOOLS/d8"
APKSIGNER="$BUILD_TOOLS/apksigner"
ZIPALIGN="$BUILD_TOOLS/zipalign"
JAVAC="javac"
KOTLINC="kotlinc"

echo "==> Cleaning build dir"
rm -rf "$BUILD_DIR"
mkdir -p "$BUILD_DIR/compiled_res" \
         "$BUILD_DIR/classes"      \
         "$BUILD_DIR/dex"          \
         "$BUILD_DIR/apk_staging"

# ---------------------------------------------------------------------------
# 1.  Compile resources
# ---------------------------------------------------------------------------
echo "==> Compiling resources"
"$AAPT2" compile --dir "$APP_SRC/res" -o "$BUILD_DIR/compiled_res"

# ---------------------------------------------------------------------------
# 2.  Link resources → generate R.java and a base APK
# ---------------------------------------------------------------------------
echo "==> Linking resources"
mkdir -p "$BUILD_DIR/gen"
"$AAPT2" link \
    -I "$ANDROID_JAR" \
    --manifest "$APP_SRC/AndroidManifest.xml" \
    "$BUILD_DIR/compiled_res"/*.flat \
    --java "$BUILD_DIR/gen" \
    --min-sdk-version 21 \
    --target-sdk-version 35 \
    --version-code 3 \
    --version-name "1.2" \
    -o "$BUILD_DIR/resources.apk"

# ---------------------------------------------------------------------------
# 3.  Compile Kotlin sources (+ generated R.java via javac)
# ---------------------------------------------------------------------------
echo "==> Compiling Java R.java"
find "$BUILD_DIR/gen" -name "*.java" > /tmp/java_sources.txt
"$JAVAC" -cp "$ANDROID_JAR" \
    -source 8 -target 8 \
    -d "$BUILD_DIR/classes" \
    @/tmp/java_sources.txt

echo "==> Compiling Kotlin sources"
find "$APP_SRC/java" -name "*.kt" > /tmp/kt_sources.txt
"$KOTLINC" \
    -cp "$ANDROID_JAR:$BUILD_DIR/classes" \
    -jvm-target 17 \
    -d "$BUILD_DIR/classes" \
    @/tmp/kt_sources.txt

# ---------------------------------------------------------------------------
# 4.  DEX
# ---------------------------------------------------------------------------
echo "==> Converting to DEX"
find "$BUILD_DIR/classes" -name "*.class" > /tmp/class_list.txt
"$D8" \
    --release \
    --min-api 21 \
    --lib "$ANDROID_JAR" \
    --output "$BUILD_DIR/dex" \
    $(cat /tmp/class_list.txt)

# ---------------------------------------------------------------------------
# 5.  Package APK
# ---------------------------------------------------------------------------
echo "==> Packaging APK"
cp "$BUILD_DIR/resources.apk" "$BUILD_DIR/apk_staging/unaligned.apk"
cd "$BUILD_DIR/apk_staging"
# Add DEX into the APK
zip -j unaligned.apk "$BUILD_DIR/dex/classes.dex"

# Zipalign
"$ZIPALIGN" -f 4 unaligned.apk aligned.apk

# ---------------------------------------------------------------------------
# 6.  Sign APK (debug key)
# ---------------------------------------------------------------------------
echo "==> Signing APK"
KEYSTORE="$HOME/.android/debug.keystore"
if [ ! -f "$KEYSTORE" ]; then
    mkdir -p "$HOME/.android"
    keytool -genkeypair -v \
        -keystore "$KEYSTORE" \
        -alias androiddebugkey \
        -storepass android \
        -keypass android \
        -keyalg RSA \
        -keysize 2048 \
        -validity 10000 \
        -dname "CN=Android Debug,O=Android,C=US" 2>/dev/null
fi
"$APKSIGNER" sign \
    --ks "$KEYSTORE" \
    --ks-key-alias androiddebugkey \
    --ks-pass pass:android \
    --key-pass pass:android \
    --out "$BUILD_DIR/apk_staging/ble_heartrate_debug.apk" \
    aligned.apk

# ---------------------------------------------------------------------------
# 7.  Copy to release directory
# ---------------------------------------------------------------------------
RELEASE_DIR="$PROJECT_ROOT/app/release"
mkdir -p "$RELEASE_DIR"
cp "$BUILD_DIR/apk_staging/ble_heartrate_debug.apk" "$RELEASE_DIR/"
echo ""
echo "✅  APK built: $RELEASE_DIR/ble_heartrate_debug.apk"
echo "    $(du -sh "$RELEASE_DIR/ble_heartrate_debug.apk" | cut -f1)"
