#!/bin/bash
# AI Phone Assistant - generic AOSP-tree build wrapper.
#
# Usage:
#   ./build.sh <aosp-root> <lunch-combo> [product]
#   ./build.sh /path/to/lineage lineage_lmi-ap3a-userdebug lmi
#
# Syncs this repo's sources into <aosp-root>/packages/apps/AIPhoneAssistant
# and builds the APK with the tree's toolchain (platform key, hidden APIs).
# Device-specific bits (sepolicy install, device.mk, deploy) live in the
# WD40 repo: https://github.com/Inokinoki/WD40 (integrations/aiphoneassistant).
#
# This script makes NO changes outside packages/apps/AIPhoneAssistant.

set -e

if [ $# -lt 2 ]; then
    echo "Usage: $0 <aosp-root> <lunch-combo> [product]"
    echo "Example: $0 \$HOME/Builds/LineageOS lineage_lmi-ap3a-userdebug lmi"
    exit 1
fi

AOSP_ROOT="$(readlink -f "$1")"
LUNCH="$2"
PRODUCT="${3:-}"

if [ ! -d "$AOSP_ROOT/build" ] || [ ! -f "$AOSP_ROOT/build/envsetup.sh" ]; then
    echo "Error: $AOSP_ROOT does not look like an AOSP/LineageOS tree"
    exit 1
fi

APP_SOURCE="$(cd "$(dirname "$(readlink -f "$0")")" && pwd)"
APP_DEST="$AOSP_ROOT/packages/apps/AIPhoneAssistant"

echo "=============================================="
echo "  AI Phone Assistant build"
echo "  tree:   $AOSP_ROOT"
echo "  lunch:  $LUNCH"
echo "=============================================="

# 1. Sync this repo's sources into the tree (app sources only)
echo "[1/2] Syncing sources to $APP_DEST ..."
rm -rf "$APP_DEST/src" "$APP_DEST/res"
cp -r "$APP_SOURCE/src" "$APP_DEST/"
cp -r "$APP_SOURCE/res" "$APP_DEST/"
cp "$APP_SOURCE/Android.bp" "$APP_SOURCE/AndroidManifest.xml" \
   "$APP_SOURCE/privapp-permissions-aiphoneassistant.xml" \
   "$APP_SOURCE/proguard.flags" "$APP_DEST/"

# 2. Build
echo "[2/2] Building ..."
cd "$AOSP_ROOT"
# shellcheck source=/dev/null
source build/envsetup.sh
lunch "$LUNCH"
m AIPhoneAssistant -j"$(nproc)"

# Report the output path
OUT_APK="out/target/product/${PRODUCT:-<product>}/system_ext/priv-app/AIPhoneAssistant/AIPhoneAssistant.apk"
echo ""
echo "Done. APK: $OUT_APK"
echo "Day-2 updates (device already has the system copy):"
echo "  adb install -r $OUT_APK"
echo "First install / sepolicy / device.mk: see WD40 integrations/aiphoneassistant/INTEGRATION.md"
