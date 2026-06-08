#!/usr/bin/env bash
# Run this locally on Windows (via Git Bash / WSL) if you want to
# fetch the snapclient binaries without triggering a GitHub Actions build.
# Android Studio needs the binaries in assets/ to build/run locally.

set -e
SNAPDROID_URL="https://github.com/badaix/snapdroid/releases/download/v0.29.0.2/Snapcast-signed-v0.29.0.2.apk"
ASSETS_DIR="app/src/main/assets"

cd "$(dirname "$0")/.."

echo "Downloading Snapdroid APK..."
wget -q "$SNAPDROID_URL" -O /tmp/snapdroid.apk || \
  curl -L "$SNAPDROID_URL" -o /tmp/snapdroid.apk

echo "Extracting..."
mkdir -p /tmp/snapdroid_extracted
unzip -q /tmp/snapdroid.apk -d /tmp/snapdroid_extracted

mkdir -p "$ASSETS_DIR"

echo "APK contents (lib dirs):"
find /tmp/snapdroid_extracted/lib -type f 2>/dev/null | sort || echo "No lib/ dir"
find /tmp/snapdroid_extracted/assets -type f 2>/dev/null | sort || echo "No assets/ dir"

for ABI in arm64-v8a armeabi-v7a x86_64; do
  for NAME in snapclient snapclient_aarch64 snapclient_armv7 libsnapclient.so; do
    SRC="/tmp/snapdroid_extracted/lib/$ABI/$NAME"
    if [ -f "$SRC" ]; then
      cp "$SRC" "$ASSETS_DIR/snapclient_$ABI"
      chmod +x "$ASSETS_DIR/snapclient_$ABI"
      echo "✓ snapclient_$ABI"
      break
    fi
  done
done

echo ""
echo "Assets:"
ls -lah "$ASSETS_DIR"
echo ""
echo "Done. You can now build in Android Studio."
