#!/bin/bash
# 场景2：管理员闭环（批量创建部分失败/停号登录拒绝/改密审批流一次性令牌）
set -u
SCENARIO=02-admin
source "$(dirname "$0")/common.sh"
: > "$LOG_DIR/02-admin.log"
TOK_ADMIN=$(cat /tmp/p2reg/tok_admin.txt)
AUTH="Authorization: Bearer $TOK_ADMIN"

echo "=== S2.1 批量创建学生：1 新 + 1 重复 → 部分失败契约（created/skipped） ===" | tee -a "$LOG_DIR/02-admin.log"
req "batch create: 1 new + 1 duplicate" -X POST "$BASE/api/admin/users" -H "$AUTH" \
  -H 'Content-Type: application/json' \
  -d '{"ids":["U90000000006","U90000000003"],"roles":["viewer"]}'
psqlq "SELECT 'db check: '||id||' created_by='||coalesce(created_by,'NULL') FROM users WHERE id IN ('U90000000006','U90000000003') ORDER BY id" >> "$LOG_DIR/02-admin.log"

echo "=== S2.2 停号 U90000000006 → 其登录 401；恢复 → 可登录 ===" | tee -a "$LOG_DIR/02-admin.log"
req "disable U90000000006" -X POST "$BASE/api/admin/users/U90000000006/status" -H "$AUTH" \
  -H 'Content-Type: application/json' -d '{"enabled":false}'
req "disabled login attempt" -X POST "$BASE/api/auth/login" -H 'Content-Type: application/json' \
  -d '{"username":"U90000000006","password":"U90000000006"}'
req "re-enable U90000000006" -X POST "$BASE/api/admin/users/U90000000006/status" -H "$AUTH" \
  -H 'Content-Type: application/json' -d '{"enabled":true}'
req "re-enabled login" -X POST "$BASE/api/auth/login" -H 'Content-Type: application/json' \
  -d '{"username":"U90000000006","password":"U90000000006"}' -o /dev/null
psqlq "SELECT 'tokens for 06: '||count(*) FROM api_tokens t JOIN users u ON u.id=t.user_id WHERE u.id='U90000000006'" >> "$LOG_DIR/02-admin.log"

echo "=== S2.3 改密审批流：学生申请 → admin 审批 → 一次性令牌重置 → 新密码登录、旧 token 失效 ===" | tee -a "$LOG_DIR/02-admin.log"
# 学生登录拿旧 token
reqsave "student03 login (pre-reset)" /tmp/p2reg/tok_s3.json -X POST "$BASE/api/auth/login" \
  -H 'Content-Type: application/json' -d '{"username":"U90000000003","password":"U90000000003"}'
TOK_S3=$(python3 -c "import json;print(json.load(open('/tmp/p2reg/tok_s3.json'))['token'])")
echo "$TOK_S3" > /tmp/p2reg/tok_s3.txt
# 学生申请改密
req "student password-reset-requests" -X POST "$BASE/api/auth/password-reset-requests" \
  -H 'Content-Type: application/json' -d '{"username":"U90000000003"}'
# admin 查看申请列表
reqsave "admin list reset requests" /tmp/p2reg/reset_list.json -H "$AUTH" "$BASE/api/admin/password-reset-requests"
REQ_ID=$(python3 -c "
import json;d=json.load(open('/tmp/p2reg/reset_list.json'))
open('/tmp/p2reg/req_id.txt','w').write(d['requests'][0]['id']);print('first request id saved; status=',d['requests'][0].get('status'))")
REQ_ID=$(cat /tmp/p2reg/req_id.txt)
echo "request_id=$REQ_ID (日志中已打码形式另见)" | tee -a "$LOG_DIR/02-admin.log"
# 审批 → 一次性 reset_token
reqsave "admin approve" /tmp/p2reg/approve.json -X POST "$BASE/api/admin/password-reset-requests/$REQ_ID/approve" -H "$AUTH"
RESET_TOKEN=$(python3 -c "import json;print(json.load(open('/tmp/p2reg/approve.json'))['reset_token'])")
echo "reset_token len=$(echo -n "$RESET_TOKEN"|wc -c|tr -d ' ')" | tee -a "$LOG_DIR/02-admin.log"
# 一次性令牌重置成功
req "password-reset with one-time token" -X POST "$BASE/api/auth/password-reset" \
  -H 'Content-Type: application/json' \
  -d "{\"token\":\"$RESET_TOKEN\",\"new_password\":\"NewPass-9003\"}"
# 旧密码 401、新密码 200；重放同一令牌应失败
req "old password rejected" -X POST "$BASE/api/auth/login" -H 'Content-Type: application/json' \
  -d '{"username":"U90000000003","password":"U90000000003"}'
reqsave "new password login" /tmp/p2reg/tok_s3b.json -X POST "$BASE/api/auth/login" \
  -H 'Content-Type: application/json' -d '{"username":"U90000000003","password":"NewPass-9003"}'
python3 -c "import json;d=json.load(open('/tmp/p2reg/tok_s3b.json'));print('post-reset must_change_password:',d.get('must_change_password'))" | tee -a "$LOG_DIR/02-admin.log"
echo "$TOK_S3" > /tmp/p2reg/tok_s3_old.txt
req "old bearer token after reset (should 401)" -H "Authorization: Bearer $TOK_S3" "$BASE/api/usage/me"
req "reuse one-time token (should 4xx)" -X POST "$BASE/api/auth/password-reset" \
  -H 'Content-Type: application/json' \
  -d "{\"token\":\"$RESET_TOKEN\",\"new_password\":\"Again-9003\"}"
# 把学生密码重置回学号，供后续场景使用（直接 SQL，回归内部状态复位）
psqlq "UPDATE users SET password_hash='\$2a\$10\$FN3X5jYm6WiCTS3GefGmjudNAWuvBj9rgUgoj46oyxjCtMMDk9bJi' WHERE id='U90000000003'" >/dev/null
echo "student03 password restored to initial (SQL, 回归状态复位)" >> "$LOG_DIR/02-admin.log"

echo "scenario 02 done" | tee -a "$LOG_DIR/02-admin.log"
