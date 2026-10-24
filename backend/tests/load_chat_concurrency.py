"""
Lightweight concurrency/load test for the chat endpoint.

Examples:
    cd ai_chat_assistant/backend
    .venv/bin/python tests/load_chat_concurrency.py
    .venv/bin/python tests/load_chat_concurrency.py --levels 1,5,10,20,30 --requests-per-level 30
    .venv/bin/python tests/load_chat_concurrency.py --base-url http://127.0.0.1:8090
    # 混合负载：70% 知识问答 + 30% 插件评测（account 模式登录取 token）
    .venv/bin/python tests/load_chat_concurrency.py --mix grading --grading-ratio 0.3 \
        --username 2024001 --password 'xxx'
    # 或直接给静态 Bearer token（token 模式 / 已有账号 token）
    .venv/bin/python tests/load_chat_concurrency.py --mix grading --token 'xxx'
"""

from __future__ import annotations

import argparse
import asyncio
import json
import random
import statistics
import sys
import time
from dataclasses import asdict, dataclass
from pathlib import Path
from typing import Dict, List, Optional

import httpx

try:  # 生产镜像不含 pytest：CLI 形态（python 直跑）不需要它，仅 pytest 形态需要
    import pytest
except ModuleNotFoundError:  # pragma: no cover - 仅在无 pytest 的容器内 CLI 运行命中
    pytest = None  # type: ignore[assignment]

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))

from app.config import get_settings
from app.core.llm_client import OpenAICompatibleClient
from app.db import close_database_provider

QUESTION_POOL = [
    "engine_ops_t 是什么？",
    "can_handle 和 process 有什么区别？",
    "什么情况下应该返回 ENGINE_HANDLED？",
    "如果我要学习 DNS 插件，应该先看哪些文件？",
    "HTTP 插件和 DNS 插件的检测思路有什么不同？",
    "Shadowsocks 这类加密流量为什么更适合概率识别？",
]

FALLBACK_MARKERS = (
    "模型生成暂时不可用",
)

# 评测配额耗尽的信号：HTTP 429，或 agent 把 grade_plugin 工具的
# quota_exceeded 错误转述进 200 回答（detail 含「配额已用完」，见 agent/tools.py）。
QUOTA_MARKERS = (
    "配额已用完",
    "quota_exceeded",
    "quota exceeded",
)

# 压测用插件源码夹具：(协议名, C 源码)。作为 multipart 附件上传，
# 文件名按 chat.py 的约定「学号_姓名_协议.c」构造（第三段作 protocol_hint）。
_DNS_PLUGIN_OK = """/* 压测用极简 DNS 插件骨架：结构对齐 engine_template，预期可编译。 */
#include <stdbool.h>
#include <stdint.h>
#include <stdlib.h>
#include "../engine.h"

static int dns_init(engine_ctx_t *ctx)
{
    ctx->private_data = NULL;
    return ENGINE_OK;
}

static bool dns_can_handle(engine_ctx_t *ctx,
                           const session_entry_t *session,
                           const parsed_packet_t *pkt)
{
    (void)ctx;
    (void)session;
    if (!pkt || !pkt->payload || pkt->payload_len < 12) return false;

    /* DNS 头部固定 12 字节，这里只做轻量筛选。 */
    return true;
}

static int dns_process(engine_ctx_t *ctx,
                       session_entry_t *session,
                       const parsed_packet_t *pkt)
{
    (void)ctx;
    (void)session;
    if (!pkt || !pkt->payload || pkt->payload_len < 12) return ENGINE_CONTINUE;

    return ENGINE_HANDLED;
}

static void dns_cleanup(engine_ctx_t *ctx)
{
    if (ctx) ctx->private_data = NULL;
}

static const engine_ops_t dns_ops = {
    .name = "dns",
    .protocol_id = 0,
    .priority = 200,
    .init = dns_init,
    .can_handle = dns_can_handle,
    .process = dns_process,
    .cleanup = dns_cleanup,
    .get_stats = NULL,
    .reset_stats = NULL,
};

const engine_ops_t* engine_get_ops(void)
{
    return &dns_ops;
}
"""

_DNS_PLUGIN_BROKEN = """/* 压测用故意带错的插件：缺少分号且符号未定义，预期编译失败。 */
#include <stdbool.h>
#include "../engine.h"

const engine_ops_t* engine_get_ops(void)
{
    return undefined_symbol_call()
}
"""

PLUGIN_FIXTURES = [
    ("dns", _DNS_PLUGIN_OK),
    ("dns", _DNS_PLUGIN_BROKEN),
]

# 流量类型序列的固定随机种子：保证混合比例精确且每次运行可复现。
_TRAFFIC_PLAN_SEED = 1337

# Every test in this module drives the real chat endpoint against the real
# LLM/embedding providers configured in .env — never run them in unit CI.
if pytest is not None:
    pytestmark = pytest.mark.live


@dataclass
class RequestResult:
    ok: bool
    status_code: int
    latency_ms: float
    fallback: bool
    confidence: str
    citations: int
    trace_total_ms: float = 0.0
    retrieval_ms: float = 0.0
    answer_ms: float = 0.0
    error: str = ""
    traffic: str = "chat"  # chat=知识问答，grading=插件评测
    quota_exceeded: bool = False  # 评测配额耗尽（不计入 error 口径）


@dataclass
class LevelSummary:
    concurrency: int
    requests: int
    wall_ms: float
    throughput_rps: float
    ok_count: int
    fallback_count: int
    error_count: int
    avg_ms: float
    p50_ms: float
    p95_ms: float
    max_ms: float
    avg_trace_total_ms: float
    avg_retrieval_ms: float
    avg_answer_ms: float
    p95_answer_ms: float


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description="Run a lightweight chat concurrency test.")
    parser.add_argument("--base-url", default="", help="Optional running backend URL. Omit to use ASGI transport.")
    parser.add_argument("--levels", default="1,5,10,20,30", help="Comma-separated concurrency levels.")
    parser.add_argument("--requests-per-level", type=int, default=30, help="Requests to send at each concurrency level.")
    parser.add_argument("--timeout", type=float, default=180.0, help="Per-request timeout in seconds.")
    parser.add_argument("--debug-trace", action="store_true", help="Ask the backend to return per-stage timing data.")
    parser.add_argument(
        "--cold-cache",
        action="store_true",
        help="Make every question unique to measure uncached retrieval and generation.",
    )
    parser.add_argument(
        "--mix",
        choices=["chat", "grading"],
        default="chat",
        help="Traffic mix: 'chat' 纯知识问答（默认，行为与旧版一致）；'grading' 混入插件评测流量。",
    )
    parser.add_argument(
        "--grading-ratio",
        type=float,
        default=0.3,
        help="--mix grading 时评测流量占比（0~1，默认 0.3）。",
    )
    parser.add_argument("--username", default="", help="account 模式登录用户名（学号），与 --password 搭配。")
    parser.add_argument("--password", default="", help="account 模式登录密码。")
    parser.add_argument("--token", default="", help="直接使用的 Bearer token（优先于 --username/--password）。")
    return parser


def is_fallback(answer: str) -> bool:
    return any(marker in answer for marker in FALLBACK_MARKERS)


def is_quota_exceeded(status_code: int, answer: str) -> bool:
    """评测配额耗尽：HTTP 429（每日配额/限流）或回答中带有配额提示。"""
    return status_code == 429 or any(marker in answer for marker in QUOTA_MARKERS)


async def build_client(base_url: str, timeout: float, token: str = "") -> httpx.AsyncClient:
    headers = {"Authorization": f"Bearer {token}"} if token else None
    if base_url:
        return httpx.AsyncClient(base_url=base_url, timeout=timeout, headers=headers)

    from app.main import app

    transport = httpx.ASGITransport(app=app)
    return httpx.AsyncClient(transport=transport, base_url="http://testserver", timeout=timeout, headers=headers)


async def login_for_token(client: httpx.AsyncClient, username: str, password: str) -> str:
    """account 模式登录换取 Bearer token（POST /api/auth/login）。"""
    response = await client.post("/api/auth/login", json={"username": username, "password": password})
    if response.status_code != 200:
        raise RuntimeError(f"登录失败（HTTP {response.status_code}）：{response.text[:200]}")
    return str(response.json()["token"])


def build_traffic_plan(requests: int, mix: str, grading_ratio: float) -> List[str]:
    """生成每个请求的流量类型序列。

    混合模式下用固定种子 shuffle，保证评测比例精确、两种流量交织且可复现；
    chat 模式下恒为纯问答，行为与旧版一致。
    """
    if mix != "grading":
        return ["chat"] * requests
    grading_count = round(requests * grading_ratio)
    plan = ["chat"] * (requests - grading_count) + ["grading"] * grading_count
    random.Random(_TRAFFIC_PLAN_SEED).shuffle(plan)
    return plan


async def probe_model_connectivity() -> Dict[str, Dict[str, str]]:
    settings = get_settings()
    client = OpenAICompatibleClient(settings)
    report = {
        "llm": {"ok": False, "detail": ""},
        "embedding": {"ok": False, "detail": ""},
    }
    try:
        text = await client.chat([{"role": "user", "content": "Reply with exactly OK"}])
        report["llm"] = {"ok": True, "detail": text[:80]}
    except Exception as exc:
        report["llm"] = {"ok": False, "detail": f"{type(exc).__name__}: {exc}"}
    try:
        vectors = await client.embed(["OK"])
        dims = len(vectors[0]) if vectors and vectors[0] else 0
        report["embedding"] = {"ok": True, "detail": f"dim={dims}"}
    except Exception as exc:
        report["embedding"] = {"ok": False, "detail": f"{type(exc).__name__}: {exc}"}
    return report


def percentile(values: List[float], pct: float) -> float:
    if not values:
        return 0.0
    if len(values) == 1:
        return values[0]
    ordered = sorted(values)
    index = (len(ordered) - 1) * pct
    lower = int(index)
    upper = min(lower + 1, len(ordered) - 1)
    weight = index - lower
    return ordered[lower] * (1 - weight) + ordered[upper] * weight


def _result_from_response(
    response: httpx.Response,
    latency_ms: float,
    traffic: str,
) -> RequestResult:
    """把 /api/chat 响应解析为 RequestResult（问答与评测流量共用）。"""
    payload = response.json()
    answer = payload.get("answer", "")
    trace = payload.get("debug_trace") or {}
    stages = trace.get("stages_ms", {})
    quota = traffic == "grading" and is_quota_exceeded(response.status_code, answer)
    # 配额耗尽是预期内的背压信号，不计入 error 口径（单独统计）。
    error = "" if response.status_code == 200 or quota else answer[:200]
    if response.status_code != 200 and not answer:
        detail = payload.get("detail", "")
        error = "" if quota else str(detail)[:200]
    return RequestResult(
        ok=response.status_code == 200,
        status_code=response.status_code,
        latency_ms=latency_ms,
        fallback=is_fallback(answer),
        confidence=payload.get("confidence", ""),
        citations=len(payload.get("citations", [])),
        trace_total_ms=float(trace.get("total_ms") or 0.0),
        retrieval_ms=float(stages.get("retrieval_total_ms") or 0.0),
        answer_ms=float(stages.get("answer_generation_ms") or 0.0),
        error=error,
        traffic=traffic,
        quota_exceeded=quota,
    )


def _result_from_exception(exc: Exception, latency_ms: float, traffic: str) -> RequestResult:
    return RequestResult(
        ok=False,
        status_code=0,
        latency_ms=latency_ms,
        fallback=False,
        confidence="",
        citations=0,
        error=f"{type(exc).__name__}: {exc}",
        traffic=traffic,
    )


async def single_request(client: httpx.AsyncClient, question: str, debug_trace: bool) -> RequestResult:
    started = time.perf_counter()
    try:
        response = await client.post(
            "/api/chat",
            json={"message": question, "context": {"page": "assistant", "debug_trace": debug_trace}},
        )
        latency_ms = (time.perf_counter() - started) * 1000
        return _result_from_response(response, latency_ms, traffic="chat")
    except Exception as exc:
        latency_ms = (time.perf_counter() - started) * 1000
        return _result_from_exception(exc, latency_ms, traffic="chat")


async def single_grading_request(client: httpx.AsyncClient, index: int, debug_trace: bool) -> RequestResult:
    """评测流量：multipart 上传内置插件源码。

    multipart 契约（见 chat.py 的 _parse_chat_request）：表单字段 message /
    session_id / context（JSON 字符串），文件字段名 file；文件名按
    「学号_姓名_协议.c」约定构造，第三段作为 protocol_hint 传给 grade_plugin。
    """
    protocol, source = PLUGIN_FIXTURES[index % len(PLUGIN_FIXTURES)]
    filename = f"loadtest_{index:05d}_{protocol}.c"
    started = time.perf_counter()
    try:
        response = await client.post(
            "/api/chat",
            data={
                "message": f"请评测这个 {protocol} 插件（压测流量）",
                "context": json.dumps({"page": "assistant", "debug_trace": debug_trace}),
            },
            files={"file": (filename, source.encode("utf-8"), "text/x-c")},
        )
        latency_ms = (time.perf_counter() - started) * 1000
        return _result_from_response(response, latency_ms, traffic="grading")
    except Exception as exc:
        latency_ms = (time.perf_counter() - started) * 1000
        return _result_from_exception(exc, latency_ms, traffic="grading")


async def run_level(
    client: httpx.AsyncClient,
    concurrency: int,
    requests: int,
    debug_trace: bool,
    cold_cache: bool = False,
    traffic_plan: Optional[List[str]] = None,
) -> tuple[LevelSummary, List[RequestResult]]:
    semaphore = asyncio.Semaphore(concurrency)
    results: List[Optional[RequestResult]] = [None] * requests
    # 缺省为纯问答流量，保持旧版调用方（含 pytest 冒烟用例）行为不变。
    plan = traffic_plan or ["chat"] * requests

    async def worker(index: int) -> None:
        async with semaphore:
            if plan[index] == "grading":
                results[index] = await single_grading_request(client, index, debug_trace)
                return
            question = QUESTION_POOL[index % len(QUESTION_POOL)]
            if cold_cache:
                question = f"{question}（冷缓存压测请求 {index + 1}）"
            results[index] = await single_request(client, question, debug_trace)

    started = time.perf_counter()
    await asyncio.gather(*(worker(index) for index in range(requests)))
    wall_ms = (time.perf_counter() - started) * 1000
    final = [item for item in results if item is not None]
    latencies = [item.latency_ms for item in final]
    trace_totals = [item.trace_total_ms for item in final if item.trace_total_ms]
    retrieval_times = [item.retrieval_ms for item in final if item.retrieval_ms]
    answer_times = [item.answer_ms for item in final if item.answer_ms]
    summary = LevelSummary(
        concurrency=concurrency,
        requests=requests,
        wall_ms=wall_ms,
        throughput_rps=requests / (wall_ms / 1000) if wall_ms else 0.0,
        ok_count=sum(1 for item in final if item.ok),
        fallback_count=sum(1 for item in final if item.fallback),
        error_count=sum(1 for item in final if item.error),
        avg_ms=statistics.fmean(latencies) if latencies else 0.0,
        p50_ms=percentile(latencies, 0.50),
        p95_ms=percentile(latencies, 0.95),
        max_ms=max(latencies) if latencies else 0.0,
        avg_trace_total_ms=statistics.fmean(trace_totals) if trace_totals else 0.0,
        avg_retrieval_ms=statistics.fmean(retrieval_times) if retrieval_times else 0.0,
        avg_answer_ms=statistics.fmean(answer_times) if answer_times else 0.0,
        p95_answer_ms=percentile(answer_times, 0.95),
    )
    return summary, final


def summarize_traffic(results: List[RequestResult], traffic: str, wall_ms: float) -> Dict[str, object]:
    """按流量类型（chat/grading）分列的吞吐与延迟统计。

    吞吐按该类型请求数 / 整个 level 的墙钟时间计算；配额耗尽单独计数，
    不计入 error_count。
    """
    items = [item for item in results if item.traffic == traffic]
    latencies = [item.latency_ms for item in items]
    return {
        "traffic": traffic,
        "requests": len(items),
        "throughput_rps": len(items) / (wall_ms / 1000) if wall_ms else 0.0,
        "ok_count": sum(1 for item in items if item.ok),
        "error_count": sum(1 for item in items if item.error),
        "quota_exceeded_count": sum(1 for item in items if item.quota_exceeded),
        "avg_ms": statistics.fmean(latencies) if latencies else 0.0,
        "p50_ms": percentile(latencies, 0.50),
        "p95_ms": percentile(latencies, 0.95),
        "max_ms": max(latencies) if latencies else 0.0,
    }


async def main() -> int:
    args = build_parser().parse_args()
    levels = [int(part) for part in args.levels.split(",") if part.strip()]
    if not 0.0 <= args.grading_ratio <= 1.0:
        print("错误：--grading-ratio 必须在 0~1 之间", file=sys.stderr)
        return 4

    mixed = args.mix == "grading"
    token = args.token.strip()
    client = await build_client(args.base_url, args.timeout, token=token)

    # 评测流量需要登录身份（配额按用户计）：优先 --token，其次账号密码登录。
    if mixed and not token:
        if not (args.username and args.password):
            print(
                "错误：--mix grading 需要认证身份，请提供 --token，"
                "或同时提供 --username/--password（account 模式登录取 token）。",
                file=sys.stderr,
            )
            await client.aclose()
            return 4
        try:
            token = await login_for_token(client, args.username, args.password)
        except Exception as exc:
            print(f"错误：登录失败：{exc}", file=sys.stderr)
            await client.aclose()
            return 4
        client.headers["Authorization"] = f"Bearer {token}"

    connectivity = await probe_model_connectivity()
    summaries: List[LevelSummary] = []
    # 每个 level 的分流量统计（仅混合模式有实际意义）。
    traffic_breakdowns: List[Dict[str, object]] = []

    try:
        for concurrency in levels:
            plan = build_traffic_plan(args.requests_per_level, args.mix, args.grading_ratio)
            summary, results = await run_level(
                client,
                concurrency,
                args.requests_per_level,
                args.debug_trace,
                cold_cache=args.cold_cache,
                traffic_plan=plan,
            )
            summaries.append(summary)
            print(json.dumps({"level_complete": asdict(summary)}, ensure_ascii=False), flush=True)
            if mixed:
                breakdown = {
                    "concurrency": concurrency,
                    "chat": summarize_traffic(results, "chat", summary.wall_ms),
                    "grading": summarize_traffic(results, "grading", summary.wall_ms),
                }
                traffic_breakdowns.append(breakdown)
                print(json.dumps({"level_traffic": breakdown}, ensure_ascii=False), flush=True)
    finally:
        await client.aclose()
        await OpenAICompatibleClient.close_clients()
        close_database_provider()

    print("=== Model Connectivity ===")
    print(json.dumps(connectivity, ensure_ascii=False, indent=2))
    print("\n=== Concurrency Results ===")
    for summary in summaries:
        print(json.dumps(asdict(summary), ensure_ascii=False))

    if mixed:
        print("\n=== Per-Traffic Breakdown ===")
        for breakdown in traffic_breakdowns:
            print(json.dumps(breakdown, ensure_ascii=False))
        quota_total = sum(
            breakdown["grading"]["quota_exceeded_count"] for breakdown in traffic_breakdowns
        )
        if quota_total:
            print(f"\n注意：评测配额耗尽 {quota_total} 次（quota_exceeded，未计入 error_count）。")

    if not connectivity["llm"]["ok"] or not connectivity["embedding"]["ok"]:
        return 2
    if any(summary.error_count > 0 for summary in summaries):
        return 3
    return 0


if pytest is not None:  # pytest 形态入口（live）；CLI 直跑无 pytest 时跳过定义

    @pytest.fixture
    async def asgi_client():
        """In-process ASGI client against the real app and providers."""
        client = await build_client("", timeout=180.0)
        try:
            yield client
        finally:
            await client.aclose()
            await OpenAICompatibleClient.close_clients()

    async def test_model_connectivity():
        report = await probe_model_connectivity()
        await OpenAICompatibleClient.close_clients()
        assert report["llm"]["ok"], report["llm"]["detail"]
        assert report["embedding"]["ok"], report["embedding"]["detail"]

    async def test_chat_concurrency_smoke(asgi_client):
        """Minimal load smoke: one request at concurrency 1 must succeed."""
        summary, _ = await run_level(asgi_client, concurrency=1, requests=1, debug_trace=False)
        assert summary.error_count == 0
        assert summary.ok_count == 1


if __name__ == "__main__":
    raise SystemExit(asyncio.run(main()))
