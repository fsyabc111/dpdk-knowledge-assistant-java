"""
Reusable end-to-end chat smoke test.

Usage:
    cd ai_chat_assistant/backend
    .venv/bin/python tests/test_chat_e2e.py
    .venv/bin/python tests/test_chat_e2e.py --base-url http://127.0.0.1:8090

Default mode uses the FastAPI app in-process via ASGI transport, so it works
even when local port binding is restricted. Pass --base-url to hit a running
server over HTTP instead.

Under pytest the same check helpers run as ``live`` tests (real LLM/embedding
network access and an ingested knowledge base required)::

    .venv/bin/python -m pytest tests/test_chat_e2e.py -m live
"""

from __future__ import annotations

import argparse
import asyncio
import json
import sys
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Dict, List, Optional

import httpx
import pytest

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))

from app.config import get_settings
from app.core.llm_client import OpenAICompatibleClient

pytestmark = pytest.mark.live


QUESTIONS = [
    "这个项目的整体运行链路是什么？",
    "traffic_monitor 收到包以后经过哪些模块？",
    "engine_ops_t 是什么？",
    "can_handle 和 process 有什么区别？",
    "什么情况下应该返回 ENGINE_HANDLED？",
    "为什么插件热路径不能做同步 I/O？",
    "HTTP 插件和 DNS 插件的检测思路有什么不同？",
    "如果我要学习 DNS 插件，应该先看哪些文件？",
    "Shadowsocks 这类加密流量为什么更适合概率识别？",
    "当前知识库里有没有说明某个不存在的协议 X？",
]

FOLLOW_UP = "那如果继续深入 DNS 插件，第二步该看什么？"
FALLBACK_MARKERS = (
    "模型生成暂时不可用",
)

DEFAULT_PREVIEW_CHARS = 220


@dataclass
class ChatCheck:
    question: str
    status_code: int
    confidence: str
    citations: List[str]
    fallback: bool
    answer_preview: str
    debug_trace: Dict[str, Any]


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description="Run end-to-end chat smoke tests.")
    parser.add_argument(
        "--base-url",
        default="",
        help="Optional running backend base URL. Omit to use ASGI in-process transport.",
    )
    parser.add_argument(
        "--show-answer-chars",
        type=int,
        default=220,
        help="Number of answer characters to keep in the report preview.",
    )
    parser.add_argument(
        "--limit",
        type=int,
        default=0,
        help="Optional limit for how many canned questions to run before the follow-up question.",
    )
    parser.add_argument(
        "--question",
        action="append",
        default=[],
        help="Optional custom question. Pass multiple times to override the canned list.",
    )
    parser.add_argument(
        "--debug-trace",
        action="store_true",
        help="Ask the backend to include per-stage timing data in each response.",
    )
    return parser


def is_fallback_answer(answer: str) -> bool:
    return any(marker in answer for marker in FALLBACK_MARKERS)


async def probe_model_connectivity() -> dict:
    settings = get_settings()
    client = OpenAICompatibleClient(settings)
    report = {
        "llm": {"ok": False, "detail": ""},
        "embedding": {"ok": False, "detail": ""},
    }

    try:
        text = await client.chat([{"role": "user", "content": "Reply with OK"}])
        report["llm"] = {"ok": True, "detail": text[:80]}
    except Exception as exc:  # pragma: no cover - diagnostic path
        report["llm"] = {"ok": False, "detail": f"{type(exc).__name__}: {exc}"}

    try:
        vectors = await client.embed(["ok"])
        dims = len(vectors[0]) if vectors and vectors[0] else 0
        report["embedding"] = {"ok": True, "detail": f"dim={dims}"}
    except Exception as exc:  # pragma: no cover - diagnostic path
        report["embedding"] = {"ok": False, "detail": f"{type(exc).__name__}: {exc}"}

    return report


async def build_client(base_url: str) -> tuple[httpx.AsyncClient, Optional[object]]:
    if base_url:
        return httpx.AsyncClient(base_url=base_url, timeout=120), None

    from app.main import app

    transport = httpx.ASGITransport(app=app)
    client = httpx.AsyncClient(transport=transport, base_url="http://testserver", timeout=120)
    return client, transport


async def run_chat_checks(
    base_url: str,
    preview_chars: int,
    questions: List[str],
    debug_trace: bool,
) -> List[ChatCheck]:
    client, _ = await build_client(base_url)
    results: List[ChatCheck] = []
    session_id: Optional[str] = None

    try:
        for question in questions:
            response = await client.post(
                "/api/chat",
                json={
                    "session_id": session_id,
                    "message": question,
                    "context": {"page": "assistant", "debug_trace": debug_trace},
                },
            )
            data = response.json()
            session_id = data.get("session_id", session_id)
            answer = data.get("answer", "")
            results.append(
                ChatCheck(
                    question=question,
                    status_code=response.status_code,
                    confidence=data.get("confidence", ""),
                    citations=[item.get("source_path", "") for item in data.get("citations", [])],
                    fallback=is_fallback_answer(answer),
                    answer_preview=answer[:preview_chars].replace("\n", " "),
                    debug_trace=data.get("debug_trace") or {},
                )
            )
    finally:
        await client.aclose()

    return results


def render_report(connectivity: dict, checks: List[ChatCheck]) -> str:
    fallback_count = sum(1 for item in checks if item.fallback)
    lines = [
        "=== Model Connectivity ===",
        json.dumps(connectivity, ensure_ascii=False, indent=2),
        "",
        "=== Chat Checks ===",
    ]
    for item in checks:
        citation_preview = ", ".join(item.citations[:3]) if item.citations else "<none>"
        lines.extend(
            [
                f"Q: {item.question}",
                f"  status={item.status_code} confidence={item.confidence} fallback={item.fallback}",
                f"  citations={citation_preview}",
                f"  answer={item.answer_preview}",
            ]
        )
        if item.debug_trace:
            stages = item.debug_trace.get("stages_ms", {})
            lines.append(
                "  trace="
                f"total:{item.debug_trace.get('total_ms', 0)}ms "
                f"retrieval:{stages.get('retrieval_total_ms', 0)}ms "
                f"rewrite:{stages.get('rewrite_ms', 0)}ms "
                f"embed:{stages.get('embedding_ms', 0)}ms "
                f"answer:{stages.get('answer_generation_ms', 0)}ms"
            )
        lines.append("")
    lines.append(f"Summary: {len(checks) - fallback_count}/{len(checks)} answers used the real LLM path.")
    return "\n".join(lines)


def default_questions(limit: int = 0, custom: Optional[List[str]] = None) -> List[str]:
    """Question list used by both the CLI and the pytest checks.

    Custom questions replace the canned list; the follow-up question is only
    appended when running the canned list (it depends on session continuity).
    """
    questions = list(custom) if custom else list(QUESTIONS)
    if limit > 0:
        questions = questions[:limit]
    if not custom:
        questions.append(FOLLOW_UP)
    return questions


# ---------------------------------------------------------------------------
# pytest checks (live): same helpers as the CLI, one assertion concern each.
# ---------------------------------------------------------------------------


@pytest.fixture(scope="module")
def connectivity() -> dict:
    return asyncio.run(probe_model_connectivity())


@pytest.fixture(scope="module")
def chat_checks() -> List[ChatCheck]:
    return asyncio.run(
        run_chat_checks("", DEFAULT_PREVIEW_CHARS, default_questions(), debug_trace=True)
    )


def test_llm_connectivity(connectivity):
    assert connectivity["llm"]["ok"], connectivity["llm"]["detail"]


def test_embedding_connectivity(connectivity):
    assert connectivity["embedding"]["ok"], connectivity["embedding"]["detail"]


def test_every_question_gets_a_response(chat_checks):
    assert len(chat_checks) == len(QUESTIONS) + 1


def test_all_questions_return_200(chat_checks):
    failures = [
        f"{item.question} -> {item.status_code}"
        for item in chat_checks
        if item.status_code != 200
    ]
    assert not failures, "; ".join(failures)


def test_no_fallback_answers(chat_checks):
    fallbacks = [item.question for item in chat_checks if item.fallback]
    assert not fallbacks, "fallback answers for: " + "; ".join(fallbacks)


# ---------------------------------------------------------------------------
# argparse CLI (ops tool) — behavior unchanged.
# ---------------------------------------------------------------------------


async def main() -> int:
    args = build_parser().parse_args()
    questions = default_questions(limit=args.limit, custom=args.question or None)
    connectivity = await probe_model_connectivity()
    checks = await run_chat_checks(args.base_url, args.show_answer_chars, questions, args.debug_trace)
    print(render_report(connectivity, checks))

    if not connectivity["llm"]["ok"] or not connectivity["embedding"]["ok"]:
        return 2
    if any(item.status_code != 200 for item in checks):
        return 3
    if any(item.fallback for item in checks):
        return 4
    return 0


if __name__ == "__main__":
    raise SystemExit(asyncio.run(main()))
