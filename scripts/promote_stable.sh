#!/usr/bin/env bash
# Promotes the current vendor/snapclient/ binaries to vendor/snapclient_stable/.
#
# Run this when you've confirmed the current binaries work correctly and want
# to lock them in as the safe fallback for the in-app developer rollback option.
#
# Usage: bash scripts/promote_stable.sh

set -e
cd "$(dirname "$0")/.."

CURRENT_VERSION=$(head -1 vendor/snapclient/VERSION 2>/dev/null | tr -d '[:space:]')
STABLE_VERSION=$(head -1 vendor/snapclient_stable/VERSION 2>/dev/null | tr -d '[:space:]')

echo "Current: $CURRENT_VERSION"
echo "Stable:  $STABLE_VERSION"

if [ "$CURRENT_VERSION" = "$STABLE_VERSION" ]; then
  echo ""
  echo "Already at same version — nothing to promote."
  exit 0
fi

echo ""
read -p "Promote $CURRENT_VERSION → stable? (y/N) " confirm
if [ "$confirm" != "y" ] && [ "$confirm" != "Y" ]; then
  echo "Aborted."
  exit 0
fi

mkdir -p vendor/snapclient_stable

for ABI in arm64-v8a armeabi-v7a; do
  SRC="vendor/snapclient/snapclient_$ABI"
  DST="vendor/snapclient_stable/snapclient_$ABI"
  if [ -f "$SRC" ]; then
    cp "$SRC" "$DST"
    chmod +x "$DST"
    echo "✓ Promoted snapclient_$ABI"
  else
    echo "⚠  $SRC not found — skipping"
  fi
done

cp vendor/snapclient/VERSION vendor/snapclient_stable/VERSION

echo ""
echo "Promoted $CURRENT_VERSION to stable."
echo ""
echo "Next steps:"
echo "  git add vendor/snapclient_stable/"
echo "  git commit -m 'vendor: promote $CURRENT_VERSION to stable'"
echo "  git push"
