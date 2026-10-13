#!/bin/bash
# Fetch the pinned LiteRT-LM runtime (on-device LLM backend) and its JVM
# dependencies, sha256-checked, and lay them out for both build paths:
#
#   libs/litertlm/litertlm-android.aar     AAR (Soong android_library_import)
#   libs/litertlm/litertlm-classes.jar     classes.jar extracted from the AAR
#   libs/litertlm/arm64-v8a/liblitertlm_jni.so
#   libs/litertlm/*.jar                    gson, kotlin-stdlib, kotlin-reflect,
#                                          kotlinx-coroutines-{core-jvm,android}
#
# Downloads are cached in .cache/litertlm; libs/ is gitignored (no binaries in
# the repo). The model itself (~2.6 GB .litertlm) is NOT fetched here; see
# README "On-device model".
#
# Usage: tools/fetch-litertlm.sh

set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
CACHE="$ROOT/.cache/litertlm"
LIBS="$ROOT/libs/litertlm"

GOOGLE="https://dl.google.com/android/maven2"
CENTRAL="https://repo1.maven.org/maven2"

# <file name> <url> <sha256>
DEPS=(
    "litertlm-android-0.18.0.aar
     $GOOGLE/com/google/ai/edge/litertlm/litertlm-android/0.18.0/litertlm-android-0.18.0.aar
     706cbb8a87739b2818111bf3a19a78b8f7781586d10f80adf3415581fab7595f"
    "gson-2.14.0.jar
     $CENTRAL/com/google/code/gson/gson/2.14.0/gson-2.14.0.jar
     2cbd119bf1961c28788310963dc80ba65f58cdeec1dd139c8bdb1240faa2c36f"
    "kotlin-stdlib-2.4.0.jar
     $CENTRAL/org/jetbrains/kotlin/kotlin-stdlib/2.4.0/kotlin-stdlib-2.4.0.jar
     ccb14ff83fabcb11458b798dbc9824748ccdffeec79c9aba789e6ed1cda86b1c"
    "kotlin-reflect-2.4.0.jar
     $CENTRAL/org/jetbrains/kotlin/kotlin-reflect/2.4.0/kotlin-reflect-2.4.0.jar
     f0d8e8908624eb4478bb131e9f887366680d2e239741195e548fa5c906963b90"
    "kotlinx-coroutines-core-jvm-1.11.0.jar
     $CENTRAL/org/jetbrains/kotlinx/kotlinx-coroutines-core-jvm/1.11.0/kotlinx-coroutines-core-jvm-1.11.0.jar
     d1d75aa01dffbb4d1c520e67e4c4e7f5f6174718e7cb4632412503f2f0e604fa"
    "kotlinx-coroutines-android-1.11.0.jar
     $CENTRAL/org/jetbrains/kotlinx/kotlinx-coroutines-android/1.11.0/kotlinx-coroutines-android-1.11.0.jar
     c2cb206d27017c7d1bf5ff179787397543d13748dbabb0d7237e1585e0b29044"
)

command -v unzip >/dev/null || { echo "Missing unzip" >&2; exit 1; }
mkdir -p "$CACHE" "$LIBS/arm64-v8a"

for entry in "${DEPS[@]}"; do
    read -r name url sha <<<"$(echo $entry)"
    dest="$CACHE/$name"
    if [ ! -f "$dest" ] || ! echo "$sha  $dest" | sha256sum -c --quiet 2>/dev/null; then
        echo "Downloading $name"
        curl -fsSL -o "$dest.tmp" "$url"
        echo "$sha  $dest.tmp" | sha256sum -c --quiet || { echo "sha256 mismatch: $name" >&2; exit 1; }
        mv "$dest.tmp" "$dest"
    fi
    case "$name" in
        litertlm-android-*.aar)
            cp -f "$dest" "$LIBS/litertlm-android.aar"
            unzip -p "$dest" classes.jar > "$LIBS/litertlm-classes.jar"
            unzip -p "$dest" jni/arm64-v8a/liblitertlm_jni.so > "$LIBS/arm64-v8a/liblitertlm_jni.so"
            ;;
        *)
            # Version-less names so Android.bp does not change on bumps.
            cp -f "$dest" "$LIBS/${name%-[0-9]*.jar}.jar"
            ;;
    esac
done
echo "LiteRT-LM runtime ready in ${LIBS#$ROOT/}"
