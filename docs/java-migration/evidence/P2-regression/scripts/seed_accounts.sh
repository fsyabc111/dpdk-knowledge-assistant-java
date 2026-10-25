#!/bin/bash
# ST-03 合成账号种子：向本轮身份文件的库种入 U90000000001..05（口令=学号，bcrypt 运行时生成）。
#
# 前置：run_00_setup.sh 已建库；应用已启动并完成迁移（users 表存在）。
# 纪律：
# - 写库前重做只读身份核验（current_database/data_directory 与身份文件比对）；
# - bcrypt 哈希由 tools/BcryptGen.java 运行时生成，脚本不提交任何哈希/口令字面量；
# - 合成账号口令=学号（与历史 seed_users.sql 约定一致），仅存在于本轮独立测试库。
#
# U90000000006（leader 树内 viewer）由场景2/collect_state.py 通过 admin 批量创建接口
# 建立（与历史等价流一致）；U90000000007（无班级学生）在本脚本以 created_by=NULL 种子，
# 保证 foreign-tree 等价主体确实树外。
set -eu
SCENARIO=00b-seed
source "$(dirname "$0")/common.sh"

echo "== ST-00b 合成账号种子：库 $(state_get pg.database 2>/dev/null || echo '(身份文件缺失)') =="
[ -f "$STATE_FILE" ] || { echo "FATAL: 身份文件不存在（先跑 run_00_setup.sh）" >&2; exit 2; }
DB="$(state_get pg.database)"
DATADIR_EXPECT="$(state_get pg.data_dir)"

echo "-- [零写入核验] 目标库身份（只读 SELECT）"
CUR_DB="$(psql -h "$PGHOST" -p "$PGPORT" -U "$PGUSER" -d "$DB" -v ON_ERROR_STOP=1 -tAc "SELECT current_database()")"
CUR_DD="$(psql -h "$PGHOST" -p "$PGPORT" -U "$PGUSER" -d "$DB" -v ON_ERROR_STOP=1 -tAc "SELECT current_setting('data_directory')")"
[ "$CUR_DB" = "$DB" ] && case "$CUR_DD" in "$DATADIR_EXPECT"*) : ;; *) echo "FATAL: data_directory=${CUR_DD} 与身份文件不符，拒绝写入" >&2; exit 2 ;; esac || {
  echo "FATAL: current_database=${CUR_DB} 与身份文件 ${DB} 不符，拒绝写入" >&2
  exit 2
}

echo "-- users 表存在性（应用迁移是否完成）"
psql -h "$PGHOST" -p "$PGPORT" -U "$PGUSER" -d "$DB" -v ON_ERROR_STOP=1 -tAc "SELECT 1 FROM users LIMIT 0" >/dev/null || {
  echo "FATAL: users 表不存在：先启动应用完成迁移再种账号" >&2
  exit 2
}

seed_user() {  # seed_user <id> <roles_json> <created_by|NULL>
  local id="$1" roles="$2" created_by="$3"
  local hash
  hash="$(bcrypt_hash "$id")" || exit 2
  psql -h "$PGHOST" -p "$PGPORT" -U "$PGUSER" -d "$DB" -v ON_ERROR_STOP=1 -tAc \
    "INSERT INTO public.users (id, password_hash, roles, daily_quota, enabled, created_at, must_change_password, created_by) VALUES ('${id}', '${hash}', '${roles}', NULL, true, now(), true, ${created_by}) ON CONFLICT (id) DO NOTHING" >/dev/null
  echo "   seeded ${id} roles=${roles}（bcrypt 运行时生成，不入库证据）"
}

echo "-- 种子 U1..U5（口令=学号；已有则跳过）"
seed_user "U90000000001" '["leader"]' "NULL"
seed_user "U90000000002" '["admin"]' "NULL"
seed_user "U90000000003" '["viewer"]' "'U90000000002'"
seed_user "U90000000004" '["viewer"]' "'U90000000002'"
seed_user "U90000000005" '["viewer"]' "'U90000000002'"
# U7 必须无班级（golden foreign-tree 等价主体）：batch create 会把它挂到调用 admin
# 的班上（U90000000002），与 ASG1 创建者同班 → 越权可见性案例失真。故在此以
# created_by=NULL 直接种子；collect_state 的批量创建对已存在 id 幂等 skipped。
seed_user "U90000000007" '["viewer"]' "NULL"

echo "-- 复核（只读）"
psql -h "$PGHOST" -p "$PGPORT" -U "$PGUSER" -d "$DB" -tAc \
  "SELECT 'db check: '||id||' roles='||roles||' created_by='||coalesce(created_by,'NULL') FROM users WHERE id LIKE 'U900000000%' ORDER BY id" | sed -E 's/\$2[aby]\$[0-9]{2}\$[.\/A-Za-z0-9]{53}/<BCRYPT_MASKED>/g'
echo "完成：合成账号就绪。下一步：collect_state.py 登录收集 token（含经 admin API 创建 U90000000006/U90000000007）。"
