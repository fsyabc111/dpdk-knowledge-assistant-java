#!/usr/bin/env bash
# 创建隔离演练证书；不覆盖现有资产，证书与私钥不入库。
set -euo pipefail
[[ $# == 1 && "$1" == /* ]] || { echo '用法：generate-cert.sh /绝对路径/新证书目录' >&2; exit 2; }
[[ $(id -u) != 0 ]] || { echo "请以非root用户生成演练证书" >&2; exit 1; }
cert_dir="$1"
[[ ! -e "$cert_dir" ]] || { echo '目录已存在，拒绝覆盖' >&2; exit 1; }
umask 077
mkdir -p -- "$cert_dir"
openssl req -x509 -newkey rsa:2048 -nodes -days 7 \
  -subj /CN=localhost -addext 'subjectAltName=DNS:localhost,IP:127.0.0.1' \
  -keyout "$cert_dir/server.key" -out "$cert_dir/server.crt" >/dev/null 2>&1
# 私钥始终为0600；Compose代理显式采用此非root所有者UID/GID。
chmod 700 "$cert_dir"
chmod 644 "$cert_dir/server.crt"
chmod 600 "$cert_dir/server.key"
echo "DPDK_JAVA_PROXY_UID=$(id -u)"
echo "DPDK_JAVA_PROXY_GID=$(id -g)"
echo '已生成 7 天有效的 localhost 隔离演练证书'
