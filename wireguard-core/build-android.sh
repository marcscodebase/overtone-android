#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
ANDROID_SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Library/Android/sdk}}"

if [[ -z "${ANDROID_NDK_HOME:-}" ]]; then
  if [[ -n "${ANDROID_NDK_ROOT:-}" && -d "${ANDROID_NDK_ROOT:-}" ]]; then
    ANDROID_NDK_HOME="$ANDROID_NDK_ROOT"
  else
    ANDROID_NDK_HOME="$(ls -d "$ANDROID_SDK"/ndk/* 2>/dev/null | sort -V | tail -n 1 || true)"
  fi
fi

if [[ -z "$ANDROID_NDK_HOME" || ! -d "$ANDROID_NDK_HOME" ]]; then
  echo "ERROR: Android NDK not found. Install via sdkmanager \"ndk;27.2.12479018\"." >&2
  exit 1
fi

OS_TAG="$(uname -s | tr '[:upper:]' '[:lower:]')"
HOST_TAG="${OS_TAG}-x86_64"
TOOLCHAIN="$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/$HOST_TAG/bin"
if [[ ! -d "$TOOLCHAIN" ]]; then
  HOST_TAG="${OS_TAG}-$(uname -m)"
  TOOLCHAIN="$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/$HOST_TAG/bin"
fi

API_LEVEL=31
JNILIBS_DIR="$PROJECT_ROOT/app/src/main/jniLibs"

build_abi() {
  local abi="$1"
  local goarch="$2"
  local cc="$3"
  local out_dir="$JNILIBS_DIR/$abi"
  mkdir -p "$out_dir"
  echo "Building libwguserspace.so for $abi ($goarch)..."
  (
    cd "$SCRIPT_DIR"
    GOOS=android \
    GOARCH="$goarch" \
    CGO_ENABLED=1 \
    CC="$cc" \
    CGO_LDFLAGS="-Wl,-z,max-page-size=16384 -Wl,--build-id=none" \
    go build \
      -buildmode=c-shared \
      -buildvcs=false \
      -trimpath \
      -ldflags="-s -w -buildid=" \
      -o "$out_dir/libwguserspace.so" \
      .
    rm -f "$out_dir/libwguserspace.h"
  )
  ls -lh "$out_dir/libwguserspace.so"
}

build_abi "arm64-v8a" "arm64" "$TOOLCHAIN/aarch64-linux-android${API_LEVEL}-clang"
build_abi "x86_64" "amd64" "$TOOLCHAIN/x86_64-linux-android${API_LEVEL}-clang"

echo "Done! Built reproducible userspace WireGuard JNI libraries in $JNILIBS_DIR"
