#!/usr/bin/env bash
set -euo pipefail
# download-sherpa.sh - Download pre-built sherpa-onnx native libraries for Agora.
# Invoked from .github/workflows/build.yml on CI (ubuntu-latest, has network).
# Local offline builds cannot run this script (no network); .so files are
# .gitignore'd and must be fetched fresh on CI each build, mirroring build-proot.sh.

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SHERPA_VERSION="${SHERPA_VERSION:-1.13.5}"
TARBALL="sherpa-onnx-v${SHERPA_VERSION}-android.tar.bz2"
URL="https://github.com/k2-fsa/sherpa-onnx/releases/download/v${SHERPA_VERSION}/${TARBALL}"

BLD="$SCRIPT_DIR/.build-sherpa"
JNILIBS="$SCRIPT_DIR/app/src/main/jniLibs/arm64-v8a"
FDROID_JNILIBS="$SCRIPT_DIR/app/src/fdroid/jniLibs/arm64-v8a"

echo "=== download-sherpa.sh: version=$SHERPA_VERSION ==="

mkdir -p "$BLD" "$JNILIBS" "$FDROID_JNILIBS"

if command -v wget &>/dev/null; then
    wget -q -O "$BLD/$TARBALL" "$URL"
elif command -v curl &>/dev/null; then
    curl -fsSL -o "$BLD/$TARBALL" "$URL"
else
    echo "ERROR: neither wget nor curl is available."
    exit 1
fi

echo "Extracting $TARBALL ..."
tar xjf "$BLD/$TARBALL" -C "$BLD"

SRC_DIR="$BLD/jniLibs/arm64-v8a"
if [ ! -d "$SRC_DIR" ]; then
    echo "ERROR: $SRC_DIR not found after extraction."
    exit 1
fi

# Only libonnxruntime.so and libsherpa-onnx-jni.so are needed for JNI usage.
# libsherpa-onnx-c-api.so / libsherpa-onnx-cxx-api.so are for non-JNI users.
for lib in libonnxruntime.so libsherpa-onnx-jni.so; do
    if [ -f "$SRC_DIR/$lib" ]; then
        cp -v "$SRC_DIR/$lib" "$JNILIBS/$lib"
        cp -v "$SRC_DIR/$lib" "$FDROID_JNILIBS/$lib"
    else
        echo "ERROR: $lib not found in $SRC_DIR"
        exit 1
    fi
done

echo "=== sherpa-onnx native libraries ready ==="
ls -lh "$JNILIBS"
