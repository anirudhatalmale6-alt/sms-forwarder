#!/bin/bash
set -e

SDK="/var/lib/freelancer/projects/40264897/android-sdk"
BUILD_TOOLS="$SDK/build-tools/34.0.0"
PLATFORM="$SDK/platforms/android-33/android.jar"
PROJ="/var/lib/freelancer/projects/40107017/sms-forwarder"

echo "=== Cleaning ==="
rm -rf "$PROJ/build"
mkdir -p "$PROJ/build/gen" "$PROJ/build/classes" "$PROJ/build/dex" "$PROJ/build/apk"

echo "=== Generating R.java ==="
"$BUILD_TOOLS/aapt2" compile --dir "$PROJ/app/src/main/res" -o "$PROJ/build/res.zip"

"$BUILD_TOOLS/aapt2" link \
    -o "$PROJ/build/apk/base.apk" \
    -I "$PLATFORM" \
    --manifest "$PROJ/app/src/main/AndroidManifest.xml" \
    --java "$PROJ/build/gen" \
    --auto-add-overlay \
    "$PROJ/build/res.zip"

echo "=== Compiling Java ==="
find "$PROJ/app/src/main/java" -name "*.java" > "$PROJ/build/sources.txt"
find "$PROJ/build/gen" -name "*.java" >> "$PROJ/build/sources.txt"

javac \
    -source 11 -target 11 \
    -classpath "$PLATFORM" \
    -d "$PROJ/build/classes" \
    @"$PROJ/build/sources.txt" \
    -Xlint:none 2>&1

echo "=== Creating DEX ==="
"$BUILD_TOOLS/d8" \
    --release \
    --lib "$PLATFORM" \
    --output "$PROJ/build/dex" \
    $(find "$PROJ/build/classes" -name "*.class")

echo "=== Building APK ==="
cp "$PROJ/build/apk/base.apk" "$PROJ/build/apk/unsigned.apk"

# Add DEX to APK
cd "$PROJ/build/dex"
zip -j "$PROJ/build/apk/unsigned.apk" classes.dex
cd "$PROJ"

echo "=== Aligning APK ==="
"$BUILD_TOOLS/zipalign" -f 4 "$PROJ/build/apk/unsigned.apk" "$PROJ/build/apk/aligned.apk"

echo "=== Signing APK ==="
# Generate keystore if not exists
if [ ! -f "$PROJ/debug.keystore" ]; then
    keytool -genkeypair -v \
        -keystore "$PROJ/debug.keystore" \
        -alias smsforward \
        -keyalg RSA -keysize 2048 \
        -validity 10000 \
        -storepass android \
        -keypass android \
        -dname "CN=SMS Forward, OU=Dev, O=Dev, L=Tel Aviv, S=IL, C=IL"
fi

"$BUILD_TOOLS/apksigner" sign \
    --ks "$PROJ/debug.keystore" \
    --ks-key-alias smsforward \
    --ks-pass pass:android \
    --key-pass pass:android \
    --v1-signing-enabled true \
    --v2-signing-enabled true \
    --v3-signing-enabled true \
    --out "$PROJ/SMSForward-v1.0.apk" \
    "$PROJ/build/apk/aligned.apk"

echo "=== Done! ==="
echo "APK: $PROJ/SMSForward-v1.0.apk"
ls -lh "$PROJ/SMSForward-v1.0.apk"
