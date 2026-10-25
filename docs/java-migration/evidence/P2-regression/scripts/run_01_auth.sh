#!/bin/bash
# 场景1：认证闭环（登录成功/错误密码401同体/无凭据401/登出失效）
set -u
SCENARIO=01-auth
source "$(dirname "$0")/common.sh"
# 登录限流为冻结契约（10 次/分/IP）：场景间节拍，避免背靠背连跑触发 429
PACE="${DPDK_REPLAY_SCENARIO_PACE:-15}"; [ "$PACE" -gt 0 ] && sleep "$PACE"
: > "$LOG_DIR/01-auth.log"

echo "=== S1.1 admin 登录成功（口令=学号，见 seed_users.sql） ===" | tee -a "$LOG_DIR/01-auth.log"
reqsave "admin login" $REPLAY_TMP/tok_admin.json -X POST "$BASE/api/auth/login" \
  -H 'Content-Type: application/json' -d '{"username":"U90000000002","password":"U90000000002"}'
python3 - <<'EOF'
import json,os
d=json.load(open(os.environ['REPLAY_TMP']+'/tok_admin.json'))
print("login body keys:", sorted(d.keys()))
print("subject:", d.get("subject"), "roles:", d.get("roles"),
      "must_change_password:", d.get("must_change_password"),
      "expires_at:", d.get("expires_at"), "token_len:", len(d.get("token","")))
EOF

echo "=== S1.2 错误密码 401（响应体与无此用户同体——验收 A/B 语义） ===" | tee -a "$LOG_DIR/01-auth.log"
req "wrong password" -X POST "$BASE/api/auth/login" -H 'Content-Type: application/json' \
  -d '{"username":"U90000000002","password":"wrong-pass"}'
req "nonexistent user" -X POST "$BASE/api/auth/login" -H 'Content-Type: application/json' \
  -d '{"username":"U99999999999","password":"whatever"}'

echo "=== S1.3 无凭据访问 admin 接口 401 ===" | tee -a "$LOG_DIR/01-auth.log"
req "no token GET /api/admin/users" "$BASE/api/admin/users"
req "malformed bearer" -H 'Authorization: Bearer not-a-real-token' "$BASE/api/admin/users"

echo "=== S1.4 学生登录 + 登出后旧 token 失效 ===" | tee -a "$LOG_DIR/01-auth.log"
reqsave "student login" $REPLAY_TMP/tok_s3.json -X POST "$BASE/api/auth/login" \
  -H 'Content-Type: application/json' -d '{"username":"U90000000003","password":"U90000000003"}'
python3 -c "import json,os;d=json.load(open(os.environ['REPLAY_TMP']+'/tok_s3.json'));print('student login roles:',d.get('roles'))"
TOK_S3=$(python3 -c "import json,os;print(json.load(open(os.environ['REPLAY_TMP']+'/tok_s3.json'))['token'])")
req "student me-ish call (GET /api/usage/me with token)" -H "Authorization: Bearer $TOK_S3" "$BASE/api/usage/me"
req "logout" -X POST -H "Authorization: Bearer $TOK_S3" "$BASE/api/auth/logout"
req "revoke check: same token after logout" -H "Authorization: Bearer $TOK_S3" "$BASE/api/usage/me"

echo "=== S1.5 管理员 token 基本可用（GET /api/admin/users 200） ===" | tee -a "$LOG_DIR/01-auth.log"
TOK_ADMIN=$(python3 -c "import json,os;print(json.load(open(os.environ['REPLAY_TMP']+'/tok_admin.json'))['token'])")
req "admin GET /api/admin/users" -H "Authorization: Bearer $TOK_ADMIN" "$BASE/api/admin/users"
echo "TOK_ADMIN=$(echo "$TOK_ADMIN" | head -c 6)...(已脱敏保存 $REPLAY_TMP/tok_admin.json)" >> "$LOG_DIR/01-auth.log"
state_set tokens.admin "$TOK_ADMIN"
state_set tokens.student_a "$TOK_S3"
echo "scenario 01 done" | tee -a "$LOG_DIR/01-auth.log"
