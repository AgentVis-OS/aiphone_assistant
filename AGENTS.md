# AGENTS.md — rules for coding agents in this repo

## Builds: ALWAYS prefer the incremental repack; full build only once

Incremental builds whenever possible. The full Soong build is a **one-time
bootstrap**: it produces the framework jars, host tools and previous APK that
the incremental pipeline consumes. Once `out/` has a successful build, iterate
with the incremental pipeline below — it takes ~2 minutes end to end and was
verified on 2026-10-12.

### Java-only changes (the common case)

Assume `TREE=/home/inoki/Builds/LineageOS/lineage-22_2-poco-f2` and that the
tree has one previous successful build (the APK and intermediates under
`out/`). Work in a scratch dir:

1. Compile just this repo's sources (`find src -name '*.java' > sources.txt`):

   ```bash
   $TREE/prebuilts/jdk/jdk21/linux-x86/bin/javac -source 17 -target 17 \
     -nowarn -encoding UTF-8 -d classes @sources.txt \
     -classpath "<jars below, colon-separated>"
   ```

   Classpath jars (all from the previous build):
   - `$TREE/out/target/common/obj/JAVA_LIBRARIES/framework-minus-apex_intermediates/classes.jar`
   - `$TREE/out/target/common/obj/JAVA_LIBRARIES/core-libart.com.android.art_intermediates/classes.jar`
     (this is where `org.json` lives — without it every JSON import fails)
   - `$TREE/out/target/common/obj/JAVA_LIBRARIES/core-oj.com.android.art_intermediates/classes.jar`
   - `$TREE/out/soong/.intermediates/packages/apps/AIPhoneAssistant/AIPhoneAssistant/android_common/javac/AIPhoneAssistant.jar`
     (provides the generated `R` classes; `busybox/R.jar` is incomplete —
     don't use it)

2. Dex:

   ```bash
   $TREE/out/host/linux-x86/bin/d8 --release --min-api 34 \
     --lib $TREE/out/target/common/obj/JAVA_LIBRARIES/framework-minus-apex_intermediates/classes.jar \
     --output dex @classlist.txt
   ```

3. Repack + re-sign (the tree signs with the AOSP test platform key; verify
   with `apksigner verify --print-certs` if unsure — `adb install -r` needs a
   matching signature):

   ```bash
   cp $TREE/out/target/product/lmi/system_ext/priv-app/AIPhoneAssistant/AIPhoneAssistant.apk new.apk
   zip -q new.apk classes.dex          # replaces the classes.dex entry
   $TREE/out/host/linux-x86/bin/zipalign -f 4 new.apk aligned.apk
   $TREE/out/host/linux-x86/bin/apksigner sign \
     --key $TREE/build/target/product/security/platform.pk8 \
     --cert $TREE/build/target/product/security/platform.x509.pem \
     --out aiphone.apk aligned.apk
   adb install -r aiphone.apk
   ```

### Resource / manifest changes

The repack reuses the previous APK's resources, so resource edits need one
extra step: regenerate the resource base with
`aapt2 compile --dir res -o res.zip` and
`aapt2 link -I $TREE/out/target/product/lmi/obj/APPS/framework-res_intermediates/package.apk
--manifest AndroidManifest.xml --java gen res.zip` (not yet exercised — do it
when first needed, then repack as above). `--java` R.java feeds step 1.

### When a full build is needed

Only when: it is the first build in the tree (no previous `out/` artifacts to
reuse); `Android.bp` / sepolicy / device integration changed; or the user
explicitly asks. Run `build.sh <tree> lineage_lmi-ap3a-userdebug` in the
background and continue with other work until it finishes.

## Device notes

- `adb root` works only after enabling Root debugging (Developer options) AND
  `setprop service.adb.root 1` (restarts adbd); needed for reading
  `/data/data/com.android.aiphoneassistant/` (chat.db holds the screenshots).
- After device reboot: `svc power stayon true` and
  `settings put system screen_off_timeout 600000` must be re-applied.
- Text injection: LatinIME + `input text` (spaces as `%s`) for ASCII tasks;
  ADBKeyboard broadcasts were unreliable.
