# SELinux policy for AIPhoneAssistant

## Why this exists

On any enforcing build (LineageOS 22.2 / Android 15 is enforcing), getting the
**signature permission** (e.g. `READ_FRAME_BUFFER`, `INJECT_EVENTS`) only passes
Android's framework `checkPermission`. SELinux is a *separate* gate that can still
deny the underlying operation — and because the app calls these via **reflection**
on hidden APIs, a denial shows up as a **silent failure** (screenshot returns `null`,
the tap "doesn't happen"), not a Java exception.

This policy grants the app's domain (`system_app`) the SELinux access the two core
operations actually need.

## Files

| File | Partition / scope | What it enables |
|------|-------------------|-----------------|
| `sepolicy/private/aiphoneassistant.te` | system_ext private | **Primary paths:** `SurfaceControl.screenshot()` (SurfaceFlinger binder) + `InputManager.injectInputEvent()` (input service) |
| `sepolicy/vendor/aiphoneassistant.te` | vendor | **Optional fallback:** VirtualDisplay/ImageReader GraphicBuffer allocation (gpu/ion/graphics/dma-heap device nodes) |

Install the **private** file first — that alone should make the reflection-based
screenshot + injection work. Add the **vendor** file only if you pursue the
VirtualDisplay fallback and hit device-node denials.

## Install into the device tree

These dirs are already wired in `device/xiaomi/sm8250-common/BoardConfigCommon.mk`
(`SYSTEM_EXT_PRIVATE_SEPOLICY_DIRS` / `BOARD_VENDOR_SEPOLICY_DIRS`), so dropping the
files in is enough — no makefile edit required.

```bash
DEV=device/xiaomi/sm8250-common/sepolicy     # relative to AOSP root
AOSP=/home/inoki/Builds/LineageOS/lineage-22_2-poco-f2
APP=/home/inoki/aiphone_assistant

sudo cp "$APP/sepolicy/private/aiphoneassistant.te" "$AOSP/$DEV/private/"
sudo cp "$APP/sepolicy/vendor/aiphoneassistant.te"  "$AOSP/$DEV/vendor/"
sudo chown root:root "$AOSP/$DEV/private/aiphoneassistant.te" "$AOSP/$DEV/vendor/aiphoneassistant.te"
sudo chmod 644       "$AOSP/$DEV/private/aiphoneassistant.te" "$AOSP/$DEV/vendor/aiphoneassistant.te"
```

Then rebuild (note: LineageOS 22.2 / Android 15 uses the new
`<product>-<release>-<variant>` lunch form — release is `ap3a`):

```bash
cd "$AOSP"
source build/envsetup.sh
lunch lineage_lmi-ap3a-userdebug
make AIPhoneAssistant selinux_policy -j$(nproc)
# (or a full `make -j$(nproc)` then flash boot + vendor images)
```

> **Verified:** this exact policy builds clean. `make selinux_policy` for
> `lineage_lmi-ap3a-userdebug` completes with `#### build completed successfully`
> and the `sepolicy_neverallows` check passes — no `unknown type`, no neverallow
> violations. (Built with the `android-build-env` container, AOSP's own
> `prebuilts/jdk/jdk21`, mounted over the existing tree.)

## Verify on-device

```bash
adb shell getenforce                      # expect: Enforcing

# reproduce one screenshot / one tap, then:
adb shell dmesg | grep avc | grep system_app
adb shell logcat -b kernel | grep avc
```

If everything works, `dmesg | grep avc` shows **no new** `denied` lines for
`system_app` while the app captures/injects.

## Driving the exact rules with audit2allow (source of truth)

The hand-written files above cover the high-probability denials. The authoritative,
device-specific set comes from the kernel's own denial log:

```bash
# 1. Clear the ring buffer, reproduce capture+inject, then capture denials:
adb shell dmesg -c >/dev/null
# ...use the app (trigger a screenshot + a tap)...
adb shell dmesg | grep avc > /tmp/avc.txt

# 2. Convert denials into policy rules:
$AOSP/out/host/linux-x86/bin/audit2allow -i /tmp/avc.txt
#   (or:  audit2allow -p $AOSP/out/target/product/lmi/odm/etc/selinux/precompiled_sepolicy -i /tmp/avc.txt)

# 3. Merge the generated `allow` lines into the matching file above
#    (service/binder rules -> sepolicy/private; device-node rules -> sepolicy/vendor).
```

Append `audit2allow` output to the appropriate file, rebuild, repeat until clean.

## Option A — a dedicated SELinux domain (hardening, optional)

Extending `system_app` widens it for *all* system apps. For a high-risk app like
this, the cleaner approach is its own domain, following the existing pattern in the
device tree (`device/xiaomi/sm8250-common/sepolicy/private/seapp_contexts`):

```
user=system seinfo=platform name=com.android.aiphoneassistant domain=aiphone_app type=system_app_data_file
```

…plus a `aiphone_app.te` declaring the domain and granting exactly the rules above
(replace `system_app` with `aiphone_app`). Use the `devicesettings_app` entry already
in that `seapp_contexts` as a template. This is more work (the new domain needs a
base app-domain definition), so it's recommended only once the basic policy is
proven and you want to tighten the blast radius.

## Troubleshooting

- **Build fails with `neverallow`** on a `vendor/` rule (e.g. direct app access to a
  DMA heap): don't widen it globally. Drop that specific line and prefer the
  SurfaceFlinger-binder path, or move to Option A's dedicated domain.
- **`make: ... 'surfaceflinger_service' not found in vendor`**: that rule belongs in
  the **private** (system_ext) file, not vendor — service types are system-side. The
  split above already accounts for this.
- **Still denied after install?** Confirm the files are actually in the rebuilt image:
  `adb shell ls /system_ext/etc/selinux/` and check the policy built without errors.
