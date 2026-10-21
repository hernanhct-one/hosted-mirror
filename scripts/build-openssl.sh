#!/bin/bash
# Compila libcrypto estático de OpenSSL para Android (armeabi-v7a y arm64-v8a).
# Resultado: third_party/openssl-android/<abi>/{include,lib/libcrypto.a}
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SRC="$ROOT/third_party/openssl-3.5.4"
OUT="$ROOT/third_party/openssl-android"
export ANDROID_NDK_ROOT="${ANDROID_NDK_ROOT:-$HOME/Library/Android/sdk/ndk/27.2.12479018}"
export PATH="$ANDROID_NDK_ROOT/toolchains/llvm/prebuilt/darwin-x86_64/bin:$PATH"
API=24

build() {
  local abi="$1" target="$2"
  local work="$ROOT/third_party/build-openssl-$abi"
  rm -rf "$work" && cp -R "$SRC" "$work"
  cd "$work"
  ./Configure "$target" -D__ANDROID_API__=$API no-shared no-tests no-apps no-docs \
    no-ssl3 no-comp no-engine no-dso no-ui-console no-afalgeng --prefix="$OUT/$abi"
  make -j"$(sysctl -n hw.ncpu)" build_libs
  make install_dev
  rm -rf "$work"
}

build armeabi-v7a android-arm
build arm64-v8a android-arm64
echo "OpenSSL listo en $OUT"
