"""
Answer quality regression test for the knowledge assistant.

This test asks real questions, prints the answer and citations, then checks a
small deterministic rubric for each question. It is intentionally stricter than
the smoke test in test_chat_e2e.py: the goal is to catch retrieval or prompting
regressions that still return HTTP 200.

Usage:
    cd ai_chat_assistant/backend
    .venv/bin/python tests/test_answer_quality.py
    .venv/bin/python tests/test_answer_quality.py --case engine_ops
    .venv/bin/python tests/test_answer_quality.py --case engine_ops --show-retrieval
    .venv/bin/python tests/test_answer_quality.py --question "engine_ops_t 是什么？" --expect engine_ops_t --expect can_handle

Use --base-url to test a running server instead of the in-process ASGI app.

Under pytest each built-in QualityCase runs as an independent test, all marked
``live`` because they need a real LLM/embedding backend and an ingested
knowledge base::

    .venv/bin/python -m pytest tests/test_answer_quality.py -q -m live
"""

from __future__ import annotations

import argparse
import asyncio
import json
import sys
import time
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any, Dict, List, Optional

import httpx
import pytest

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))

pytestmark = pytest.mark.live


FALLBACK_MARKERS = (
    "模型生成暂时不可用",
    "当前知识库还没有检索到足够依据",
)


@dataclass
class QualityCase:
    id: str
    question: str
    required_terms: List[str] = field(default_factory=list)
    any_terms: List[List[str]] = field(default_factory=list)
    expected_citation_fragments: List[str] = field(default_factory=list)
    forbidden_terms: List[str] = field(default_factory=list)
    min_citations: int = 1


@dataclass
class CaseResult:
    case: QualityCase
    status_code: int
    confidence: str
    answer: str
    citations: List[str]
    checks: List[str]
    failures: List[str]
    retrieval_debug: List[Dict[str, Any]] = field(default_factory=list)
    debug_trace: Dict[str, Any] = field(default_factory=dict)

    @property
    def passed(self) -> bool:
        return self.status_code == 200 and not self.failures


QUALITY_CASES = [
    QualityCase(
        id="topic_state_machine",
        question="哪些协议会用到状态机",
        required_terms=["状态机"],
        any_terms=[
            ["POP3", "SMTP", "SSH", "OpenVPN"],
            ["命令", "阶段", "握手", "状态"],
        ],
        expected_citation_fragments=["engine_pop3"],
    ),
    QualityCase(
        id="topic_verdict",
        question="verdict 引擎怎么给事件打分",
        required_terms=["verdict"],
        any_terms=[
            ["打分", "score", "评分"],
            ["权重", "confidence", "阈值"],
        ],
        expected_citation_fragments=["verdict_engine"],
    ),
    QualityCase(
        id="topic_storage_clickhouse",
        question="ClickHouse 数据是怎么写入的",
        required_terms=["ClickHouse"],
        any_terms=[
            ["写入", "insert", "flush"],
            ["storage_engine", "ch_storage", "JSONEachRow"],
        ],
        expected_citation_fragments=["storage_engine"],
    ),
    QualityCase(
        id="topic_wireguard",
        question="WireGuard VPN 检测原理",
        required_terms=["WireGuard"],
        any_terms=[
            ["UDP", "handshake", "握手"],
            ["can_handle", "process", "检测"],
        ],
        expected_citation_fragments=["engine_wireguard"],
    ),
    QualityCase(
        id="filename_ftp_can_handle",
        question="engine_ftp.c 的 can_handle 函数",
        required_terms=["FTP", "can_handle"],
        any_terms=[
            ["端口", "21", "payload"],
            ["快速", "筛选", "检测"],
        ],
        expected_citation_fragments=["engine_ftp.c"],
    ),
    QualityCase(
        id="filename_session_struct",
        question="session.h 里怎么定义流表结构的",
        required_terms=["session"],
        any_terms=[
            ["session_entry_t", "session_entry"],
            ["typedef struct", "结构体", "字段"],
            ["五元组", "flow", "hash"],
        ],
        expected_citation_fragments=["session.h"],
    ),
    QualityCase(
        id="dedup_http_https_tls",
        question="HTTP HTTPS TLS 三个插件有什么区别",
        required_terms=["HTTP", "HTTPS", "TLS"],
        any_terms=[
            ["明文", "加密", "证书"],
            ["SNI", "JA3", "ClientHello"],
            ["端口", "payload", "握手"],
        ],
        expected_citation_fragments=["http"],
    ),
    QualityCase(
        id="proto_dns",
        question="DNS 协议检测怎么解析域名",
        required_terms=["DNS"],
        any_terms=[
            ["qname", "域名", "查询名"],
            ["UDP", "53"],
            ["dns_ext", "dns_ext_data_t", "answer"],
        ],
        expected_citation_fragments=["engine_dns"],
    ),
    QualityCase(
        id="proto_quic",
        question="QUIC 协议怎么检测版本协商",
        required_terms=["QUIC"],
        any_terms=[
            ["version", "版本协商", "Version Negotiation"],
            ["UDP", "long header", "header"],
        ],
        expected_citation_fragments=["engine_quic"],
    ),
    QualityCase(
        id="proto_dtls_tls",
        question="DTLS 和 TLS 检测逻辑有什么不同",
        required_terms=["DTLS", "TLS"],
        any_terms=[
            ["UDP", "TCP"],
            ["record", "握手", "ClientHello"],
            ["证书", "SNI", "JA3"],
        ],
        expected_citation_fragments=["engine_dtls"],
    ),
    QualityCase(
        id="proto_rdp",
        question="RDP 远程桌面检测",
        required_terms=["RDP"],
        any_terms=[
            ["3389", "远程桌面"],
            ["TPKT", "X.224", "握手"],
            ["can_handle", "process"],
        ],
        expected_citation_fragments=["engine_rdp"],
    ),
    QualityCase(
        id="proto_shadowsocks",
        question="Shadowsocks 加密代理检测",
        required_terms=["Shadowsocks"],
        any_terms=[
            ["加密", "代理"],
            ["概率", "熵", "heuristic", "特征"],
            ["UDP", "TCP"],
        ],
        expected_citation_fragments=["engine_shadowsocks"],
    ),
    QualityCase(
        id="proto_v2ray",
        question="V2Ray 协议检测",
        required_terms=["V2Ray"],
        any_terms=[
            ["代理", "隧道"],
            ["TLS", "WebSocket", "VMess"],
            ["can_handle", "process", "检测"],
        ],
        expected_citation_fragments=["engine_v2ray"],
    ),
    QualityCase(
        id="proto_imap",
        question="IMAP 邮件检测",
        required_terms=["IMAP"],
        any_terms=[
            ["邮件", "mail"],
            ["143", "993", "端口"],
            ["命令", "状态", "LOGIN"],
        ],
        expected_citation_fragments=["engine_imap"],
    ),
    QualityCase(
        id="proto_smb",
        question="SMB 文件共享检测",
        required_terms=["SMB"],
        any_terms=[
            ["文件共享", "445"],
            ["Negotiate", "session", "协议"],
            ["can_handle", "process", "检测"],
        ],
        expected_citation_fragments=["engine_smb"],
    ),
    QualityCase(
        id="proto_openvpn",
        question="OpenVPN can_handle UDP TCP",
        required_terms=["OpenVPN", "can_handle"],
        any_terms=[
            ["UDP", "TCP"],
            ["opcode", "packet", "握手"],
            ["1194", "端口", "payload"],
        ],
        expected_citation_fragments=["engine_openvpn"],
    ),
    QualityCase(
        id="proto_ipsec",
        question="IPsec IKE 协商检测",
        required_terms=["IPsec"],
        any_terms=[
            ["IKE", "协商"],
            ["500", "4500", "UDP"],
            ["SA", "payload", "exchange"],
        ],
        expected_citation_fragments=["engine_ipsecvpn"],
    ),
    QualityCase(
        id="subsys_ti",
        question="威胁情报怎么加载匹配",
        required_terms=["威胁情报"],
        any_terms=[
            ["加载", "匹配"],
            ["domain", "ip", "hash"],
            ["ti_intel_manager", "engine_ti", "mmap"],
        ],
        expected_citation_fragments=["ti_intel_manager"],
    ),
    QualityCase(
        id="subsys_cert",
        question="TLS 证书提取 SNI SAN",
        required_terms=["TLS"],
        any_terms=[
            ["证书", "cert"],
            ["SNI", "SAN"],
            ["ClientHello", "certificate", "解析"],
        ],
        expected_citation_fragments=["cert_extractor"],
    ),
    QualityCase(
        id="engine_ops",
        question="engine_ops_t 是什么？",
        required_terms=["engine_ops_t", "can_handle", "process"],
        any_terms=[
            ["init", "cleanup"],
            ["插件", "engine"],
            ["回调", "函数指针"],
        ],
        expected_citation_fragments=["engine.h"],
        min_citations=1,
    ),
]


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description="Run answer quality regression checks.")
    parser.add_argument(
        "--base-url",
        default="",
        help="Optional running backend base URL. Omit to use ASGI in-process transport.",
    )
    parser.add_argument(
        "--case",
        action="append",
        default=[],
        help="Run one or more built-in cases by id.",
    )
    parser.add_argument(
        "--question",
        default="",
        help="Run one custom question instead of built-in cases.",
    )
    parser.add_argument(
        "--expect",
        action="append",
        default=[],
        help="Required term for a custom question. Can be passed multiple times.",
    )
    parser.add_argument(
        "--expect-citation",
        action="append",
        default=[],
        help="Expected citation path fragment for a custom question.",
    )
    parser.add_argument(
        "--json",
        action="store_true",
        help="Print machine-readable JSON report instead of the text report.",
    )
    parser.add_argument(
        "--show-retrieval",
        action="store_true",
        help="Include local retrieval top chunks in the report for debugging failures.",
    )
    parser.add_argument(
        "--debug-trace",
        action="store_true",
        help="Ask the backend to include per-stage timing data in each response.",
    )
    parser.add_argument(
        "--retrieval-limit",
        type=int,
        default=8,
        help="Number of local retrieval chunks to show with --show-retrieval.",
    )
    return parser


async def build_client(base_url: str) -> httpx.AsyncClient:
    if base_url:
        return httpx.AsyncClient(base_url=base_url, timeout=180)

    from app.main import app

    transport = httpx.ASGITransport(app=app)
    return httpx.AsyncClient(transport=transport, base_url="http://testserver", timeout=180)


def is_fallback_answer(answer: str) -> bool:
    return any(marker in answer for marker in FALLBACK_MARKERS)


def contains_term(text: str, term: str) -> bool:
    return term.lower() in text.lower()


def citation_matches(citations: List[str], fragment: str) -> bool:
    return any(fragment.lower() in citation.lower() for citation in citations)


def compact_text(text: str, limit: int = 180) -> str:
    compacted = " ".join(text.split())
    return compacted if len(compacted) <= limit else compacted[: limit - 3] + "..."


async def collect_retrieval_debug(case: QualityCase, limit: int) -> List[Dict[str, Any]]:
    # 唯一检索数据面：LlamaIndex 发布版本适配器。
    from app.services.llama_index_service import LlamaIndexRetrievalAdapter

    chunks = await LlamaIndexRetrievalAdapter().search(case.question, limit=max(1, limit))
    return [
        {
            "rank": index,
            "chunk_id": chunk.id,
            "source_path": chunk.source_path,
            "section": chunk.section,
            "score": round(chunk.score, 4),
            "preview": compact_text(chunk.content),
        }
        for index, chunk in enumerate(chunks, start=1)
    ]


def evaluate_case(
    case: QualityCase,
    status_code: int,
    data: Dict[str, Any],
    retrieval_debug: Optional[List[Dict[str, Any]]] = None,
) -> CaseResult:
    answer = data.get("answer", "")
    citations = [item.get("source_path", "") for item in data.get("citations", [])]
    confidence = data.get("confidence", "")
    checks: List[str] = []
    failures: List[str] = []

    if status_code != 200:
        failures.append(f"HTTP status is {status_code}, expected 200")
    else:
        checks.append("HTTP status is 200")

    if is_fallback_answer(answer):
        failures.append("answer used fallback path")
    else:
        checks.append("answer is not fallback")

    if len(citations) < case.min_citations:
        failures.append(f"citation count {len(citations)} < required {case.min_citations}")
    else:
        checks.append(f"citation count {len(citations)} >= {case.min_citations}")

    for term in case.required_terms:
        if contains_term(answer, term):
            checks.append(f"required term found: {term}")
        else:
            failures.append(f"required term missing: {term}")

    for options in case.any_terms:
        if any(contains_term(answer, term) for term in options):
            checks.append(f"one of terms found: {options}")
        else:
            failures.append(f"none of terms found: {options}")

    for fragment in case.expected_citation_fragments:
        if citation_matches(citations, fragment):
            checks.append(f"citation fragment found: {fragment}")
        else:
            failures.append(f"citation fragment missing: {fragment}")

    for term in case.forbidden_terms:
        if contains_term(answer, term):
            failures.append(f"forbidden term found: {term}")
        else:
            checks.append(f"forbidden term absent: {term}")

    return CaseResult(
        case=case,
        status_code=status_code,
        confidence=confidence,
        answer=answer,
        citations=citations,
        checks=checks,
        failures=failures,
        retrieval_debug=retrieval_debug or [],
        debug_trace=data.get("debug_trace") or {},
    )


async def run_case(
    client: httpx.AsyncClient,
    case: QualityCase,
    show_retrieval: bool,
    retrieval_limit: int,
    debug_trace: bool,
) -> CaseResult:
    response = await client.post(
        "/api/chat",
        json={
            "session_id": None,
            "message": case.question,
            "context": {"page": "answer_quality_test", "case_id": case.id, "debug_trace": debug_trace},
        },
    )
    try:
        data = response.json()
    except json.JSONDecodeError:
        data = {"answer": response.text, "citations": [], "confidence": ""}
    retrieval_debug: List[Dict[str, Any]] = []
    if show_retrieval:
        try:
            retrieval_debug = await collect_retrieval_debug(case, retrieval_limit)
        except Exception as exc:
            retrieval_debug = [{"error": str(exc)}]
    return evaluate_case(case, response.status_code, data, retrieval_debug)


def select_cases(args: argparse.Namespace) -> List[QualityCase]:
    if args.question:
        return [
            QualityCase(
                id="custom",
                question=args.question,
                required_terms=args.expect,
                expected_citation_fragments=args.expect_citation,
                min_citations=1,
            )
        ]

    if not args.case:
        return list(QUALITY_CASES)

    by_id = {case.id: case for case in QUALITY_CASES}
    missing = [case_id for case_id in args.case if case_id not in by_id]
    if missing:
        raise SystemExit(f"Unknown case id(s): {', '.join(missing)}")
    return [by_id[case_id] for case_id in args.case]


def render_text_report(results: List[CaseResult]) -> str:
    lines: List[str] = []
    passed = sum(1 for result in results if result.passed)
    lines.append("=== Answer Quality Report ===")
    lines.append(f"Summary: {passed}/{len(results)} passed")
    lines.append("")

    for result in results:
        icon = "PASS" if result.passed else "FAIL"
        lines.extend(
            [
                f"[{icon}] {result.case.id}",
                f"Q: {result.case.question}",
                f"status={result.status_code} confidence={result.confidence}",
                f"citations={', '.join(result.citations) if result.citations else '<none>'}",
                "",
                "Answer:",
                result.answer.strip() or "<empty>",
                "",
                "Checks:",
            ]
        )
        for check in result.checks:
            lines.append(f"  + {check}")
        for failure in result.failures:
            lines.append(f"  - {failure}")
        if result.debug_trace:
            stages = result.debug_trace.get("stages_ms", {})
            lines.append("")
            lines.append(
                "Trace: "
                f"total={result.debug_trace.get('total_ms', 0)}ms "
                f"retrieval={stages.get('retrieval_total_ms', 0)}ms "
                f"rewrite={stages.get('rewrite_ms', 0)}ms "
                f"embed={stages.get('embedding_ms', 0)}ms "
                f"answer={stages.get('answer_generation_ms', 0)}ms"
            )
        if result.retrieval_debug:
            lines.append("")
            lines.append("Retrieval debug:")
            for item in result.retrieval_debug:
                if "error" in item:
                    lines.append(f"  ! {item['error']}")
                    continue
                lines.append(
                    "  "
                    f"{item['rank']}. score={item['score']} "
                    f"{item['source_path']}::{item['section']} "
                    f"[{item['chunk_id']}]"
                )
                lines.append(f"     {item['preview']}")
        lines.append("")

    return "\n".join(lines)


def render_json_report(results: List[CaseResult]) -> str:
    payload = []
    for result in results:
        payload.append(
            {
                "case_id": result.case.id,
                "question": result.case.question,
                "passed": result.passed,
                "status_code": result.status_code,
                "confidence": result.confidence,
                "citations": result.citations,
                "answer": result.answer,
                "checks": result.checks,
                "failures": result.failures,
                "retrieval_debug": result.retrieval_debug,
                "debug_trace": result.debug_trace,
            }
        )
    return json.dumps(payload, ensure_ascii=False, indent=2)


async def main() -> int:
    args = build_parser().parse_args()
    cases = select_cases(args)
    client = await build_client(args.base_url)
    results: List[CaseResult] = []
    total = len(cases)
    try:
        for index, case in enumerate(cases, start=1):
            start = time.monotonic()
            print(f"[{index}/{total}] running case: {case.id}  Q: {case.question}", flush=True)
            result = await run_case(client, case, args.show_retrieval, args.retrieval_limit, args.debug_trace)
            elapsed = time.monotonic() - start
            icon = "PASS" if result.passed else "FAIL"
            print(
                f"[{index}/{total}] {icon} {case.id} ({elapsed:.1f}s) "
                f"status={result.status_code} confidence={result.confidence} "
                f"citations={len(result.citations)}",
                flush=True,
            )
            preview = (result.answer.strip().replace("\n", " "))[:120]
            print(f"           answer: {preview}{'...' if len(result.answer.strip()) > 120 else ''}", flush=True)
            if result.failures:
                print(f"           failures: {'; '.join(result.failures)}", flush=True)
            results.append(result)
    finally:
        await client.aclose()

    print(render_json_report(results) if args.json else render_text_report(results), flush=True)
    return 0 if all(result.passed for result in results) else 1


# ---------------------------------------------------------------------------
# pytest entry points
#
# Each built-in QualityCase becomes one independent test. These hit the real
# LLM/embedding stack and the ingested knowledge base, hence the module-level
# ``live`` marker. The argparse CLI above is unchanged for manual/ops runs.
# ---------------------------------------------------------------------------


@pytest.fixture
async def asgi_client() -> httpx.AsyncClient:
    """In-process ASGI client, mirroring the CLI default (no --base-url)."""
    client = await build_client("")
    try:
        yield client
    finally:
        await client.aclose()


@pytest.mark.parametrize("quality_case", QUALITY_CASES, ids=[case.id for case in QUALITY_CASES])
async def test_answer_quality_case(asgi_client: httpx.AsyncClient, quality_case: QualityCase) -> None:
    result = await run_case(
        asgi_client,
        quality_case,
        show_retrieval=False,
        retrieval_limit=8,
        debug_trace=False,
    )
    assert result.status_code == 200, f"HTTP status is {result.status_code}, expected 200"
    assert not result.failures, (
        f"case {quality_case.id} failed checks: {'; '.join(result.failures)}\n"
        f"answer: {compact_text(result.answer)}\n"
        f"citations: {', '.join(result.citations) or '<none>'}"
    )


if __name__ == "__main__":
    raise SystemExit(asyncio.run(main()))
