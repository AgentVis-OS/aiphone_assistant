# AI Phone Assistant - Quick Reference

## Build & flash (lmi / POCO F2 Pro)

### Docker build (verified working)

```bash
# 0. One-time: sync source + SELinux into the AOSP tree (sudo, or see below)
sudo bash /home/inoki/aiphone_assistant/build_lmi.sh

# 1. Build inside the android-build-env container (no sudo needed)
bash /home/inoki/aiphone_assistant/build_docker.sh

# 2. Either flash the full image, or quick-push just the APK to a rooted device
bash /home/inoki/aiphone_assistant/push.sh
```

The Docker path is the reliable one on this machine: the AOSP tree/out dir are
owned by the invoking user, and soong's nsjail sandbox is stubbed out (the
sandbox would otherwise hang as a non-root user on Ubuntu 24.04 and drop
privileges to `nobody` as root, breaking writes to out/).

### Host build (needs a root shell)

```bash
sudo bash /home/inoki/aiphone_assistant/build_lmi.sh   # sync step only
cd /home/inoki/Builds/LineageOS/lineage-22_2-poco-f2
source build/envsetup.sh && lunch lineage_lmi-ap3a-userdebug
m AIPhoneAssistant -j$(nproc)
```

Note: lunch on this tree requires the three-part target `lineage_lmi-ap3a-userdebug`.

`build.sh` and `integrate.sh` are legacy wrappers that now just call
`build_lmi.sh`.

## First-run configuration

1. Open **AI Assistant Settings** from the launcher.
2. Set endpoint / model / API key (OpenAI-compatible URL; local servers OK).
3. **Test Connection** should toast "Model replied: OK".
4. Set a **Task** (empty task = assistant stays idle).
5. Enable the switch.

## Useful log filters

```bash
adb logcat -s AIPhoneAssistant AIPhoneAssistant.ScreenCap \
               AIPhoneAssistant.Input AIPhoneAssistant.LLM AIPhoneAssistant.Boot
adb shell dumpsys package com.android.aiphoneassistant | grep -E \
  "READ_FRAME_BUFFER|INJECT_EVENTS|CAPTURE_VIDEO_OUTPUT"
adb shell dmesg | grep avc | grep system_app
```

## Response actions the model can return

`tap`, `swipe`, `long_press`, `scroll`, `key_press` (BACK/HOME/APP_SWITCH/...),
`text_input`, `wait`, `none` — see README.md for the exact JSON shapes.
