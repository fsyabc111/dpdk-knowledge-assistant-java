"""RAG 检索质量评估 runner（第一阶段：评估体系先行）。

与 tests/agent/live_eval_runner.py（端到端 agent 行为漂移）互补：本脚本
**进程内直接调用检索服务**，对同目录 queries.yml 题库逐题检索，计算
recall@1/5/15、MRR、must_top 命中率等量化指标，按分类汇总，并与本地基线
（backend/data/rag_eval_baseline.json，不入库）对比打印漂移项。

触发时机：改检索逻辑（召回/融合/重排/MMR）、换 embedding 模型、调权重、
重建索引前后手工跑；流程见 docs/runbook.md「RAG 检索评估」一节。检索
逻辑改动以本题库指标为准入门槛，不允许整体 recall@15 退步。

用法：
    cd backend
    .venv/bin/python tests/rag_eval/rag_eval_runner.py                       # 全量（唯一数据面：llama_index 发布版本）
    .venv/bin/python tests/rag_eval/rag_eval_runner.py --case proto_dns      # 单例
    .venv/bin/python tests/rag_eval/rag_eval_runner.py --update-baseline     # 落新基线（合并写，含环境元信息）
    .venv/bin/python tests/rag_eval/rag_eval_runner.py --fail-on-regression  # 整体 recall@15 退步即非零退出
    .venv/bin/python tests/rag_eval/rag_eval_runner.py --fail-on-regression --regression-threshold 0.05  # 放松回归容差
    .venv/bin/python tests/rag_eval/rag_eval_runner.py --fail-on-regression --ignore-env-mismatch        # 环境漂移仅告警
    .venv/bin/python tests/rag_eval/rag_eval_runner.py --max-avg-latency 3 --max-p95-latency 10          # 性能门禁
    .venv/bin/python tests/rag_eval/rag_eval_runner.py --output report.json  # 机器可读报告

判定口径（退出码）：0=全绿；1=存在 FAIL 用例 / 性能超限 / recall@15 回归；
2=基线与当前环境元信息存在 blocking 差异（模型/引擎/权重/索引版本/题库），
拒绝判定回归（--ignore-env-mismatch 可降级为警告）。2 优先于 1。
环境元信息（collect_env_metadata）随基线与 JSON 报告落盘，白名单字段，
绝不含 api_base/api_key 等敏感配置；code_revision 跨提交对比是正当用法，
只告警不阻断。

需要真实的 embedding/LLM 配置（backend/.env）与已入库的知识库，与
tests/test_retrieval.py 同属 live 场景。pytest 形态（live mark，不进默认集）
是一条整体指标用例，下限由 RAG_EVAL_MIN_RECALL15 控制（默认不设线）：

    .venv/bin/python -m pytest tests/rag_eval/rag_eval_runner.py -q -m live
"""

from __future__ import annotations

import argparse
import asyncio
import hashlib
import json
import os
import subprocess
import sys
import time
from dataclasses import dataclass, field
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Dict, List, Optional, Tuple

import yaml

try:  # 生产镜像不含 pytest：CLI 形态（python 直跑）不需要它，仅 pytest 形态需要
    import pytest
except ModuleNotFoundError:  # pragma: no cover - 仅在无 pytest 的容器内 CLI 运行命中
    pytest = None  # type: ignore[assignment]

SCRIPT_DIR = Path(__file__).resolve().parent
BACKEND_ROOT = SCRIPT_DIR.parents[1]
if str(BACKEND_ROOT) not in sys.path:
    sys.path.insert(0, str(BACKEND_ROOT))
if str(SCRIPT_DIR) not in sys.path:
    sys.path.insert(0, str(SCRIPT_DIR))

from metrics import (  # noqa: E402
    coverage_at_k,
    first_hit_rank,
    group_hit_ranks,
    normalize_groups,
    reciprocal_rank,
    summarize,
)

DEFAULT_CASES_FILE = SCRIPT_DIR / "queries.yml"
DEFAULT_BASELINE_FILE = BACKEND_ROOT / "data" / "rag_eval_baseline.json"
METRIC_KS = (1, 5, 15)
DEFAULT_ASSERT_LIMIT = 10  # 与迁移前 test_retrieval.py 的默认 limit 一致

if pytest is not None:
    pytestmark = pytest.mark.live


# ---------------------------------------------------------------------------
# 环境元信息（可复现性）：白名单字段，绝不落 api_base/api_key 等敏感配置
# ---------------------------------------------------------------------------

# config.Settings 上全部 23 个 retrieval_* 启发式权重字段（显式枚举即白名单，
# 新增敏感字段不会被顺手带进快照）。
RETRIEVAL_PARAM_FIELDS = (
    "retrieval_w_semantic",
    "retrieval_w_lexical",
    "retrieval_w_ident",
    "retrieval_w_lexical_no_semantic",
    "retrieval_w_ident_no_semantic",
    "retrieval_rrf_k",
    "retrieval_rrf_prior_scale",
    "retrieval_rrf_prior_cap",
    "retrieval_mmr_redundancy_penalty",
    "retrieval_mmr_per_file_penalty",
    "retrieval_mmr_per_file_cap",
    "retrieval_filename_boost_exact",
    "retrieval_filename_boost_partial",
    "retrieval_topic_boost_specific",
    "retrieval_topic_boost_generic",
    "retrieval_topic_boost_specific_bare",
    "retrieval_topic_boost_generic_bare",
    "retrieval_topic_boost_specific_infix",
    "retrieval_topic_boost_generic_infix",
    "retrieval_ident_header_score",
    "retrieval_ident_body_score",
    "retrieval_definition_boost_typedef",
    "retrieval_definition_boost_funcsig",
)

# blocking 字段：任一不一致则两轮结果不可比，--fail-on-regression 拒绝判定。
# code_revision 不在其中——跨提交对比是正当用法，只告警。
_ENV_BLOCKING_FIELDS = (
    "rag_provider",
    "retrieval_strategy",
    "model_name",
    "embedding_model_name",
    "llama_index_fts_config",
    "llama_index_hnsw_enabled",
    "active_release_id",
    "release_configuration",
)

_ENV_FIELD_LABELS = {
    "rag_provider": "RAG provider",
    "retrieval_strategy": "检索策略",
    "model_name": "生成模型",
    "embedding_model_name": "embedding 模型",
    "llama_index_fts_config": "FTS 配置",
    "llama_index_hnsw_enabled": "HNSW 开关",
    "active_release_id": "活动索引版本",
    "release_configuration": "索引配置指纹",
    "code_revision": "代码版本",
}

UNKNOWN = "unknown"


def _detect_code_revision() -> str:
    """git HEAD（backend 目录）；失败回退 RAG_EVAL_CODE_REV，再回退 unknown。"""
    try:
        proc = subprocess.run(
            ["git", "rev-parse", "HEAD"],
            cwd=BACKEND_ROOT,
            capture_output=True,
            text=True,
            timeout=5,
        )
        if proc.returncode == 0 and proc.stdout.strip():
            return proc.stdout.strip()
    except Exception:
        pass
    return os.environ.get("RAG_EVAL_CODE_REV") or UNKNOWN


def _detect_active_release() -> Tuple[str, str]:
    """活动索引版本与配置指纹；老库无 rag_index_state 表等任何异常都降级 unknown。"""
    try:
        from app.services.rag_index_service import active_release, release_configuration

        release_id = active_release()
        if not release_id:
            return UNKNOWN, UNKNOWN
        return release_id, release_configuration(release_id) or UNKNOWN
    except Exception:
        return UNKNOWN, UNKNOWN


def collect_env_metadata(settings: Any, cases_path: Path, cases: list, limit: int) -> dict:
    """收集本轮评估的可复现元信息。白名单取字段，绝不写入 api_base/api_key。"""
    release_id, configuration = _detect_active_release()
    return {
        "code_revision": _detect_code_revision(),
        "rag_provider": settings.rag_provider,
        "retrieval_strategy": settings.retrieval_strategy,
        "model_name": settings.model_name,
        "embedding_model_name": settings.embedding_model_name,
        "llama_index_fts_config": settings.llama_index_fts_config,
        "llama_index_hnsw_enabled": settings.llama_index_hnsw_enabled,
        "active_release_id": release_id,
        "release_configuration": configuration,
        "retrieval_params": {name: getattr(settings, name) for name in RETRIEVAL_PARAM_FIELDS},
        "cases": {
            "file": cases_path.name,
            "count": len(cases),
            # sha256 仅作评估题库内容溯源，不属于「RAG 业务链不使用 hash」决策范围
            "sha256": hashlib.sha256(cases_path.read_bytes()).hexdigest(),
        },
        "limit": limit,
    }


def _compare_scalar_env(baseline_meta: dict, current_meta: dict, field_name: str, blocking: List[str],
                        warnings: List[str]) -> None:
    """单字段比较；任一侧 unknown 跳过并进 warnings。code_revision 只告警。"""
    old = baseline_meta.get(field_name, UNKNOWN)
    new = current_meta.get(field_name, UNKNOWN)
    label = _ENV_FIELD_LABELS.get(field_name, field_name)
    if old == UNKNOWN or new == UNKNOWN:
        warnings.append(f"{label}（{field_name}）一侧为 unknown（基线={old} 当前={new}），跳过比较")
        return
    if old == new:
        return
    message = f"{label}（{field_name}）不一致：基线={old!r} 当前={new!r}"
    if field_name == "code_revision":
        warnings.append(message + "（跨提交对比属正当用法，仅警告）")
    else:
        blocking.append(message)


def _compare_env_dict(old: Any, new: Any, label: str, blocking: List[str], warnings: List[str]) -> None:
    """dict 字段比较，消息只列差异键；任一侧不是 dict 按 unknown 跳过。"""
    if not isinstance(old, dict) or not isinstance(new, dict):
        warnings.append(f"{label}一侧为 unknown，跳过比较")
        return
    if old == new:
        return
    diff_keys = sorted(key for key in set(old) | set(new) if old.get(key) != new.get(key))
    detail = ", ".join(f"{key}: 基线={old.get(key)!r} 当前={new.get(key)!r}" for key in diff_keys)
    blocking.append(f"{label}不一致（{len(diff_keys)} 键）：{detail}")


def compare_env(baseline_meta: dict, current_meta: dict) -> Tuple[List[str], List[str]]:
    """对比基线与本轮环境元信息，返回（blocking 差异, warning 消息）。

    旧格式基线无 metadata（空 dict）时无法校验，只回 warning；任一侧字段为
    "unknown" 跳过该字段比较。code_revision 差异永远只进 warnings。
    """
    if not baseline_meta:
        return [], ["基线无环境元信息（旧格式基线），无法校验环境一致性"]
    blocking: List[str] = []
    warnings: List[str] = []
    for field_name in _ENV_BLOCKING_FIELDS:
        _compare_scalar_env(baseline_meta, current_meta, field_name, blocking, warnings)
    _compare_scalar_env(baseline_meta, current_meta, "code_revision", blocking, warnings)
    _compare_env_dict(
        baseline_meta.get("retrieval_params", UNKNOWN),
        current_meta.get("retrieval_params", UNKNOWN),
        "检索权重参数（retrieval_params）",
        blocking,
        warnings,
    )
    old_cases = baseline_meta.get("cases")
    new_cases = current_meta.get("cases")
    if not isinstance(old_cases, dict) or not isinstance(new_cases, dict):
        warnings.append("题库信息（cases）一侧为 unknown，跳过比较")
    else:
        for key in ("sha256", "count"):
            old_value = old_cases.get(key, UNKNOWN)
            new_value = new_cases.get(key, UNKNOWN)
            if old_value == UNKNOWN or new_value == UNKNOWN:
                warnings.append(f"题库 {key}（cases.{key}）一侧为 unknown，跳过比较")
            elif old_value != new_value:
                blocking.append(f"题库 {key}（cases.{key}）不一致：基线={old_value!r} 当前={new_value!r}")
    return blocking, warnings


# ---------------------------------------------------------------------------
# 用例加载与评估
# ---------------------------------------------------------------------------


def load_cases(path: Path) -> List[Dict[str, Any]]:
    with open(path, encoding="utf-8") as handle:
        payload = yaml.safe_load(handle) or {}
    cases = payload.get("cases") or []
    if not cases:
        raise SystemExit(f"题库为空：{path}")
    return cases


@dataclass
class CaseResult:
    case_id: str
    category: str
    question: str
    observe: bool = False
    ranked: List[Dict[str, Any]] = field(default_factory=list)  # rank/source_path/score
    hit_ranks: List[Optional[int]] = field(default_factory=list)
    coverage: Dict[str, float] = field(default_factory=dict)
    mrr: float = 0.0
    must_top: Optional[List[str]] = None
    must_top_ok: Optional[bool] = None
    expect_nonempty: bool = False
    assert_limit: int = DEFAULT_ASSERT_LIMIT
    error: str = ""
    elapsed_sec: float = 0.0

    @property
    def passed(self) -> bool:
        """硬断言语义（与 test_retrieval.py 一致）；observe 用例只观察不卡线。"""
        if self.error:
            return False
        if self.observe:
            return True
        if self.expect_nonempty:
            return bool(self.ranked)
        if self.hit_ranks:
            if coverage_at_k(self.hit_ranks, self.assert_limit) < 1.0:
                return False
            if self.must_top and self.must_top_ok is False:
                return False
        return True


def evaluate_case(case: Dict[str, Any], chunks: List[Any], elapsed_sec: float) -> CaseResult:
    groups = normalize_groups(case.get("expected_sources"))
    ranked_paths = [chunk.source_path for chunk in chunks]
    hit_ranks = group_hit_ranks(ranked_paths, groups)
    result = CaseResult(
        case_id=case["id"],
        category=str(case.get("category") or "未分类"),
        question=str(case.get("question") or ""),
        observe=bool(case.get("observe")),
        ranked=[
            {"rank": index, "source_path": chunk.source_path, "score": round(float(chunk.score), 4)}
            for index, chunk in enumerate(chunks, start=1)
        ],
        hit_ranks=hit_ranks,
        coverage={f"recall@{k}": coverage_at_k(hit_ranks, k) for k in METRIC_KS},
        mrr=reciprocal_rank(hit_ranks),
        expect_nonempty=bool(case.get("expect_nonempty")),
        assert_limit=int(case.get("limit") or DEFAULT_ASSERT_LIMIT),
        elapsed_sec=elapsed_sec,
    )
    must_top = case.get("must_top")
    if must_top:
        alternatives = [must_top] if isinstance(must_top, str) else [str(item) for item in must_top]
        result.must_top = alternatives
        top_path = ranked_paths[0].lower() if ranked_paths else ""
        result.must_top_ok = any(fragment.lower() in top_path for fragment in alternatives)
    return result


def build_retriever():
    """唯一检索数据面：LlamaIndex 发布版本检索（S4 收敛后无 legacy 层可选）。"""
    from app.services.llama_index_service import LlamaIndexRetrievalAdapter

    return LlamaIndexRetrievalAdapter(), "llama_index_hybrid"


async def run_cases(
    cases: List[Dict[str, Any]], retriever: Any, limit: int
) -> List[CaseResult]:
    results: List[CaseResult] = []
    for index, case in enumerate(cases, start=1):
        print(f"[{index}/{len(cases)}] running: {case['id']} ({case.get('category', '')})", flush=True)
        start = time.monotonic()
        try:
            chunks = await retriever.search(str(case.get("question") or ""), limit=limit)
            result = evaluate_case(case, chunks, time.monotonic() - start)
        except Exception as exc:
            result = CaseResult(
                case_id=case["id"],
                category=str(case.get("category") or "未分类"),
                question=str(case.get("question") or ""),
                observe=bool(case.get("observe")),
                expect_nonempty=bool(case.get("expect_nonempty")),
                error=f"{type(exc).__name__}: {exc}",
                elapsed_sec=time.monotonic() - start,
            )
        icon = "PASS" if result.passed else ("OBSV" if result.observe else "FAIL")
        rank = first_hit_rank(result.hit_ranks)
        print(
            f"[{index}/{len(cases)}] {icon} {result.case_id} ({result.elapsed_sec:.1f}s) "
            f"first_hit={rank or '-'} recall@15={result.coverage.get('recall@15', 0):.2f}",
            flush=True,
        )
        if result.error:
            print(f"           error: {result.error}", flush=True)
        results.append(result)
    return results


# ---------------------------------------------------------------------------
# 基线与漂移对比
# ---------------------------------------------------------------------------


def load_baseline(path: Path) -> Dict[str, Any]:
    if not path.exists():
        return {}
    try:
        return json.loads(path.read_text(encoding="utf-8"))
    except json.JSONDecodeError:
        return {}


def write_baseline(
    path: Path,
    layer_name: str,
    results: List[CaseResult],
    summary: Dict[str, Any],
    previous: Optional[Dict[str, Any]] = None,
    metadata: Optional[Dict[str, Any]] = None,
) -> None:
    """固化基线。previous 存在时合并写：--case 部分跑只覆盖本轮题目。

    metadata 是本轮环境元信息（整体覆盖而非合并）：部分跑只更新 cases，
    但环境快照代表「最后一次跑」的状态，供 --fail-on-regression 校验可比性。
    """
    cases: Dict[str, Any] = dict((previous or {}).get("cases") or {})
    for result in results:
        cases[result.case_id] = {
            "passed": result.passed,
            "first_hit_rank": first_hit_rank(result.hit_ranks),
            "recall@15": result.coverage.get("recall@15", 0.0),
            "top1": result.ranked[0]["source_path"] if result.ranked else None,
        }
    payload = {
        "version": 1,
        "updated_at": datetime.now(timezone.utc).isoformat(timespec="seconds"),
        "layer": layer_name,
        "metadata": metadata or {},
        "overall": summary.get("overall") or {},
        "cases": cases,
    }
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(payload, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def diff_baseline(baseline: Dict[str, Any], results: List[CaseResult]) -> List[str]:
    baseline_cases = baseline.get("cases") or {}
    lines: List[str] = []
    for result in results:
        old = baseline_cases.get(result.case_id)
        if old is None:
            lines.append(f"[新增] {result.case_id}：基线中不存在")
            continue
        old_passed = bool(old.get("passed"))
        if old_passed and not result.passed:
            lines.append(f"[退步] {result.case_id}：上次 PASS 本次 FAIL")
        elif not old_passed and result.passed:
            lines.append(f"[改善] {result.case_id}：上次 FAIL 本次 PASS")
        old_rank = old.get("first_hit_rank")
        new_rank = first_hit_rank(result.hit_ranks)
        if isinstance(old_rank, int) and isinstance(new_rank, int) and new_rank > old_rank:
            lines.append(f"[排名下滑] {result.case_id}：{old_rank} -> {new_rank}")
        elif isinstance(old_rank, int) and new_rank is None and old_passed and result.passed:
            lines.append(f"[排名下滑] {result.case_id}：{old_rank} -> 未命中")
    return lines


# ---------------------------------------------------------------------------
# 门禁判定（纯函数，便于离线测试）
# ---------------------------------------------------------------------------


def evaluate_gates(
    *,
    results: List[CaseResult],
    summary: Dict[str, Any],
    baseline: Dict[str, Any],
    current_metadata: Dict[str, Any],
    fail_on_regression: bool,
    regression_threshold: float,
    max_avg_latency: Optional[float],
    max_p95_latency: Optional[float],
    ignore_env_mismatch: bool,
) -> Tuple[int, List[str]]:
    """汇总全部门禁判定，返回（exit_code, 消息行）。

    退出码语义：0=全绿；1=用例 FAIL / 性能超限 / recall@15 回归；
    2=环境元信息 blocking 不一致，拒绝判定回归（2 优先于 1）。
    """
    exit_code = 0
    lines: List[str] = []
    overall = summary.get("overall") or {}

    failed = [result.case_id for result in results if not result.passed]
    if failed:
        exit_code = 1
        lines.append(f"用例未通过（{len(failed)}）：{', '.join(failed)}")

    for label, key, gate in (
        ("avg", "avg_latency_sec", max_avg_latency),
        ("p95", "p95_latency_sec", max_p95_latency),
    ):
        if gate is None:
            continue
        value = overall.get(key)
        if value is None:
            lines.append(f"警告：summary 无 {key} 数据，{label} 延迟门禁未生效")
        elif float(value) > gate:
            exit_code = 1
            lines.append(f"性能超限：{key}={value}s 超过上限 {gate}s")

    if fail_on_regression and baseline:
        blocking, env_warnings = compare_env(baseline.get("metadata") or {}, current_metadata)
        lines.extend(f"环境警告：{line}" for line in env_warnings)
        if blocking:
            lines.append("环境不一致，拒绝判定回归：")
            lines.extend(f"  {line}" for line in blocking)
            if not ignore_env_mismatch:
                return 2, lines
            lines.append("（--ignore-env-mismatch：忽略上述环境差异，继续比对指标）")
        old_recall = (baseline.get("overall") or {}).get("recall@15")
        if old_recall is not None:
            new_recall = overall.get("recall@15")
            delta = float(new_recall or 0.0) - float(old_recall)
            if delta < -abs(regression_threshold):
                exit_code = 1
                lines.append(
                    f"回归：整体 recall@15 {float(old_recall):.3f} -> "
                    f"{float(new_recall or 0.0):.3f}（下降 {-delta:.3f} > 阈值 {regression_threshold}）"
                )
    return exit_code, lines


# ---------------------------------------------------------------------------
# 报告
# ---------------------------------------------------------------------------


def _summary_records(results: List[CaseResult]) -> List[Dict[str, Any]]:
    return [
        {
            "category": result.category,
            "hit_ranks": result.hit_ranks,
            "coverage": result.coverage,
            "mrr": result.mrr,
            "must_top": result.must_top,
            "must_top_ok": result.must_top_ok,
            "elapsed_sec": result.elapsed_sec,
        }
        for result in results
    ]


def _metadata_line(metadata: Dict[str, Any]) -> str:
    """紧凑的单行环境摘要：模型 / 索引版本 / 题库 / 代码版本。"""
    cases_meta = metadata.get("cases") or {}
    revision = str(metadata.get("code_revision") or UNKNOWN)
    return (
        "环境："
        f"model={metadata.get('model_name', UNKNOWN)} "
        f"embedding={metadata.get('embedding_model_name', UNKNOWN)} "
        f"release={metadata.get('active_release_id', UNKNOWN)} "
        f"题库={cases_meta.get('file', UNKNOWN)}({cases_meta.get('count', '?')}例) "
        f"rev={revision[:12]}"
    )


def render_text_report(
    results: List[CaseResult],
    summary: Dict[str, Any],
    drift: List[str],
    metadata: Optional[Dict[str, Any]] = None,
    env_warnings: Optional[List[str]] = None,
) -> str:
    passed = sum(1 for result in results if result.passed)
    lines = ["=== RAG Retrieval Eval Report ===", f"Summary: {passed}/{len(results)} passed"]
    if metadata:
        lines.append(_metadata_line(metadata))
    lines.append("")
    overall = summary["overall"]
    lines.append(
        "整体指标："
        + " ".join(f"recall@{k}={overall[f'recall@{k}']:.3f}" for k in METRIC_KS)
        + f" MRR={overall['mrr']:.3f}"
        + (
            f" must_top={overall['must_top_hit_rate']:.3f}（{overall['must_top_cases']} 例）"
            if "must_top_hit_rate" in overall
            else ""
        )
        + (
            f" avg={overall['avg_latency_sec']}s p95={overall['p95_latency_sec']}s"
            if "avg_latency_sec" in overall
            else ""
        )
    )
    for category, bucket in summary["by_category"].items():
        lines.append(
            f"  {category}: cases={bucket['cases']} judged={bucket['judged_cases']} "
            f"recall@15={bucket['recall@15']:.3f} MRR={bucket['mrr']:.3f}"
        )
    lines.append("")
    for result in results:
        icon = "PASS" if result.passed else ("OBSV" if result.observe else "FAIL")
        lines.append(f"[{icon}] {result.case_id} ({result.category})")
        lines.append(f"  Q: {result.question}")
        if result.error:
            lines.append(f"  error: {result.error}")
        else:
            lines.append(
                f"  first_hit={first_hit_rank(result.hit_ranks) or '-'} "
                f"coverage@15={result.coverage.get('recall@15', 0):.2f} mrr={result.mrr:.2f}"
                + (f" must_top={'OK' if result.must_top_ok else 'MISS'}" if result.must_top else "")
            )
            top = result.ranked[:5]
            lines.append("  top5: " + (", ".join(item["source_path"] for item in top) or "<empty>"))
        lines.append("")
    lines.append("=== 基线漂移 ===")
    if drift:
        lines.extend(f"  {line}" for line in drift)
    else:
        lines.append("  （无基线或无可对比项）")
    if env_warnings:
        lines.append("")
        lines.append("=== 环境警告 ===")
        lines.extend(f"  {line}" for line in env_warnings)
    return "\n".join(lines)


def build_json_report(
    results: List[CaseResult],
    summary: Dict[str, Any],
    drift: List[str],
    layer_name: str,
    metadata: Optional[Dict[str, Any]] = None,
) -> Dict[str, Any]:
    return {
        "generated_at": datetime.now(timezone.utc).isoformat(timespec="seconds"),
        "layer": layer_name,
        "metadata": metadata or {},
        "summary": summary,
        "drift": drift,
        "cases": [
            {
                "id": result.case_id,
                "category": result.category,
                "question": result.question,
                "passed": result.passed,
                "observe": result.observe,
                "hit_ranks": result.hit_ranks,
                "coverage": result.coverage,
                "mrr": result.mrr,
                "must_top": result.must_top,
                "must_top_ok": result.must_top_ok,
                "error": result.error,
                "elapsed_sec": round(result.elapsed_sec, 2),
                "ranked": result.ranked,
            }
            for result in results
        ],
    }


# ---------------------------------------------------------------------------
# CLI
# ---------------------------------------------------------------------------


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description="RAG 检索质量评估（recall@k / MRR + 基线漂移）")
    parser.add_argument("--case", action="append", default=[], help="只跑指定 id 的用例，可多次传")
    parser.add_argument("--cases", default=str(DEFAULT_CASES_FILE), help="题库 yml 路径")
    # S4 收敛后只有 llama_index 一条检索数据面，不再保留 --layer 选择。
    parser.add_argument("--limit", type=int, default=15, help="检索条数（指标按 recall@1/5/15 计算）")
    parser.add_argument("--baseline", default=str(DEFAULT_BASELINE_FILE), help="基线 JSON 路径")
    parser.add_argument("--update-baseline", action="store_true", help="把本轮结果合并写为新基线")
    parser.add_argument("--fail-on-regression", action="store_true",
                        help="整体 recall@15 相对基线下降超过 --regression-threshold 时退出码非零")
    parser.add_argument("--regression-threshold", type=float, default=0.0,
                        help="recall@15 允许的相对基线下浮（默认 0.0，即不允许回退；可设更大值放松）")
    parser.add_argument("--ignore-env-mismatch", action="store_true",
                        help="环境元信息与基线不一致时只告警不拒绝判定（默认环境不一致拒绝判定回归，退出码 2）")
    parser.add_argument("--max-avg-latency", type=float, default=None,
                        help="平均延迟上限（秒），超限退出码 1；默认不设线，可用 RAG_EVAL_MAX_AVG_LATENCY_SEC 兜底（CLI 优先）")
    parser.add_argument("--max-p95-latency", type=float, default=None,
                        help="p95 延迟上限（秒），超限退出码 1；默认不设线，可用 RAG_EVAL_MAX_P95_LATENCY_SEC 兜底（CLI 优先）")
    parser.add_argument("--output", default="", help="机器可读 JSON 报告输出路径")
    return parser


def _latency_gate(cli_value: Optional[float], env_name: str) -> Optional[float]:
    """性能门禁阈值：CLI 优先，env 兜底，都未设则不检查。"""
    if cli_value is not None:
        return cli_value
    raw = os.environ.get(env_name)
    if not raw:
        return None
    try:
        return float(raw)
    except ValueError:
        raise SystemExit(f"环境变量 {env_name} 不是合法数字：{raw!r}") from None


def select_cases(cases: List[Dict[str, Any]], case_ids: List[str]) -> List[Dict[str, Any]]:
    if not case_ids:
        return cases
    by_id = {case["id"]: case for case in cases}
    missing = [case_id for case_id in case_ids if case_id not in by_id]
    if missing:
        raise SystemExit(f"未知用例 id：{', '.join(missing)}（可选：{', '.join(sorted(by_id))}）")
    return [by_id[case_id] for case_id in case_ids]


async def main() -> int:
    args = build_parser().parse_args()
    cases_path = Path(args.cases)
    cases = select_cases(load_cases(cases_path), args.case)
    retriever, layer_name = build_retriever()
    limit = max(args.limit, max(METRIC_KS), max((int(c.get("limit") or 0) for c in cases), default=0))

    from app.config import get_settings

    metadata = await asyncio.to_thread(collect_env_metadata, get_settings(), cases_path, cases, limit)

    results = await run_cases(cases, retriever, limit)
    summary = summarize(_summary_records(results), ks=METRIC_KS)

    baseline_path = Path(args.baseline)
    baseline = load_baseline(baseline_path)
    drift = diff_baseline(baseline, results) if baseline else []
    _, env_warnings = compare_env(baseline.get("metadata") or {}, metadata) if baseline else ([], [])
    if args.update_baseline:
        write_baseline(baseline_path, layer_name, results, summary, previous=baseline, metadata=metadata)
        print(f"基线已更新：{baseline_path}", flush=True)
    elif not baseline:
        print(f"（无基线文件 {baseline_path}，跳过漂移对比；--update-baseline 可建立）", flush=True)

    print(render_text_report(results, summary, drift, metadata=metadata, env_warnings=env_warnings), flush=True)
    if args.output:
        Path(args.output).write_text(
            json.dumps(
                build_json_report(results, summary, drift, layer_name, metadata=metadata),
                ensure_ascii=False,
                indent=2,
            )
            + "\n",
            encoding="utf-8",
        )
        print(f"JSON 报告已写入：{args.output}", flush=True)

    exit_code, gate_lines = evaluate_gates(
        results=results,
        summary=summary,
        baseline=baseline,
        current_metadata=metadata,
        fail_on_regression=args.fail_on_regression,
        regression_threshold=args.regression_threshold,
        max_avg_latency=_latency_gate(args.max_avg_latency, "RAG_EVAL_MAX_AVG_LATENCY_SEC"),
        max_p95_latency=_latency_gate(args.max_p95_latency, "RAG_EVAL_MAX_P95_LATENCY_SEC"),
        ignore_env_mismatch=args.ignore_env_mismatch,
    )
    for line in gate_lines:
        print(line, flush=True)
    return exit_code


# ---------------------------------------------------------------------------
# pytest 入口（live mark，不进默认集）
#
# 单条整体指标用例：跑全量题库并断言整体 recall@15 不低于
# RAG_EVAL_MIN_RECALL15（未设置则只打印不卡线）。逐例硬断言由
# tests/test_retrieval.py（同一题库 parametrize）负责。
# ---------------------------------------------------------------------------

if pytest is not None:

    def test_rag_eval_metrics_floor() -> None:
        async def _run() -> tuple[List[CaseResult], Dict[str, Any]]:
            retriever, _ = build_retriever()
            cases = load_cases(DEFAULT_CASES_FILE)
            results = await run_cases(cases, retriever, limit=max(METRIC_KS))
            return results, summarize(_summary_records(results), ks=METRIC_KS)

        results, summary = asyncio.run(_run())
        print("\n" + render_text_report(results, summary, drift=[]))
        assert not [result for result in results if result.error], (
            "存在检索异常用例：" + ", ".join(result.case_id for result in results if result.error)
        )
        floor = float(os.environ.get("RAG_EVAL_MIN_RECALL15", "0") or 0)
        if floor > 0:
            assert summary["overall"]["recall@15"] >= floor, (
                f"整体 recall@15={summary['overall']['recall@15']:.3f} 低于下限 {floor}"
            )


if __name__ == "__main__":
    raise SystemExit(asyncio.run(main()))
