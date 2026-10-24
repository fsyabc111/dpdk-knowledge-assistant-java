"""live 评测集与模型漂移检查（docs/archive/2026-09-18/agent_upgrade_plan.md §8「live 评测」，M3）。

黄金场景集（tests/agent/test_golden_scenarios.py）用脚本化假模型做确定性断言；
本脚本是对照组：用真实模型打真实 /api/chat/stream 接口，跑同目录
live_eval.yml 里的小集，按 expect（工具选择/状态码）+ rubric（答案文本）
判定通过与否，并与本地基线（backend/data/live_eval_baseline.json，不入库）
对比打印漂移项（上次 pass 这次 fail、工具序列变化等）。

触发时机：模型升级、agent 提示词大改之后手工跑（流程见 docs/runbook.md
「live 评测集与模型漂移检查」一节）。本脚本不 import app 包，是纯 HTTP
客户端，可以对任何可达的部署跑。

用法：
    cd backend
    .venv/bin/python tests/agent/live_eval_runner.py                        # 打本机 8090
    .venv/bin/python tests/agent/live_eval_runner.py --case route_kb_dns_qname
    .venv/bin/python tests/agent/live_eval_runner.py --username 2024001 --password 'xxx'
    .venv/bin/python tests/agent/live_eval_runner.py --token <bearer>
    .venv/bin/python tests/agent/live_eval_runner.py --update-baseline      # 把本轮结果写成新基线
    .venv/bin/python tests/agent/live_eval_runner.py --output report.json   # 机器可读报告
    .venv/bin/python tests/agent/live_eval_runner.py --skip-usage           # 不回读账本用量

pytest 形态（每题一个用例，全部 live mark，不进默认集）：
    .venv/bin/python -m pytest tests/agent/live_eval_runner.py -q -m live
    LIVE_EVAL_SKIP_USAGE=1 .venv/bin/python -m pytest tests/agent/live_eval_runner.py -q -m live

pytest 形态用环境变量配置目标与凭据：LIVE_EVAL_BASE_URL（默认
http://127.0.0.1:8090）、LIVE_EVAL_TOKEN 或 LIVE_EVAL_USERNAME /
LIVE_EVAL_PASSWORD。local 认证模式下不需要任何凭据。

P2 §4 起的两处口径（数字与 P0.1 不可直接比）：

1. **词表按词边界匹配**（``TERM_CALIBER = word-boundary-v1``，此前是子串口径
   ``substring-v0``）：`process` 不再被 `packets_processed` 满足。这是**收紧**；
   逐题差异记在 ``term_caliber`` 字段并在报告里以 ``~`` 行列出。同一份 run 的
   JSON 报告与基线都带口径版本，跨口径对比会先打一行 ``[口径变更]``。
2. **逐题 token/成本**：CLI 形态给每题派生 request_id（uuid5(run_id, case_id)，
   客户端字段要求是 UUID），跑完按 request_id 回读 P1 账本
   （``GET /api/usage/cost``）。读不到只记 ``usage_error``，**不影响 PASS/FAIL**；
   token 未知显示为「未知」而不是 0。同一 run_id 重复跑会被产品路径**幂等重放**
   （拿回上一轮旧答案），所以 run_id 默认取当前时间戳，显式 --run-id 时自负其责。
"""

from __future__ import annotations

import argparse
import asyncio
import json
import os
import re
import time
import uuid
from dataclasses import dataclass, field
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Dict, List, Optional, Tuple

import httpx
import yaml

try:  # 生产镜像不含 pytest：CLI 形态（python 直跑）不需要它，仅 pytest 形态需要
    import pytest
except ModuleNotFoundError:  # pragma: no cover - 仅在无 pytest 的容器内 CLI 运行命中
    pytest = None  # type: ignore[assignment]

SCRIPT_DIR = Path(__file__).resolve().parent
BACKEND_ROOT = SCRIPT_DIR.parents[1]
DEFAULT_CASES_FILE = SCRIPT_DIR / "live_eval.yml"
DEFAULT_BASELINE_FILE = BACKEND_ROOT / "data" / "live_eval_baseline.json"
DEFAULT_BASE_URL = "http://127.0.0.1:8090"

# 每轮评测的 run_id：request_id 由它派生，**每次运行都不同**。产品路径对同
# request_id 会做幂等重放（rag_issue_service.replay），复用 id 会拿回上一轮的
# 旧答案当成本轮结果——那是评测里最隐蔽的一种假通过。
RUN_ID_FORMAT = "%Y%m%dT%H%M%S"
# 词表判定口径版本。基线/报告都带上它：P0.1 是子串口径（`substring-v0`），P2 §4 起
# 是词边界口径（`word-boundary-v1`）。同一份数字在两个口径下不可直接比。
TERM_CALIBER = "word-boundary-v1"
REQUEST_ID_NAMESPACE = uuid.UUID("6f9619ff-8b86-d011-b42d-00cf4fc964ff")
LEDGER_DAYS = 1  # 账本回读窗口（滚动天数）；评测刚结束，1 天足够覆盖

# 结构化失败分类：这里是**断言种类**（哪一类卡口没过），不是验收路径那套
# 答非所问分类（retrieval_miss/false_reject/...）。live 集的 expect 只有
# http_status/tools/citations + rubric 词表，没有 answer_status 期望标签，
# 硬套那五类会编出并不存在的归因；产品路径的 answer_status 单独回报。
FAILURE_KINDS: Tuple[Tuple[str, str], ...] = (
    ("HTTP 状态码", "http_status"),
    ("SSE 出现 error 事件或请求异常", "sse_error"),
    ("工具序列", "tool_sequence"),
    ("禁调工具被调用", "forbidden_tool"),
    ("引用数", "citations"),
    ("必需词缺失", "required_term"),
    ("任选词全缺失", "any_terms"),
    ("违禁词出现", "forbidden_term"),
)

if pytest is not None:
    pytestmark = pytest.mark.live


# ---------------------------------------------------------------------------
# 用例加载与结果模型
# ---------------------------------------------------------------------------


def load_cases(path: Path) -> List[Dict[str, Any]]:
    """读取 yml 题库；attachment.source 与 size_bytes 二选一在运行时展开。"""
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
    status_code: int
    tools_started: List[str] = field(default_factory=list)  # tool_start 事件序列
    tool_ends: List[Dict[str, Any]] = field(default_factory=list)
    answer: str = ""
    citations: List[str] = field(default_factory=list)
    error_message: str = ""
    body_text: str = ""  # 非 200 时的响应体原文（护栏用例断言对象）
    elapsed_sec: float = 0.0
    checks: List[str] = field(default_factory=list)
    failures: List[str] = field(default_factory=list)
    # P2 §4 只增字段：token 归因与结构化分类。既有 failures 中文列表一字未改。
    request_id: str = ""           # 本轮派给该题的 request_id（uuid5，可复算）
    observed_request_id: str = ""  # SSE start 事件回显的 request_id（权威）
    answer_status: str = ""        # 产品路径 done 事件的 answer_status；旧路径为空=未知
    usage: Optional[Dict[str, Any]] = None  # 账本按 request_id 读回的用量
    usage_error: str = ""          # 读账失败原因（不掩盖、不影响 PASS/FAIL）
    term_caliber: List[str] = field(default_factory=list)  # 词表判定口径收紧/放宽的记录

    @property
    def passed(self) -> bool:
        return not self.failures

    @property
    def assert_text(self) -> str:
        """rubric 的断言文本：200 用例看答案，非 200 用例看错误响应体。"""
        return self.answer if self.status_code == 200 else self.body_text

    @property
    def failure_categories(self) -> List[str]:
        """结构化失败分类（断言种类），按 FAILURE_KINDS 顺序去重。"""
        kinds: List[str] = []
        for failure in self.failures:
            for prefix, kind in FAILURE_KINDS:
                if failure.startswith(prefix) and kind not in kinds:
                    kinds.append(kind)
                    break
        return kinds

    @property
    def total_tokens(self) -> Optional[int]:
        """本轮该题落账的 token 合计；读不到账或无样本时 None（未知不是 0）。"""
        if not self.usage:
            return None
        return self.usage.get("total_tokens")


# ---------------------------------------------------------------------------
# SSE 采集
# ---------------------------------------------------------------------------


def _attachment_payload(attachment: Dict[str, Any]) -> tuple[str, bytes]:
    """展开附件定义：内联源码原样用；size_bytes 生成填充内容（护栏用）。"""
    filename = str(attachment.get("filename") or "plugin.c")
    if "source" in attachment:
        return filename, str(attachment["source"]).encode("utf-8")
    size = int(attachment.get("size_bytes") or 0)
    filler = b"/* live-eval guard filler */\n" + b"x" * max(0, size)
    return filename, filler[: max(size, 1)]


async def run_case(
    client: httpx.AsyncClient,
    case: Dict[str, Any],
    token: Optional[str],
    run_id: str = "",
) -> CaseResult:
    """逐题打 /api/chat/stream，解析 SSE 记录工具序列、答案与引用数。

    ``run_id`` 非空时给该题派生一个可复算的 request_id（uuid5），使账本里的
    用量行能与题目对上；不传则交由服务端生成（pytest 形态即如此）。
    """
    result = CaseResult(case_id=case["id"], category=case.get("category", ""), status_code=0)
    headers = {"Authorization": f"Bearer {token}"} if token else {}
    context = {"page": "live_eval", "case_id": case["id"]}
    if run_id:
        # ChatRequest.request_id 是 UUID 字段：非 UUID 文本会 422，所以用 uuid5 派生。
        result.request_id = str(uuid.uuid5(REQUEST_ID_NAMESPACE, f"{run_id}/{case['id']}"))
    attachment = case.get("attachment")
    if attachment:
        filename, content = _attachment_payload(attachment)
        form: Dict[str, Any] = {"message": case["question"], "context": json.dumps(context)}
        if result.request_id:
            form["request_id"] = result.request_id
        request_kwargs: Dict[str, Any] = {
            "data": form,
            "files": {"file": (filename, content, "text/x-c")},
        }
    else:
        payload: Dict[str, Any] = {"session_id": None, "message": case["question"], "context": context}
        if result.request_id:
            payload["request_id"] = result.request_id
        request_kwargs = {"json": payload}

    start = time.monotonic()
    try:
        async with client.stream(
            "POST", "/api/chat/stream", headers=headers, **request_kwargs
        ) as response:
            result.status_code = response.status_code
            if response.status_code != 200:
                result.body_text = (await response.aread()).decode("utf-8", errors="replace")
                return result
            event_name: Optional[str] = None
            data_lines: List[str] = []
            async for line in response.aiter_lines():
                if line.startswith("event:"):
                    event_name = line[len("event:"):].strip()
                elif line.startswith("data:"):
                    data_lines.append(line[len("data:"):].strip())
                elif not line:
                    if event_name and data_lines:
                        _handle_event(result, event_name, "\n".join(data_lines))
                    event_name, data_lines = None, []
    except httpx.HTTPError as exc:
        result.error_message = f"请求失败：{type(exc).__name__}: {exc}"
    finally:
        result.elapsed_sec = time.monotonic() - start
    return result


def _handle_event(result: CaseResult, event: str, raw: str) -> None:
    """按 SSE 契约归集事件；未知事件类型忽略（与前端解析一样宽松）。"""
    try:
        data = json.loads(raw)
    except json.JSONDecodeError:
        return
    if event == "start":
        # 服务端回显的 request_id 是权威值（旧管线用自己的 trace id，不用我们派的）。
        result.observed_request_id = str(data.get("request_id") or "")
    elif event == "tool_start":
        result.tools_started.append(str(data.get("tool") or ""))
    elif event == "tool_end":
        result.tool_ends.append(data)
    elif event in ("delta", "token"):  # delta 是现行契约，token 兼容旧称
        result.answer += str(data.get("delta") or data.get("token") or "")
    elif event == "sources":
        citations = data.get("citations") or []
        result.citations = [str(item.get("source_path", "")) for item in citations]
    elif event == "done":
        # 产品路径的 done 带 answer_status；旧管线不带 → 留空（未知，不猜）。
        status = data.get("answer_status")
        if status:
            result.answer_status = str(status)
    elif event == "error":
        result.error_message = str(data.get("message") or "")


# ---------------------------------------------------------------------------
# 判定
# ---------------------------------------------------------------------------


# 词表匹配的边界字符：下划线算**词内**字符，所以 `process` 不再被
# `packets_processed` 满足（P0.1 的 `route_kb_engine_ops` 就是这么假通过的）。
# 只在词条自身的首/末字符是字母数字时加边界：以 `_` 结尾的词（如 `dns_`）本就是
# 前缀写法，给它加右边界会把 `dns_qname` 这类合法命中排除掉；以引号/中文/括号
# 开头结尾的词条（`` ``` ``、`用例`）边界字符非字母数字，行为与子串匹配一致。
_TERM_BOUNDARY_CHARS = frozenset("abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789")
_TERM_WORD_CLASS = "A-Za-z0-9_"


def _term_pattern(term: str) -> "re.Pattern[str]":
    prefix = f"(?<![{_TERM_WORD_CLASS}])" if term[0] in _TERM_BOUNDARY_CHARS else ""
    suffix = f"(?![{_TERM_WORD_CLASS}])" if term[-1] in _TERM_BOUNDARY_CHARS else ""
    return re.compile(prefix + re.escape(term) + suffix, re.IGNORECASE)


def _contains(text: str, term: str) -> bool:
    """词边界匹配（P2 §4 收紧口径）。空词条不算命中。"""
    if not term:
        return False
    return _term_pattern(term).search(text) is not None


def _contains_loose(text: str, term: str) -> bool:
    """P0.1 及以前的子串口径。**只用于报告新旧口径差异**，不参与判定。"""
    if not term:
        return False
    return term.lower() in text.lower()


def _note_caliber(result: CaseResult, label: str, term: str, direction: str) -> None:
    result.term_caliber.append(
        f"[口径{direction}] {label} {term!r}："
        + ("旧口径（子串）命中，新口径（词边界）未命中" if direction == "收紧"
           else "新口径（词边界）未命中但子串命中，不再判违禁")
    )


def _is_subsequence(expected: List[str], actual: List[str]) -> bool:
    """期望工具序列是实际序列的有序子序列（允许模型多查一次 kb 等）。"""
    remaining = iter(actual)
    return all(any(item == candidate for candidate in remaining) for item in expected)


def evaluate_case(case: Dict[str, Any], result: CaseResult) -> CaseResult:
    expect = case.get("expect") or {}
    rubric = case.get("rubric") or {}
    checks, failures = result.checks, result.failures

    expected_status = int(expect.get("http_status", 200))
    if result.status_code != expected_status:
        failures.append(f"HTTP 状态码 {result.status_code}，期望 {expected_status}")
    else:
        checks.append(f"HTTP 状态码 {expected_status}")

    if result.error_message and expected_status == 200:
        failures.append(f"SSE 出现 error 事件或请求异常：{result.error_message}")

    # 非 200 期望的护栏用例：端点在进 Agent 前拒绝，工具/引用断言不适用。
    if expected_status == 200:
        expected_tools = [str(tool) for tool in expect.get("tools") or []]
        if expected_tools:
            if _is_subsequence(expected_tools, result.tools_started):
                checks.append(f"工具序列包含期望子序列：{expected_tools}")
            else:
                failures.append(
                    f"工具序列 {result.tools_started} 不含期望子序列 {expected_tools}"
                )
        for tool in expect.get("forbidden_tools") or []:
            if tool in result.tools_started:
                failures.append(f"禁调工具被调用：{tool}")
            else:
                checks.append(f"禁调工具未出现：{tool}")
        min_citations = int(expect.get("min_citations", 0))
        if len(result.citations) < min_citations:
            failures.append(f"引用数 {len(result.citations)} < 要求 {min_citations}")
        elif min_citations:
            checks.append(f"引用数 {len(result.citations)} >= {min_citations}")

    text = result.assert_text
    for term in rubric.get("required_terms") or []:
        term = str(term)
        if _contains(text, term):
            checks.append(f"必需词命中：{term}")
        else:
            failures.append(f"必需词缺失：{term}")
            if _contains_loose(text, term):
                _note_caliber(result, "必需词", term, "收紧")
    for options in rubric.get("any_terms") or []:
        strict_hits = [str(term) for term in options if _contains(text, str(term))]
        if strict_hits:
            checks.append(f"任选词命中：{options}")
        else:
            failures.append(f"任选词全缺失：{options}")
            loose_hits = [str(term) for term in options if _contains_loose(text, str(term))]
            for term in loose_hits:
                _note_caliber(result, "任选词组", term, "收紧")
    for term in rubric.get("forbidden_terms") or []:
        term = str(term)
        if _contains(text, term):
            failures.append(f"违禁词出现：{term!r}（反馈纪律/不泄露红线）")
        else:
            checks.append(f"违禁词未出现：{term!r}")
            if _contains_loose(text, term):
                _note_caliber(result, "违禁词", term, "放宽")
    return result


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
    path: Path, base_url: str, results: List[CaseResult], previous: Optional[Dict[str, Any]] = None
) -> None:
    """把本轮结果固化为基线。只存判定与答案摘要（200 字符），不存全文。

    previous 存在时做合并：--case 部分跑只覆盖本轮跑到的题目，
    其余题目保留旧基线，避免部分运行清空全量基线。
    """
    cases: Dict[str, Any] = dict((previous or {}).get("cases") or {})
    for result in results:
        cases[result.case_id] = {
            "passed": result.passed,
            "tools": result.tools_started,
            "failures": result.failures,
            "answer_preview": " ".join(result.answer.split())[:200],
        }
    payload = {
        "version": 1,
        "term_caliber": TERM_CALIBER,
        "updated_at": datetime.now(timezone.utc).isoformat(timespec="seconds"),
        "base_url": base_url,
        "cases": cases,
    }
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(payload, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def diff_baseline(baseline: Dict[str, Any], results: List[CaseResult]) -> List[str]:
    """与基线逐题对比，返回人类可读的漂移项列表。"""
    baseline_cases = baseline.get("cases") or {}
    lines: List[str] = []
    old_caliber = baseline.get("term_caliber")
    if old_caliber and old_caliber != TERM_CALIBER:
        # 口径变了就不能只报「退步/修复」：同一份答案在两个口径下判定本就不同。
        lines.append(
            f"[口径变更] 基线口径 {old_caliber} → 本轮 {TERM_CALIBER}；"
            f"下述差异含口径影响，需对照每题 term_caliber 逐条看"
        )
    for result in results:
        old = baseline_cases.get(result.case_id)
        if old is None:
            lines.append(f"[新增] {result.case_id}：基线中不存在")
            continue
        old_passed = bool(old.get("passed"))
        if old_passed and not result.passed:
            lines.append(f"[退步] {result.case_id}：上次 PASS 本次 FAIL（{'; '.join(result.failures)}）")
        elif not old_passed and result.passed:
            lines.append(f"[修复] {result.case_id}：上次 FAIL 本次 PASS")
        old_tools = [str(tool) for tool in old.get("tools") or []]
        if old_tools != result.tools_started:
            lines.append(
                f"[工具漂移] {result.case_id}：{old_tools or '[]'} -> {result.tools_started or '[]'}"
            )
    return lines


# ---------------------------------------------------------------------------
# 报告
# ---------------------------------------------------------------------------


def _usage_line(result: CaseResult) -> str:
    """逐题用量一行。读不到账就说读不到——绝不显示成 0。"""
    if result.usage_error:
        return f"usage: 读账失败（{result.usage_error}）"
    if result.usage is None:
        return "usage: 未回读（--skip-usage）"
    total = result.total_tokens
    tokens = "未知" if total is None else str(total)
    money = result.usage.get("cost_micros_by_currency") or {}
    cost = "、".join(f"{value / 1_000_000:.4f} {currency}" for currency, value in sorted(money.items()))
    unknown = result.usage.get("unknown_entries") or 0
    return (f"usage: tokens={tokens}（有值条目 {result.usage.get('known_entries', 0)} 条，"
            f"未取到用量 {unknown} 条） cost={cost or '未出价'}")


async def attach_usage(client: httpx.AsyncClient, results: List[CaseResult], days: int = LEDGER_DAYS) -> None:
    """跑完后按 request_id 从 P1 账本回读每题用量（只读，不触发上游调用）。

    读不到只记 ``usage_error``，**不影响 PASS/FAIL**：账本不可读是观测问题，
    不是答案问题，把两者混成一个红灯会让评测结论不可信。
    """
    for result in results:
        request_id = result.observed_request_id or result.request_id
        if not request_id:
            continue
        try:
            response = await client.get(
                "/api/usage/cost", params={"days": days, "request_id": request_id}
            )
            response.raise_for_status()
            payload = response.json()
        except Exception as exc:  # 网络/权限/解析失败都如实记下
            result.usage_error = f"{type(exc).__name__}: {exc}"
            continue
        totals = payload.get("totals") or {}
        result.usage = {
            "request_id": payload.get("request_id") or request_id,
            "total_tokens": totals.get("total_tokens"),
            "known_entries": totals.get("known_entries", 0),
            "cost_micros_by_currency": totals.get("cost_micros_by_currency") or {},
            "unknown_entries": payload.get("unknown_entries", 0),
            "by_stage": [
                {"stage": item.get("key"), "total_tokens": item.get("total_tokens"),
                 "entries": item.get("entries")}
                for item in payload.get("by_stage") or []
            ],
        }


def render_text_report(results: List[CaseResult], drift: List[str]) -> str:
    passed = sum(1 for result in results if result.passed)
    lines = ["=== Live Eval Report ===", f"Summary: {passed}/{len(results)} passed", ""]
    for result in results:
        icon = "PASS" if result.passed else "FAIL"
        lines.append(f"[{icon}] {result.case_id} ({result.category})")
        lines.append(f"  status={result.status_code} tools={result.tools_started or '[]'} "
                     f"citations={len(result.citations)} elapsed={result.elapsed_sec:.1f}s")
        lines.append("  " + _usage_line(result))
        preview = " ".join(result.assert_text.split())[:160]
        lines.append(f"  answer: {preview}{'...' if len(result.assert_text.split()) > 30 else ''}")
        for check in result.checks:
            lines.append(f"  + {check}")
        for failure in result.failures:
            lines.append(f"  - {failure}")
        for note in result.term_caliber:
            lines.append(f"  ~ {note}")
        lines.append("")
    if drift:
        lines.append("=== 基线漂移 ===")
        lines.extend(f"  {line}" for line in drift)
    else:
        lines.append("=== 基线漂移 ===\n  （无基线或无上可对比项）")
    return "\n".join(lines)


def build_json_report(results: List[CaseResult], drift: List[str], run_id: str = "") -> Dict[str, Any]:
    """机器可读报告。**只增键**：既有 failures 中文列表逐字保留，供既有消费者继续用。"""
    return {
        "generated_at": datetime.now(timezone.utc).isoformat(timespec="seconds"),
        "run_id": run_id,
        "term_caliber": TERM_CALIBER,
        "summary": {
            "total": len(results),
            "passed": sum(1 for result in results if result.passed),
        },
        "drift": drift,
        "cases": [
            {
                "id": result.case_id,
                "category": result.category,
                "passed": result.passed,
                "status_code": result.status_code,
                "tools": result.tools_started,
                "tool_ends": result.tool_ends,
                "citations": result.citations,
                "answer": result.answer,
                "checks": result.checks,
                "failures": result.failures,
                "elapsed_sec": round(result.elapsed_sec, 2),
                # P2 §4 只增字段
                "request_id": result.request_id,
                "observed_request_id": result.observed_request_id,
                "answer_status": result.answer_status or None,
                "failure_categories": result.failure_categories,
                "term_caliber": result.term_caliber,
                "usage": result.usage,
                "usage_error": result.usage_error,
            }
            for result in results
        ],
    }


# ---------------------------------------------------------------------------
# CLI
# ---------------------------------------------------------------------------


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description="live 评测集 + 模型漂移检查（打真实 /api/chat/stream）")
    parser.add_argument("--base-url", default=os.environ.get("LIVE_EVAL_BASE_URL", DEFAULT_BASE_URL),
                        help=f"后端地址，默认 {DEFAULT_BASE_URL}")
    parser.add_argument("--case", action="append", default=[], help="只跑指定 id 的用例，可多次传")
    parser.add_argument("--token", default=os.environ.get("LIVE_EVAL_TOKEN", ""),
                        help="Bearer token（account/token 模式）")
    parser.add_argument("--username", default=os.environ.get("LIVE_EVAL_USERNAME", ""),
                        help="account 模式登录用户名（与 --password 一起用）")
    parser.add_argument("--password", default=os.environ.get("LIVE_EVAL_PASSWORD", ""),
                        help="account 模式登录密码")
    parser.add_argument("--cases", default=str(DEFAULT_CASES_FILE), help="题库 yml 路径")
    parser.add_argument("--baseline", default=str(DEFAULT_BASELINE_FILE), help="基线 JSON 路径")
    parser.add_argument("--update-baseline", action="store_true", help="把本轮结果写成新基线")
    parser.add_argument("--output", default="", help="机器可读 JSON 报告输出路径")
    parser.add_argument("--timeout", type=float, default=300.0, help="单题总超时秒数（默认 300）")
    parser.add_argument("--run-id", default="", help="本轮 run_id（默认当前 UTC 时间戳；request_id 由它派生）")
    parser.add_argument("--skip-usage", action="store_true",
                        default=os.environ.get("LIVE_EVAL_SKIP_USAGE", "").lower() in ("1", "true", "yes"),
                        help="不回读账本用量（默认回读，按 request_id 归因到题）")
    return parser


async def login(base_url: str, username: str, password: str, timeout: float) -> str:
    """account 模式换 Bearer token；失败直接退出（评测没必要带着错凭据跑）。"""
    async with httpx.AsyncClient(base_url=base_url, timeout=timeout) as client:
        response = await client.post("/api/auth/login", json={"username": username, "password": password})
    if response.status_code != 200:
        raise SystemExit(f"登录失败（{response.status_code}）：{response.text[:200]}")
    return str(response.json()["token"])


def select_cases(cases: List[Dict[str, Any]], case_ids: List[str]) -> List[Dict[str, Any]]:
    if not case_ids:
        return cases
    by_id = {case["id"]: case for case in cases}
    missing = [case_id for case_id in case_ids if case_id not in by_id]
    if missing:
        raise SystemExit(f"未知用例 id：{', '.join(missing)}（可选：{', '.join(sorted(by_id))}）")
    return [by_id[case_id] for case_id in case_ids]


async def run_all(
    base_url: str,
    cases: List[Dict[str, Any]],
    token: Optional[str],
    timeout: float,
    run_id: str = "",
    read_usage: bool = True,
) -> List[CaseResult]:
    results: List[CaseResult] = []
    async with httpx.AsyncClient(base_url=base_url, timeout=timeout) as client:
        for index, case in enumerate(cases, start=1):
            print(f"[{index}/{len(cases)}] running: {case['id']} ({case.get('category', '')})", flush=True)
            result = evaluate_case(case, await run_case(client, case, token, run_id))
            icon = "PASS" if result.passed else "FAIL"
            print(
                f"[{index}/{len(cases)}] {icon} {result.case_id} ({result.elapsed_sec:.1f}s) "
                f"status={result.status_code} tools={result.tools_started or '[]'}",
                flush=True,
            )
            if result.failures:
                print(f"           failures: {'; '.join(result.failures)}", flush=True)
            for note in result.term_caliber:
                print(f"           {note}", flush=True)
            results.append(result)
        if read_usage:
            await attach_usage(client, results)
        else:
            print("（--skip-usage：未回读账本用量）", flush=True)
    return results


async def main() -> int:
    args = build_parser().parse_args()
    cases = select_cases(load_cases(Path(args.cases)), args.case)
    token = args.token or None
    if not token and args.username:
        token = await login(args.base_url, args.username, args.password, args.timeout)

    run_id = args.run_id or datetime.now(timezone.utc).strftime(RUN_ID_FORMAT)
    print(f"run_id={run_id}（request_id 由它派生；换 run_id 才会真实重跑，同 id 会被幂等重放）",
          flush=True)
    results = await run_all(
        args.base_url, cases, token, args.timeout, run_id, read_usage=not args.skip_usage
    )

    baseline_path = Path(args.baseline)
    baseline = load_baseline(baseline_path)
    drift = diff_baseline(baseline, results) if baseline else []
    if args.update_baseline:
        write_baseline(baseline_path, args.base_url, results, previous=baseline)
        print(f"基线已更新：{baseline_path}", flush=True)
    elif not baseline:
        print(f"（无基线文件 {baseline_path}，跳过漂移对比；--update-baseline 可建立）", flush=True)

    print(render_text_report(results, drift), flush=True)
    if args.output:
        Path(args.output).write_text(
            json.dumps(build_json_report(results, drift, run_id), ensure_ascii=False, indent=2) + "\n",
            encoding="utf-8",
        )
        print(f"JSON 报告已写入：{args.output}", flush=True)
    return 0 if all(result.passed for result in results) else 1


# ---------------------------------------------------------------------------
# pytest 入口（live mark，不进默认集）
#
# 每个 yml 用例一个测试，目标与凭据走环境变量（见模块 docstring）。
# 漂移对比只属于 CLI 手工流程，pytest 形态只断言单题通过。
# ---------------------------------------------------------------------------

LIVE_CASES = load_cases(DEFAULT_CASES_FILE)

if pytest is not None:

    @pytest.mark.parametrize("live_case", LIVE_CASES, ids=[case["id"] for case in LIVE_CASES])
    def test_live_eval_case(live_case: Dict[str, Any]) -> None:
        base_url = os.environ.get("LIVE_EVAL_BASE_URL", DEFAULT_BASE_URL)
        token = os.environ.get("LIVE_EVAL_TOKEN") or None
        username = os.environ.get("LIVE_EVAL_USERNAME", "")
        password = os.environ.get("LIVE_EVAL_PASSWORD", "")

        async def _run() -> CaseResult:
            actual_token = token
            if not actual_token and username:
                actual_token = await login(base_url, username, password, timeout=60)
            async with httpx.AsyncClient(base_url=base_url, timeout=300) as client:
                return evaluate_case(live_case, await run_case(client, live_case, actual_token))

        result = asyncio.run(_run())
        assert result.passed, (
            f"live 用例 {result.case_id} 失败：{'；'.join(result.failures)}\n"
            f"失败分类：{result.failure_categories or '[]'}\n"
            f"口径记录：{result.term_caliber or '[]'}\n"
            f"工具序列：{result.tools_started}\n"
            f"答案摘要：{' '.join(result.assert_text.split())[:300]}"
        )


if __name__ == "__main__":
    raise SystemExit(asyncio.run(main()))
