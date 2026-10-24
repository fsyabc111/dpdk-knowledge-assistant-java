#!/usr/bin/env bash
# Automatic knowledge-index rebuild (roadmap M4).
#
# 每次扫描全部知识源，按正文和配置直接比较，不使用 Git/content hash 验证。
# ingest (unchanged nodes are skipped by the ingest itself). A lock directory
# prevents overlapping runs; failures raise the alert webhook.
#
# Environment:
#   COMPOSE_DIR       compose project dir         (default: this repo root)
#   INGEST_CMD        ingest command override     (default: docker compose exec -T backend python scripts/ingest_llama_index.py)
#   LOCK_FILE         lock base name ($LOCK_FILE.d is the lock dir; default: /tmp/assistant-ingest.lock)
#   ALERT_WEBHOOK_URL optional webhook notified on ingest failure
#   DRY_RUN=1         only report whether an ingest would run
#
# Cron example (every 5 minutes, per docs/archive/2026-09-18/rag_handoff.md):
#   */5 * * * * ALERT_WEBHOOK_URL=<webhook> /opt/dpdk/ai_chat_assistant/deploy/auto_ingest.sh >> /var/log/assistant-ingest.log 2>&1

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
COMPOSE_DIR="${COMPOSE_DIR:-$REPO_ROOT}"
INGEST_CMD="${INGEST_CMD:-docker compose exec -T backend python scripts/ingest_llama_index.py}"
LOCK_FILE="${LOCK_FILE:-/tmp/assistant-ingest.lock}"
ALERT_WEBHOOK_URL="${ALERT_WEBHOOK_URL:-}"
DRY_RUN="${DRY_RUN:-0}"

now() { date -u +"%Y-%m-%dT%H:%M:%SZ"; }

alert_failure() {
  local reason="$1"
  echo "$(now) INGEST FAILED: $reason" >&2
  if [[ -n "$ALERT_WEBHOOK_URL" ]]; then
    curl -fsS -m 10 -H "Content-Type: application/json" \
      -d "{\"alert\": \"assistant-ingest\", \"reason\": \"$reason\", \"time\": \"$(now)\"}" \
      "$ALERT_WEBHOOK_URL" || true
  fi
}

if [[ "$DRY_RUN" == "1" ]]; then
  echo "$(now) dry-run: would scan all configured knowledge sources"
  exit 0
fi

# mkdir is atomic on both Linux and Git Bash (which lacks flock).
LOCK_DIR="$LOCK_FILE.d"
if ! mkdir "$LOCK_DIR" 2>/dev/null; then
  echo "$(now) another ingest is running; skipping"
  exit 0
fi
trap 'rmdir "$LOCK_DIR"' EXIT

echo "$(now) scanning all configured knowledge sources"
if (cd "$COMPOSE_DIR" && eval "$INGEST_CMD"); then
  echo "$(now) ingest complete"
else
  alert_failure "ingest command failed"
  exit 1
fi
