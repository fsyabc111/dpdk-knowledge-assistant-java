"""S3-b：B6 执行源带外 + B7 同请求评测去重 + B8 消毒口径统一。

- B6：grade_plugin 的执行源以服务端寄存为准（上传原件 > 消息抽取的粘贴块 >
  模型参数复述），MCP 入参与落库 code_hash 都恒等于实际执行字节；
- B7：同 (protocol, 执行源) 的重复评测命中请求级去重表——先于配额扣减，
  不重打 MCP、不重复记账、不落第二条成绩；失败/超时结果不缓存，允许重试；
- B8：_persistable_question 无附件分支与产品路径同一口径走 redact（粘贴
  源码不落会话正文），_sanitize_question 预览同样走 redact；``` 与 ~~~
  围栏同等处理。

替身沿用 test_agent_service.py：ScriptedChatModel / FakeMcp /
make_orchestrator；数据库走 conftest 的 sqlite_db 临时库，无网络。
"""
from __future__ import annotations

import hashlib
import json
import sys
from pathlib import Path

import pytest
from langchain_core.messages import AIMessage

ROOT = Path(__file__).resolve().parents[1]
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))

from test_agent_service import FakeMcp, ScriptedChatModel, make_orchestrator

from app.agent.tools import build_tools, extract_fenced_source
from app.schemas.chat import UploadedCode
from app.services.agent_service import _sanitize_question
from app.services.rag_issue_service import redact

HARNESS_OK = {
    "ok": True, "protocol": "dns", "score": 90, "cases_total": 9, "passed": 8,
    "failed": 1, "crashed": 0, "timedout": 0, "cheat": 0,
    "results": [
        {"name": "dns_basic_1", "category": "[POSITIVE]", "result": "PASS", "failures": []},
        {"name": "dns_malformed", "category": "[MALFORMED]", "result": "FAIL",
         "failures": [{"desc": "qname", "expected": "example.com", "actual": "www.example.com"}]},
    ],
}

PASTED_SOURCE = "/* 粘贴的 dns 插件 */\n#include <stdint.h>\nint plugin_main(void){return 0;}\n"
UPLOADED_SOURCE = "/* 上传原件 dns 插件 */\n#include <stdint.h>\nint plugin_main(void){return 1;}\n"
# 模型在 tool args 里复述的源码：与上传原件/粘贴块字节不同（live 实测确实如此）。
MODEL_ARG_SOURCE = "/* 模型复述源码（字节不同） */\nint plugin_main(void){return -1;}\n"


def make_run(question, attachment=None, mcp=None, roles=("viewer",)):
    """走 _build_run 装配真实 context——B6 粘贴源抽取的装配点就在这里。"""
    orchestrator = make_orchestrator(
        ScriptedChatModel(responses=[AIMessage(content="占位答复")]),
        mcp or FakeMcp(HARNESS_OK),
    )
    _, context = orchestrator._build_run(
        None, "stu_1", list(roles), question, attachment
    )
    return orchestrator, context


def grade_tool(context):
    return {tool.name: tool for tool in build_tools(context)}["grade_plugin"]


def submissions():
    from app.db import get_conn

    with get_conn() as conn:
        return conn.execute(
            "SELECT user_id, protocol, code_hash, score, results_json "
            "FROM grading_submissions"
        ).fetchall()


def grading_quota_count(user_id="stu_1"):
    from app.db import get_conn

    with get_conn() as conn:
        row = conn.execute(
            "SELECT chat_count FROM usage_counters WHERE subject=? AND kind='grading'",
            (user_id,),
        ).fetchone()
    return int(row["chat_count"]) if row else 0


# ---------------------------------------------------------------------------
# B6 执行源带外
# ---------------------------------------------------------------------------


async def test_uploaded_source_is_executed_not_model_restatement(sqlite_db):
    """上传路径：模型复述与原件不一致时，MCP 收到上传原件字节。"""
    mcp = FakeMcp(HARNESS_OK)
    attachment = UploadedCode("stu_dns.c", ".c", UPLOADED_SOURCE)
    _, context = make_run("帮我评测这个插件", attachment=attachment, mcp=mcp)
    assert context.uploaded_source == UPLOADED_SOURCE
    assert context.pasted_source is None  # 有附件时 uploaded_source 是权威，不再抽取

    result = await grade_tool(context).ainvoke(
        {"protocol": "dns", "plugin_source": MODEL_ARG_SOURCE}
    )
    assert result["ok"] is True
    assert result["data"]["source_origin"] == "upload"
    assert mcp.calls[0][1]["plugin_source"] == UPLOADED_SOURCE
    assert mcp.calls[0][1]["plugin_source"] != MODEL_ARG_SOURCE

    (row,) = submissions()
    assert row["code_hash"] == hashlib.sha256(UPLOADED_SOURCE.encode()).hexdigest()
    assert json.loads(row["results_json"])["source_origin"] == "upload"


async def test_pasted_fenced_source_is_executed_not_model_args(sqlite_db):
    """粘贴路径：_build_run 从消息抽取 ```c 块作为执行源，MCP 收到该字节。"""
    question = f"帮我评测 dns 插件：\n```c\n{PASTED_SOURCE}```"
    mcp = FakeMcp(HARNESS_OK)
    _, context = make_run(question, mcp=mcp)
    assert context.pasted_source == PASTED_SOURCE  # 抽取的就位（围栏已剥离）

    result = await grade_tool(context).ainvoke(
        {"protocol": "dns", "plugin_source": MODEL_ARG_SOURCE}
    )
    assert result["ok"] is True
    assert result["data"]["source_origin"] == "pasted"
    assert mcp.calls[0][1]["plugin_source"] == PASTED_SOURCE

    (row,) = submissions()
    assert row["code_hash"] == hashlib.sha256(PASTED_SOURCE.encode()).hexdigest()
    assert json.loads(row["results_json"])["source_origin"] == "pasted"


async def test_no_fenced_source_falls_back_to_model_args(sqlite_db):
    """兜底：无附件且消息无围栏块 → 执行模型参数原文，origin=model_args。"""
    mcp = FakeMcp(HARNESS_OK)
    _, context = make_run("帮我评测这段代码（正文复述）", mcp=mcp)
    assert context.uploaded_source is None
    assert context.pasted_source is None

    result = await grade_tool(context).ainvoke(
        {"protocol": "dns", "plugin_source": MODEL_ARG_SOURCE}
    )
    assert result["ok"] is True
    assert result["data"]["source_origin"] == "model_args"
    assert mcp.calls[0][1]["plugin_source"] == MODEL_ARG_SOURCE

    (row,) = submissions()
    assert row["code_hash"] == hashlib.sha256(MODEL_ARG_SOURCE.encode()).hexdigest()
    assert json.loads(row["results_json"])["source_origin"] == "model_args"


def test_extract_fenced_source_picks_longest_and_handles_unclosed():
    """extract_fenced_source：多块取内容最长者；尾围栏未闭合时其后内容
    整体视为源码；``` 与 ~~~ 互不误闭合；无围栏返回 None。"""
    assert extract_fenced_source("普通问题，没有代码") is None
    assert extract_fenced_source("") is None
    # 多个块取内容最长者
    text = "```\nint a;\n```\n中间文字\n```c\n" + PASTED_SOURCE + "int extra;\n```"
    assert extract_fenced_source(text) == PASTED_SOURCE + "int extra;\n"
    # 未闭合尾围栏：其后全部内容视为源码
    assert extract_fenced_source("评测：\n```c\n" + PASTED_SOURCE) == PASTED_SOURCE
    # ``` 块内的 ~~~ 不算闭合（反向引用配对）
    assert extract_fenced_source("```c\nint a; /* ~~~ */\n```") == "int a; /* ~~~ */\n"


# ---------------------------------------------------------------------------
# B8 消毒口径统一
# ---------------------------------------------------------------------------


async def test_tilde_fence_extracted_and_redacted(sqlite_db):
    """~~~ 围栏与 ``` 同口径：可抽取为执行源，也被 redact 抹除。"""
    question = f"帮我评测 dns 插件：\n~~~c\n{PASTED_SOURCE}\n~~~"
    _, context = make_run(question, mcp=FakeMcp(HARNESS_OK))
    assert context.pasted_source == PASTED_SOURCE

    cleaned = redact(question)
    assert "[代码已省略]" in cleaned
    assert "plugin_main" not in cleaned and "#include" not in cleaned


async def test_persistable_question_redacts_pasted_source(sqlite_db):
    """B8：无附件的粘贴源码不落会话正文（与产品路径同一 redact 口径）。"""
    orchestrator, _ = make_run("占位")
    for fence in ("```", "~~~"):
        question = f"帮我评测 dns 插件：\n{fence}c\n{PASTED_SOURCE}\n{fence}"
        stored = orchestrator._persistable_question(question, None)
        assert "plugin_main" not in stored and "#include" not in stored
        assert "[代码已省略]" in stored
    # 有附件分支不变：原文 + 附件标记（附件原文本就不进正文）。
    attachment = UploadedCode("stu_dns.c", ".c", UPLOADED_SOURCE)
    assert (
        orchestrator._persistable_question("看看附件", attachment)
        == "看看附件\n[上传附件：stu_dns.c]"
    )


async def test_stored_user_message_omits_pasted_source(sqlite_db):
    """端到端：answer() 落库的用户消息经 redact——粘贴源码不进会话正文。"""
    model = ScriptedChatModel(responses=[AIMessage(content="收到，我先看看。")])
    orchestrator = make_orchestrator(model, FakeMcp(HARNESS_OK))
    question = f"帮我评测 dns 插件：\n```c\n{PASTED_SOURCE}```"
    response = await orchestrator.answer(
        None, question, user_id="stu_1", user_roles=["viewer"]
    )

    messages = await orchestrator.sessions.get_messages(response.session_id)
    user_msg = next(m for m in messages if m["role"] == "user")
    assert "plugin_main" not in user_msg["content"]
    assert "#include" not in user_msg["content"]
    assert "[代码已省略]" in user_msg["content"]


def test_sanitize_question_preview_has_no_source():
    """降级预览与落库同一消毒口径：围栏源码不回显，位置替换为省略说明。"""
    question = f"看看这段代码：\n```c\n{PASTED_SOURCE}\n```\n能过吗？"
    preview = _sanitize_question(question)
    assert "（含代码片段，已省略）" in preview
    assert "plugin_main" not in preview and "#include" not in preview
    assert "```" not in preview and "~~~" not in preview
    # ~~~ 围栏同样口径
    tilde = _sanitize_question(f"看看：\n~~~c\n{PASTED_SOURCE}~~~")
    assert "（含代码片段，已省略）" in tilde and "plugin_main" not in tilde


# ---------------------------------------------------------------------------
# B7 同请求评测去重
# ---------------------------------------------------------------------------


async def test_same_source_deduplicated_before_quota(sqlite_db):
    """同 (protocol, 执行源) 第二次调用命中去重：不扣配额、不打 MCP、不重复
    记账，返回带 deduplicated 标记的同一份 data。"""
    mcp = FakeMcp(HARNESS_OK)
    _, context = make_run("评测", mcp=mcp)  # 无围栏 → 模型参数路径
    grade = grade_tool(context)

    first = await grade.ainvoke({"protocol": "dns", "plugin_source": MODEL_ARG_SOURCE})
    second = await grade.ainvoke({"protocol": "dns", "plugin_source": MODEL_ARG_SOURCE})
    assert first["ok"] is True and "deduplicated" not in first["data"]
    assert second["ok"] is True and second["data"]["deduplicated"] is True
    # 除标记外载荷与首次完全一致
    assert {
        k: v for k, v in second["data"].items() if k != "deduplicated"
    } == first["data"]

    assert len([c for c in mcp.calls if c[0] == "grade_plugin"]) == 1
    assert len(context.grading_results) == 1
    assert len(submissions()) == 1
    assert grading_quota_count() == 1  # 去重命中先于配额扣减


async def test_dedup_key_uses_execution_source_not_model_args(sqlite_db):
    """去重键算在执行源上：两次模型复述参数不同但粘贴原件相同 → 仍命中。"""
    question = f"评测：\n```c\n{PASTED_SOURCE}```"
    mcp = FakeMcp(HARNESS_OK)
    _, context = make_run(question, mcp=mcp)
    grade = grade_tool(context)

    await grade.ainvoke({"protocol": "dns", "plugin_source": PASTED_SOURCE})
    second = await grade.ainvoke(
        {"protocol": "dns", "plugin_source": "模型这次复述错了 int x;"}
    )
    assert second["data"]["deduplicated"] is True
    assert len(mcp.calls) == 1  # 执行源同为粘贴原件，参数差异不参与去重键


async def test_different_source_or_protocol_not_deduplicated(sqlite_db):
    """去重键是 (protocol, 执行源)：换源码或换协议都重新评测。"""
    mcp = FakeMcp(HARNESS_OK)
    _, context = make_run("评测", mcp=mcp)
    grade = grade_tool(context)

    await grade.ainvoke({"protocol": "dns", "plugin_source": MODEL_ARG_SOURCE})
    other = await grade.ainvoke(
        {"protocol": "dns", "plugin_source": MODEL_ARG_SOURCE + "// v2\n"}
    )
    assert other["ok"] is True and "deduplicated" not in other["data"]
    third = await grade.ainvoke({"protocol": "http", "plugin_source": MODEL_ARG_SOURCE})
    assert third["ok"] is True and "deduplicated" not in third["data"]

    assert len(mcp.calls) == 3
    assert len(submissions()) == 3
    assert grading_quota_count() == 3


async def test_failed_grading_is_not_cached(sqlite_db):
    """失败结果不进入去重表：同一源码重试会真正重打 MCP。"""
    calls = []

    async def flaky(tool_name, arguments):
        calls.append((tool_name, arguments))
        if len(calls) == 1:
            return {"ok": False, "error": "harness_unavailable", "detail": "评测服务不可达"}
        return dict(HARNESS_OK)

    _, context = make_run("评测", mcp=flaky)
    grade = grade_tool(context)

    first = await grade.ainvoke({"protocol": "dns", "plugin_source": MODEL_ARG_SOURCE})
    assert first["ok"] is False and first["error"] == "harness_unavailable"
    second = await grade.ainvoke({"protocol": "dns", "plugin_source": MODEL_ARG_SOURCE})
    assert second["ok"] is True and "deduplicated" not in second["data"]
    assert len(calls) == 2
    assert len(submissions()) == 1


if __name__ == "__main__":
    sys.exit(pytest.main([__file__, "-v"]))
