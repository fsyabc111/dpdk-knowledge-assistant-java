#!/usr/bin/env bash
# Minimal health alerting for the assistant (roadmap M3, starter level).
#
# Checks:
#   1. HEALTH_URL must answer 2xx within HEALTH_TIMEOUT seconds.
#   2. If LOG_FILE is set, its most recent LOG_SCAN_LINES lines must not
#      contain '"level": "ERROR"'.
# Failures accumulate in a state file; after FAIL_THRESHOLD (default 3)
# consecutive failures an alert is POSTed to ALERT_WEBHOOK_URL. The counter
# resets on the first healthy run.
#
# Cron example (every 5 minutes):
#   */5 * * * * HEALTH_URL=https://assistant.example.com/api/health \
#     ALERT_WEBHOOK_URL=https://oapi.dingtalk.com/robot/send?access_token=... \
#     /opt/dpdk/ai_chat_assistant/deploy/healthcheck.sh >> /var/log/assistant-healthcheck.log 2>&1
#
# Local verification without sending anything:
#   HEALTH_URL=http://127.0.0.1:9 deploy/healthcheck.sh --dry-run

set -euo pipefail

HEALTH_URL="${HEALTH_URL:-http://127.0.0.1:8090/api/health}"
HEALTH_TIMEOUT="${HEALTH_TIMEOUT:-10}"
FAIL_THRESHOLD="${FAIL_THRESHOLD:-3}"
ALERT_WEBHOOK_URL="${ALERT_WEBHOOK_URL:-}"
LOG_FILE="${LOG_FILE:-}"
LOG_SCAN_LINES="${LOG_SCAN_LINES:-200}"
STATE_FILE="${STATE_FILE:-/tmp/assistant-healthcheck.failcount}"
DRY_RUN=0
[[ "${1:-}" == "--dry-run" ]] && DRY_RUN=1

now() { date -u +"%Y-%m-%dT%H:%M:%SZ"; }

read_failures() {
  [[ -f "$STATE_FILE" ]] && cat "$STATE_FILE" || echo 0
}

write_failures() {
  echo "$1" > "$STATE_FILE"
}

alert() {
  local reason="$1"
  local failures="$2"
  local payload
  payload=$(printf '{"alert": "assistant-healthcheck", "reason": "%s", "consecutive_failures": %s, "time": "%s", "health_url": "%s"}' \
    "$reason" "$failures" "$(now)" "$HEALTH_URL")
  echo "$(now) ALERT: $reason (consecutive_failures=$failures)"
  if [[ "$DRY_RUN" == "1" ]]; then
    echo "dry-run: would POST $payload"
    return 0
  fi
  if [[ -z "$ALERT_WEBHOOK_URL" ]]; then
    echo "ALERT_WEBHOOK_URL is not set; alert not delivered" >&2
    return 1
  fi
  curl -fsS -m "$HEALTH_TIMEOUT" -H "Content-Type: application/json" -d "$payload" "$ALERT_WEBHOOK_URL"
}

failures="$(read_failures)"
reason=""

if ! curl -fsS -m "$HEALTH_TIMEOUT" -o /dev/null "$HEALTH_URL"; then
  reason="health endpoint unreachable or non-2xx: $HEALTH_URL"
elif [[ -n "$LOG_FILE" ]] && [[ -f "$LOG_FILE" ]]; then
  if tail -n "$LOG_SCAN_LINES" "$LOG_FILE" | grep -q '"level": "ERROR"'; then
    reason="ERROR lines found in last $LOG_SCAN_LINES lines of $LOG_FILE"
  fi
fi

if [[ -z "$reason" ]]; then
  if [[ "$failures" != "0" ]]; then
    echo "$(now) recovered after $failures failure(s); resetting counter"
  fi
  write_failures 0
  echo "$(now) OK $HEALTH_URL"
  exit 0
fi

failures=$((failures + 1))
write_failures "$failures"
echo "$(now) check failed ($failures/$FAIL_THRESHOLD): $reason"
if (( failures >= FAIL_THRESHOLD )); then
  alert "$reason" "$failures"
  # Reset so the next alert only fires after another full threshold streak.
  write_failures 0
fi
