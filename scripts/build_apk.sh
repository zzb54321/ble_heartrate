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
APP_BUILD_GRADLE="$PROJECT_ROOT/app/build.gradle"

AAPT2="$BUILD_TOOLS/aapt2"
D8="$BUILD_TOOLS/d8"
APKSIGNER="$BUILD_TOOLS/apksigner"
ZIPALIGN="$BUILD_TOOLS/zipalign"
JAVAC="javac"
KOTLINC="kotlinc"

VERSION_CODE="$(awk '/versionCode/ { print $2; exit }' "$APP_BUILD_GRADLE")"
VERSION_NAME="$(awk -F'"' '/versionName/ { print $2; exit }' "$APP_BUILD_GRADLE")"
if [ -z "$VERSION_CODE" ] || [ -z "$VERSION_NAME" ]; then
    echo "Failed to read versionCode/versionName from $APP_BUILD_GRADLE" >&2
    exit 1
fi

KOTLINC_BIN="$(readlink -f "$(command -v "$KOTLINC")")"
KOTLIN_LIB_DIR="$(cd "$(dirname "$KOTLINC_BIN")/.." && pwd)/lib"
KOTLIN_RUNTIME_JARS=()
for jar in kotlin-stdlib.jar kotlin-stdlib-jdk7.jar kotlin-stdlib-jdk8.jar; do
    if [ -f "$KOTLIN_LIB_DIR/$jar" ]; then
        KOTLIN_RUNTIME_JARS+=("$KOTLIN_LIB_DIR/$jar")
    fi
done
if [ "${#KOTLIN_RUNTIME_JARS[@]}" -eq 0 ]; then
    echo "Failed to locate Kotlin runtime jars under $KOTLIN_LIB_DIR" >&2
    exit 1
fi

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
    --version-code "$VERSION_CODE" \
    --version-name "$VERSION_NAME" \
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
mapfile -t CLASS_FILES < /tmp/class_list.txt
D8_INPUTS=("${CLASS_FILES[@]}" "${KOTLIN_RUNTIME_JARS[@]}")
"$D8" \
    --release \
    --min-api 21 \
    --lib "$ANDROID_JAR" \
    --output "$BUILD_DIR/dex" \
    "${D8_INPUTS[@]}"

# ---------------------------------------------------------------------------
# 5.  Package APK
# ---------------------------------------------------------------------------
echo "==> Packaging APK"
cp "$BUILD_DIR/resources.apk" "$BUILD_DIR/apk_staging/unaligned.apk"
cd "$BUILD_DIR/apk_staging"
# Add all DEX files into the APK
find "$BUILD_DIR/dex" -maxdepth 1 -name "*.dex" -print0 | xargs -0 zip -j unaligned.apk

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
