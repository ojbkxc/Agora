#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
AAR_OUTPUT="$PROJECT_ROOT/app/libs/conch.aar"
ANDROID_API="${CONCH_ANDROID_API:-24}"
GO_VERSION="${CONCH_GO_VERSION:-1.23.4}"

echo "=== Building conch .aar via gomobile ==="
echo "  Source:  $SCRIPT_DIR"
echo "  Output:  $AAR_OUTPUT"
echo "  API:     $ANDROID_API"
echo "  Go:      $GO_VERSION"

if ! command -v go >/dev/null 2>&1; then
    echo "ERROR: Go is not installed. Install Go $GO_VERSION or later."
    exit 1
fi

if ! command -v gomobile >/dev/null 2>&1; then
    echo "Installing gomobile..."
    go install golang.org/x/mobile/cmd/gomobile@latest
    go install golang.org/x/mobile/cmd/gobind@latest
fi

export CGO_ENABLED=1
export GOFLAGS=-mod=mod

cd "$SCRIPT_DIR"

echo "Initializing gomobile..."
gomobile init || true

echo "Building .aar for android/arm64..."
gomobile bind \
    -target=android/arm64 \
    -androidapi="$ANDROID_API" \
    -ldflags="-s -w" \
    -o "$AAR_OUTPUT" \
    ./mobile

mkdir -p "$(dirname "$AAR_OUTPUT")"

echo "=== conch.aar built successfully ==="
ls -lh "$AAR_OUTPUT"
