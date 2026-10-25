#!/bin/bash
# ST-03 受控空库种子入口：零写入身份核验 → 创建全新测试库 → 记录身份文件。
#
# 纪律（与 Java TestPg 同等）：
# 1. 任何写操作（CREATE DATABASE / COMMENT）之前，先对目标实例做只读身份核验
#    （current_user / SHOW data_directory / 库名占用检查），不符即退出且零写入；
# 2. 不设默认口令：口令只从 DPDK_TEST_PG_PASSWORD 读取，脚本无口令字面量；
# 3. 本轮动态身份（库名/round 标记/后续 token/作业 id）写入身份文件
#    logs/replay-state.json（.gitignore，权限 600，不含口令）。
#
# 环境变量：
#   DPDK_TEST_PG_HOST       默认 127.0.0.1
#   DPDK_TEST_PG_PORT       默认 55481
#   DPDK_TEST_PG_USER       默认 p1_java
#   DPDK_TEST_PG_PASSWORD   必填（不设默认口令）
#   DPDK_TEST_PG_DATA_DIR   必填（期望 data_directory 前缀，如 <repo>/.migration-artifacts/p1/pgdata）
#   DPDK_REPLAY_MAINTDB     维护库，默认 postgres（身份核验与建库在其上执行）
#   DPDK_REPLAY_BASE_URL    默认 http://127.0.0.1:8090（写入身份文件）
#   DPDK_REPLAY_ROUND       本轮标记，默认 date +%Y%m%d-%H%M%S（进库名，保证全新）
#   DPDK_REPLAY_STATE       身份文件路径，默认 logs/replay-state.json
set -eu
SCENARIO=00-setup
source "$(dirname "$0")/common.sh"

: "${DPDK_TEST_PG_DATA_DIR:?需要 DPDK_TEST_PG_DATA_DIR（期望 data_directory 前缀，核验用）}"
MAINTDB="${DPDK_REPLAY_MAINTDB:-postgres}"
ROUND="${DPDK_REPLAY_ROUND:-$(date +%Y%m%d-%H%M%S)}"
ROUND_ID="p2replay-${ROUND}"
NEWDB="${ROUND_ID}"   # 库名含本轮标记

idcheck() {  # 只读身份核验（在指定库上执行 SELECT，不写任何东西）
  local db="$1"
  psql -h "$PGHOST" -p "$PGPORT" -U "$PGUSER" -d "$db" -v ON_ERROR_STOP=1 -tAc \
    "SELECT current_user||'|'||current_setting('data_directory')||'|'||current_setting('server_version')"
}

echo "== ST-00 受控空库种子：round=${ROUND} =="
echo "-- [零写入核验 1/3] 目标实例身份（维护库 ${MAINTDB}，只读 SELECT）"
IDENT="$(idcheck "$MAINTDB")" || {
  echo "FATAL: 无法连接目标实例 ${PGHOST}:${PGPORT}/${MAINTDB}（未写入任何东西）" >&2
  exit 2
}
IDENT_USER="${IDENT%%|*}"
IDENT_REST="${IDENT#*|}"
IDENT_DATA_DIR="${IDENT_REST%%|*}"
if [ "$IDENT_USER" != "$PGUSER" ]; then
  echo "FATAL: current_user=${IDENT_USER} 与期望 ${PGUSER} 不符；目标不是受控实例，拒绝写入（零写入退出）" >&2
  exit 2
fi
case "$IDENT_DATA_DIR" in
  "$DPDK_TEST_PG_DATA_DIR"*) : ;;  # 前缀匹配
  *)
    echo "FATAL: data_directory=${IDENT_DATA_DIR} 与期望前缀 ${DPDK_TEST_PG_DATA_DIR} 不符；拒绝写入（零写入退出）" >&2
    exit 2
    ;;
esac
echo "   身份匹配：user=${IDENT_USER} data_directory 前缀匹配 server_version=${IDENT##*|}"

echo "-- [零写入核验 2/3] 目标库不存在（全新建库，不复用不覆盖）"
if psql -h "$PGHOST" -p "$PGPORT" -U "$PGUSER" -d "$MAINTDB" -tAc \
     "SELECT 1 FROM pg_database WHERE datname='${NEWDB}'" | grep -q 1; then
  echo "FATAL: 库 ${NEWDB} 已存在；换一个 DPDK_REPLAY_ROUND，拒绝复用（零写入退出）" >&2
  exit 2
fi

echo "-- [核验通过，开始写] CREATE DATABASE ${NEWDB} + 本轮标记 COMMENT"
psql -h "$PGHOST" -p "$PGPORT" -U "$PGUSER" -d "$MAINTDB" -v ON_ERROR_STOP=1 -c \
  "CREATE DATABASE ${NEWDB}" >/dev/null
psql -h "$PGHOST" -p "$PGPORT" -U "$PGUSER" -d "$MAINTDB" -v ON_ERROR_STOP=1 -c \
  "COMMENT ON DATABASE ${NEWDB} IS 'java-migration-p2-replay ${ROUND_ID}'" >/dev/null

echo "-- [建库后核验 3/3] 新库身份（current_database + COMMENT + data_directory）"
NEW_IDENT="$(idcheck "$NEWDB")" || {
  echo "FATAL: 新库连接失败（建库已发生，请人工检查 ${NEWDB}）" >&2
  exit 2
}
NEW_DBNAME="$(psql -h "$PGHOST" -p "$PGPORT" -U "$PGUSER" -d "$NEWDB" -tAc "SELECT current_database()")"
NEW_COMMENT="$(psql -h "$PGHOST" -p "$PGPORT" -U "$PGUSER" -d "$MAINTDB" -tAc \
  "SELECT description FROM pg_shdescription JOIN pg_database ON objoid=pg_database.oid WHERE datname='${NEWDB}'")"
NEW_DATA_DIR="$(psql -h "$PGHOST" -p "$PGPORT" -U "$PGUSER" -d "$NEWDB" -tAc "SELECT current_setting('data_directory')")"
[ "$NEW_DBNAME" = "$NEWDB" ] && [ "$NEW_COMMENT" = "java-migration-p2-replay ${ROUND_ID}" ] \
  && case "$NEW_DATA_DIR" in "$DPDK_TEST_PG_DATA_DIR"*) true ;; *) false ;; esac || {
  echo "FATAL: 新库身份核验不符：db=${NEW_DBNAME} comment=${NEW_COMMENT} datadir=${NEW_DATA_DIR}" >&2
  exit 2
}
echo "   新库身份匹配：${NEW_DBNAME}（comment=${NEW_COMMENT}）"

echo "-- 写身份文件 ${STATE_FILE}（含本轮库名；绝不含口令）"
state_set round_id "$ROUND_ID"
state_set generated_at "$(date -u +%Y-%m-%dT%H:%M:%S+00:00)"
state_set base_url "$BASE"
state_set pg.host "$PGHOST"
state_set pg.port "$PGPORT"
state_set pg.user "$PGUSER"
state_set pg.database "$NEWDB"
state_set pg.data_dir "$NEW_DATA_DIR"
state_set pg.maintdb "$MAINTDB"
# 账号约定登记（口令=学号，见 seed_accounts.sh；不写口令）
state_set accounts.leader "U90000000001"
state_set accounts.admin "U90000000002"
state_set accounts.student_a "U90000000003"
state_set accounts.student_b "U90000000004"
state_set accounts.free "U90000000005"
state_set accounts.viewer "U90000000005"
state_set accounts.s7 "U90000000007"

echo "完成：全新测试库 ${NEWDB} 已建立并记录于身份文件。"
echo "下一步：启动应用（指向 ${NEWDB}，完成迁移）→ seed_accounts.sh 种账号"
echo "        → collect_state.py 收集各角色 token → run_01..06 场景 → run_08_golden.py 重放。"
