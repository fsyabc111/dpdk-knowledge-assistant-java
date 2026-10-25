#!/bin/bash
# 场景5：成绩汇总/CSV 导出（BOM/CRLF/表头/未分配学生行）
set -u
SCENARIO=05-report
source "$(dirname "$0")/common.sh"
: > "$LOG_DIR/05-report.log"
TOK_ADMIN=$(state_get tokens.admin)
AUTH="Authorization: Bearer $TOK_ADMIN"
ASG1=$(state_get assignment_ids.asg1)

echo "=== S5.1 admin 成绩汇总（GET /api/assignments/{id}/grades） ===" | tee -a "$LOG_DIR/05-report.log"
reqsave "admin assignment grades" $REPLAY_TMP/grades.json -H "$AUTH" "$BASE/api/assignments/$ASG1/grades"
python3 -c "
import json,os;d=json.load(open(os.environ['REPLAY_TMP']+'/grades.json'))
print('assignment title:',d['assignment']['title'])
for r in d['rows']:
    print('row user=%s scores=%s total_earned=%s submission_count=%s' % (r['user_id'], r['scores'], r['total_earned'], r['submission_count']))"

echo "=== S5.2 admin 导出 CSV（检查 BOM/CRLF/表头/未分配学生行） ===" | tee -a "$LOG_DIR/05-report.log"
{
  echo "\$ curl -s -i -H 'Authorization: Bearer <TOKEN_MASKED>' $BASE/api/assignments/$ASG1/export"
  curl -s -D $REPLAY_TMP/csv_headers.txt -o $REPLAY_TMP/export.csv -H "$AUTH" "$BASE/api/assignments/$ASG1/export"
  cat $REPLAY_TMP/csv_headers.txt
  echo
} | mask >> "$LOG_DIR/05-report.log"
python3 - <<'EOF'
import os
raw = open(os.environ['REPLAY_TMP']+'/export.csv','rb').read()
print("bytes:", len(raw))
print("BOM utf-8-sig:", raw[:3] == b'\xef\xbb\xbf')
print("CRLF line endings:", b'\r\n' in raw and raw.replace(b'\r\n',b'').find(b'\n') == -1)
text = raw.decode('utf-8-sig')
lines = text.split('\r\n')
print("header:", lines[0])
print("data rows:")
for ln in lines[1:]:
    if ln:
        print("  ", ln)
EOF

echo "=== S5.3 学生角色访问成绩端点（404 不泄露存在性） ===" | tee -a "$LOG_DIR/05-report.log"
TOK_S3=$(state_get tokens.student_a)
req "student grades (404 expected)" -H "Authorization: Bearer $TOK_S3" "$BASE/api/assignments/$ASG1/grades"
req "student export (404 expected)" -H "Authorization: Bearer $TOK_S3" "$BASE/api/assignments/$ASG1/export"

echo "scenario 05 done" | tee -a "$LOG_DIR/05-report.log"
