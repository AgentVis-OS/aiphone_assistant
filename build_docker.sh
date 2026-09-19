#!/bin/bash
# AI Phone Assistant - Docker build (verified working setup)
#
# Builds the app inside the android-build-env container. Requirements that are
# already in place on this machine (see git history / README):
#   1. The AOSP tree and out/ are owned by the invoking user (uid 1000).
#   2. prebuilts/build-tools/{linux-x86,linux_musl-x86}/bin/nsjail is a stub
#      script (exit 1) - the real binary is kept as nsjail.disabled. This makes
#      soong skip its nsjail sandbox (which would otherwise drop privileges to
#      nobody and hang/fail on this machine).
#   3. /etc/passwd is mounted read-only so uid 1000 resolves to a real user.
#
# The container runs privileged so the Go runtime / build tools are not
# restricted by seccomp or AppArmor profiles.

set -e

AOSP_ROOT="/home/inoki/Builds/LineageOS/lineage-22_2-poco-f2"
IMAGE="android-build-env:latest"
LUNCH_TARGET="lineage_lmi-ap3a-userdebug"
TARGET="${1:-AIPhoneAssistant}"

docker run --rm --privileged \
    -v "$AOSP_ROOT:$AOSP_ROOT" \
    -v /etc/passwd:/etc/passwd:ro \
    -v /etc/group:/etc/group:ro \
    --user "$(id -u):$(id -g)" \
    -e HOME=/tmp/buildhome \
    -w "$AOSP_ROOT" \
    "$IMAGE" \
    bash -c "mkdir -p \$HOME && source build/envsetup.sh && lunch $LUNCH_TARGET && m $TARGET -j\$(nproc)"

echo ""
echo "Output: $AOSP_ROOT/out/target/product/lmi/system_ext/priv-app/AIPhoneAssistant/"
