#!/bin/bash
# Quick push script for AIPhoneAssistant (lmi / POCO F2 Pro)
# Usage: ./push.sh
#
# NOTE: this pushes only the APK and the permissions XML. The SELinux rules in
# sepolicy/ cannot be pushed at runtime - they are compiled into the image, so
# after changing them run a full image build + flash instead.

set -e

AOSP_ROOT="/home/inoki/Builds/LineageOS/lineage-22_2-poco-f2"
PRODUCT_DIR="$AOSP_ROOT/out/target/product/lmi"
APK_PATH="$PRODUCT_DIR/system_ext/priv-app/AIPhoneAssistant/AIPhoneAssistant.apk"
PERMS_PATH="$PRODUCT_DIR/system_ext/etc/permissions/privapp-permissions-aiphoneassistant.xml"

echo "=== AIPhoneAssistant - Quick Deploy (lmi) ==="
echo ""

# Build if the APK is missing
if [ ! -f "$APK_PATH" ]; then
    echo "APK not found. Building first..."
    cd "$AOSP_ROOT"
    source build/envsetup.sh
    lunch lineage_lmi-ap3a-userdebug
    m AIPhoneAssistant -j"$(nproc)"
fi

if ! adb devices | grep -qw "device$"; then
    echo "No ADB device found:"
    adb devices
    exit 1
fi

echo "Pushing APK to device..."
adb root
sleep 2
adb remount || { adb disable-verity && adb reboot && sleep 30 && adb root && adb remount; }

adb shell "mkdir -p /system/system_ext/priv-app/AIPhoneAssistant"
adb push "$APK_PATH" /system/system_ext/priv-app/AIPhoneAssistant/
adb shell "chmod 644 /system/system_ext/priv-app/AIPhoneAssistant/AIPhoneAssistant.apk"

if [ -f "$PERMS_PATH" ]; then
    adb shell "mkdir -p /system/system_ext/etc/permissions"
    adb push "$PERMS_PATH" /system/system_ext/etc/permissions/
fi

echo ""
echo "Deployed! Rebooting..."
adb reboot

echo ""
echo "After reboot, verify with:"
echo "  adb logcat | grep AIPhoneAssistant"
echo "  adb shell dumpsys package com.android.aiphoneassistant | grep -A20 'granted=true'"
