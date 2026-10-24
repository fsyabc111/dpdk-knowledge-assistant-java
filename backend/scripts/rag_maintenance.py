"""RAG 运维：默认只读统计；显式参数执行留存清理或索引回滚。"""
import argparse
import json
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from app.config import get_settings
from app.db import get_conn
from app.services.rag_index_service import active_release, rollback_release
from app.services.rag_issue_service import RagIssueService


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    group = parser.add_mutually_exclusive_group()
    group.add_argument("--apply-retention", action="store_true")
    group.add_argument("--rollback-release")
    args = parser.parse_args()
    if args.apply_retention:
        result = RagIssueService().maintain(get_settings().rag_retention_days)
    elif args.rollback_release:
        rollback_release(args.rollback_release)
        result = {"active_release": active_release()}
    else:
        with get_conn() as conn:
            result = {"active_release": active_release(), "requests": [dict(row) for row in conn.execute(
                "SELECT status,COUNT(*) AS count FROM rag_requests GROUP BY status"
            ).fetchall()], "releases": [dict(row) for row in conn.execute(
                "SELECT id,created_at,node_count FROM rag_releases ORDER BY created_at DESC LIMIT 20"
            ).fetchall()]}
    print(json.dumps(result, ensure_ascii=False))


if __name__ == "__main__":
    main()
