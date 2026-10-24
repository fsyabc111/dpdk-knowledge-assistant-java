#!/usr/bin/env bash

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
if [[ -f "$ROOT_DIR/deploy/external_disk.json" ]]; then
  exec python3 "$ROOT_DIR/deploy/external_disk.py" stop
fi

BACKEND_PORT=8090
FRONTEND_PORT=5174

is_windows() {
  [[ -n "${WINDIR:-}" || "$OSTYPE" == msys* || "$OSTYPE" == cygwin* ]]
}

stop_port() {
  local port="$1" name="$2"
  local pids=""

  if is_windows; then
    pids=$(netstat -ano | grep -E "[:.]${port}[[:space:]]" | grep -i LISTENING | awk '{print $NF}' | sort -u || true)
  else
    pids=$(lsof -ti "tcp:${port}" -sTCP:LISTEN 2>/dev/null || true)
  fi

  if [[ -z "$pids" ]]; then
    echo "$name: nothing listening on port $port"
    return 0
  fi

  for pid in $pids; do
    echo "Stopping $name (PID $pid, port $port)..."
    if is_windows; then
      taskkill //PID "$pid" //F //T >/dev/null 2>&1 || true
    else
      kill "$pid" 2>/dev/null || true
    fi
  done
}

stop_port "$BACKEND_PORT" "backend (uvicorn)"
stop_port "$FRONTEND_PORT" "frontend (vite)"

echo "Done."
