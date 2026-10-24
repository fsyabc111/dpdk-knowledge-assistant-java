#!/usr/bin/env bash
# macOS / Colima 本地评测：internal 网络不发布端口，由 VM 的 SSH 提供回环转发。
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
STATE_FILE="$ROOT_DIR/.runtime/harness-forward"
SSH_CONFIG="$HOME/.colima/_lima/colima-dpdk/ssh.config"
COMPOSE=(docker --context colima-dpdk compose -f "$ROOT_DIR/docker-compose.local.yml")
ACTION="${1:-start}"

# 全栈外接部署由统一入口管理；不再创建本地 harness 栈或 SSH 转发。
if [[ -f "$ROOT_DIR/deploy/external_disk.json" ]]; then
  echo "请使用 python3 deploy/external_disk.py vm-start/up/stop 管理完整部署。" >&2
  exit 2
fi

if [[ "$ACTION" != start && "$ACTION" != stop ]]; then
  echo "Usage: bash local_harness.sh [start|stop]" >&2
  exit 2
fi

container_ip() {
  local container_id
  container_id="$("${COMPOSE[@]}" ps -q harness)"
  [[ -n "$container_id" ]] || return 1
  docker --context colima-dpdk inspect "$container_id" \
    --format '{{range .NetworkSettings.Networks}}{{.IPAddress}}{{end}}'
}

cancel_forward() {
  local previous="" ip=""
  if [[ -f "$STATE_FILE" ]]; then
    IFS= read -r previous < "$STATE_FILE" || true
  elif ip="$(container_ip 2>/dev/null)" && [[ -n "$ip" ]]; then
    previous="127.0.0.1:8100:$ip:8100"
  fi
  if [[ -n "$previous" && -f "$SSH_CONFIG" ]]; then
    ssh -F "$SSH_CONFIG" -O cancel -L "$previous" lima-colima-dpdk 2>/dev/null || true
  fi
  rm -f "$STATE_FILE"
}

if [[ "$ACTION" == stop ]]; then
  cancel_forward
  "${COMPOSE[@]}" down
  exit 0
fi

command -v colima >/dev/null
command -v docker >/dev/null
if ! colima status dpdk >/dev/null 2>&1; then
  colima start dpdk
fi
cancel_forward
"${COMPOSE[@]}" up -d --build
HARNESS_IP="$(container_ip)"
[[ -n "$HARNESS_IP" ]] || { echo "Harness container has no IP" >&2; exit 1; }
FORWARD="127.0.0.1:8100:$HARNESS_IP:8100"
ssh -F "$SSH_CONFIG" -O forward -L "$FORWARD" lima-colima-dpdk
mkdir -p "$ROOT_DIR/.runtime"
printf '%s\n' "$FORWARD" > "$STATE_FILE"
echo "Harness endpoint: http://127.0.0.1:8100/mcp"
