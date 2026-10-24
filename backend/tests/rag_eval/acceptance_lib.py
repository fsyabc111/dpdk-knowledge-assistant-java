"""产品答案验收纯逻辑库：题集展开、筛选抽样、自动初判分类、人工评分合并与汇总。

无网络、无应用依赖（不 import app.*），供同目录 product_acceptance_runner.py
（CLI）与离线单测共用，进默认测试集。

重要边界：product_acceptance.yml 已声明 human_review_required: true——expected
只是编写时的候选标签，未经教师核对确认；本模块任何自动输出（含 auto 初判分类）
都不构成人工验收通过。
"""

from __future__ import annotations

import math
import random
import re
from datetime import datetime, timezone
from pathlib import Path

import yaml

EXPECTED_LABELS = ("answered", "unanswered", "needs_clarification", "not_applicable")
FAILURE_CATEGORIES = ("retrieval_miss", "insufficient_evidence", "generation_error", "false_reject", "system_error")
PENDING_REVIEW = "pending_review"
REJECT_LABELS = ("unanswered", "needs_clarification", "not_applicable")

DISCLAIMER = (
    "expected 为题集编写时的候选标签，未经教师核对确认；auto 自动分类仅是"
    "基于 answer_status 与引用有无的机械初判，可能误判；在完成人工评分"
    "（correct/claims_supported/citation_ok/privacy_pass）之前，本工具任何"
    "输出都不构成产品答案验收通过。"
)

DEFAULT_SEED = 20260913
BASELINE_VERSION = 1

_REVIEW_BOOL_FIELDS = ("correct", "claims_supported", "citation_ok", "privacy_pass")
# 引用级盲审字段（P2 新增）：旧评分文件不含这些键，读取时按 None 处理。
_REVIEW_LIST_FIELDS = ("unsupported_citation_indexes",)
_REVIEW_EXTRA_FIELDS = ("missing_evidence",)


def expand_cases(data: dict) -> list[dict]:
    """按题集规则展开用例：协议模板题在前（id 规则 protocol_{protocol}_{i}），独立题在后。"""
    cases: list[dict] = [
        {
            "id": f"protocol_{protocol}_{index}",
            "question": template.format(protocol=protocol),
            "expected": "answered",
            "history": [],
        }
        for protocol in data["protocols"]
        for index, template in enumerate(data["protocol_questions"], 1)
    ]
    for item in data.get("cases") or []:
        case = dict(item)
        case.setdefault("history", [])
        cases.append(case)
    return cases


def load_cases(path) -> list[dict]:
    """从 yml 题集文件加载并展开用例。"""
    data = yaml.safe_load(Path(path).read_text(encoding="utf-8")) or {}
    return expand_cases(data)


def _bucket_key(case: dict) -> str:
    """分层抽样的分布桶：协议模板题按协议分桶，独立题按来源归一桶。"""
    case_id = str(case.get("id") or "")
    if case_id.startswith("protocol_"):
        return case_id.rsplit("_", 1)[0]
    return "cases"


def select_cases(
    cases: list[dict],
    ids: list[str] | None = None,
    expected: str | None = None,
    sample: int | None = None,
    seed: int = DEFAULT_SEED,
) -> list[dict]:
    """筛选用例：ids 过滤 → expected 过滤 → 确定性分层抽样。

    未知 id 抛 ValueError 并列出可选 id；sample 超过可选数时截断为全量。
    """
    selected = list(cases)
    if ids:
        by_id = {case["id"]: case for case in selected}
        unknown = [case_id for case_id in ids if case_id not in by_id]
        if unknown:
            raise ValueError(f"未知用例 id：{', '.join(unknown)}（可选：{', '.join(sorted(by_id))}）")
        wanted = set(ids)
        selected = [case for case in selected if case["id"] in wanted]
    if expected:
        selected = [case for case in selected if case.get("expected") == expected]
    if sample is None:
        return selected
    if sample < 1:
        raise ValueError(f"sample 必须是正整数：{sample}")
    if sample >= len(selected):
        return selected
    return _stratified_sample(selected, sample, seed)


def _stratified_sample(cases: list[dict], sample: int, seed: int) -> list[dict]:
    """确定性分层抽样：每种现存 expected 标签先保底 1 题，再按协议/来源桶轮转补足。"""
    rng = random.Random(seed)
    by_label: dict[str, list[dict]] = {}
    for case in cases:
        by_label.setdefault(str(case.get("expected")), []).append(case)
    label_order = [label for label in EXPECTED_LABELS if label in by_label]
    label_order += sorted(set(by_label) - set(label_order))
    picked: list[dict] = []
    picked_ids: set[str] = set()
    for label in label_order:
        group = list(by_label[label])
        rng.shuffle(group)
        picked.append(group[0])
        picked_ids.add(group[0]["id"])
    if len(picked) >= sample:
        return picked[:sample]
    buckets: dict[str, list[dict]] = {}
    for case in cases:
        if case["id"] not in picked_ids:
            buckets.setdefault(_bucket_key(case), []).append(case)
    for bucket in buckets.values():
        rng.shuffle(bucket)
    names = sorted(buckets)
    while len(picked) < sample:
        progressed = False
        for name in names:
            if len(picked) >= sample:
                break
            if buckets[name]:
                picked.append(buckets[name].pop())
                progressed = True
        if not progressed:
            break
    return picked


def classify_case(case: dict, response: dict | None, error: str) -> dict:
    """自动初判分类：仅按 error / answer_status / 引用有无机械映射，供人工评分参考。

    返回 {"auto_category": FAILURE_CATEGORIES 之一或 "pending_review", "auto_basis": 一句话中文依据}。
    """
    expected = str(case.get("expected") or "")
    if error:
        return {"auto_category": "system_error", "auto_basis": f"请求未获正常响应（HTTP 错误/超时/异常）：{error}"}
    if not response:
        return {"auto_category": "system_error", "auto_basis": "无响应数据可判定，按系统错误处理"}
    status = str(response.get("answer_status") or "")
    if status == "system_error":
        return {"auto_category": "system_error", "auto_basis": "服务端返回 answer_status=system_error"}
    citations = response.get("citations") or []
    if expected == "answered":
        if status == "answered":
            return {"auto_category": PENDING_REVIEW, "auto_basis": "状态一致（answered），事实正确性与引用质量待人工评分"}
        if status in ("unanswered", "needs_clarification"):
            if not citations:
                return {"auto_category": "retrieval_miss", "auto_basis": f"期望可答但实际 {status} 且无引用，疑似召回问题"}
            if status == "unanswered":
                return {"auto_category": "false_reject", "auto_basis": "期望可答、有引用仍判 unanswered，疑似校验误拒"}
            return {"auto_category": "insufficient_evidence", "auto_basis": "期望可答、有引用但要求澄清，疑似证据不足"}
    elif expected in REJECT_LABELS and status == "answered":
        return {"auto_category": "generation_error", "auto_basis": f"期望 {expected}（拒答类）但实际作答，疑似该拒未拒/生成错误"}
    if status == expected:
        return {"auto_category": PENDING_REVIEW, "auto_basis": f"状态一致（{expected}），拒答合理性与措辞待人工评分"}
    if expected in REJECT_LABELS and status in REJECT_LABELS:
        return {
            "auto_category": PENDING_REVIEW,
            "auto_basis": f"拒答类别不一致（expected={expected}，实际={status}），待人工判定",
        }
    return {"auto_category": PENDING_REVIEW, "auto_basis": f"组合未覆盖（expected={expected}，实际={status}），待人工判定"}


def empty_review() -> dict:
    """人工评分模板：全部留空，由教师评分时填写。"""
    return {
        "correct": None,
        "claims_supported": None,
        "citation_ok": None,
        "privacy_pass": None,
        "unsupported_citation_indexes": None,
        "missing_evidence": None,
        "failure_category": None,
        "notes": "",
        "reviewer": "",
        "reviewed_at": None,
    }


def build_run_record(case: dict, response: dict | None, error: str, elapsed_ms: int) -> dict:
    """组装单题 run 记录：用例摘要 + 响应要点 + 自动初判 + 空人工评分模板。

    题集里可选的 ``gold_answer`` / ``gold_citations`` 原样带进记录（供愿意写
    金标准的教师使用）。盲审默认不需要它们：判定依据是**引用到的证据正文**，
    见 ``evidence_report``。
    """
    answer_status = str(response.get("answer_status")) if response else None
    return {
        "case": {
            "id": case.get("id"),
            "question": case.get("question"),
            "history": list(case.get("history") or []),
            "expected": case.get("expected"),
            "gold_answer": case.get("gold_answer"),
            "gold_citations": list(case.get("gold_citations") or []),
        },
        "answer_status": answer_status,
        "answer": response.get("answer") if response else None,
        "citations": list(response.get("citations") or []) if response else [],
        "status_matches": (answer_status == case.get("expected")) if response else None,
        "elapsed_ms": elapsed_ms,
        "http_error": error or "",
        "auto": classify_case(case, response, error),
        "human_review": empty_review(),
    }


def validate_review(case_id: str, entry: dict, citation_count: int | None = None) -> list[str]:
    """校验单条人工评分条目，返回错误消息列表（空列表=合法）。

    ``citation_count`` 给出本题的引用条数时，越界的引用编号会被**报错而不是
    静默丢弃**——评分文件写错编号是最容易发生也最难发现的一类错误。
    """
    if not isinstance(entry, dict):
        return [f"[{case_id}] 评分条目必须是对象"]
    errors: list[str] = []
    category = entry.get("failure_category")
    if category is not None and category not in FAILURE_CATEGORIES:
        errors.append(f"[{case_id}] failure_category 非法：{category!r}（可选：{', '.join(FAILURE_CATEGORIES)}）")
    for field_name in _REVIEW_BOOL_FIELDS + _REVIEW_EXTRA_FIELDS:
        value = entry.get(field_name)
        if value is not None and not isinstance(value, bool):
            errors.append(f"[{case_id}] {field_name} 必须是 bool 或 null，实际：{value!r}")
    for field_name in _REVIEW_LIST_FIELDS:
        value = entry.get(field_name)
        if value is None:
            continue
        if not isinstance(value, list) or not all(
            isinstance(item, int) and not isinstance(item, bool) for item in value
        ):
            errors.append(f"[{case_id}] {field_name} 必须是整数列表或 null，实际：{value!r}")
            continue
        if citation_count is not None:
            out_of_range = sorted({item for item in value if item < 1 or item > citation_count})
            if out_of_range:
                errors.append(
                    f"[{case_id}] {field_name} 编号越界：{out_of_range}"
                    f"（本题引用编号为 1..{citation_count}）"
                )
    return errors


# ---------------------------------------------------------------------------
# 引用级盲审：把「可核对的证据」交到审稿人手里
# ---------------------------------------------------------------------------

_SENTENCE_SPLIT = re.compile(r"(?<=[。！？；!?;\n])")
EVIDENCE_CHARS = 400


def citation_contexts(answer: str, index: int) -> list[str]:
    """答案里引用 ``[index]`` 的句子；没有标点分隔时整段返回。

    盲审要回答的是「这条引用是否支撑它所在的那句话」，所以必须把**论断句**
    和证据正文并排放在一起，而不是让人在一篇长答案里找 ``[3]``。
    """
    marker = f"[{index}]"
    return [
        sentence.strip()
        for sentence in _SENTENCE_SPLIT.split(str(answer or ""))
        if marker in sentence
    ]


def evidence_report(record: dict, sources: dict, evidence_chars: int = EVIDENCE_CHARS) -> list[str]:
    """单题盲审清单：逐条引用列出「论断句 + 证据正文」，纯格式化无 IO。

    ``sources`` 是 chunk_id → 证据字典（``content``/``source_path`` 等）的映射，
    取不到时传 None。取数由调用方负责（runner 复用应用既有的
    ``get_llama_index_source`` / ``RetrievalService.get_chunk``）。
    """
    case = record.get("case") or {}
    lines = [
        f"===== {case.get('id')} =====",
        f"expected={case.get('expected')}  实际={record.get('answer_status')}"
        f"  耗时={record.get('elapsed_ms')}ms"
        + (f"  请求错误={record.get('http_error')}" if record.get("http_error") else ""),
        f"问题：{case.get('question')}",
    ]
    for item in case.get("history") or []:
        lines.append(f"  （历史轮）{item}")
    lines.append(f"答案：{(record.get('answer') or '（无答案）')[:evidence_chars]}")
    citations = record.get("citations") or []
    if not citations:
        lines.append("引用：无")
    for index, citation in enumerate(citations, start=1):
        chunk_id = str(citation.get("chunk_id") or "")
        lines.append(
            f"-- 引用 [{index}] {chunk_id}  {citation.get('source_path')}"
            f"  §{citation.get('section')}  score={citation.get('score')}"
        )
        for context in citation_contexts(record.get("answer") or "", index) or ["（答案中未出现该编号）"]:
            lines.append(f"     论断：{context}")
        source = sources.get(chunk_id)
        if not source:
            lines.append("     证据：⚠ 取不到证据正文（chunk_id 不在库中或已下线）")
            continue
        content = str(source.get("content") or "")
        lines.append(f"     证据（前 {evidence_chars} 字符）：{content[:evidence_chars]}")
        if len(content) > evidence_chars:
            lines.append(f"     （证据正文共 {len(content)} 字符，以上为头部）")
    lines.append("人工判定：以上引用的论断是否被其证据正文支撑？________________")
    return lines


def merge_review(run_payload: dict, scores: dict) -> tuple[dict, list[str]]:
    """把人工评分按 case id 合并进 records[].human_review（原地修改并返回）。

    scores 形如 {"reviewer": str, "reviewed_at": str, "reviews": {case_id: 评分条目}}；
    允许部分字段（仅覆盖非 None 值），顶层 reviewer/reviewed_at 作条目缺省值。
    未知 case id 与校验错误进入错误列表，不静默跳过。
    """
    errors: list[str] = []
    records = run_payload.get("records") or []
    by_id = {str((record.get("case") or {}).get("id")): record for record in records}
    default_reviewer = str(scores.get("reviewer") or "")
    default_reviewed_at = scores.get("reviewed_at")
    for case_id, entry in (scores.get("reviews") or {}).items():
        record = by_id.get(str(case_id))
        if record is None:
            errors.append(f"[{case_id}] 未知 case id：run 结果中不存在")
            continue
        problems = validate_review(
            str(case_id), entry, citation_count=len(record.get("citations") or [])
        )
        if problems:
            errors.append("；".join(problems))
            continue
        review = record.setdefault("human_review", empty_review())
        for key in empty_review():
            if key in ("reviewer", "reviewed_at"):
                continue
            value = entry.get(key)
            if value is not None:
                review[key] = value
        review["reviewer"] = str(entry.get("reviewer") or default_reviewer)
        review["reviewed_at"] = entry.get("reviewed_at") or default_reviewed_at
    return run_payload, errors


def _is_reviewed(review: dict) -> bool:
    """四个结论字段或 failure_category 任一非空即视为已完成人工评分。"""
    return any(review.get(field) is not None for field in (*_REVIEW_BOOL_FIELDS, "failure_category"))


def _empty_verdicts() -> dict:
    return {field: {"true": 0, "false": 0, "unrated": 0} for field in _REVIEW_BOOL_FIELDS}


def _percentile(values: list[int], ratio: float) -> int | None:
    """最近秩百分位；样本为空返回 None（未知不是 0）。"""
    if not values:
        return None
    ordered = sorted(values)
    rank = max(1, min(len(ordered), math.ceil(len(ordered) * ratio)))
    return ordered[rank - 1]


def summarize_runs(payloads: list[dict]) -> dict:
    """跨 run 汇总：auto 初判分类与人工确认分类分列，附免责声明与人工评分覆盖率。

    人工结论按字段分列 true/false/unrated（``unrated`` 与 ``false`` 必须分开——
    没评过不等于评了不通过），并给出逐标签桶的同一组计数，供「只统计可答题」
    这类受限口径使用。耗时统计一并给出，供后续设定耗时门禁。
    """
    runs: list[dict] = []
    totals = {"cases": 0, "status_match": 0, "status_mismatch": 0, "errors": 0}
    by_expected: dict[str, dict] = {}
    auto_categories: dict[str, int] = {category: 0 for category in FAILURE_CATEGORIES}
    human_categories: dict[str, int] = {category: 0 for category in FAILURE_CATEGORIES}
    unsupported_citations = 0
    missing_evidence = 0
    latencies: list[int] = []
    reviewed = 0
    for payload in payloads:
        run_totals = {"cases": 0, "status_match": 0, "status_mismatch": 0, "errors": 0}
        for record in payload.get("records") or []:
            run_totals["cases"] += 1
            matches = record.get("status_matches")
            has_error = bool(record.get("http_error"))
            if matches is True:
                run_totals["status_match"] += 1
            elif matches is False:
                run_totals["status_mismatch"] += 1
            if has_error:
                run_totals["errors"] += 1
            elapsed = record.get("elapsed_ms")
            if isinstance(elapsed, int) and not isinstance(elapsed, bool):
                latencies.append(elapsed)
            label = str((record.get("case") or {}).get("expected") or "")
            bucket = by_expected.setdefault(label, {
                "cases": 0, "status_match": 0, "status_mismatch": 0, "errors": 0,
                "reviewed": 0, "verdicts": _empty_verdicts(),
                # 逐标签桶的人工失败分类：误拒答率这类「分母只含可答题」的口径
                # 必须同源计数，否则分子会把拒答题也算进来，比率可超过 100%。
                "human_failure_categories": {category: 0 for category in FAILURE_CATEGORIES},
            })
            bucket["cases"] += 1
            if matches is True:
                bucket["status_match"] += 1
            elif matches is False:
                bucket["status_mismatch"] += 1
            if has_error:
                bucket["errors"] += 1
            # auto 失败分类仅统计 status 与 expected 不符或出错的记录
            if matches is not True or has_error:
                category = str((record.get("auto") or {}).get("auto_category") or "")
                auto_categories[category] = auto_categories.get(category, 0) + 1
            review = record.get("human_review") or {}
            human_category = review.get("failure_category")
            if human_category in FAILURE_CATEGORIES:
                human_categories[human_category] += 1
                bucket["human_failure_categories"][human_category] += 1
            if _is_reviewed(review):
                reviewed += 1
                bucket["reviewed"] += 1
            for field in _REVIEW_BOOL_FIELDS:
                value = review.get(field)
                key = "true" if value is True else "false" if value is False else "unrated"
                bucket["verdicts"][field][key] += 1
            unsupported_citations += len(review.get("unsupported_citation_indexes") or [])
            if review.get("missing_evidence") is True:
                missing_evidence += 1
        for key in totals:
            totals[key] += run_totals[key]
        runs.append({"generated_at": payload.get("generated_at"), **run_totals})
    human_verdicts = _empty_verdicts()
    for bucket in by_expected.values():
        for field, counts in bucket["verdicts"].items():
            for key, value in counts.items():
                human_verdicts[field][key] += value
    human_verdicts["unsupported_citations"] = unsupported_citations
    human_verdicts["missing_evidence"] = missing_evidence
    ordered = {label: by_expected[label] for label in EXPECTED_LABELS if label in by_expected}
    ordered.update({label: bucket for label, bucket in by_expected.items() if label not in ordered})
    total = totals["cases"]
    return {
        "human_review_required": True,
        "disclaimer": DISCLAIMER,
        "runs": runs,
        "totals": totals,
        "by_expected": ordered,
        "auto_failure_categories": auto_categories,
        "human_failure_categories": human_categories,
        "human_verdicts": human_verdicts,
        "latency_ms": {
            "count": len(latencies),
            "avg": round(sum(latencies) / len(latencies)) if latencies else None,
            "p95": _percentile(latencies, 0.95),
            "max": max(latencies) if latencies else None,
        },
        "review_coverage": {
            "reviewed": reviewed,
            "total": total,
            "rate": round(reviewed / total, 4) if total else 0.0,
        },
    }


# ---------------------------------------------------------------------------
# 验收判定：门禁、退出码与基线（纯函数，不读文件、不调模型）
# ---------------------------------------------------------------------------


def _rate(counts: dict) -> float | None:
    """true/(true+false)；无人评分时返回 None——**未知不是 0**。"""
    rated = counts.get("true", 0) + counts.get("false", 0)
    return round(counts.get("true", 0) / rated, 4) if rated else None


def acceptance_metrics(summary: dict) -> dict:
    """把汇总折算成可比数值指标；门禁判定与基线对比共用同一口径。

    受限口径（写进文档，避免两处漂移）：``correct_rate`` 只统计 expected=answered
    的题（拒答题问「答案是否正确」没有意义）；``false_reject_rate`` 的分子分母
    **都只取可答题桶**（只有可答题才谈得上误拒；分子若取全局计数，一条被标成
    误拒的拒答题会让比率超过 100%）；比率类指标的 ``None`` 表示**没有样本**，
    不是 0。
    """
    verdicts = summary.get("human_verdicts") or {}
    by_expected = summary.get("by_expected") or {}
    answered = by_expected.get("answered") or {}
    answered_verdicts = answered.get("verdicts") or {}
    answered_cases = answered.get("cases", 0) or 0
    totals = summary.get("totals") or {}
    latency = summary.get("latency_ms") or {}
    cases = totals.get("cases", 0) or 0
    return {
        "cases": cases,
        "review_coverage_rate": (summary.get("review_coverage") or {}).get("rate", 0.0),
        "correct_rate": _rate(answered_verdicts.get("correct") or {}),
        "citation_ok_rate": _rate(verdicts.get("citation_ok") or {}),
        "claims_supported_rate": _rate(verdicts.get("claims_supported") or {}),
        "privacy_pass_rate": _rate(verdicts.get("privacy_pass") or {}),
        "false_reject_rate": (
            round(
                (answered.get("human_failure_categories") or {}).get("false_reject", 0) / answered_cases, 4
            )
            if answered_cases else None
        ),
        "system_error_rate": round(totals.get("errors", 0) / cases, 4) if cases else None,
        "pending_review_count": (summary.get("auto_failure_categories") or {}).get(PENDING_REVIEW, 0),
        "unsupported_citations": verdicts.get("unsupported_citations", 0),
        "missing_evidence": verdicts.get("missing_evidence", 0),
        "avg_latency_ms": latency.get("avg"),
        "p95_latency_ms": latency.get("p95"),
    }


# 门禁名 → (指标, 方向, 中文说明)。方向 min = 越大越好。
GATE_METRICS = {
    "min_correct_rate": ("correct_rate", "min", "可答题答案正确率"),
    "min_citation_ok_rate": ("citation_ok_rate", "min", "引用支撑率"),
    "max_false_reject_rate": ("false_reject_rate", "max", "误拒答率"),
    "max_system_error_rate": ("system_error_rate", "max", "系统错误率"),
    "max_pending_review": ("pending_review_count", "max", "自动分类未定题数"),
    "max_avg_latency_ms": ("avg_latency_ms", "max", "平均耗时(ms)"),
    "max_p95_latency_ms": ("p95_latency_ms", "max", "P95 耗时(ms)"),
}


def evaluate_acceptance(summary: dict, gates: dict | None = None) -> dict:
    """按显式门禁判定验收结论；阈值默认不设线，只报事实。

    人工评分覆盖率不足 100% 时结论恒为 ``incomplete`` 并**拒绝给出通过/不通过**
    ——这正是声明里那条纪律的可执行版本：没评分就不算验收。

    退出码由调用方按 ``conclusion`` 决定（见 runner）：``fail``→1、``incomplete``→2、
    ``pass``/``no_gates``→0。
    """
    active = {key: value for key, value in (gates or {}).items() if value is not None}
    unknown = sorted(set(active) - set(GATE_METRICS))
    if unknown:
        raise ValueError(f"未知门禁：{', '.join(unknown)}（可选：{', '.join(sorted(GATE_METRICS))}）")
    metrics = acceptance_metrics(summary)
    checks = []
    for key, threshold in active.items():
        metric_name, direction, label = GATE_METRICS[key]
        actual = metrics.get(metric_name)
        passed = actual is not None and (
            actual >= threshold if direction == "min" else actual <= threshold
        )
        checks.append({
            "gate": key, "metric": metric_name, "label": label, "direction": direction,
            "threshold": threshold, "actual": actual, "passed": passed,
        })
    if metrics["review_coverage_rate"] < 1.0:
        return {
            "conclusion": "incomplete",
            "metrics": metrics,
            "checks": checks,
            "blocking": [
                f"人工评分覆盖率 {metrics['review_coverage_rate']:.2%} < 100%："
                f"未评分即未验收，拒绝判定"
            ],
        }
    if not checks:
        return {"conclusion": "no_gates", "metrics": metrics, "checks": [], "blocking": []}
    failed = [check for check in checks if not check["passed"]]
    return {
        "conclusion": "fail" if failed else "pass",
        "metrics": metrics,
        "checks": checks,
        "blocking": [
            f"{check['label']} = {check['actual']}，未满足 {check['direction']} {check['threshold']}"
            for check in failed
        ],
    }


# 基线对比方向：up = 越大越好（变小是回退），down = 越小越好（变大是回退）。
METRIC_DIRECTION = {
    "review_coverage_rate": "up",
    "correct_rate": "up",
    "citation_ok_rate": "up",
    "claims_supported_rate": "up",
    "privacy_pass_rate": "up",
    "false_reject_rate": "down",
    "system_error_rate": "down",
    "pending_review_count": "down",
    "unsupported_citations": "down",
    "missing_evidence": "down",
    "avg_latency_ms": "down",
    "p95_latency_ms": "down",
}

# 耗时受机器负载影响，10% 以内的抖动不报回退（把噪声报成回退会淹没真信号）。
LATENCY_TOLERANCE = 0.1


def baseline_payload(payloads: list[dict], generated_at: str | None = None) -> dict:
    """从 run payload 生成基线快照：指标 + 逐题事实（标签、状态、人工结论）。

    基线是**教师确认过的那一轮**的冻结事实，供后续 run 对比回退；它不替代
    当轮判定，也不代表任何题「通过」。
    """
    summary = summarize_runs(payloads)
    cases: dict[str, dict] = {}
    for payload in payloads:
        for record in payload.get("records") or []:
            case_id = str((record.get("case") or {}).get("id"))
            review = record.get("human_review") or {}
            cases[case_id] = {
                "expected": (record.get("case") or {}).get("expected"),
                "answer_status": record.get("answer_status"),
                "http_error": bool(record.get("http_error")),
                "human_review": {
                    field: review.get(field)
                    for field in (*_REVIEW_BOOL_FIELDS, *_REVIEW_LIST_FIELDS,
                                  *_REVIEW_EXTRA_FIELDS, "failure_category")
                },
            }
    return {
        "version": BASELINE_VERSION,
        "generated_at": generated_at or datetime.now(timezone.utc).isoformat(timespec="seconds"),
        "disclaimer": DISCLAIMER,
        "human_review_required": True,
        "metrics": acceptance_metrics(summary),
        "cases": cases,
    }


def compare_with_baseline(baseline: dict, current: dict) -> dict:
    """逐指标与逐题对比基线，**只报事实变化，不重新判定验收**。"""
    base_metrics = baseline.get("metrics") or {}
    current_metrics = current.get("metrics") or {}
    metric_changes: list[dict] = []
    regressions: list[str] = []
    for name, direction in METRIC_DIRECTION.items():
        before, after = base_metrics.get(name), current_metrics.get(name)
        if before is None or after is None:
            continue
        delta = after - before
        tolerance = abs(before) * LATENCY_TOLERANCE if name.endswith("_latency_ms") else 0.0
        worse = (delta < 0 if direction == "up" else delta > 0) and abs(delta) > tolerance
        metric_changes.append({
            "metric": name, "direction": direction,
            "baseline": before, "current": after, "delta": round(delta, 4),
            "regressed": worse,
        })
        if worse:
            regressions.append(name)
    case_changes: list[dict] = []
    for case_id, before in (baseline.get("cases") or {}).items():
        after = (current.get("cases") or {}).get(case_id)
        if after is None:
            continue
        if (before.get("answer_status") != after.get("answer_status")
                or bool(before.get("http_error")) != bool(after.get("http_error"))):
            case_changes.append({
                "case": case_id, "field": "answer_status",
                "baseline": before.get("answer_status"), "current": after.get("answer_status"),
            })
        before_review = before.get("human_review") or {}
        after_review = after.get("human_review") or {}
        for field in _REVIEW_BOOL_FIELDS:
            if before_review.get(field) is True and after_review.get(field) is False:
                case_changes.append({
                    "case": case_id, "field": field, "baseline": True, "current": False,
                })
                regressions.append(f"{case_id}.{field}")
    return {"metrics": metric_changes, "cases": case_changes, "regressions": regressions}
