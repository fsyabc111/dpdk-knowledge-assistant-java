"""完整负载结束后的只读 PG 总账核对；不初始化或改写数据。"""
from __future__ import annotations

import json
import hashlib
from pathlib import Path
import sys

ROOT = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(ROOT))
from migration.oracle.performance.run import EVIDENCE, write_json
from migration.oracle.safety import configure


def main():
    summary = json.loads((EVIDENCE / "baseline-summary.json").read_text())
    measurements = [r for r in summary["rounds"] if r["phase"] == "measurement"]
    if len(measurements) != 3 or not all(summary.get("correctness_assertions", {}).values()) or not summary.get("correctness_assertions"):
        raise RuntimeError("完整三轮/最终业务断言未就绪，禁止把中途状态当最终总账")
    identity = configure("p0_benchmark_oracle")
    from app.config import get_settings
    import psycopg
    from psycopg.rows import dict_row
    with psycopg.connect(get_settings().database_url, row_factory=dict_row) as conn:
        conn.execute("SET TRANSACTION READ ONLY")
        states = list(conn.execute("SELECT status,count(*) AS n FROM rag_requests GROUP BY status ORDER BY status"))
        usage = list(conn.execute("SELECT kind,sum(chat_count) AS n FROM usage_counters GROUP BY kind ORDER BY kind"))
        providers = list(conn.execute("SELECT kind,stage,usage_source,count(*) AS n,sum(call_count) AS calls, "
                                     "count(*) FILTER(WHERE cost_micros IS NULL) AS cost_unknown, "
                                     "count(*) FILTER(WHERE cost_micros=0) AS cost_explicit_zero, "
                                     "count(*) FILTER(WHERE prompt_tokens IS NULL OR completion_tokens IS NULL) AS token_count_unknown "
                                     "FROM upstream_usage_records GROUP BY kind,stage,usage_source ORDER BY kind,stage,usage_source"))
        jobs = list(conn.execute("SELECT status,count(*) AS n FROM rag_task_jobs GROUP BY status ORDER BY status"))
        remaining_inputs = conn.execute("SELECT count(*) AS n FROM rag_task_inputs").fetchone()["n"]
        formal = conn.execute("SELECT count(*) FILTER(WHERE assignment_id IS NOT NULL) AS formal, "
                              "count(*) FILTER(WHERE assignment_id IS NULL) AS chat, count(DISTINCT code_hash) AS source_hashes "
                              "FROM grading_submissions").fetchone()
        schema = conn.execute("SELECT version_num FROM alembic_version").fetchone()["version_num"]
    expected_tasks = summary["final"]["tables"]["rag_requests"]
    checks = {"schema_0015": schema == "0015_explain_purpose", "all_tasks_answered": states == [{"status": "answered", "n": expected_tasks}],
              "terminal_inputs_zero": remaining_inputs == 0, "no_chat_grade_in_plain_qa_mix": formal["chat"] == 0,
              "formal_count_matches_snapshot": formal["formal"] == summary["final"]["tables"]["grading_submissions"],
              "one_runtime_source_hash": formal["source_hashes"] == 1,
              "chat_charge_matches_tasks": next(r["n"] for r in usage if r["kind"] == "chat") == expected_tasks}
    output = {"status": "通过" if all(checks.values()) else "失败", "checks": checks, "identity": identity,
              "baseline_summary_sha256": hashlib.sha256((EVIDENCE / "baseline-summary.json").read_bytes()).hexdigest(),
              "task_states": states, "business_usage": usage, "upstream_usage": providers, "job_states": jobs,
              "remaining_inputs": remaining_inputs, "grades": formal,
              "cost_scope": "合成模型usage来自固定脚本；没有配置真实单价，cost_unknown单列且不转换为0。正式MCP调用以负载边界计数为准，不能把usage表缺少某类记录解释成真实上游零费用；无真实账单或评测花费证据"}
    write_json(EVIDENCE / "final-pg-audit.json", output)
    print(json.dumps(output, ensure_ascii=False))
    if not all(checks.values()):
        raise RuntimeError("最终PG总账断言失败")


if __name__ == "__main__":
    main()
