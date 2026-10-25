#!/bin/bash
# 场景4：正式提交闭环（multipart .c 提交→fake MCP 评测→成绩；重复提交取成绩；
# SQL 验源码不落库；停号不影响他人；配额 429）
set -u
SCENARIO=04-submission
source "$(dirname "$0")/common.sh"
# 登录限流为冻结契约（10 次/分/IP）：场景间节拍，避免背靠背连跑触发 429
PACE="${DPDK_REPLAY_SCENARIO_PACE:-15}"; [ "$PACE" -gt 0 ] && sleep "$PACE"
: > "$LOG_DIR/04-submission.log"
TOK_ADMIN=$(state_get tokens.admin)
AUTH="Authorization: Bearer $TOK_ADMIN"
ASG1=$(state_get assignment_ids.asg1)

cat > $REPLAY_TMP/synthetic_plugin.c <<'EOF'
/* P2 回归合成插件源（不含任何真实凭据/密钥） */
#include <stdio.h>
int main(void) { printf("synthetic dpdk plugin\n"); return 0; }
EOF

# 学生03登录（密码已复位）
reqsave "student03 login" $REPLAY_TMP/tok_s3.json -X POST "$BASE/api/auth/login" \
  -H 'Content-Type: application/json' -d '{"username":"U90000000003","password":"U90000000003"}'
TOK_S3=$(python3 -c "import json,os;print(json.load(open(os.environ['REPLAY_TMP']+'/tok_s3.json'))['token'])")
state_set tokens.student_a "$TOK_S3"
# 学生04登录
reqsave "student04 login" $REPLAY_TMP/tok_s4.json -X POST "$BASE/api/auth/login" \
  -H 'Content-Type: application/json' -d '{"username":"U90000000004","password":"U90000000004"}'
TOK_S4=$(python3 -c "import json,os;print(json.load(open(os.environ['REPLAY_TMP']+'/tok_s4.json'))['token'])")
state_set tokens.student_b "$TOK_S4"

echo "=== S4.1 学生03 multipart 提交 .c → fake MCP 评测 → 成绩 ===" | tee -a "$LOG_DIR/04-submission.log"
reqsave "student03 submit #1" $REPLAY_TMP/submit1.json \
  -X POST "$BASE/api/assignments/$ASG1/submit" -H "Authorization: Bearer $TOK_S3" \
  -F "protocol=dns" -F "file=@$REPLAY_TMP/synthetic_plugin.c;type=text/plain"
python3 -c "
import json,os;d=json.load(open(os.environ['REPLAY_TMP']+'/submit1.json'))
print('submit#1 keys:',sorted(d.keys()))
print('score=',d.get('score'),'passed=',d.get('passed'),'total=',d.get('total'),'points=',d.get('points'),'earned=',d.get('earned'),'best_earned=',d.get('best_earned'))"
echo "harness tool_calls after submit#1: $(python3 -c "import json,os;print(json.load(open(os.environ['REPLAY_TMP']+'/harness_state.json'))['tool_calls'])")" >> "$LOG_DIR/04-submission.log"

echo "=== S4.2 同学生再提交 → 成绩列表（作业详情 best_score；管理端 submissions 列表） ===" | tee -a "$LOG_DIR/04-submission.log"
reqsave "student03 submit #2" $REPLAY_TMP/submit2.json \
  -X POST "$BASE/api/assignments/$ASG1/submit" -H "Authorization: Bearer $TOK_S3" \
  -F "protocol=dns" -F "file=@$REPLAY_TMP/synthetic_plugin.c;type=text/plain"
python3 -c "import json,os;d=json.load(open(os.environ['REPLAY_TMP']+'/submit2.json'));print('submit#2 score=',d.get('score'),'submission_id=',d.get('submission_id'))"
reqsave "student03 assignment detail (best scores)" $REPLAY_TMP/detail1.json -H "Authorization: Bearer $TOK_S3" "$BASE/api/assignments/$ASG1"
python3 -c "
import json,os;d=json.load(open(os.environ['REPLAY_TMP']+'/detail1.json'))
print('detail items best_score/earned/submission_count:',[(i['protocol'],i['best_score'],i['earned'],i['submission_count']) for i in d['items']])
print('total_earned=',d.get('total_earned'),'total_points=',d.get('total_points'))"
reqsave "admin grading submissions list" $REPLAY_TMP/gsub.json -H "$AUTH" "$BASE/api/grading/submissions?limit=10"
python3 -c "
import json,os;d=json.load(open(os.environ['REPLAY_TMP']+'/gsub.json'))
rows=d['submissions']
print('submissions rows:',len(rows),'user_ids:',sorted(set(r['user_id'] for r in rows)))
print('row keys:',sorted(rows[0].keys()) if rows else [])"

echo "=== S4.3 SQL 验证：成绩行只含 hash，results_json 有内容且无源码文本 ===" | tee -a "$LOG_DIR/04-submission.log"
{
  echo "-- grading_submissions 概览（user/protocol/score/hash前12位/长度）"
  psqlq "SELECT user_id||' '||protocol||' score='||score||' hash='||substring(code_hash from 1 for 12)||'… hashlen='||length(code_hash)||' results_len='||length(results_json::text)||' assignment_id='||coalesce(assignment_id,'NULL') FROM grading_submissions ORDER BY created_at"
  echo "-- 源码特征串在整行内不应出现（期望 0 行）"
  psqlq "SELECT 'source-text leak rows: '||count(*) FROM grading_submissions WHERE code_hash LIKE '%synthetic%' OR results_json::text LIKE '%synthetic%' OR results_json::text LIKE '%printf%' OR code_hash LIKE '%stdio%'"
  echo "-- api_tokens 无源码文本（期望 0；api_tokens 只存 token_hash）"
  psqlq "SELECT 'tokens-with-source: '||count(*) FROM api_tokens WHERE token_hash LIKE '%synthetic%'"
} >> "$LOG_DIR/04-submission.log"
psqlq "SELECT user_id||' '||protocol||' score='||score||' hash='||substring(code_hash from 1 for 12)||'… results_len='||length(results_json::text) FROM grading_submissions ORDER BY created_at" | head -5

echo "=== S4.4 admin 停号学生03 → 03 token 失效；学生04提交不受影响 ===" | tee -a "$LOG_DIR/04-submission.log"
req "disable student03" -X POST "$BASE/api/admin/users/U90000000003/status" -H "$AUTH" -H 'Content-Type: application/json' -d '{"enabled":false}'
req "student03 call after disable (401 expected)" -H "Authorization: Bearer $TOK_S3" "$BASE/api/usage/me"
reqsave "student04 submit while 03 disabled" $REPLAY_TMP/submit4.json \
  -X POST "$BASE/api/assignments/$ASG1/submit" -H "Authorization: Bearer $TOK_S4" \
  -F "protocol=dns" -F "file=@$REPLAY_TMP/synthetic_plugin.c;type=text/plain"
python3 -c "import json,os;d=json.load(open(os.environ['REPLAY_TMP']+'/submit4.json'));print('student04 submit score=',d.get('score'))"
req "re-enable student03" -X POST "$BASE/api/admin/users/U90000000003/status" -H "$AUTH" -H 'Content-Type: application/json' -d '{"enabled":true}'
# 03 的旧 token 已被停号吊销，重新登录供后续场景
reqsave "student03 relogin" $REPLAY_TMP/tok_s3.json -X POST "$BASE/api/auth/login" \
  -H 'Content-Type: application/json' -d '{"username":"U90000000003","password":"U90000000003"}'
TOK_S3=$(python3 -c "import json,os;print(json.load(open(os.environ['REPLAY_TMP']+'/tok_s3.json'))['token'])")
state_set tokens.student_a "$TOK_S3"

echo "=== S4.5 配额：学生04 连续提交到超限（默认 grading_daily_quota=20）→ 429 ===" | tee -a "$LOG_DIR/04-submission.log"
# 学生04 已用 1 次；循环提交记录状态码，直到 429 或 40 次
: > $REPLAY_TMP/quota_codes.txt
for i in $(seq 2 45); do
  CODE=$(curl -s -o $REPLAY_TMP/q_body.json -w '%{http_code}' -X POST "$BASE/api/assignments/$ASG1/submit" \
    -H "Authorization: Bearer $TOK_S4" -F "protocol=dns" -F "file=@$REPLAY_TMP/synthetic_plugin.c;type=text/plain")
  echo "submit#$i -> $CODE" >> $REPLAY_TMP/quota_codes.txt
  if [ "$CODE" = "429" ]; then
    echo "first 429 at submit#$i" >> "$LOG_DIR/04-submission.log"
    { echo "### quota 429 response"; echo '$ curl -s -i ...'; curl -s -i -X POST "$BASE/api/assignments/$ASG1/submit" -H "Authorization: Bearer $TOK_S4" -F "protocol=dns" -F "file=@$REPLAY_TMP/synthetic_plugin.c;type=text/plain"; echo; } | mask >> "$LOG_DIR/04-submission.log"
    break
  fi
done
cat $REPLAY_TMP/quota_codes.txt >> "$LOG_DIR/04-submission.log"
echo "harness tool_calls total: $(python3 -c "import json,os;print(json.load(open(os.environ['REPLAY_TMP']+'/harness_state.json'))['tool_calls'])")" >> "$LOG_DIR/04-submission.log"
{
  echo "-- usage_counters after quota loop（grading 计数存 chat_count 列，kind='grading'）"
  psqlq "SELECT 'subject='||subject||' kind='||kind||' day='||day||' chat_count='||chat_count FROM usage_counters WHERE kind='grading' ORDER BY subject,day"
} >> "$LOG_DIR/04-submission.log"

echo "scenario 04 done" | tee -a "$LOG_DIR/04-submission.log"
