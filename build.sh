#!/bin/bash
# AI Phone Assistant - generic AOSP-tree build wrapper.
#
# Usage:
#   ./build.sh [aosp-root] [lunch-combo]
#   ./build.sh ~/Builds/LineageOS lineage_lmi-ap3a-userdebug
#
# All arguments optional with sensible resolution:
#   aosp-root    $AOSP_ROOT env var, else required
#   lunch-combo  $LUNCH_COMBO env var, else "<guess>-userdebug" (see below)
#
# WITH_LITERTLM=1 also ships the optional on-device LLM backend (LiteRT-LM);
# the product must set the matching Soong config variable, see Android.bp.
#
# The [product] used for the output path is derived from the lunch combo's
# first segment with a leading "lineage_" stripped (LineageOS convention:
# lineage_lmi-ap3a-userdebug -> out/target/product/lmi).
#
# Syncs this repo's sources into <aosp-root>/packages/apps/AIPhoneAssistant
# and builds the APK with the tree's toolchain (platform key, hidden APIs).
# Device-specific bits (sepolicy install, device.mk, deploy) live in the
# WD40 repo: https://github.com/Inokinoki/WD40 (integrations/aiphoneassistant).
#
# This script makes NO changes outside packages/apps/AIPhoneAssistant.

set -e

AOSP_ROOT="${1:-${AOSP_ROOT:-}}"
LUNCH="${2:-${LUNCH_COMBO:-}}"

if [ -z "$AOSP_ROOT" ]; then
    echo "Usage: $0 [aosp-root] [lunch-combo]  (or export AOSP_ROOT / LUNCH_COMBO)" >&2
    exit 1
fi
AOSP_ROOT="$(readlink -f "$AOSP_ROOT")"

if [ ! -d "$AOSP_ROOT/build" ] || [ ! -f "$AOSP_ROOT/build/envsetup.sh" ]; then
    echo "Error: $AOSP_ROOT does not look like an AOSP/LineageOS tree" >&2
    exit 1
fi

# Derive the product (output dir name) from the lunch combo's device segment.
if [ -n "$LUNCH" ]; then
    PRODUCT="${LUNCH%%-*}"            # lineage_lmi
    PRODUCT="${PRODUCT#lineage_}"     # lmi
fi

if [ -z "$LUNCH" ]; then
    # No combo given: derive the available LineageOS products straight from
    # the device trees (device/<vendor>/<target>/lineage_<target>.mk is what
    # registers the lineage_<target>-<release>-<variant> lunch combos).
    echo "No lunch combo given. LineageOS products in this tree:"
    for mk in "$AOSP_ROOT"/device/*/*/lineage_*.mk; do
        [ -f "$mk" ] || continue
        target="$(basename "$mk" .mk)"
        echo "  ${target}-<release>-userdebug"
    done | sort -u | head -30
    echo "Re-run: $0 <aosp-root> <combo>  (e.g. lineage_lmi-ap3a-userdebug)"
    exit 1
fi

APP_SOURCE="$(cd "$(dirname "$(readlink -f "$0")")" && pwd)"
APP_DEST="$AOSP_ROOT/packages/apps/AIPhoneAssistant"

echo "=============================================="
echo "  AI Phone Assistant build"
echo "  tree:   $AOSP_ROOT"
echo "  lunch:  $LUNCH  (product: $PRODUCT)"
echo "=============================================="

# 1. Sync this repo's sources into the tree (app sources only)
echo "[1/2] Syncing sources to $APP_DEST ..."
rm -rf "$APP_DEST/src" "$APP_DEST/src-litertlm" "$APP_DEST/res" "$APP_DEST/libs"
cp -r "$APP_SOURCE/src" "$APP_DEST/"
cp -r "$APP_SOURCE/res" "$APP_DEST/"
if [ "${WITH_LITERTLM:-0}" = 1 ]; then
    # Optional on-device LLM backend: sources + LiteRT-LM runtime (libs/,
    # incl. its generated Android.bp). The product must ALSO set
    #   $(call soong_config_set,aiphoneassistant,with_litertlm,true)
    # or Soong builds the remote-only app.
    "$APP_SOURCE/tools/fetch-litertlm.sh"
    cp -r "$APP_SOURCE/src-litertlm" "$APP_SOURCE/libs" "$APP_DEST/"
fi
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

OUT_APK="out/target/product/$PRODUCT/system_ext/priv-app/AIPhoneAssistant/AIPhoneAssistant.apk"
echo ""
echo "Done. APK: $AOSP_ROOT/$OUT_APK"
echo "Day-2 updates (device already has the system copy):"
echo "  adb install -r $OUT_APK"
echo "First install / sepolicy / device.mk: see WD40 integrations/aiphoneassistant/INTEGRATION.md"
