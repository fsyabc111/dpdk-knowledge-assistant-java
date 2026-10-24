"""只读盘点 0014 前已标记完成、但无法按 job 身份核实成绩的账本行。"""

import argparse
import json
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from app.db import get_conn, get_database_provider


def audit(limit: int) -> dict:
    ph = get_database_provider().placeholder
    where = "j.submission_done=1 AND r.status!='cancelled' AND g.id IS NULL"
    join = ("FROM rag_task_jobs j JOIN rag_requests r ON r.id=j.task_id "
            "LEFT JOIN grading_submissions g ON g.task_job_id=j.job_id")
    with get_conn() as conn:
        total = conn.execute(f"SELECT COUNT(*) AS n {join} WHERE {where}").fetchone()["n"]
        rows = conn.execute(
            f"SELECT j.task_id,j.job_id {join} WHERE {where} "
            f"ORDER BY j.created_at,j.job_id LIMIT {ph}", (limit,),
        ).fetchall()
    return {"legacy_unlinked_total": int(total),
            "items": [{"task_id": row["task_id"], "job_id": row["job_id"]} for row in rows],
            "note": "无法从旧数据判定成绩缺失还是仅缺关联；本工具不补写、不删除"}


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--limit", type=int, default=200)
    args = parser.parse_args()
    if args.limit < 1 or args.limit > 10000:
        parser.error("--limit 必须在 1–10000 之间")
    print(json.dumps(audit(args.limit), ensure_ascii=False))
