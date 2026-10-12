# AI Phone Assistant - Build Integration

## Day-to-day: incremental repack (recommended)

Incremental builds whenever possible; the full build is only needed once, to
bootstrap the tree (`out/` artifacts — framework jars, host tools, previous
APK — that the incremental pipeline consumes). For all iteration afterwards
use the incremental pipeline (javac + d8 + dex swap + platform-key re-sign,
`adb install -r`), documented with exact commands in [AGENTS.md](AGENTS.md).
Typical turnaround: ~2 minutes.

## Full build: build.sh

One script handles sync + build (replaces the old `build_lmi.sh`):

```bash
bash /home/inoki/aiphone_assistant/build.sh \
     /home/inoki/Builds/LineageOS/lineage-22_2-poco-f2 \
     lineage_lmi-ap3a-userdebug
```

Arguments are optional if `AOSP_ROOT` / `LUNCH_COMBO` env vars are set.
The script:

1. Copies `src/`, `res/`, `Android.bp`, `AndroidManifest.xml`,
   `privapp-permissions-aiphoneassistant.xml`, `proguard.flags` into
   `<tree>/packages/apps/AIPhoneAssistant/`.
2. Sources `envsetup.sh`, runs `lunch lineage_lmi-ap3a-userdebug`
   (`TARGET_RELEASE=ap3a`; the old `lineage_lmi-userdebug` combo no longer
   exists) and `m AIPhoneAssistant`.

Output: `out/target/product/lmi/system_ext/priv-app/AIPhoneAssistant/`

This is the bootstrap build — run it once per tree (and again only when
`Android.bp` / sepolicy / device integration change, or `out/` was cleaned);
iterate incrementally afterwards.

## Device integration (one-time)

Device-specific bits (SELinux rules into
`device/xiaomi/sm8250-common/sepolicy/{private,vendor}/` — required for
capture/input on enforcing builds, see [SEPOLICY.md](SEPOLICY.md) — and
`PRODUCT_PACKAGES += AIPhoneAssistant privapp-permissions-aiphoneassistant`
in `device/xiaomi/lmi/device.mk`) are already applied to this tree and are
maintained in the WD40 repo
(https://github.com/Inokinoki/WD40, `integrations/aiphoneassistant`).
They only matter for full image builds/flashes; `adb install -r` updates
need nothing extra. Remember SELinux policy changes always require a full
image flash.
