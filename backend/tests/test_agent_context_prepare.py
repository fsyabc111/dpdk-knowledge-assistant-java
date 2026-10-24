"""上下文预算准备单元测试（P0.1）。

纯函数级、无 DB、无网络。对应线上失败：`.logs/live_eval_qwen38_flash.json` 中
4 题的 `fallback_reason=agent graph failed: RuntimeError: agent_context_budget`。

覆盖：单轮超大工具结果、多轮分级压缩、预算边界、tool_call_id 配对不变式、
计量口径（含 tool_calls 参数）、开关关闭 no-op、地板溢出、压缩保留评分事实。
"""
from __future__ import annotations

import json
import sys
from pathlib import Path

import pytest
from langchain_core.messages import (
    AIMessage,
    HumanMessage,
    SystemMessage,
    ToolMessage,
)

ROOT = Path(__file__).resolve().parents[1]
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))

from app.agent.context import message_chars, prepare_messages

SYSTEM = "系统提示词：教学助教规则与红线。"


# ---------------------------------------------------------------------------
# 构造工具
# ---------------------------------------------------------------------------


def kb_payload(chunk_chars: int = 1500, chunks: int = 8, query: str = "q") -> str:
    """模拟 kb_search 的 ToolMessage 内容（上限 8×1500，见 tools._KB_CHUNK_CONTENT_LIMIT）。"""
    return json.dumps({
        "ok": True,
        "data": {
            "query": query,
            "chunks": [
                {"ref": index, "source_path": f"doc{index}.md", "section": "s",
                 "title": f"t{index}", "content": "长" * chunk_chars}
                for index in range(1, chunks + 1)
            ],
            "count": chunks,
        },
    }, ensure_ascii=False)


def tool_call(name: str, args: dict, call_id: str) -> dict:
    return {"name": name, "args": args, "id": call_id, "type": "tool_call"}


def kb_round(call_ids: list, chunk_chars: int = 1500, chunks: int = 8) -> list:
    """一轮：一个 AIMessage 发起 N 次 kb_search + N 条 ToolMessage 结果。"""
    calls = [tool_call("kb_search", {"query": f"q{index}"}, cid)
             for index, cid in enumerate(call_ids)]
    messages: list = [AIMessage(content="", tool_calls=calls)]
    messages.extend(
        ToolMessage(content=kb_payload(chunk_chars, chunks, f"q{index}"),
                    tool_call_id=cid, name="kb_search")
        for index, cid in enumerate(call_ids)
    )
    return messages


def total(messages: list) -> int:
    return sum(message_chars(message) for message in messages)


def assert_pairing_intact(messages: list) -> None:
    """不变式：ToolMessage 与其 AIMessage.tool_calls 必须成对出现，无孤儿。"""
    call_ids = {
        call["id"]
        for message in messages
        for call in (getattr(message, "tool_calls", None) or [])
    }
    answered: set = set()
    for message in messages:
        if getattr(message, "type", "") != "tool":
            continue
        assert message.tool_call_id in call_ids, f"孤儿 ToolMessage：{message.tool_call_id}"
        answered.add(message.tool_call_id)
    for message in messages:
        for call in getattr(message, "tool_calls", None) or []:
            assert call["id"] in answered, f"tool_call 缺结果：{call['id']}"


def tool_contents(messages: list) -> list:
    return [m.content for m in messages if getattr(m, "type", "") == "tool"]


# ---------------------------------------------------------------------------
# prep_01：单轮超大工具结果（线上 route_kb_engine_ops 形状）
# ---------------------------------------------------------------------------


def test_prep01_single_oversized_round_is_compressed():
    """一轮 8 次 kb_search 原文约 10 万字符——远超预算，准备后必须收敛到限内。"""
    history: list = [HumanMessage(content="engine_ops_t 是什么？")]
    history.extend(kb_round([f"c{i}" for i in range(8)]))

    limit = 8000
    prepared = prepare_messages(SYSTEM, history, max_chars=limit)

    assert not prepared.over_budget
    assert prepared.total_chars <= limit
    assert len(prepared.messages) == len(history) + 1  # 系统提示词 + 全部消息
    assert_pairing_intact(prepared.messages)
    # 最近一轮被压缩（原文 10 万字符不可能留在限内）
    assert all('"chunks"' not in content for content in tool_contents(prepared.messages))


# ---------------------------------------------------------------------------
# prep_02：多轮分级压缩——够用即停，早轮压 top-2，最近一轮保持原文
# ---------------------------------------------------------------------------


def test_prep02_multi_round_compresses_older_keeps_latest_raw():
    """只压早轮就能进限时，最近一轮必须保留原文（M4a E5 既有语义，黄金 s29）。"""
    history: list = [HumanMessage(content="多轮检索")]
    history.extend(kb_round(["c1"], chunk_chars=400, chunks=3))
    history.extend(kb_round(["c2"], chunk_chars=400, chunks=3))

    raw_total = total([SystemMessage(content=SYSTEM), *history])
    # 限值刚好比原文少一点：必须压早轮，但不该动最近一轮。
    limit = raw_total - 1
    prepared = prepare_messages(SYSTEM, history, max_chars=limit)

    assert not prepared.over_budget
    assert prepared.level == 1, "只应升到 L1（早轮压缩），不得一次压到底"
    assert prepared.total_chars <= limit
    by_id = {m.tool_call_id: m.content for m in prepared.messages
             if getattr(m, "type", "") == "tool"}
    assert "top_chunks" in by_id["c1"]
    assert '"chunks"' in by_id["c2"], "最近一轮保持原文"
    assert_pairing_intact(prepared.messages)


def test_prep02b_latest_round_raw_under_generous_limit():
    """预算充裕时也不动最近一轮（只有早轮走 E5 压缩），避免无谓的信息损失。"""
    history: list = [HumanMessage(content="简单问题")]
    history.extend(kb_round(["c1"], chunk_chars=100, chunks=2))
    history.extend(kb_round(["c2"], chunk_chars=100, chunks=2))

    prepared = prepare_messages(SYSTEM, history, max_chars=100000)
    assert prepared.level == 1
    assert prepared.messages[-1].content == history[-1].content, "最近一轮原文"
    human = [m for m in prepared.messages if getattr(m, "type", "") == "human"]
    assert human and human[0].content == "简单问题", "用户消息不参与压缩"
    assert not prepared.over_budget


# ---------------------------------------------------------------------------
# prep_03：预算边界
# ---------------------------------------------------------------------------


def test_prep03_at_baseline_limit_stays_at_level_one():
    """恰好等于「早轮压缩后」的体积（`<=` 语义）→ 停在 L1，不继续损失信息。"""
    history: list = [HumanMessage(content="边界")]
    history.extend(kb_round(["c1"], chunk_chars=300, chunks=4))
    history.extend(kb_round(["c2"], chunk_chars=300, chunks=4))
    baseline = total(prepare_messages(SYSTEM, history, max_chars=100000).messages)

    prepared = prepare_messages(SYSTEM, history, max_chars=baseline)
    assert prepared.level == 1
    assert prepared.total_chars == baseline


def test_prep03b_just_over_baseline_escalates_only_one_level():
    """刚好比 L1 少一个字符时才升级，且只升一级。"""
    history: list = [HumanMessage(content="边界")]
    history.extend(kb_round(["c1"], chunk_chars=300, chunks=4))
    history.extend(kb_round(["c2"], chunk_chars=300, chunks=4))
    baseline = total(prepare_messages(SYSTEM, history, max_chars=100000).messages)

    prepared = prepare_messages(SYSTEM, history, max_chars=baseline - 1)
    assert prepared.level == 2
    assert prepared.total_chars <= baseline - 1


# ---------------------------------------------------------------------------
# prep_04：tool_call_id 配对不变式
# ---------------------------------------------------------------------------


def test_prep04_pairing_survives_every_level():
    """逐级收紧预算，任何一级都不得产生孤儿 ToolMessage / 悬空 tool_call。"""
    history: list = [HumanMessage(content="历史一"), AIMessage(content="历史答复一")]
    history.extend(kb_round(["c1", "c2"], chunk_chars=1500, chunks=8))
    history.append(HumanMessage(content="继续问"))
    history.extend(kb_round(["c3"], chunk_chars=1500, chunks=8))
    full = total([SystemMessage(content=SYSTEM), *history])

    for limit in (full, full // 2, full // 5, full // 20, 900, 700):
        prepared = prepare_messages(SYSTEM, history, max_chars=limit)
        assert_pairing_intact(prepared.messages)


def test_prep04b_keeps_last_round_over_older_history():
    """极端预算下优先丢最旧的消息，最近一轮工具结果必须留下。"""
    history: list = [HumanMessage(content="很久以前的提问" + "旧" * 500)]
    history.extend(kb_round(["c1"], chunk_chars=1500, chunks=4))
    history.extend(kb_round(["c2"], chunk_chars=1500, chunks=2))

    prepared = prepare_messages(SYSTEM, history, max_chars=4000)
    assert not prepared.over_budget
    kept_ids = {m.tool_call_id for m in prepared.messages if getattr(m, "type", "") == "tool"}
    assert "c2" in kept_ids, "最近一轮不得被丢"
    assert_pairing_intact(prepared.messages)


# ---------------------------------------------------------------------------
# prep_05：计量口径
# ---------------------------------------------------------------------------


def test_prep05_counts_tool_call_args():
    """tool_calls 的参数（grade_plugin 携带完整 plugin_source）必须计入预算。"""
    source = "x" * 5000
    message = AIMessage(content="", tool_calls=[
        tool_call("grade_plugin", {"protocol": "dns", "plugin_source": source}, "c1"),
    ])
    assert message_chars(message) >= len(source)
    assert message_chars(SystemMessage(content=SYSTEM)) == len(SYSTEM)
    assert message_chars(HumanMessage(content="abc")) == 3


def test_prep05b_large_tool_call_args_trigger_compression():
    """历史轮次的 tool_calls 参数（grade_plugin 携带完整 plugin_source）必须能收敛。

    参数只计体积不压内容时，历史轮里的源码副本会成为无法压缩的死重。
    """
    source = "y" * 20000
    history: list = [
        HumanMessage(content="评测"),
        AIMessage(content="", tool_calls=[
            tool_call("grade_plugin", {"protocol": "dns", "plugin_source": source}, "c1"),
        ]),
        ToolMessage(content=kb_payload(200, 2), tool_call_id="c1", name="grade_plugin"),
    ]
    history.extend(kb_round(["c2"], chunk_chars=200, chunks=2))

    prepared = prepare_messages(SYSTEM, history, max_chars=8000)

    assert not prepared.over_budget
    assert prepared.total_chars <= 8000
    assert_pairing_intact(prepared.messages)
    # 最近一轮的参数不动，历史轮的重参数被压成占位符
    latest = [m for m in prepared.messages if getattr(m, "tool_calls", None)][-1]
    assert latest.tool_calls[0]["args"]["query"] == "q0"
    older = [m for m in prepared.messages if getattr(m, "tool_calls", None)][0]
    assert "已省略" in json.dumps(older.tool_calls[0]["args"], ensure_ascii=False)
    assert older.tool_calls[0]["id"] == "c1", "tool_call id 必须保留"


# ---------------------------------------------------------------------------
# prep_06：开关关闭 no-op
# ---------------------------------------------------------------------------


def test_prep06_disabled_is_noop():
    """max_chars=0 表示关闭上下文预算（AGENT_CONTEXT_TRIM_ENABLED=false 语义）。"""
    history: list = [HumanMessage(content="q")]
    history.extend(kb_round(["c1"], chunk_chars=1500, chunks=8))

    prepared = prepare_messages(SYSTEM, history, max_chars=0)
    assert prepared.level == 0
    assert not prepared.over_budget
    assert prepared.messages == [SystemMessage(content=SYSTEM), *history]


# ---------------------------------------------------------------------------
# prep_07：地板溢出——不抛异常，交给调用方安全降级
# ---------------------------------------------------------------------------


def test_prep07_floor_overflow_reports_instead_of_raising():
    """系统提示词 + 当前用户消息本身超限时，返回 over_budget 而不是抛异常。

    线上表现是 `RuntimeError: agent_context_budget` 直接终止生成；新契约要求
    可解释、可安全降级，因此这里断言「不抛」且关键内容仍在。
    """
    huge_question = "帮我评测：" + "z" * 40000
    history: list = [HumanMessage(content=huge_question)]
    history.extend(kb_round(["c1"], chunk_chars=1500, chunks=8))

    prepared = prepare_messages(SYSTEM, history, max_chars=10000)

    assert prepared.over_budget is True
    assert prepared.messages[0].content == SYSTEM, "系统安全规则不得被丢弃"
    assert any(m.content == huge_question for m in prepared.messages), "当前请求不得被丢弃"
    assert_pairing_intact(prepared.messages)


def test_prep07b_large_reference_prompt_still_fits_with_trim():
    """系统提示词被教师参考实现撑大（线上 13076 字符）时，仍应靠压缩进限。"""
    from app.agent.prompts import build_system_prompt

    system = build_system_prompt() + "\n\n## 教师参考实现（仅供你理解错因）\n" + "R" * 11600
    history: list = [HumanMessage(content="帮我评测 dns 插件")]
    history.extend(kb_round(["c1"], chunk_chars=1500, chunks=8))
    history.extend(kb_round(["c2"], chunk_chars=1500, chunks=8))

    prepared = prepare_messages(system, history, max_chars=24000)
    assert not prepared.over_budget
    assert prepared.total_chars <= 24000
    assert prepared.level >= 1


# ---------------------------------------------------------------------------
# prep_08：压缩保留评分事实（教学反馈契约）
# ---------------------------------------------------------------------------


def test_prep08_grading_summary_keeps_score_and_failures():
    """grade_plugin 被压缩后仍须保留得分/通过数与失败用例的期望·实际差异。"""
    from app.agent.tools import tool_context_summary

    payload = json.dumps({
        "ok": True,
        "data": {
            "protocol": "dns", "score": 62, "passed": 5, "cases_total": 8,
            "results": [
                {"name": "dns_basic_1", "result": "PASS", "failures": []},
                {"name": "dns_basic_3", "result": "FAIL", "failures": [
                    {"desc": "qname 标签编码", "expected": "example.com", "actual": "www.example.com"},
                ]},
            ],
        },
    }, ensure_ascii=False)

    summary = tool_context_summary("grade_plugin", payload)

    assert len(summary) < len(payload), "压缩后必须更小"
    assert "62" in summary and "5" in summary and "8" in summary
    assert "dns_basic_3" in summary
    assert "example.com" in summary and "www.example.com" in summary
    # 不得退化成只有一句话、丢掉全部评分事实
    assert "qname" in summary


def test_prep08b_grading_summary_is_bounded():
    """大量失败用例时压缩结果必须有界，不能把上下文重新撑爆。"""
    from app.agent.tools import tool_context_summary

    payload = json.dumps({
        "ok": True,
        "data": {
            "protocol": "dns", "score": 0, "passed": 0, "cases_total": 40,
            "results": [
                {"name": f"dns_case_{index}", "result": "FAIL", "failures": [
                    {"desc": "d" * 200, "expected": "e" * 200, "actual": "a" * 200},
                    {"desc": "d" * 200, "expected": "e" * 200, "actual": "a" * 200},
                ]}
                for index in range(40)
            ],
        },
    }, ensure_ascii=False)

    summary = tool_context_summary("grade_plugin", payload)
    assert len(summary) <= 2000, f"压缩结果无界：{len(summary)}"
    assert "dns_case_0" in summary  # 前若干条失败用例仍可定位


# ---------------------------------------------------------------------------
# prep_09：kb_search 摘要是「覆盖式」而非「前缀式」（P2；route_kb_engine_ops 定因）
# ---------------------------------------------------------------------------
#
# 线上失败形状（P0.1 记录）：`can_handle` 在 `plugin/engine.h` 片段的第 938 字符，
# 检索可达，但旧摘要 = 前 2 名片段 × 前 300 字符 ⇒ 结果是**位置函数**：任何落在
# 第 300 字符之后、或第 3 名之后的证据都不可能恢复。以下用例把「位置决定去留」
# 钉死为失败，并把「是不是关键行」钉死为通过条件。

_KB_WINDOW = 1500  # 片段进入上下文的可见窗口上限（tools._KB_CHUNK_CONTENT_LIMIT）
_SIG_TOKEN = "can_handle_marker"


def _prose(chars: int, filler: str = "长") -> str:
    """铺满 chars 个字符的散文（每行 30 字符，不含任何声明标记）。"""
    line = filler * 29 + "\n"
    text = line * (chars // 30)
    return text + filler * (chars - len(text))


def _window_with_signature(offset: int, token: str = _SIG_TOKEN) -> str:
    """1500 字符片段：在指定偏移处放一条含 token 的签名行。

    offset 覆盖头（<120）、中段、尾（>1440）三处，摘要对三者都必须保住关键行。
    """
    line = f"int {token}(const uint8_t *pkt, uint32_t len);"
    assert offset + len(line) <= _KB_WINDOW, "签名行必须完整落在 1500 字符窗口内"
    content = _prose(offset) + line
    return content + _prose(_KB_WINDOW - len(content))


def _kb_payload_with(contents: list, query: str = "q") -> str:
    return json.dumps({
        "ok": True,
        "data": {
            "query": query,
            "chunks": [
                {"ref": index, "source_path": f"doc{index}.md", "section": "s",
                 "title": f"t{index}", "content": content}
                for index, content in enumerate(contents, start=1)
            ],
            "count": len(contents),
        },
    }, ensure_ascii=False)


@pytest.mark.parametrize("offset", [60, 300, 700, 938, 1200, 1440])
def test_prep09_kb_summary_keeps_signature_line_at_any_offset(offset):
    """同一条签名行放在窗口各处，压缩后都必须还在。

    旧实现只留前 300 字符 ⇒ offset=700/938/1200/1450 全部丢失（线上 `can_handle`
    正是第 938 字符）。新实现按「关键行」而非「前缀」保留。
    """
    from app.agent.tools import tool_context_summary

    payload = _kb_payload_with([_window_with_signature(offset), _prose(_KB_WINDOW)])
    summary = tool_context_summary("kb_search", payload)
    assert _SIG_TOKEN in summary, f"偏移 {offset} 的签名行被压缩丢弃"


def test_prep09b_kb_summary_keeps_third_ranked_chunk():
    """第 3 名片段也必须可恢复（旧实现只留前 2 名，名次决定去留）。"""
    from app.agent.tools import tool_context_summary

    contents = [
        _prose(_KB_WINDOW),
        _prose(_KB_WINDOW),
        _window_with_signature(900, token="third_chunk_only"),
    ]
    summary = tool_context_summary("kb_search", _kb_payload_with(contents))
    assert "third_chunk_only" in summary


def test_prep09c_kb_summary_prefers_query_matched_lines():
    """声明行密集时，含检索词的行优先于等距抽样。

    线上形状：模型查 `can_handle`，命中的片段里声明行很多，等距抽样会把它抽没——
    「检索到了却看不见」正是这次要修的东西。
    """
    from app.agent.tools import tool_context_summary

    lines = [f"static int helper_{index}(const uint8_t *pkt);" for index in range(17)]
    lines.append("int can_handle(const uint8_t *pkt, uint32_t len);")
    lines.extend(f"static int helper_{index}(const uint8_t *pkt);" for index in range(100, 114))
    content = "\n".join(lines)
    assert len(content) <= _KB_WINDOW
    assert content.index("can_handle(const") > 300, "目标行必须在旧实现的截断点之外"

    summary = tool_context_summary(
        "kb_search", _kb_payload_with([content], query="can_handle 的签名")
    )
    # 只断言片段正文：query 会回显在摘要里，直接搜字符串会被回显满足（假通过）。
    digest = json.loads(summary)["top_chunks"][0]["content"]
    assert "can_handle" in digest, f"检索词命中的声明行被抽样抽掉：{digest!r}"


def test_prep09d_kb_summary_is_bounded():
    """覆盖式摘要是压缩，不是复制：8×1500 字符原文必须收敛，且有硬上界。"""
    from app.agent.tools import tool_context_summary

    contents = [_window_with_signature(offset)
                for offset in (200, 400, 600, 800, 1000, 1100, 1200, 1300)]
    payload = _kb_payload_with(contents)
    summary = tool_context_summary("kb_search", payload)

    assert len(summary) < len(payload) // 4, f"压缩不足：{len(summary)}/{len(payload)}"
    assert len(summary) <= 1400, f"压缩结果无界：{len(summary)}"
    assert len(summary) < len(contents[0]), "摘要必须小于单个片段原文"


def test_prep09e_kb_summary_keeps_locator_fields():
    """摘要必须保住能回到知识源的定位字段（路径/小节/标题）。"""
    from app.agent.tools import tool_context_summary

    payload = _kb_payload_with([_window_with_signature(900)])
    summary = tool_context_summary("kb_search", payload)
    for field in ("doc1.md", "t1"):
        assert field in summary, f"摘要丢失定位字段 {field}"


def test_prep09f_prepare_messages_keeps_deep_evidence_at_level_two():
    """端到端（对齐线上 route_kb_engine_ops）：预算升到 L2 时，

    位于片段深处的签名行仍必须留在真正送进模型的消息里——压缩不能把证据压没。
    """
    history: list = [HumanMessage(content="engine_ops_t 里 can_handle 的签名是什么？")]
    history.append(AIMessage(content="", tool_calls=[
        tool_call("kb_search", {"query": "can_handle"}, "c1"),
    ]))
    history.append(ToolMessage(
        content=_kb_payload_with([_window_with_signature(938)], query="can_handle"),
        tool_call_id="c1", name="kb_search",
    ))
    history.extend(kb_round(["c2"], chunk_chars=1500, chunks=8))

    prepared = prepare_messages(SYSTEM, history, max_chars=6000)

    assert not prepared.over_budget
    assert prepared.level == 2, "形状要求升到 L2（连最近一轮也压）"
    assert _SIG_TOKEN in "".join(tool_contents(prepared.messages)), "深层证据被压没了"
    assert_pairing_intact(prepared.messages)


# ---------------------------------------------------------------------------
# prep_10：分级观测口径（每一级都留痕）
# ---------------------------------------------------------------------------


def _trim_events(caplog_records: list) -> list:
    events = []
    for record in caplog_records:
        message = record.getMessage()
        if message.startswith("{"):
            parsed = json.loads(message)
            if parsed.get("event") == "agent_context_trimmed":
                events.append(parsed)
    return events


def test_prep10_trim_log_records_level_one(caplog):
    """第 1 级（早轮压缩，常规健康路径）也必须留痕。

    P0.1 只在 level >= 2 记录，于是「L1 就够」被系统性隐掉，
    「14/14 都是 L2」不可解释——无法判断压缩粒度是否够用。
    """
    import logging

    history: list = [HumanMessage(content="q")]
    history.extend(kb_round(["c1"], chunk_chars=300, chunks=4))
    history.extend(kb_round(["c2"], chunk_chars=300, chunks=4))

    with caplog.at_level(logging.WARNING, logger="assistant"):
        prepare_messages(SYSTEM, history, max_chars=100000)

    events = _trim_events(list(caplog.records))
    assert events, "第 1 级没有留痕"
    assert events[-1]["level"] == 1
    assert events[-1]["applied"] is True, "早轮确实被压缩，applied 必须为真"


def test_prep10b_trim_log_marks_noop_as_not_applied(caplog):
    """没有可压缩内容时，applied 必须为假——「升级了」不等于「真的收缩了」。"""
    import logging

    history: list = [HumanMessage(content="q"), AIMessage(content="答")]

    with caplog.at_level(logging.WARNING, logger="assistant"):
        prepared = prepare_messages(SYSTEM, history, max_chars=100000)

    assert prepared.level == 1
    events = _trim_events(list(caplog.records))
    assert events and events[-1]["level"] == 1
    assert events[-1]["applied"] is False


# ---------------------------------------------------------------------------
# prep_11：「已执行」判据（B5）——已完成工具轮不再是不可动的死重
# ---------------------------------------------------------------------------
#
# 历史反例 F1：系统 10k + 用户 9k + 最后单元是**已完成**评测轮（plugin_source
# 参数约 9k + 短结果），预算 24k。旧口径把「最后一个单元」整体豁免——参数压不掉、
# 单元不可丢 → 虚假 floor_exceeded，而真实「系统 + 当前请求」仅约 19k。
# 新判据按 per-call 配对：tool_call 的 id 有配对 ToolMessage = 已执行，参数可压、
# 所在单元受第 5 级保护；无配对的悬空调用 = 未执行，参数原文保留且单元可被丢弃。


def _noop_summary(name: str, content: str) -> str:
    """不压缩的 summarizer：把「升到哪一级」隔离给被测级别，避免摘要实现干扰断言。"""
    return content


def test_prep11_completed_last_round_args_are_compactable():
    """F1 回归：最后单元是**已完成**评测轮时，其超长参数仍可被压掉。

    旧语义下该形状必判 floor_exceeded（最后单元两级都动不了）；新契约下
    第 3 级按调用配对压参数后即进限——且必须是「压缩」而非「整单元丢弃」，
    因为这条已完成工具结果正是 finalize 需要的证据。
    """
    system = "S" * 10000
    source = "P" * 9000
    history: list = [HumanMessage(content="帮我评测 dns 插件：" + "问" * 9000)]
    history.append(AIMessage(content="", tool_calls=[
        tool_call("grade_plugin", {"protocol": "dns", "plugin_source": source}, "g1"),
    ]))
    history.append(ToolMessage(
        content='{"ok": true, "data": {"score": 80, "passed": 4, "cases_total": 5}}',
        tool_call_id="g1", name="grade_plugin",
    ))

    prepared = prepare_messages(system, history, max_chars=24000, summarizer=_noop_summary)

    assert prepared.over_budget is False
    assert prepared.level == 3, "已执行轮的参数应在第 3 级被压掉后直接进限"
    assert prepared.total_chars <= 24000
    assert_pairing_intact(prepared.messages)

    ai = next(m for m in prepared.messages if getattr(m, "tool_calls", None))
    call = ai.tool_calls[0]
    assert call["id"] == "g1" and call["name"] == "grade_plugin"
    assert call["args"]["protocol"] == "dns", "短实参不该被改写"
    assert call["args"]["plugin_source"].startswith("[已省略"), "已执行调用的超长参数必须压成占位符"
    # ToolMessage 仍在原位：紧跟其 AIMessage，配对不破
    follower = prepared.messages[prepared.messages.index(ai) + 1]
    assert getattr(follower, "type", "") == "tool" and follower.tool_call_id == "g1"


def test_prep11b_pending_call_args_keep_raw():
    """未执行的悬空调用（无配对 ToolMessage）参数必须原文保留。

    它是模型刚发出、尚未执行的请求，压掉参数等于篡改尚未发生的调用；
    同一条 AIMessage 里已执行的调用则照压不误（per-call 判定）。
    """
    history: list = [HumanMessage(content="评测一下")]
    history.append(AIMessage(content="", tool_calls=[
        tool_call("grade_plugin", {"protocol": "dns", "plugin_source": "Y" * 9000}, "g1"),
    ]))
    history.append(ToolMessage(
        content='{"ok": true, "data": {"score": 80}}', tool_call_id="g1", name="grade_plugin",
    ))
    pending_source = "D" * 3000
    history.append(AIMessage(content="", tool_calls=[
        tool_call("grade_plugin", {"protocol": "dns", "plugin_source": pending_source}, "g_pending"),
    ]))

    prepared = prepare_messages(SYSTEM, history, max_chars=6000, summarizer=_noop_summary)

    assert prepared.level == 3 and not prepared.over_budget
    by_call = {
        call["id"]: call
        for message in prepared.messages
        for call in (getattr(message, "tool_calls", None) or [])
    }
    assert "已省略" in json.dumps(by_call["g1"]["args"], ensure_ascii=False)
    assert by_call["g_pending"]["args"]["plugin_source"] == pending_source, "未执行调用参数必须原文"
    assert by_call["g_pending"]["name"] == "grade_plugin"
    # 悬空是输入固有形状（模型刚发出），但不许出现孤儿 ToolMessage
    for message in prepared.messages:
        if getattr(message, "type", "") == "tool":
            assert message.tool_call_id in by_call


def test_prep11c_drop_units_drops_pending_keeps_answered():
    """第 5 级：压完仍超限时，悬空未执行单元可被整单元丢弃，已应答轮保留。

    旧口径无条件保护最后一个单元，会让悬空调用比「真实工具证据」更不可丢：
    本形状在旧实现里先丢已应答轮、再因悬空单元受保护而判 floor_exceeded。
    """
    history: list = [HumanMessage(content="继续")]
    history.append(AIMessage(content="", tool_calls=[
        tool_call("kb_search", {"query": "q0"}, "a1"),
    ]))
    history.append(ToolMessage(
        content=kb_payload(1500, 4), tool_call_id="a1", name="kb_search",
    ))
    history.append(AIMessage(content="", tool_calls=[
        tool_call("grade_plugin", {"protocol": "dns", "plugin_source": "D" * 20000}, "g_pending"),
    ]))

    prepared = prepare_messages(SYSTEM, history, max_chars=5000, summarizer=_noop_summary)

    assert not prepared.over_budget and prepared.level == 5
    assert prepared.stats["dropped_units"] == 1
    assert_pairing_intact(prepared.messages)  # 整单元丢弃后无孤儿、无悬空
    call_ids = {
        call["id"]
        for message in prepared.messages
        for call in (getattr(message, "tool_calls", None) or [])
    }
    assert "a1" in call_ids, "最后一个已应答轮必须保留"
    assert "g_pending" not in call_ids, "悬空未执行单元应被整单元丢弃"
    assert any(getattr(m, "type", "") == "tool" and m.tool_call_id == "a1"
               for m in prepared.messages), "已应答轮的 ToolMessage 必须留下"
    assert any(getattr(m, "type", "") == "human" for m in prepared.messages)


if __name__ == "__main__":
    sys.exit(pytest.main([__file__, "-v"]))
