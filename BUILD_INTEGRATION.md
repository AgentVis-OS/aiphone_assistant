# AI Phone Assistant - Build Integration

Everything is handled by a single script:

```bash
sudo bash /home/inoki/aiphone_assistant/build_lmi.sh
```

It performs all integration steps:

1. Copies `src/`, `res/`, `Android.bp`, `AndroidManifest.xml`,
   `privapp-permissions-aiphoneassistant.xml`, `proguard.flags` into
   `$AOSP/packages/apps/AIPhoneAssistant/`.
2. Installs the SELinux rules into
   `device/xiaomi/sm8250-common/sepolicy/{private,vendor}/`
   (required for capture/input on enforcing builds; see SEPOLICY.md).
3. Ensures `PRODUCT_PACKAGES += AIPhoneAssistant
   privapp-permissions-aiphoneassistant` is in `device/xiaomi/lmi/device.mk`.

Then build:

```bash
cd /home/inoki/Builds/LineageOS/lineage-22_2-poco-f2
source build/envsetup.sh
lunch lineage_lmi-userdebug
m AIPhoneAssistant -j$(nproc)
```

Output: `out/target/product/lmi/system_ext/priv-app/AIPhoneAssistant/`

To flash: rebuild the system image (`m systemimage` / `m otapackage`) — or
push just the APK with `push.sh` on a userdebug device with verity disabled.
Remember that SELinux policy changes always require a full image flash.
