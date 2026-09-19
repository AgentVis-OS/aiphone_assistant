#!/bin/bash
# AI Phone Assistant - One-click integration + build for lmi (POCO F2 Pro)
# Run this script directly: bash /home/inoki/aiphone_assistant/build_lmi.sh
#
# Copies the app source into the (root-owned) AOSP tree, installs the SELinux
# rules into the device tree (if not present) and builds the APK.

set -e

AOSP_ROOT="/home/inoki/Builds/LineageOS/lineage-22_2-poco-f2"
APP_SOURCE="/home/inoki/aiphone_assistant"
APP_DEST="$AOSP_ROOT/packages/apps/AIPhoneAssistant"
SEPOLICY_COMMON="$AOSP_ROOT/device/xiaomi/sm8250-common/sepolicy"

echo "=============================================="
echo "  AI Phone Assistant Build for lmi"
echo "=============================================="

# The AOSP tree is root-owned; use sudo for all writes into it
SUDO=""
if [ "$(id -u)" -ne 0 ]; then
    SUDO="sudo"
    sudo -v || { echo "Need sudo privileges to write into $AOSP_ROOT"; exit 1; }
fi

# 1. Create directory structure
echo "[1/6] Creating directories..."
$SUDO mkdir -p "$APP_DEST"

# 2. Copy source files (full copy: src, res, config)
echo "[2/6] Copying source files..."
$SUDO rm -rf "$APP_DEST/src" "$APP_DEST/res"
$SUDO cp -r "$APP_SOURCE/src" "$APP_DEST/"
$SUDO cp -r "$APP_SOURCE/res" "$APP_DEST/"
$SUDO cp "$APP_SOURCE/Android.bp" "$APP_DEST/"
$SUDO cp "$APP_SOURCE/AndroidManifest.xml" "$APP_DEST/"
$SUDO cp "$APP_SOURCE/privapp-permissions-aiphoneassistant.xml" "$APP_DEST/"
$SUDO cp "$APP_SOURCE/proguard.flags" "$APP_DEST/"

echo "      Copied to: $APP_DEST"

# 3. Install SELinux rules into the device tree
echo "[3/6] Installing SELinux rules..."
if [ -d "$SEPOLICY_COMMON/private" ]; then
    $SUDO cp "$APP_SOURCE/sepolicy/private/aiphoneassistant.te" "$SEPOLICY_COMMON/private/"
    echo "      Installed $SEPOLICY_COMMON/private/aiphoneassistant.te"
else
    echo "      WARNING: $SEPOLICY_COMMON/private not found - install"
    echo "      sepolicy/private/aiphoneassistant.te manually or capture/input"
    echo "      will be denied on enforcing builds (check: adb shell dmesg | grep avc)"
fi
if [ -d "$SEPOLICY_COMMON/vendor" ]; then
    $SUDO cp "$APP_SOURCE/sepolicy/vendor/aiphoneassistant.te" "$SEPOLICY_COMMON/vendor/"
    echo "      Installed $SEPOLICY_COMMON/vendor/aiphoneassistant.te (optional rules)"
fi

# 4. Add to device.mk (lmi inherits from this)
echo "[4/6] Adding to device build configuration..."
DEVICE_MK="$AOSP_ROOT/device/xiaomi/lmi/device.mk"
if ! grep -q "AIPhoneAssistant" "$DEVICE_MK"; then
    $SUDO sh -c "cat >> '$DEVICE_MK' << 'EOF'

# AI Phone Assistant - System app for screen capture and LLM integration
PRODUCT_PACKAGES += AIPhoneAssistant privapp-permissions-aiphoneassistant
EOF"
    echo "      Added to $DEVICE_MK"
else
    echo "      Already in $DEVICE_MK"
fi

# 5. Permissions
echo "[5/6] Setting file permissions..."
$SUDO find "$APP_DEST" -type f -exec chmod 644 {} \;
$SUDO find "$APP_DEST" -type d -exec chmod 755 {} \;

echo ""
echo "=============================================="
echo "  Integration complete!"
echo "=============================================="
echo ""
echo "Now build with:"
echo "  cd $AOSP_ROOT"
echo "  source build/envsetup.sh"
echo "  lunch lineage_lmi-userdebug"
echo "  m AIPhoneAssistant -j\$(nproc)"
echo ""
echo "Output: $AOSP_ROOT/out/target/product/lmi/system_ext/priv-app/AIPhoneAssistant/"
echo ""
echo "NOTE: SELinux rules only take effect after a full image build + flash"
echo "      (they cannot be pushed at runtime - see push.sh)."
