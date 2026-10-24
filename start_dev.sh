#!/usr/bin/env bash

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
BACKEND_DIR="$ROOT_DIR/backend"
FRONTEND_DIR="$ROOT_DIR/frontend"
LOG_DIR="$ROOT_DIR/.logs"

# 外接部署已正式接管时，旧入口也只启动同一套 Compose，避免重新使用宿主机库。
if [[ -f "$ROOT_DIR/deploy/external_disk.json" ]]; then
  python3 "$ROOT_DIR/deploy/external_disk.py" vm-start
  exec python3 "$ROOT_DIR/deploy/external_disk.py" up
fi
BACKEND_LOG="$LOG_DIR/backend.log"
FRONTEND_LOG="$LOG_DIR/frontend.log"
BACKEND_PID=""
FRONTEND_PID=""

require_file() {
  local path="$1"
  local hint="$2"
  if [[ ! -e "$path" ]]; then
    echo "Missing: $path"
    echo "$hint"
    exit 1
  fi
}

cleanup() {
  local exit_code=$?
  if [[ -n "$BACKEND_PID" ]] && kill -0 "$BACKEND_PID" 2>/dev/null; then
    kill "$BACKEND_PID" 2>/dev/null || true
  fi
  if [[ -n "$FRONTEND_PID" ]] && kill -0 "$FRONTEND_PID" 2>/dev/null; then
    kill "$FRONTEND_PID" 2>/dev/null || true
  fi
  wait 2>/dev/null || true
  exit "$exit_code"
}

trap cleanup INT TERM EXIT

require_file "$BACKEND_DIR/.venv/bin/python" "Create the backend virtualenv first: cd backend && python3 -m venv .venv && source .venv/bin/activate && pip install -r requirements.txt"
require_file "$BACKEND_DIR/.env" "Copy backend/.env.example to backend/.env and fill in the model credentials first."
require_file "$FRONTEND_DIR/node_modules" "Install frontend dependencies first: cd frontend && npm install"

mkdir -p "$LOG_DIR"

echo "Starting backend..."
(
  cd "$BACKEND_DIR"
  exec ./.venv/bin/python -m uvicorn app.main:app --host 127.0.0.1 --port 8090
) >"$BACKEND_LOG" 2>&1 &
BACKEND_PID=$!

echo "Starting frontend..."
(
  cd "$FRONTEND_DIR"
  exec npm run dev
) >"$FRONTEND_LOG" 2>&1 &
FRONTEND_PID=$!

sleep 2

if ! kill -0 "$BACKEND_PID" 2>/dev/null; then
  echo "Backend exited early. See $BACKEND_LOG"
  exit 1
fi

if ! kill -0 "$FRONTEND_PID" 2>/dev/null; then
  echo "Frontend exited early. See $FRONTEND_LOG"
  exit 1
fi

cat <<EOF
Services started.

Backend:  http://127.0.0.1:8090
Frontend: http://127.0.0.1:5174

Logs:
  $BACKEND_LOG
  $FRONTEND_LOG

Press Ctrl-C to stop both services.
EOF

wait "$BACKEND_PID" "$FRONTEND_PID"
