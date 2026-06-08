#!/usr/bin/env bash
# Fetches snapclient binaries from the official Snapdroid APK.
# Populates BOTH vendor/snapclient/ (committed copy) and app/src/main/assets/
# (used by Android Studio for local builds).
#
# Run from Git Bash or WSL on Windows, or any Linux/Mac terminal.

set -e
SNAPDROID_URL="https://github.com/badaix/snapdroid/releases/download/v0.29.0.2/Snapcast-signed-v0.29.0.2.apk"
SNAPDROID_VERSION="v0.29.0.2"
SNAPDROID_APK="Snapcast-signed-v0.29.0.2.apk"

cd "$(dirname "$0")/.."

echo "Downloading Snapdroid APK ($SNAPDROID_VERSION)..."
wget -q "$SNAPDROID_URL" -O /tmp/snapdroid.apk || \
  curl -L "$SNAPDROID_URL" -o /tmp/snapdroid.apk

echo "Extracting..."
rm -rf /tmp/snapdroid_extracted
unzip -q /tmp/snapdroid.apk -d /tmp/snapdroid_extracted

echo ""
echo "APK contents (lib + assets):"
find /tmp/snapdroid_extracted/lib    -type f 2>/dev/null | sort || echo "  No lib/ dir"
find /tmp/snapdroid_extracted/assets -type f 2>/dev/null | sort || echo "  No assets/ dir"
echo ""

mkdir -p vendor/snapclient
mkdir -p app/src/main/assets

for ABI in arm64-v8a armeabi-v7a; do
  FOUND=0
  for NAME in snapclient snapclient_aarch64 snapclient_armv7 libsnapclient.so; do
    SRC="/tmp/snapdroid_extracted/lib/$ABI/$NAME"
    if [ -f "$SRC" ]; then
      cp "$SRC" "vendor/snapclient/snapclient_$ABI"
      cp "$SRC" "app/src/main/assets/snapclient_$ABI"
      chmod +x "vendor/snapclient/snapclient_$ABI" "app/src/main/assets/snapclient_$ABI"
      echo "✓ snapclient_$ABI  (from lib/$ABI/$NAME)"
      FOUND=1
      break
    fi
  done
  if [ "$FOUND" -eq 0 ]; then
    echo "⚠  snapclient_$ABI not found — check APK contents above"
  fi
done

# Write/update VERSION file
printf '%s\nhttps://github.com/badaix/snapdroid/releases/tag/%s\n%s\n' \
  "$SNAPDROID_VERSION" "$SNAPDROID_VERSION" "$SNAPDROID_APK" \
  > vendor/snapclient/VERSION

echo ""
echo "vendor/snapclient/:"
ls -lah vendor/snapclient/
echo ""
echo "app/src/main/assets/:"
ls -lah app/src/main/assets/
echo ""
echo "Done. Commit vendor/snapclient/ to lock this version in your repo."
