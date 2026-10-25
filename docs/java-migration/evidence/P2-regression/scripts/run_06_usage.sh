#!/bin/bash
# 场景6：会话/用量（无公开建会话端点→SQL 造数+读接口；usage/me；subjects/cost 空账形态）
set -u
SCENARIO=06-usage
source "$(dirname "$0")/common.sh"
: > "$LOG_DIR/06-usage.log"
TOK_ADMIN=$(state_get tokens.admin)
AUTH="Authorization: Bearer $TOK_ADMIN"
TOK_S3=$(state_get tokens.student_a)

echo "=== S6.1 会话：无公开创建端点 → SQL 造合成会话+消息 → 读接口 ===" | tee -a "$LOG_DIR/06-usage.log"
{
  echo "-- SQL：为学生03造 2 条合成会话（id 用 uuid 形态占位，无真实聊天内容）"
  psqlq "INSERT INTO assistant_sessions (id,user_id,title,memory_summary,created_at,updated_at) VALUES ('sess-p2reg-01','U90000000003','P2回归会话甲','',now(),now()),('sess-p2reg-02','U90000000003','P2回归会话乙','',now(),now())"
  psqlq "INSERT INTO assistant_messages (id,session_id,role,content,metadata_json,created_at) VALUES ('msg-p2reg-01','sess-p2reg-01','user','合成提问（回归占位文本）','{}'::jsonb,now()),('msg-p2reg-02','sess-p2reg-01','assistant','合成回答（回归占位文本）','{}'::jsonb,now())"
  echo "-- api_tokens 无 sid 冲突；sessions 计数"
  psqlq "SELECT 'sessions for 03: '||count(*) FROM assistant_sessions WHERE user_id='U90000000003'"
} >> "$LOG_DIR/06-usage.log"
reqsave "student03 list sessions" $REPLAY_TMP/sess_list.json -H "Authorization: Bearer $TOK_S3" "$BASE/api/chat/sessions"
python3 -c "
import json,os;d=json.load(open(os.environ['REPLAY_TMP']+'/sess_list.json'))
print('list keys:',sorted(d.keys()))
print('sessions:',[(s['id'],s.get('title'),s.get('message_count')) for s in d.get('sessions',[])])"
reqsave "student03 session detail" $REPLAY_TMP/sess_one.json -H "Authorization: Bearer $TOK_S3" "$BASE/api/chat/sessions/sess-p2reg-01"
python3 -c "
import json,os;d=json.load(open(os.environ['REPLAY_TMP']+'/sess_one.json'))
print('detail keys:',sorted(d.keys()))
print('messages:',[(m['role'],m['content'][:12]+'…') for m in d.get('messages',[])])"
req "student03 other-user session (404 expected: admin's none, but cross-check unknown id)" -H "Authorization: Bearer $TOK_S3" "$BASE/api/chat/sessions/sess-does-not-exist"

echo "=== S6.2 usage/me（学生；chat 计数 0，grading 不计入 fetchCounters） ===" | tee -a "$LOG_DIR/06-usage.log"
reqsave "student03 usage/me" $REPLAY_TMP/usage_s3.json -H "Authorization: Bearer $TOK_S3" "$BASE/api/usage/me"
python3 -c "
import json,os;d=json.load(open(os.environ['REPLAY_TMP']+'/usage_s3.json'))
print('subject=',d['subject'],'roles=',d['roles'],'daily_quota=',d['daily_quota'])
print('today=',d['today'],'recent_days[-1]=',d['recent_days'][-1])"

echo "=== S6.3 usage/subjects（admin；空 upstream 账本形态） ===" | tee -a "$LOG_DIR/06-usage.log"
reqsave "admin usage subjects" $REPLAY_TMP/subjects.json -H "$AUTH" "$BASE/api/usage/subjects"
cat $REPLAY_TMP/subjects.json | head -c 400; echo

echo "=== S6.4 usage/cost（admin；空账：成本未知→null 非 0 的字段形态） ===" | tee -a "$LOG_DIR/06-usage.log"
reqsave "admin usage cost" $REPLAY_TMP/cost.json -H "$AUTH" "$BASE/api/usage/cost?days=7"
python3 -m json.tool --sort-keys $REPLAY_TMP/cost.json | head -20
python3 - <<'EOF' >> "$LOG_DIR/06-usage.log"
import json,os
d=json.load(open(os.environ['REPLAY_TMP']+'/cost.json'))
print("cost body:", json.dumps(d, ensure_ascii=False, sort_keys=True))
print("total_cost is None:", d.get("total_cost", "MISSING") is None)
EOF

echo "=== S6.5 会话删除（学生删自己的） ===" | tee -a "$LOG_DIR/06-usage.log"
req "student03 delete session 02" -X DELETE -H "Authorization: Bearer $TOK_S3" "$BASE/api/chat/sessions/sess-p2reg-02"
req "deleted session detail (404 expected)" -H "Authorization: Bearer $TOK_S3" "$BASE/api/chat/sessions/sess-p2reg-02"

echo "scenario 06 done" | tee -a "$LOG_DIR/06-usage.log"
