#!/usr/bin/env bash
# Database backup for the assistant (roadmap M4).
#
# postgres (production, compose deployment):
#   pg_dump -Fc via `docker compose exec -T postgres`; omit temporary task input
#   rows while retaining the table schema, then rotate to KEEP copies.
# sqlite (explicit local fallback):
#   plain file copy of DATABASE_URL's sqlite file.
#
# Environment:
#   BACKUP_DIR        target directory        (default: <repo>/backend/data/backups)
#   KEEP              number of dumps to keep (default: 7)
#   DATABASE_BACKEND  postgres | sqlite       (default: postgres)
#   SQLITE_PATH       sqlite file to copy     (default: <repo>/backend/data/assistant.db)
#   COMPOSE_DIR       compose project dir     (default: repo root)
#   ALERT_WEBHOOK_URL optional webhook notified on failure
#
# Cron example (daily 03:20):
#   20 3 * * * ALERT_WEBHOOK_URL=<webhook> /opt/dpdk/ai_chat_assistant/deploy/backup.sh >> /var/log/assistant-backup.log 2>&1

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
BACKUP_DIR="${BACKUP_DIR:-$REPO_ROOT/backend/data/backups}"
KEEP="${KEEP:-7}"
DATABASE_BACKEND="${DATABASE_BACKEND:-postgres}"
SQLITE_PATH="${SQLITE_PATH:-$REPO_ROOT/backend/data/assistant.db}"
COMPOSE_DIR="${COMPOSE_DIR:-$REPO_ROOT}"
ALERT_WEBHOOK_URL="${ALERT_WEBHOOK_URL:-}"

now() { date -u +"%Y-%m-%dT%H:%M:%SZ"; }
stamp="$(date -u +%Y%m%dT%H%M%SZ)"

alert_failure() {
  local reason="$1"
  echo "$(now) BACKUP FAILED: $reason" >&2
  if [[ -n "$ALERT_WEBHOOK_URL" ]]; then
    curl -fsS -m 10 -H "Content-Type: application/json" \
      -d "{\"alert\": \"assistant-backup\", \"reason\": \"$reason\", \"time\": \"$(now)\"}" \
      "$ALERT_WEBHOOK_URL" || true
  fi
}

mkdir -p "$BACKUP_DIR"

rotate() {
  # Keep the newest KEEP dumps, delete the rest.
  ls -1t "$BACKUP_DIR"/assistant-*.dump 2>/dev/null | tail -n "+$((KEEP + 1))" | while read -r old; do
    echo "$(now) rotating out $old"
    rm -f "$old"
  done
}

case "$DATABASE_BACKEND" in
  postgres)
    target="$BACKUP_DIR/assistant-$stamp.dump"
    # rag_task_inputs 含任务存活期的原始问题与源码，不得进入保留多日的备份。
    # --exclude-table-data 只排除数据，恢复时仍会建表供新任务使用。
    if ! (cd "$COMPOSE_DIR" && docker compose exec -T postgres \
      pg_dump -U postgres -Fc --exclude-table-data=public.rag_task_inputs assistant) > "$target"; then
      rm -f "$target"
      alert_failure "pg_dump via docker compose failed"
      exit 1
    fi
    ;;
  sqlite)
    target="$BACKUP_DIR/assistant-$stamp.dump"
    if [[ ! -f "$SQLITE_PATH" ]]; then
      alert_failure "sqlite database not found: $SQLITE_PATH"
      exit 1
    fi
    if ! cp "$SQLITE_PATH" "$target"; then
      alert_failure "copy of $SQLITE_PATH failed"
      exit 1
    fi
    ;;
  *)
    alert_failure "unsupported DATABASE_BACKEND=$DATABASE_BACKEND"
    exit 1
    ;;
esac

rotate
echo "$(now) backup written: $target ($(du -h "$target" | cut -f1))"
