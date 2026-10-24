"""Agent 图路由逻辑测试（规划 §8：有/无 tool_calls、循环上限、工具异常）。

使用脚本化 fake chat model（无网络）；fake 未实现流式时 langchain 自动
回退为整段输出，messages 流模式下整段到达一次，不影响断言。
"""
from __future__ import annotations

import json
import sys
from pathlib import Path

import pytest
from langchain_core.language_models.chat_models import BaseChatModel
from langchain_core.messages import AIMessage, HumanMessage, ToolMessage
from langchain_core.outputs import ChatGeneration, ChatResult
from langchain_core.tools import tool

ROOT = Path(__file__).resolve().parents[1]
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))

from app.agent.graph import _pair_unexecuted_tool_calls, build_agent_graph


class ScriptedChatModel(BaseChatModel):
    """Replay a scripted list of AIMessages; bind_tools is a pass-through."""

    responses: list
    calls: int = 0
    # 每次调用实际收到的消息列表（含系统提示词），供上下文预算断言。
    seen: list = []

    @property
    def _llm_type(self) -> str:
        return "scripted-fake"

    def bind_tools(self, tools, **kwargs):
        return self

    def _generate(self, messages, stop=None, run_manager=None, **kwargs):
        self.seen.append(list(messages))
        message = self.responses[min(self.calls, len(self.responses) - 1)]
        self.calls += 1
        return ChatResult(generations=[ChatGeneration(message=message)])


@tool
async def fake_lookup(query: str) -> dict:
    """查询测试资料。"""
    return {"ok": True, "data": {"echo": query}}


@tool
async def always_broken(query: str) -> dict:
    """一个总是抛异常的工具。"""
    raise RuntimeError("kaboom")


async def static_prompt(state) -> str:
    return "系统提示"


def tool_call(name: str, args: dict, call_id: str = "call_1") -> dict:
    return {"name": name, "args": args, "id": call_id, "type": "tool_call"}


def build(model, tools, max_rounds=3):
    return build_agent_graph(
        model=model, tools=tools, prompt_builder=static_prompt, max_rounds=max_rounds
    )


async def test_no_tool_calls_exits_after_one_planner_call():
    model = ScriptedChatModel(responses=[AIMessage(content="直接回答")])
    graph = build(model, [fake_lookup])
    result = await graph.ainvoke({"messages": [HumanMessage(content="hi")], "tool_rounds": 0})
    assert result["messages"][-1].content == "直接回答"
    assert result.get("tool_rounds", 0) == 0
    assert model.calls == 1


async def test_tool_call_roundtrip_feeds_result_back_to_planner():
    model = ScriptedChatModel(responses=[
        AIMessage(content="", tool_calls=[tool_call("fake_lookup", {"query": "dns"})]),
        AIMessage(content="最终答复"),
    ])
    graph = build(model, [fake_lookup])
    result = await graph.ainvoke({"messages": [HumanMessage(content="hi")], "tool_rounds": 0})
    tool_messages = [m for m in result["messages"] if isinstance(m, ToolMessage)]
    assert len(tool_messages) == 1
    payload = json.loads(tool_messages[0].content)
    assert payload == {"ok": True, "data": {"echo": "dns"}}
    assert result["messages"][-1].content == "最终答复"
    assert result["tool_rounds"] == 1


async def test_tool_loop_respects_max_rounds_and_finalizes():
    model = ScriptedChatModel(responses=[
        AIMessage(content="", tool_calls=[tool_call("fake_lookup", {"query": "1"}, "c1")]),
        AIMessage(content="", tool_calls=[tool_call("fake_lookup", {"query": "2"}, "c2")]),
        AIMessage(content="", tool_calls=[tool_call("fake_lookup", {"query": "3"}, "c3")]),
        AIMessage(content="限轮后的最终答复"),
    ])
    graph = build(model, [fake_lookup], max_rounds=2)
    result = await graph.ainvoke({"messages": [HumanMessage(content="hi")], "tool_rounds": 0})
    tool_messages = [m for m in result["messages"] if isinstance(m, ToolMessage)]
    # 第三轮 tool_call 不再执行，转入 finalize 直接产出答复。
    assert len(tool_messages) == 2
    assert result["tool_rounds"] == 2
    assert result["messages"][-1].content == "限轮后的最终答复"
    assert model.calls == 4


async def test_finalize_pairs_unexecuted_tool_calls_when_rounds_exhausted():
    """B4/F3 回归：达限转 finalize 时，末尾未执行的 tool_calls 不得悬空进入上游。

    max_rounds=1：第二轮 planner 一次发出两个 tool_calls，均未执行即被路由进
    finalize；进入模型的序列里每个 tool_call 都必须有紧邻的配对 ToolMessage。
    """
    model = ScriptedChatModel(responses=[
        AIMessage(content="", tool_calls=[tool_call("fake_lookup", {"query": "1"}, "c_kb")]),
        AIMessage(content="", tool_calls=[
            tool_call("fake_lookup", {"query": "2"}, "c_kb2"),
            tool_call("fake_lookup", {"query": "3"}, "c_kb3"),
        ]),
        AIMessage(content="达限后的最终答复"),
    ])
    graph = build(model, [fake_lookup], max_rounds=1)
    result = await graph.ainvoke({"messages": [HumanMessage(content="hi")], "tool_rounds": 0})

    assert result["messages"][-1].content == "达限后的最终答复"
    assert model.calls == 3, "planner 两次 + finalize 一次"

    finalize_messages = model.seen[-1]
    # 协议不变式：带 tool_calls 的 AIMessage 之后必须紧邻其全部配对
    # ToolMessage——悬空调用会让严格校验工具协议的上游直接拒收。
    for index, message in enumerate(finalize_messages):
        calls = getattr(message, "tool_calls", None) or []
        followers = finalize_messages[index + 1:index + 1 + len(calls)]
        assert all(getattr(m, "type", "") == "tool" for m in followers)
        assert [m.tool_call_id for m in followers] == [call["id"] for call in calls]

    # 未执行的两个调用各补一条合成 ToolMessage，形状与 run_tools 错误结果一致。
    synthesized = [
        m for m in finalize_messages
        if getattr(m, "type", "") == "tool" and m.tool_call_id in ("c_kb2", "c_kb3")
    ]
    assert [m.tool_call_id for m in synthesized] == ["c_kb2", "c_kb3"]
    assert [m.name for m in synthesized] == ["fake_lookup", "fake_lookup"]
    for message in synthesized:
        payload = json.loads(message.content)
        assert payload["ok"] is False
        assert payload["error"] == "not_executed"

    # 合成结果只进发给模型的副本：图 state 里仍只有第一轮真实执行的 ToolMessage。
    state_tools = [m for m in result["messages"] if getattr(m, "type", "") == "tool"]
    assert [m.tool_call_id for m in state_tools] == ["c_kb"]


def test_pair_unexecuted_tool_calls_noops_when_tail_is_not_pending_calls():
    """正向：末尾不是「带 tool_calls 的 AIMessage」时不得追加任何合成消息。

    覆盖 route_after_tools → finalize 的输入形状（最后是 ToolMessage）与
    普通文本答复末尾；这些形状进入 finalize 时序列本就配对完整。
    """
    after_tools = [
        HumanMessage(content="hi"),
        AIMessage(content="", tool_calls=[tool_call("fake_lookup", {"query": "x"}, "c1")]),
        ToolMessage(content='{"ok": true}', tool_call_id="c1", name="fake_lookup"),
    ]
    assert _pair_unexecuted_tool_calls(after_tools) is after_tools
    plain_answer = [HumanMessage(content="hi"), AIMessage(content="直接答复")]
    assert _pair_unexecuted_tool_calls(plain_answer) is plain_answer
    assert _pair_unexecuted_tool_calls([]) == []


def test_pair_unexecuted_tool_calls_repairs_tail_only():
    """只补末尾一条的悬空：历史中部未配对调用（输入腐化）不在 B4 范围。"""
    history = [
        HumanMessage(content="hi"),
        AIMessage(content="", tool_calls=[tool_call("fake_lookup", {"query": "x"}, "c1")]),
        HumanMessage(content="追问"),
        AIMessage(content="", tool_calls=[tool_call("fake_lookup", {"query": "y"}, "c2")]),
    ]
    patched = _pair_unexecuted_tool_calls(history)
    assert len(patched) == len(history) + 1
    tail = patched[-1]
    assert getattr(tail, "type", "") == "tool" and tail.tool_call_id == "c2"
    assert json.loads(tail.content)["error"] == "not_executed"
    # 中部悬空的 c1 原样保留（B4 只负责末尾；腐化输入由调用方负责）。
    assert all(getattr(m, "tool_call_id", None) != "c1" for m in patched)
    # 原列表不被改写。
    assert len(history) == 4


async def test_tool_exception_becomes_error_tool_message_and_graph_continues():
    model = ScriptedChatModel(responses=[
        AIMessage(content="", tool_calls=[tool_call("always_broken", {"query": "x"})]),
        AIMessage(content="降级答复"),
    ])
    graph = build(model, [always_broken])
    result = await graph.ainvoke({"messages": [HumanMessage(content="hi")], "tool_rounds": 0})
    tool_messages = [m for m in result["messages"] if isinstance(m, ToolMessage)]
    payload = json.loads(tool_messages[0].content)
    assert payload["ok"] is False
    assert payload["error"] == "tool_exception"
    assert result["messages"][-1].content == "降级答复"


async def test_unknown_tool_returns_structured_error():
    model = ScriptedChatModel(responses=[
        AIMessage(content="", tool_calls=[tool_call("nope", {})]),
        AIMessage(content="无法识别工具后的答复"),
    ])
    graph = build(model, [fake_lookup])
    result = await graph.ainvoke({"messages": [HumanMessage(content="hi")], "tool_rounds": 0})
    tool_messages = [m for m in result["messages"] if isinstance(m, ToolMessage)]
    payload = json.loads(tool_messages[0].content)
    assert payload["ok"] is False
    assert payload["error"] == "unknown_tool"
    assert result["messages"][-1].content == "无法识别工具后的答复"


async def test_streaming_yields_tool_round_and_final_text():
    """stream_mode=[updates, messages]：planner 更新携带 tool_calls，终答逐段到达。"""
    model = ScriptedChatModel(responses=[
        AIMessage(content="", tool_calls=[tool_call("fake_lookup", {"query": "dns"})]),
        AIMessage(content="流式最终答复"),
    ])
    graph = build(model, [fake_lookup])
    planner_calls = []
    tool_results = []
    deltas = []
    async for mode, payload in graph.astream(
        {"messages": [HumanMessage(content="hi")], "tool_rounds": 0},
        stream_mode=["updates", "messages"],
    ):
        if mode == "messages":
            chunk, metadata = payload
            if metadata.get("langgraph_node") in ("planner", "finalize"):
                text = chunk.content if isinstance(chunk.content, str) else ""
                if text and not getattr(chunk, "tool_call_chunks", None):
                    deltas.append(text)
            continue
        for node, update in payload.items():
            last = (update.get("messages") or [None])[-1]
            if node == "planner" and last is not None:
                planner_calls.extend(getattr(last, "tool_calls", None) or [])
            if node == "tools":
                tool_results.extend(update.get("messages") or [])
    assert [call["name"] for call in planner_calls] == ["fake_lookup"]
    assert len(tool_results) == 1
    assert "".join(deltas) == "流式最终答复"


# ---------------------------------------------------------------------------
# 上下文预算（P0.1）：planner / finalize 共用分级收缩，不再裸抛
# ---------------------------------------------------------------------------

from app.agent.context import message_chars  # noqa: E402
from app.agent.graph import TOOL_LIMIT_NOTE, ContextBudgetExceeded  # noqa: E402


@tool
async def bulk_lookup(query: str) -> dict:
    """返回超大结果，模拟单轮一次 kb_search 打满上下文。"""
    return {"ok": True, "data": {"query": query, "chunks": [{"content": "长" * 20000}]}}


def build_with_budget(model, tools, *, max_rounds=3, budget=0):
    return build_agent_graph(
        model=model, tools=tools, prompt_builder=static_prompt,
        max_rounds=max_rounds, context_max_chars=budget,
    )


def sent_chars(messages) -> int:
    return sum(message_chars(message) for message in messages)


async def test_planner_compresses_oversized_tool_result_within_budget():
    """单轮工具结果远超预算（线上 4 题形状）时可继续生成，不抛 agent_context_budget。"""
    model = ScriptedChatModel(responses=[
        AIMessage(content="", tool_calls=[tool_call("bulk_lookup", {"query": "q"}, "c1")]),
        AIMessage(content="最终答复"),
    ])
    graph = build_with_budget(model, [bulk_lookup], budget=4000)
    result = await graph.ainvoke({"messages": [HumanMessage(content="查一下")], "tool_rounds": 0})

    assert result["messages"][-1].content == "最终答复"
    assert len(model.seen) == 2, "planner 应被调用两次（发起工具 + 收结果后作答）"
    assert sent_chars(model.seen[-1]) <= 4000, "进入 planner 的消息必须收敛到预算内"
    # 原始工具结果仍留在 state 里（压缩只作用于发给模型的副本）
    tool_messages = [m for m in result["messages"] if isinstance(m, ToolMessage)]
    assert len(tool_messages[0].content) > 20000


async def test_finalize_compresses_oversized_history():
    """finalize 同样受预算约束：多轮大结果下不得抛 agent_context_budget。"""
    model = ScriptedChatModel(responses=[
        AIMessage(content="", tool_calls=[tool_call("bulk_lookup", {"query": "q1"}, "c1")]),
        AIMessage(content="", tool_calls=[tool_call("bulk_lookup", {"query": "q2"}, "c2")]),
        AIMessage(content="最终答复"),
    ])
    # max_rounds=1：第一轮工具后第二次 planner 再要工具即被路由到 finalize。
    graph = build_with_budget(model, [bulk_lookup], max_rounds=1, budget=5000)
    result = await graph.ainvoke({"messages": [HumanMessage(content="查一下")], "tool_rounds": 0})

    assert result["messages"][-1].content == "最终答复"
    finalize_messages = model.seen[-1]
    assert TOOL_LIMIT_NOTE.strip() in finalize_messages[0].content, "finalize 需带工具上限附注"
    assert sent_chars(finalize_messages) <= 5000


async def test_finalize_note_counts_toward_budget():
    """TOOL_LIMIT_NOTE 必须计入预算：否则 finalize 会稳定超出上限约一个附注长度。

    先以充裕预算实测 finalize 的实际体积 T（含附注），再令预算 = T-1：
    若附注未被计入，检查会以「不含附注的 T-49」通过而放行原文，实际体积仍为 T。
    """
    def scripted():
        return ScriptedChatModel(responses=[
            AIMessage(content="", tool_calls=[tool_call("bulk_lookup", {"query": "q1"}, "c1")]),
            AIMessage(content="", tool_calls=[tool_call("bulk_lookup", {"query": "q2"}, "c2")]),
            AIMessage(content="最终答复"),
        ])

    generous = scripted()
    await build_with_budget(generous, [bulk_lookup], max_rounds=1, budget=10**7).ainvoke(
        {"messages": [HumanMessage(content="查一下")], "tool_rounds": 0}
    )
    measured = sent_chars(generous.seen[-1])
    assert len(TOOL_LIMIT_NOTE) > 1, "附注长度必须大于 1 才具备区分度"

    tight = scripted()
    await build_with_budget(tight, [bulk_lookup], max_rounds=1, budget=measured - 1).ainvoke(
        {"messages": [HumanMessage(content="查一下")], "tool_rounds": 0}
    )
    assert sent_chars(tight.seen[-1]) <= measured - 1


async def test_floor_overflow_raises_typed_budget_error():
    """系统提示词 + 当前请求本身超限 → ContextBudgetExceeded，消息保留可观测标记。"""
    model = ScriptedChatModel(responses=[AIMessage(content="不该被调用")])
    graph = build_with_budget(model, [fake_lookup], budget=500)

    with pytest.raises(ContextBudgetExceeded) as excinfo:
        await graph.ainvoke(
            {"messages": [HumanMessage(content="超长提问" + "x" * 5000)], "tool_rounds": 0}
        )
    assert "agent_context_budget" in str(excinfo.value), "chat_trace 观测口径不变"
    assert model.calls == 0, "地板溢出时不得调用模型"


async def test_context_budget_disabled_is_noop():
    """budget=0（AGENT_CONTEXT_TRIM_ENABLED=false 语义）时不压缩也不拦截。"""
    huge = "y" * 30000
    model = ScriptedChatModel(responses=[AIMessage(content="最终答复")])
    graph = build_with_budget(model, [fake_lookup], budget=0)
    await graph.ainvoke({"messages": [HumanMessage(content=huge)], "tool_rounds": 0})

    assert model.seen[-1][1].content == huge, "关闭预算后消息原样透传"


if __name__ == "__main__":
    sys.exit(pytest.main([__file__, "-v"]))
