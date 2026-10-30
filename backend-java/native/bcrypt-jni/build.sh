#!/usr/bin/env sh
# 构建当前主机平台的 bcrypt JNI 库，并放到 dist/native/<os>-<arch>/ 供 Maven 打入 classpath。
# 依赖 Cargo.lock 锁定（--locked），不修改全局 Rust 环境；需要调用方提供 cargo（PATH 或 CARGO）。
set -eu
here=$(cd "$(dirname "$0")" && pwd)
cargo=${CARGO:-cargo}
case "$(uname -s)" in
  Darwin) os=darwin; ext=dylib ;;
  Linux) os=linux; ext=so ;;
  *) echo "unsupported OS: $(uname -s)" >&2; exit 2 ;;
esac
case "$(uname -m)" in
  arm64|aarch64) arch=aarch64 ;;
  x86_64|amd64) arch=x86_64 ;;
  *) echo "unsupported arch: $(uname -m)" >&2; exit 2 ;;
esac
"$cargo" build --release --locked --manifest-path "$here/Cargo.toml" --target-dir "$here/target"
out="$here/dist/native/$os-$arch"
mkdir -p "$out"
cp "$here/target/release/libdpdk_bcrypt_jni.$ext" "$out/"
echo "$out/libdpdk_bcrypt_jni.$ext"
