#!/bin/bash
# 场景3：作业闭环（创建2协议项→学生列表可见→截止/撤回可见性变化）
set -u
SCENARIO=03-assignments
source "$(dirname "$0")/common.sh"
: > "$LOG_DIR/03-assignments.log"
TOK_ADMIN=$(cat /tmp/p2reg/tok_admin.txt)
AUTH="Authorization: Bearer $TOK_ADMIN"

# 学生03登录（密码已复位为学号）
reqsave "student03 login" /tmp/p2reg/tok_s3.json -X POST "$BASE/api/auth/login" \
  -H 'Content-Type: application/json' -d '{"username":"U90000000003","password":"U90000000003"}'
TOK_S3=$(python3 -c "import json;print(json.load(open('/tmp/p2reg/tok_s3.json'))['token'])")
echo "$TOK_S3" > /tmp/p2reg/tok_s3.txt

echo "=== S3.1 admin 创建作业（2 协议项 dns/arp） ===" | tee -a "$LOG_DIR/03-assignments.log"
reqsave "admin create assignment" /tmp/p2reg/asg1.json -X POST "$BASE/api/assignments" -H "$AUTH" \
  -H 'Content-Type: application/json' \
  -d '{"title":"回归DNS作业","description":"P2回归","deadline":"2099-01-01T00:00:00Z","items":[{"protocol":"dns","points":20},{"protocol":"arp","points":30}]}'
python3 -c "
import json;d=json.load(open('/tmp/p2reg/asg1.json'))
open('/tmp/p2reg/asg1_id.txt','w').write(d['id'])
print('created id saved; status=',d.get('status'),'items=',d.get('items'),'deadline=',d.get('deadline'))" | tee -a "$LOG_DIR/03-assignments.log"
ASG1=$(cat /tmp/p2reg/asg1_id.txt)

echo "=== S3.2 学生列表可见（学生视角带 best_score 等字段） ===" | tee -a "$LOG_DIR/03-assignments.log"
reqsave "student list assignments" /tmp/p2reg/asg_s.json -H "Authorization: Bearer $TOK_S3" "$BASE/api/assignments"
python3 -c "
import json;d=json.load(open('/tmp/p2reg/asg_s.json'))
rows=d['assignments'];print('student sees',len(rows),'assignment(s); id match:',any(r['id']=='$ASG1' for r in rows))
print('student view keys:', sorted(rows[0].keys()) if rows else [])"
reqsave "admin list assignments (manager view)" /tmp/p2reg/asg_a.json -H "$AUTH" "$BASE/api/assignments"
python3 -c "
import json;d=json.load(open('/tmp/p2reg/asg_a.json'))
rows=d['assignments'];r=[x for x in rows if x['id']=='$ASG1'][0]
print('manager view submission_count=',r.get('submission_count'),'student_count=',r.get('student_count'))"

echo "=== S3.3 截止（PATCH deadline 到过去）→ 学生详情仍可见；提交被拒留待 S4 ===" | tee -a "$LOG_DIR/03-assignments.log"
reqsave "admin patch deadline to past" /tmp/p2reg/asg1p.json -X PATCH "$BASE/api/assignments/$ASG1" -H "$AUTH" \
  -H 'Content-Type: application/json' -d '{"deadline":"2020-01-01T00:00:00Z"}'
python3 -c "import json;d=json.load(open('/tmp/p2reg/asg1p.json'));print('deadline now:',d.get('deadline'))"
reqsave "student detail after deadline" /tmp/p2reg/asg1_sd.json -H "Authorization: Bearer $TOK_S3" "$BASE/api/assignments/$ASG1"
python3 -c "import json;d=json.load(open('/tmp/p2reg/asg1_sd.json'));print('student detail deadline:',d.get('deadline'),'status:',d.get('status'))"
# 恢复未来截止
req "admin patch deadline back to future" -X PATCH "$BASE/api/assignments/$ASG1" -H "$AUTH" \
  -H 'Content-Type: application/json' -d '{"deadline":"2099-01-01T00:00:00Z"}' -o /dev/null

echo "=== S3.4 撤回 → 学生列表消失；恢复（reactivate）→ 重新可见 ===" | tee -a "$LOG_DIR/03-assignments.log"
req "admin withdraw" -X POST "$BASE/api/assignments/$ASG1/withdraw" -H "$AUTH"
reqsave "student list after withdraw" /tmp/p2reg/asg_s2.json -H "Authorization: Bearer $TOK_S3" "$BASE/api/assignments"
python3 -c "
import json;d=json.load(open('/tmp/p2reg/asg_s2.json'))
print('student sees',len(d['assignments']),'after withdraw; gone:',not any(r['id']=='$ASG1' for r in d['assignments']))"
reqsave "student detail after withdraw (404 expected)" /tmp/p2reg/asg1_wd.json -H "Authorization: Bearer $TOK_S3" "$BASE/api/assignments/$ASG1"
req "admin reactivate" -X POST "$BASE/api/assignments/$ASG1/reactivate" -H "$AUTH"
reqsave "student list after reactivate" /tmp/p2reg/asg_s3.json -H "Authorization: Bearer $TOK_S3" "$BASE/api/assignments"
python3 -c "
import json;d=json.load(open('/tmp/p2reg/asg_s3.json'))
print('student sees',len(d['assignments']),'after reactivate; back:',any(r['id']=='$ASG1' for r in d['assignments']))"

echo "scenario 03 done" | tee -a "$LOG_DIR/03-assignments.log"
