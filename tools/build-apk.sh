#!/bin/bash
# AI Phone Assistant - standalone APK build (no AOSP/LineageOS tree needed).
#
# Builds a platform-signed AIPhoneAssistant.apk from this repo using only:
#   - JDK 17+
#   - Android SDK build-tools >= 35 (aapt2, d8, zipalign, apksigner)
#   - a pinned framework jar WITH hidden APIs (downloaded, sha256-checked)
#   - the public AOSP *test* platform key (downloaded, sha256-checked)
#
# Usage:
#   tools/build-apk.sh                # -> out/AIPhoneAssistant.apk (+ .sha256)
#
# Environment (all optional):
#   BUILD_TOOLS   dir with aapt2/d8/zipalign/apksigner (default: newest
#                 $ANDROID_HOME/build-tools/*, else tools found on PATH)
#   JAVAC         javac binary (default: javac on PATH)
#   VERSION_CODE  default 35        VERSION_NAME  default 15
#
# IMPORTANT: the APK is signed with the public AOSP test platform key, so it
# only installs/updates over builds signed with that key (the default for
# self-built LineageOS, NOT for official LineageOS builds). It is also not
# sufficient on its own: the privileged install (priv-app placement,
# privapp-permissions XML, SELinux policy) comes from the WD40 integration.

set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
CACHE="$ROOT/.cache"
OUT="$ROOT/out"
WORK="$OUT/work"

# --- pinned inputs ---------------------------------------------------------
FRAMEWORK_URL="https://raw.githubusercontent.com/Reginer/aosp-android-jar/main/android-35/android.jar"
FRAMEWORK_SHA256="60be6880d7b2b526d654460d4ce6fa81bf23efaa28b02d901d0b63a01ccf4671"
KEY_BASE="https://raw.githubusercontent.com/aosp-mirror/platform_build/android-15.0.0_r1/target/product/security"
PK8_SHA256="1ad8ef556870edb70f69a9d3c112544c07de5162ba440d84d33f8bb0c5962875"
PEM_SHA256="9837de028f460c35cc8d3fa45f14eecce30f6fbfe4b93d399aef1acb80c20d14"

VERSION_CODE="${VERSION_CODE:-35}"
VERSION_NAME="${VERSION_NAME:-15}"
JAVAC="${JAVAC:-javac}"

# --- tool discovery --------------------------------------------------------
if [ -z "${BUILD_TOOLS:-}" ] && [ -n "${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}" ]; then
    BUILD_TOOLS="$(ls -d "${ANDROID_HOME:-$ANDROID_SDK_ROOT}"/build-tools/* 2>/dev/null | sort -V | tail -1 || true)"
fi
tool() {  # tool <name>: from BUILD_TOOLS, else PATH
    if [ -n "${BUILD_TOOLS:-}" ] && [ -x "$BUILD_TOOLS/$1" ]; then echo "$BUILD_TOOLS/$1"
    elif command -v "$1" >/dev/null; then command -v "$1"
    else echo "Missing tool: $1 (set BUILD_TOOLS or ANDROID_HOME)" >&2; exit 1; fi
}
AAPT2="$(tool aapt2)"; D8="$(tool d8)"; ZIPALIGN="$(tool zipalign)"; APKSIGNER="$(tool apksigner)"
command -v "$JAVAC" >/dev/null || { echo "Missing javac (JDK 17+)" >&2; exit 1; }
command -v zip >/dev/null || { echo "Missing zip" >&2; exit 1; }

fetch() {  # fetch <url> <dest> <sha256>
    if [ ! -f "$2" ] || ! echo "$3  $2" | sha256sum -c --quiet 2>/dev/null; then
        mkdir -p "$(dirname "$2")"
        echo "Downloading $(basename "$2")"
        curl -fsSL -o "$2" "$1"
        echo "$3  $2" | sha256sum -c --quiet || { echo "sha256 mismatch: $2" >&2; exit 1; }
    fi
}
FW="$CACHE/android-35-hidden-api.jar"
fetch "$FRAMEWORK_URL" "$FW" "$FRAMEWORK_SHA256"
fetch "$KEY_BASE/platform.pk8" "$CACHE/platform.pk8" "$PK8_SHA256"
fetch "$KEY_BASE/platform.x509.pem" "$CACHE/platform.x509.pem" "$PEM_SHA256"

rm -rf "$WORK"; mkdir -p "$WORK"/{gen,classes,dex}
cd "$ROOT"

# 1. Resources: build the APK from the aapt2 link OUTPUT (see AGENTS.md; reusing
#    an old APK's resources.arsc silently misrenders strings).
"$AAPT2" compile --dir res -o "$WORK/res.zip"
"$AAPT2" link -o "$WORK/full-res.apk" -I "$FW" \
    --manifest AndroidManifest.xml \
    --min-sdk-version 35 --target-sdk-version 35 \
    --version-code "$VERSION_CODE" --version-name "$VERSION_NAME" \
    --java "$WORK/gen" "$WORK/res.zip"

# 2. Java -> classes (hidden APIs come from the pinned framework jar).
find src "$WORK/gen" -name '*.java' > "$WORK/sources.txt"
"$JAVAC" -source 17 -target 17 -Xlint:-options -nowarn -encoding UTF-8 \
    -classpath "$FW" -d "$WORK/classes" @"$WORK/sources.txt"

# 3. Dex.
find "$WORK/classes" -name '*.class' > "$WORK/classlist.txt"
"$D8" --release --min-api 34 --lib "$FW" --output "$WORK/dex" @"$WORK/classlist.txt"

# 4. Assemble, align, sign.
cp "$WORK/full-res.apk" "$WORK/final.apk"
zip -q -j "$WORK/final.apk" "$WORK/dex/classes.dex"
"$ZIPALIGN" -f 4 "$WORK/final.apk" "$WORK/final-aligned.apk"
"$APKSIGNER" sign --key "$CACHE/platform.pk8" --cert "$CACHE/platform.x509.pem" \
    --out "$OUT/AIPhoneAssistant.apk" "$WORK/final-aligned.apk"
"$APKSIGNER" verify --print-certs "$OUT/AIPhoneAssistant.apk" | head -2
(cd "$OUT" && sha256sum AIPhoneAssistant.apk > AIPhoneAssistant.apk.sha256)
echo "Built $OUT/AIPhoneAssistant.apk"
