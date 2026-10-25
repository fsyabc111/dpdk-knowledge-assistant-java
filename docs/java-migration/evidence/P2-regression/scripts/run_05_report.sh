#!/bin/bash
# 场景5：成绩汇总/CSV 导出（BOM/CRLF/表头/未分配学生行）
set -u
SCENARIO=05-report
source "$(dirname "$0")/common.sh"
: > "$LOG_DIR/05-report.log"
TOK_ADMIN=$(cat /tmp/p2reg/tok_admin.txt)
AUTH="Authorization: Bearer $TOK_ADMIN"
ASG1=$(cat /tmp/p2reg/asg1_id.txt)

echo "=== S5.1 admin 成绩汇总（GET /api/assignments/{id}/grades） ===" | tee -a "$LOG_DIR/05-report.log"
reqsave "admin assignment grades" /tmp/p2reg/grades.json -H "$AUTH" "$BASE/api/assignments/$ASG1/grades"
python3 -c "
import json;d=json.load(open('/tmp/p2reg/grades.json'))
print('assignment title:',d['assignment']['title'])
for r in d['rows']:
    print('row user=%s scores=%s total_earned=%s submission_count=%s' % (r['user_id'], r['scores'], r['total_earned'], r['submission_count']))"

echo "=== S5.2 admin 导出 CSV（检查 BOM/CRLF/表头/未分配学生行） ===" | tee -a "$LOG_DIR/05-report.log"
{
  echo "\$ curl -s -i -H 'Authorization: Bearer <TOKEN_MASKED>' $BASE/api/assignments/$ASG1/export"
  curl -s -D /tmp/p2reg/csv_headers.txt -o /tmp/p2reg/export.csv -H "$AUTH" "$BASE/api/assignments/$ASG1/export"
  cat /tmp/p2reg/csv_headers.txt
  echo
} | mask >> "$LOG_DIR/05-report.log"
python3 - <<'EOF'
raw = open('/tmp/p2reg/export.csv','rb').read()
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
TOK_S3=$(cat /tmp/p2reg/tok_s3.txt)
req "student grades (404 expected)" -H "Authorization: Bearer $TOK_S3" "$BASE/api/assignments/$ASG1/grades"
req "student export (404 expected)" -H "Authorization: Bearer $TOK_S3" "$BASE/api/assignments/$ASG1/export"

echo "scenario 05 done" | tee -a "$LOG_DIR/05-report.log"
