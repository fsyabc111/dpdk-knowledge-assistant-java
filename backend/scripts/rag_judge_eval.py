"""N08 离线 judge 验收工具：对标注题集逐条跑 ``validate_answer`` 并出报告。

**需要真实 LLM 凭据（backend/.env 的 LLM_API_BASE/KEY），属离线验收工具，
不进默认 CI**（默认集不联网、不调模型）。N08 决策（docs/decisions.md）把
模型 judge 从在线路径撤下——在线只保留确定性检查，本脚本是模型核验的
离线归宿：判断「若重新开在线核验，judge 能挡住什么、会误伤什么」。

题集默认 ``tests/rag_eval/judge_cases.yml``，字段见该文件头注释。
``mode`` 决定每条用例走哪条路径：

- ``deterministic``：``judge_enabled=False`` + ``model=None``——与在线现状
  （RAG_ONLINE_JUDGE_ENABLED=false）完全同构，不打模型、结果确定；
- ``judge``：``judge_enabled=True`` + 真实 ``DirectOpenAIChatModel``——
  模型给出的 verdict 与 ``expect_*`` 标签对比，不一致计入 mismatch 清单。

本工具是**报告-only**：mismatch 只打印不失败，退出码恒为 0（题集加载失败、
路径非法等用法错误除外）。judge 例的 expected 是人工标注的验收口径，
不是 CI 门禁——模型输出存在天然抖动。

用法：
    cd backend
    .venv/bin/python scripts/rag_judge_eval.py                          # 默认题库全量
    .venv/bin/python scripts/rag_judge_eval.py --case judge_conflict    # 单例
    .venv/bin/python scripts/rag_judge_eval.py --timeout 60             # 单次核验超时
    .venv/bin/python scripts/rag_judge_eval.py --output report.json     # 机器可读报告落盘
"""
from __future__ import annotations

import argparse
import asyncio
import json
import sys
import time
from collections import Counter
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Dict, List

import yaml

BACKEND_ROOT = Path(__file__).resolve().parents[1]
if str(BACKEND_ROOT) not in sys.path:
    sys.path.insert(0, str(BACKEND_ROOT))

DEFAULT_CASES_FILE = BACKEND_ROOT / "tests" / "rag_eval" / "judge_cases.yml"
DEFAULT_TIMEOUT_SEC = 30.0


def load_cases(path: Path) -> List[Dict[str, Any]]:
    """读题集 yml；空文件/无 cases 键直接退出。"""
    if not path.exists():
        raise SystemExit(f"题集不存在：{path}")
    payload = yaml.safe_load(path.read_text(encoding="utf-8")) or {}
    cases = payload.get("cases") or []
    if not cases:
        raise SystemExit(f"题集为空：{path}")
    return cases


def select_cases(cases: List[Dict[str, Any]], names: List[str]) -> List[Dict[str, Any]]:
    if not names:
        return cases
    by_name = {str(case.get("name")): case for case in cases}
    missing = [name for name in names if name not in by_name]
    if missing:
        raise SystemExit(f"未知用例 name：{', '.join(missing)}（可选：{', '.join(sorted(by_name))}）")
    return [by_name[name] for name in names]


async def run_case(case: Dict[str, Any], model: Any, timeout: float) -> Dict[str, Any]:
    """跑一条用例，返回 per-case 记录（实际 verdict vs expected + 用量）。"""
    # 延迟导入：模块顶层不碰应用代码，--help / 题集错误时不需要应用环境
    from app.core.usage import UsageRecorder
    from app.services.rag_product_service import validate_answer

    name = str(case.get("name") or "")
    mode = str(case.get("mode") or "judge")
    judge_enabled = mode == "judge"
    usage = UsageRecorder()
    started = time.monotonic()
    record: Dict[str, Any] = {
        "name": name,
        "mode": mode,
        "question": str(case.get("question") or ""),
        "expect_status": case.get("expect_status"),
        "expect_reason": case.get("expect_reason"),
        "actual_status": None,
        "actual_reason": None,
        "matched": None,
        "elapsed_sec": 0.0,
        "usage": {},
        "error": "",
    }
    try:
        verdict = await validate_answer(
            str(case.get("question") or ""),
            str(case.get("answer") or ""),
            dict(case.get("evidence") or {}),
            model if judge_enabled else None,
            timeout,
            usage=usage,
            request_id=name,
            judge_enabled=judge_enabled,
        )
        record["actual_status"] = verdict.status
        record["actual_reason"] = verdict.reason
        # reason 标了才比对：expect_reason 缺省表示只断言 status
        record["matched"] = (
            verdict.status == case.get("expect_status")
            and (case.get("expect_reason") is None or verdict.reason == case.get("expect_reason"))
        )
    except Exception as exc:  # 用例级失败不中断整轮：记 error 进报告
        record["error"] = f"{type(exc).__name__}: {exc}"
        record["matched"] = False
    record["elapsed_sec"] = round(time.monotonic() - started, 3)
    totals = usage.totals()
    record["usage"] = {
        "calls": totals["calls"],
        "prompt_tokens": totals["prompt_tokens"],
        "completion_tokens": totals["completion_tokens"],
        "total_tokens": totals["total_tokens"],
        "unknown_entries": totals["unknown_entries"],
    }
    return record


def summarize(records: List[Dict[str, Any]]) -> Dict[str, Any]:
    """聚合：status 分布、expect→actual 混淆、用时与 token 合计。"""
    distribution = Counter(str(r["actual_status"]) for r in records)
    confusion = Counter(
        f"{r['expect_status']}->{r['actual_status']}" for r in records if not r["matched"]
    )
    token_total = sum(r["usage"]["total_tokens"] or 0 for r in records)
    unknown_usage = sum(r["usage"]["unknown_entries"] for r in records)
    return {
        "cases": len(records),
        "matched": sum(1 for r in records if r["matched"]),
        "mismatched": sum(1 for r in records if r["matched"] is False),
        "status_distribution": dict(distribution),
        # 只统计不一致的「期望→实际」对——全对上时混淆矩阵天然为空
        "confusion": dict(confusion),
        "elapsed_sec": round(sum(r["elapsed_sec"] for r in records), 3),
        "total_tokens": token_total,
        "usage_unknown_entries": unknown_usage,
    }


def render_text(records: List[Dict[str, Any]], summary: Dict[str, Any], model_name: str) -> str:
    lines = [
        "=== RAG Judge Eval Report ===",
        f"model={model_name} cases={summary['cases']} "
        f"matched={summary['matched']} mismatched={summary['mismatched']} "
        f"elapsed={summary['elapsed_sec']}s tokens={summary['total_tokens']}",
        "",
    ]
    for record in records:
        icon = "PASS" if record["matched"] else "MISS"
        lines.append(
            f"[{icon}] {record['name']} (mode={record['mode']}) "
            f"expect={record['expect_status']}/{record.get('expect_reason') or '-'} "
            f"actual={record['actual_status']}/{record['actual_reason'] or '-'} "
            f"({record['elapsed_sec']}s)"
        )
        if record["error"]:
            lines.append(f"       error: {record['error']}")
    mismatches = [r["name"] for r in records if r["matched"] is False]
    lines.append("")
    lines.append("=== mismatch 清单 ===")
    if mismatches:
        lines.extend(f"  {name}" for name in mismatches)
    else:
        lines.append("  （无）")
    if summary["confusion"]:
        lines.append("")
        lines.append("=== expect→actual 混淆 ===")
        lines.extend(f"  {pair}: {count}" for pair, count in sorted(summary["confusion"].items()))
    return "\n".join(lines)


async def main() -> int:
    parser = argparse.ArgumentParser(description="N08 离线 judge 验收（报告-only，退出码恒 0）")
    parser.add_argument("--cases", default=str(DEFAULT_CASES_FILE), help="题集 yml 路径")
    parser.add_argument("--case", action="append", default=[], help="只跑指定 name 的用例，可多次传")
    parser.add_argument("--timeout", type=float, default=DEFAULT_TIMEOUT_SEC,
                        help=f"单次 validate_answer 的模型核验超时秒数（默认 {DEFAULT_TIMEOUT_SEC}）")
    parser.add_argument("--output", default="", help="机器可读 JSON 报告输出路径")
    args = parser.parse_args()

    cases_path = Path(args.cases)
    cases = select_cases(load_cases(cases_path), args.case)

    from app.config import get_settings
    from app.rag.providers import DirectOpenAIChatModel

    settings = get_settings()
    # 真实模型只在有 judge 例时才构造：纯 deterministic 题集不该要求 LLM 凭据
    model = DirectOpenAIChatModel(settings) if any(
        str(case.get("mode") or "judge") == "judge" for case in cases) else None

    records = [await run_case(case, model, args.timeout) for case in cases]
    summary = summarize(records)
    print(render_text(records, summary, settings.model_name), flush=True)

    if args.output:
        report = {
            "generated_at": datetime.now(timezone.utc).isoformat(timespec="seconds"),
            "tool": "rag_judge_eval",
            "model_name": settings.model_name,
            "cases_file": str(cases_path),
            "summary": summary,
            "cases": records,
        }
        Path(args.output).write_text(
            json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        print(f"JSON 报告已写入：{args.output}", flush=True)
    return 0


if __name__ == "__main__":
    raise SystemExit(asyncio.run(main()))
