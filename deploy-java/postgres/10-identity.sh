#!/usr/bin/env bash
# 仅在此全新 Compose 专属 PG 首次初始化时写入身份标记。
set -euo pipefail
[[ "$POSTGRES_DB" == dpdk_java_isolated && "$POSTGRES_USER" == dpdk_java_isolated ]] || {
  echo '拒绝初始化：不是 Java 隔离库与用户' >&2; exit 1;
}
psql --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" -v ON_ERROR_STOP=1 <<'SQL'
COMMENT ON DATABASE dpdk_java_isolated IS 'dpdk-java-isolated-rehearsal';
CREATE EXTENSION IF NOT EXISTS vector;
SQL
