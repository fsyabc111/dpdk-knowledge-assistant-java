"""Agent 工具层测试（kb_search / grade_plugin），全部 mock 级、无网络。

grade_plugin 的 MCP 调用方以可注入的 async callable 替换；配额与成绩落库
走 sqlite_db 临时库（usage_counters kind='grading' / grading_submissions）。
"""
from __future__ import annotations

import asyncio
import hashlib
import json
import sys
from pathlib import Path

import pytest

ROOT = Path(__file__).resolve().parents[1]
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))

from app.rag.types import RetrievedChunk

HARNESS_OK = {
    "ok": True,
    "protocol": "dns",
    "score": 88.9,
    "cases_total": 9,
    "passed": 8,
    "failed": 1,
    "crashed": 0,
    "timedout": 0,
    "cheat": 0,
    "results": [
        {"name": "dns_basic_query", "category": "[POSITIVE]", "result": "PASS", "failures": []},
        {"name": "dns_malformed", "category": "[MALFORMED]", "result": "FAIL",
         "failures": [{"desc": "qname", "expected": "example.com", "actual": "www.example.com"}]},
    ],
}

SOURCE = "/* dns plugin */\n#include <stdint.h>\n"


@pytest.fixture
def agent_settings(sqlite_db, monkeypatch):
    monkeypatch.setenv("GRADING_DAILY_QUOTA", "2")
    monkeypatch.setenv("GRADING_TIMEOUT_SEC", "5")
    monkeypatch.setenv("GRADING_MAX_CODE_BYTES", "1024")
    from app.config import get_settings

    get_settings.cache_clear()
    return get_settings()


class FakeRetriever:
    def __init__(self, chunks=None, error=None):
        self.chunks = chunks or []
        self.error = error

    async def search(self, query, limit=6, trace=None, conversation_context=None):
        if self.error:
            raise self.error
        return self.chunks[:limit]


def make_chunk() -> RetrievedChunk:
    return RetrievedChunk(
        id="chk_1", source_path="docs/dns.md", title="DNS", section="报文格式",
        content="qname 以标签序列编码", metadata={}, score=0.9,
    )


def make_context(settings, mcp_caller=None, roles=None, retriever=None):
    from app.agent.tools import AgentToolContext

    calls = []

    async def default_caller(tool_name, arguments):
        calls.append((tool_name, arguments))
        return dict(HARNESS_OK)

    ctx = AgentToolContext(
        settings=settings,
        user_id="stu_2024001",
        user_roles=roles if roles is not None else ["viewer"],
        session_id="sess_test",
        retriever=retriever or FakeRetriever(),
        mcp_caller=mcp_caller or default_caller,
    )
    return ctx, calls


def tools_by_name(ctx):
    from app.agent.tools import build_tools

    return {item.name: item for item in build_tools(ctx)}


# ---------------------------------------------------------------------------
# kb_search
# ---------------------------------------------------------------------------


async def test_kb_search_returns_numbered_chunks_and_collects(agent_settings):
    ctx, _ = make_context(agent_settings, retriever=FakeRetriever(chunks=[make_chunk()]))
    kb_search = tools_by_name(ctx)["kb_search"]
    result = await kb_search.ainvoke({"query": "dns qname 编码"})
    assert result["ok"] is True
    assert result["data"]["count"] == 1
    chunk = result["data"]["chunks"][0]
    assert chunk["ref"] == 1
    assert chunk["source_path"] == "docs/dns.md"
    # 原始 chunk 进入 context，供 citations/sources 事件沿用现有机制。
    assert len(ctx.collected_chunks) == 1


async def test_kb_search_collects_each_chunk_once_and_keeps_refs_positional(agent_settings):
    """同一片段被多轮召回时只入账一次；重复命中复用**原编号**，不新增、不改号。

    P2.1 实测：9 轮 kb_search 把 ``collected_chunks`` 堆到 72 条 / 67651 字符，
    证据 69958 字符 = 预算 24000 的 2.9 倍 ⇒ 硬判「资料不足」，15 题里 9 题如此。
    重复召回不是更多证据：同一片段第 8 次出现，支撑力还是那一条。

    ``ref`` 是按 ``collected_chunks`` 的**位置**发的，``validate_answer`` 也按位置校验
    （``ref > len(chunks)`` 即 invalid_citation）。所以去重只能发生在**追加之前**，
    且重复命中必须沿用原编号——否则编号会与证据错位，那比「资料不足」更糟：
    它会让 [n] 静默指向另一条片段。
    """
    def chunk(index):
        return RetrievedChunk(id=f"chk_{index}", source_path=f"docs/{index}.md", title=f"T{index}",
                              section="s", content=f"内容 {index}", metadata={}, score=0.9)

    overlap = [chunk(1), chunk(2), chunk(3)]
    ctx, _ = make_context(agent_settings, retriever=FakeRetriever(chunks=overlap))
    kb_search = tools_by_name(ctx)["kb_search"]

    first = await kb_search.ainvoke({"query": "第一轮"})
    assert [c["ref"] for c in first["data"]["chunks"]] == [1, 2, 3]
    assert len(ctx.collected_chunks) == 3

    # 第二轮完全重复：模型仍要看到这三条（不然它以为检索失败），但账本不再膨胀。
    ctx.retriever = FakeRetriever(chunks=overlap)
    second = await kb_search.ainvoke({"query": "换个说法再查"})
    assert [c["ref"] for c in second["data"]["chunks"]] == [1, 2, 3], "重复命中必须复用原编号"
    assert len(ctx.collected_chunks) == 3, "重复召回不得再次入账"

    # 部分重复：只有新片段拿到新编号，且新编号紧接在去重后的长度之后。
    ctx.retriever = FakeRetriever(chunks=[chunk(2), chunk(4), chunk(5)])
    third = await kb_search.ainvoke({"query": "第三轮"})
    assert [c["ref"] for c in third["data"]["chunks"]] == [2, 4, 5]
    assert len(ctx.collected_chunks) == 5
    assert [c.id for c in ctx.collected_chunks] == ["chk_1", "chk_2", "chk_3", "chk_4", "chk_5"]


async def test_kb_search_retriever_failure_returns_structured_error(agent_settings):
    ctx, _ = make_context(agent_settings, retriever=FakeRetriever(error=RuntimeError("boom")))
    kb_search = tools_by_name(ctx)["kb_search"]
    result = await kb_search.ainvoke({"query": "dns"})
    assert result["ok"] is False
    assert result["error"] == "retrieval_failed"


# ---------------------------------------------------------------------------
# grade_plugin
# ---------------------------------------------------------------------------


async def test_grade_plugin_success_records_submission(agent_settings):
    ctx, calls = make_context(agent_settings)
    grade_plugin = tools_by_name(ctx)["grade_plugin"]
    result = await grade_plugin.ainvoke({"protocol": "dns", "plugin_source": SOURCE})
    assert result["ok"] is True
    assert result["data"]["score"] == 88.9
    assert result["data"]["passed"] == 8
    assert result["data"]["cases_total"] == 9
    assert calls[0][0] == "grade_plugin"
    assert calls[0][1]["plugin_source"] == SOURCE

    from app.db import get_conn

    with get_conn() as conn:
        row = conn.execute(
            "SELECT user_id, session_id, protocol, code_hash, score, passed, total, results_json "
            "FROM grading_submissions"
        ).fetchone()
    assert row is not None
    assert row["user_id"] == "stu_2024001"
    assert row["session_id"] == "sess_test"
    assert row["protocol"] == "dns"
    assert row["code_hash"] == hashlib.sha256(SOURCE.encode()).hexdigest()
    assert row["score"] == 89
    assert row["passed"] == 8 and row["total"] == 9
    stored = json.loads(row["results_json"])
    # B6：本用例无上传/围栏抽取，执行源就是模型参数本身 → model_args。
    # 「pasted」现在专指服务端从消息围栏抽取的寄存源码（见 test_s3b_source_identity.py）。
    assert stored["source_origin"] == "model_args"
    assert stored["results"][1]["failures"][0]["expected"] == "example.com"


async def test_grade_plugin_uploaded_original_determines_code_hash(agent_settings):
    """上传路径：模型复述与原件不一致时，落库 code_hash 以原件 sha256 为准。"""
    original = SOURCE + "// uploaded original tail\n"
    restated = SOURCE  # 模型在 tool args 里复述的源码，与原件字节不同
    ctx, _ = make_context(agent_settings)
    ctx.uploaded_source = original
    grade_plugin = tools_by_name(ctx)["grade_plugin"]
    result = await grade_plugin.ainvoke({"protocol": "dns", "plugin_source": restated})
    assert result["ok"] is True

    from app.db import get_conn

    with get_conn() as conn:
        row = conn.execute(
            "SELECT code_hash, results_json FROM grading_submissions"
        ).fetchone()
    assert row["code_hash"] == hashlib.sha256(original.encode("utf-8")).hexdigest()
    assert row["code_hash"] != hashlib.sha256(restated.encode("utf-8")).hexdigest()
    stored = json.loads(row["results_json"])
    assert stored["source_origin"] == "upload"


def test_agent_llm_max_tokens_default_and_floor():
    """AGENT_LLM_MAX_TOKENS：默认 8192，边界校验 >= 2048（agent 工具参数需要大上限）。"""
    from pydantic import ValidationError

    from app.config import Settings

    assert Settings.model_fields["agent_llm_max_tokens"].default == 8192
    with pytest.raises(ValidationError):
        Settings(agent_llm_max_tokens=2047)


async def test_grade_plugin_counts_daily_quota_with_grading_kind(agent_settings):
    # B7 新契约：同请求内同 (protocol, 执行源) 的重复调用命中去重缓存——
    # 不扣配额、不触达 harness（去重先于配额扣减）。要测配额语义必须用不同源码。
    ctx, calls = make_context(agent_settings)
    grade_plugin = tools_by_name(ctx)["grade_plugin"]
    assert (await grade_plugin.ainvoke({"protocol": "dns", "plugin_source": SOURCE}))["ok"] is True
    dup = await grade_plugin.ainvoke({"protocol": "dns", "plugin_source": SOURCE})
    assert dup["ok"] is True and dup["data"]["deduplicated"] is True
    assert (await grade_plugin.ainvoke({"protocol": "dns", "plugin_source": SOURCE + "// v2\n"}))["ok"] is True
    third = await grade_plugin.ainvoke({"protocol": "dns", "plugin_source": SOURCE + "// v3\n"})
    assert third["ok"] is False
    assert third["error"] == "quota_exceeded"
    assert len(calls) == 2  # 去重命中与配额拒绝都未触达 harness

    from app.db import get_conn

    with get_conn() as conn:
        row = conn.execute(
            "SELECT kind, chat_count FROM usage_counters WHERE subject = ?", ("stu_2024001",)
        ).fetchall()
    kinds = {item["kind"]: item["chat_count"] for item in row}
    assert kinds == {"grading": 3}  # 计数先行：两次真实执行 + 被拒的第四次；去重命中不计


async def test_grade_plugin_operator_role_is_quota_exempt(agent_settings, monkeypatch):
    monkeypatch.setenv("GRADING_DAILY_QUOTA", "1")
    from app.config import get_settings

    get_settings.cache_clear()
    ctx, calls = make_context(get_settings(), roles=["operator"])
    grade_plugin = tools_by_name(ctx)["grade_plugin"]
    # B7：同源重复调用会去重，豁免语义要用不同源码才能观察到三次真实执行。
    for i in range(3):
        result = await grade_plugin.ainvoke({"protocol": "dns", "plugin_source": SOURCE + f"// {i}\n"})
        assert result["ok"] is True
    assert len(calls) == 3


async def test_grade_plugin_rejects_oversized_source(agent_settings):
    ctx, calls = make_context(agent_settings)
    grade_plugin = tools_by_name(ctx)["grade_plugin"]
    result = await grade_plugin.ainvoke({"protocol": "dns", "plugin_source": "x" * 2048})
    assert result["ok"] is False
    assert result["error"] == "code_too_large"
    assert calls == []


async def test_grade_plugin_harness_error_propagates_without_recording(agent_settings):
    async def failing_caller(tool_name, arguments):
        return {"ok": False, "error": "harness_unavailable", "detail": "评测服务不可达"}

    ctx, _ = make_context(agent_settings, mcp_caller=failing_caller)
    grade_plugin = tools_by_name(ctx)["grade_plugin"]
    result = await grade_plugin.ainvoke({"protocol": "dns", "plugin_source": SOURCE})
    assert result["ok"] is False
    assert result["error"] == "harness_unavailable"

    from app.db import get_conn

    with get_conn() as conn:
        count = conn.execute("SELECT COUNT(*) AS n FROM grading_submissions").fetchone()["n"]
    assert count == 0


async def test_grade_plugin_backend_timeout(agent_settings, monkeypatch):
    monkeypatch.setenv("GRADING_TIMEOUT_SEC", "1")
    from app.config import get_settings

    get_settings.cache_clear()

    async def slow_caller(tool_name, arguments):
        await asyncio.sleep(5)
        return dict(HARNESS_OK)

    ctx, _ = make_context(get_settings(), mcp_caller=slow_caller)
    grade_plugin = tools_by_name(ctx)["grade_plugin"]
    result = await grade_plugin.ainvoke({"protocol": "dns", "plugin_source": SOURCE})
    assert result["ok"] is False
    assert result["error"] == "grading_timeout"


async def test_recent_fail_count_drives_escalation(agent_settings):
    from app.services.grading_service import GradingService

    grading = GradingService()
    for _ in range(3):
        await grading.record_submission(
            user_id="stu_1", session_id=None, protocol="dns", code_hash="h",
            score=50, passed=4, total=8, results=[],
        )
    await grading.record_submission(
        user_id="stu_1", session_id=None, protocol="http", code_hash="h",
        score=100, passed=8, total=8, results=[],
    )
    assert await grading.recent_fail_count("stu_1", "dns") == 3
    # http 协议全部通过，不计 FAIL。
    assert await grading.recent_fail_count("stu_1", "http") == 0


# ---------------------------------------------------------------------------
# list_protocols / list_cases / plugin_template（M2）
# ---------------------------------------------------------------------------

LIST_PROTOCOLS_OK = {
    "ok": True,
    "protocols": [
        {"name": "dns", "cases": 9, "categories": {"[POSITIVE]": 4, "[MALFORMED]": 5}},
        {"name": "http", "cases": 11, "categories": {"[POSITIVE]": 6, "[NEGATIVE]": 5}},
    ],
}

LIST_CASES_OK = {
    "ok": True,
    "protocol": "dns",
    "cases": [
        {"name": "dns_basic_query", "category": "[POSITIVE]"},
        {"name": "dns_malformed", "category": "[MALFORMED]"},
    ],
}


async def test_list_protocols_passthrough(agent_settings):
    async def caller(tool_name, arguments):
        assert tool_name == "list_protocols"
        assert arguments == {}
        return dict(LIST_PROTOCOLS_OK)

    ctx, _ = make_context(agent_settings, mcp_caller=caller)
    result = await tools_by_name(ctx)["list_protocols"].ainvoke({})
    assert result["ok"] is True
    assert result["data"]["count"] == 2
    assert result["data"]["protocols"][0] == {
        "name": "dns", "cases": 9, "categories": {"[POSITIVE]": 4, "[MALFORMED]": 5},
    }


async def test_list_protocols_harness_error_returns_structured_error(agent_settings):
    async def caller(tool_name, arguments):
        return {"ok": False, "error": "harness_unavailable", "detail": "评测服务不可达"}

    ctx, _ = make_context(agent_settings, mcp_caller=caller)
    result = await tools_by_name(ctx)["list_protocols"].ainvoke({})
    assert result["ok"] is False
    assert result["error"] == "harness_unavailable"


async def test_list_cases_passthrough(agent_settings):
    async def caller(tool_name, arguments):
        assert tool_name == "list_cases"
        assert arguments == {"protocol": "dns"}
        return dict(LIST_CASES_OK)

    ctx, _ = make_context(agent_settings, mcp_caller=caller)
    result = await tools_by_name(ctx)["list_cases"].ainvoke({"protocol": "dns"})
    assert result["ok"] is True
    assert result["data"]["protocol"] == "dns"
    assert result["data"]["count"] == 2
    assert result["data"]["cases"][1]["category"] == "[MALFORMED]"


async def test_list_cases_unknown_protocol_propagates(agent_settings):
    async def caller(tool_name, arguments):
        return {"ok": False, "error": "unknown_protocol", "detail": "合法协议: dns, ..."}

    ctx, _ = make_context(agent_settings, mcp_caller=caller)
    result = await tools_by_name(ctx)["list_cases"].ainvoke({"protocol": "v2ray"})
    assert result["ok"] is False
    assert result["error"] == "unknown_protocol"


async def test_plugin_template_returns_skeleton(agent_settings):
    ctx, _ = make_context(agent_settings)
    result = await tools_by_name(ctx)["plugin_template"].ainvoke({"protocol": "dns"})
    assert result["ok"] is True
    data = result["data"]
    assert data["protocol"] == "dns"
    assert "engine_get_ops" in data["template"]
    assert "TODO" in data["template"]
    assert data["checklist"] and data["references"]
    assert "不是完整作业答案" in data["explanation"]


async def test_plugin_template_service_failure_returns_structured_error(agent_settings, monkeypatch):
    ctx, _ = make_context(agent_settings)
    monkeypatch.setattr(
        ctx.template_service, "generate",
        lambda req: (_ for _ in ()).throw(RuntimeError("boom")),
    )
    result = await tools_by_name(ctx)["plugin_template"].ainvoke({"protocol": "dns"})
    assert result["ok"] is False
    assert result["error"] == "template_failed"


# ---------------------------------------------------------------------------
# tool_event_label / tool_event_summary（M2 新工具）
# ---------------------------------------------------------------------------


def test_tool_event_labels_for_m2_tools():
    from app.agent.tools import tool_event_label

    assert tool_event_label("list_protocols", {}) == "正在查询协议列表…"
    assert tool_event_label("list_cases", {"protocol": "dns"}) == "正在查询 DNS 的用例…"
    assert tool_event_label("plugin_template", {"protocol": "dns"}) == "正在生成 DNS 插件骨架…"
    # kb_search 把查询词带进 label，避免多次调用时卡片文案雷同
    assert tool_event_label("kb_search", {"query": "engine_ops_t 结构"}) == "正在检索：engine_ops_t 结构"
    assert tool_event_label("kb_search", {}) == "正在检索知识库…"


def test_tool_event_summaries_for_m2_tools():
    from app.agent.tools import tool_event_summary

    assert tool_event_summary(
        "list_protocols", json.dumps({"ok": True, "data": {"count": 20}})
    )["summary"] == "20 个协议"
    assert tool_event_summary(
        "list_cases", json.dumps({"ok": True, "data": {"count": 9}})
    )["summary"] == "9 个用例"
    assert tool_event_summary(
        "plugin_template", json.dumps({"ok": True, "data": {"template": "..."}})
    )["summary"] == "已生成骨架"
    failed = tool_event_summary(
        "list_cases", json.dumps({"ok": False, "error": "unknown_protocol", "detail": "合法协议: dns"})
    )
    assert failed["ok"] is False and "合法协议" in failed["summary"]
    # kb_search summary 带查询词；旧格式结果（无 query 字段）回退为纯命中数
    assert tool_event_summary(
        "kb_search", json.dumps({"ok": True, "data": {"query": "dns 插件", "count": 8}})
    )["summary"] == "「dns 插件」命中 8 条资料"
    assert tool_event_summary(
        "kb_search", json.dumps({"ok": True, "data": {"count": 3}})
    )["summary"] == "命中 3 条资料"


if __name__ == "__main__":
    sys.exit(pytest.main([__file__, "-v"]))
