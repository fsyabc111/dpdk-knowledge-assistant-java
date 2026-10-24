"""上下文预算准备（M4a E5 的分级扩展，P0.1 修复）。

线上现象：`.logs/live_eval_qwen38_flash.json` 4 题 `fallback_reason` 为
``agent graph failed: RuntimeError: agent_context_budget``。实测根因：

1. planner 只压缩「严格早于最后一个带 tool_calls 的 AIMessage」的 ToolMessage，
   最近一轮永远原文进上下文；单次 ``kb_search`` 最多 8×1500=12724 字符，
   一轮多调即可单独超限。
2. finalize 完全不做准备，直接用原始消息列表。
3. 系统提示词在注入教师参考实现后可占预算的一半以上（本机 dns 实测 13076/24000）。
4. 原有计量 ``sum(len(str(m.content)))`` 不计 AIMessage 的 tool_calls 参数，
   而 ``grade_plugin`` 的参数要携带完整 ``plugin_source``。

本模块把这些收敛成一条确定性的「准备 + 分级降级」管线：**够用即停**，不做无谓的
信息损失；只有在预算无法满足时才向上返回 ``over_budget``，由调用方走安全降级，
不再抛异常终止生成。

配对不变式（测试锁死）：消息按「单元」处理——带 tool_calls 的 AIMessage 与其全部
ToolMessage 是**原子单元**，要么一起留要么一起删；ToolMessage 只改写 ``content``，
AIMessage 只改写``tool_calls`` 的参数，``tool_call_id`` / ``id`` / 类型 / 顺序都不变。
因此永远不产生孤儿 ToolMessage 或悬空 tool_call。
「已执行」是贯穿各级的判据：tool_call 的 id 有配对 ToolMessage 才算已执行——
第 3 级只压已执行调用的参数，第 5 级只保留最后一个已执行轮所在的单元。
"""
from __future__ import annotations

import json
import logging
from dataclasses import dataclass, field
from typing import Callable, List, Optional, Sequence

from langchain_core.messages import BaseMessage, SystemMessage, ToolMessage

logger = logging.getLogger("assistant")

# 单条 ToolMessage 在第 4 级（硬截断）保留的字符数。
_MIN_TOOL_CHARS = 200

# 历史轮次 tool_call 参数中，长于该值的字符串实参被替换为占位符（第 3 级）。
_ARG_COMPACT_THRESHOLD = 200

Summarizer = Callable[[str, str], str]


def message_chars(message: BaseMessage) -> int:
    """模型实际可见的消息体积：content + tool_calls 的参数。

    ``grade_plugin`` 的工具参数携带完整 ``plugin_source``（可达数 KB），
    只算 content 会显著低估真实请求体积。
    """
    total = len(str(getattr(message, "content", "") or ""))
    for call in getattr(message, "tool_calls", None) or []:
        total += len(str(call.get("name") or ""))
        total += len(str(call.get("id") or ""))
        total += len(json.dumps(call.get("args") or {}, ensure_ascii=False, default=str))
    return total


def messages_chars(messages: Sequence[BaseMessage]) -> int:
    return sum(message_chars(message) for message in messages)


@dataclass
class PreparedContext:
    """准备结果。``messages`` 已含系统提示词，可直接交给模型。"""

    messages: List[BaseMessage]
    total_chars: int
    # 0=原样；1=压早轮；2=压全部工具结果；3=再压历史轮参数；
    # 4=再硬截断；5=再整单元丢弃。
    level: int = 0
    # True 表示连「系统提示词 + 当前请求」都放不下，调用方应走安全降级而不是调模型。
    over_budget: bool = False
    # 观测用统计。
    stats: dict = field(default_factory=dict)


def _default_summarizer(tool_name: str, content: str) -> str:
    from .tools import tool_context_summary

    return tool_context_summary(tool_name, content)


# ---------------------------------------------------------------------------
# 单元划分（配对不变式的基础）
# ---------------------------------------------------------------------------


def _unit_slices(history: Sequence[BaseMessage]) -> List[tuple]:
    """把消息切成原子单元，返回 ``(start, end)`` 半开区间列表（索引基于 history）。

    带 tool_calls 的 AIMessage 与其后紧邻的、id 匹配的 ToolMessage 组成一个单元。
    """
    slices: List[tuple] = []
    index = 0
    while index < len(history):
        message = history[index]
        ids = {call.get("id") for call in (getattr(message, "tool_calls", None) or [])}
        if not ids:
            slices.append((index, index + 1))
            index += 1
            continue
        cursor = index + 1
        while (
            cursor < len(history)
            and isinstance(history[cursor], ToolMessage)
            and getattr(history[cursor], "tool_call_id", None) in ids
        ):
            cursor += 1
        slices.append((index, cursor))
        index = cursor
    return slices


def _answered_call_ids(history: Sequence[BaseMessage]) -> set:
    """history 中「已执行」tool_call 的 id 集合 = 有配对 ToolMessage 的调用。

    空串 / None 的 ``tool_call_id`` 不计入——无法可靠配对（与既有 truthy
    过滤口径一致）。第 3 级参数压缩与第 5 级单元保护共用这一判据。
    """
    return {
        message.tool_call_id for message in history
        if isinstance(message, ToolMessage) and message.tool_call_id
    }


def _last_answered_tool_index(history: Sequence[BaseMessage]) -> int:
    """最后一个「有工具结果」的工具轮起点；没有则 -1。

    必须是「有结果」的轮次：finalize 前的历史以一条**未执行**的 tool_calls
    AIMessage 结尾（该轮被路由到 finalize 而非 tools，没有对应 ToolMessage）。
    若按「最后一个带 tool_calls 的消息」判断，最近一轮真实工具结果反而会被判为
    早轮而压缩掉——而 finalize 正是最需要这份证据的地方。
    """
    answered = _answered_call_ids(history)
    return max(
        (index for index, message in enumerate(history)
         if any(call.get("id") in answered for call in (getattr(message, "tool_calls", None) or []))),
        default=-1,
    )


# ---------------------------------------------------------------------------
# 各级变换（纯函数，均从原始 history 出发，避免二次压缩）
# ---------------------------------------------------------------------------


def _shrink(content: str, summarizer: Summarizer, name: str) -> str:
    """压缩单条工具结果；只接受确实变小的结果（空内容压缩后反而会变大）。"""
    compressed = summarizer(name, content)
    return compressed if len(compressed) < len(content) else content


def _compress_older(history: Sequence[BaseMessage], summarizer: Summarizer) -> List[BaseMessage]:
    """第 1 级：压缩早于最后一个**有结果**工具轮的 ToolMessage（= 原有 E5 行为）。"""
    last_tool_ai = _last_answered_tool_index(history)
    return [
        message.model_copy(update={
            "content": _shrink(str(message.content), summarizer, message.name or ""),
        })
        if isinstance(message, ToolMessage) and index < last_tool_ai else message
        for index, message in enumerate(history)
    ]


def _compress_all(history: Sequence[BaseMessage], summarizer: Summarizer) -> List[BaseMessage]:
    """第 2 级：压缩全部 ToolMessage，含最近一轮。"""
    return [
        message.model_copy(update={
            "content": _shrink(str(message.content), summarizer, message.name or ""),
        })
        if isinstance(message, ToolMessage) else message
        for message in history
    ]


def _compact_arg(value) -> object:
    if isinstance(value, str) and len(value) > _ARG_COMPACT_THRESHOLD:
        return f"[已省略 {len(value)} 字]"
    return value


def _compact_historical_args(history: Sequence[BaseMessage]) -> List[BaseMessage]:
    """第 3 级：把已执行 tool_calls 里的长实参压成占位符（按调用判定）。

    参数已计入预算，而工具结果压缩动不到它；已执行轮里的 ``plugin_source``
    因此会变成无法收敛的死重。判据是 per-call 的「该调用 id 是否有配对
    ToolMessage」，而非「是不是最后一个单元」：已执行完的最后单元同样要压；
    无配对 ToolMessage 的调用是模型刚发出、尚未执行的请求，参数原文保留。
    ``id`` / ``name`` / 顺序原样保留。
    """
    answered = _answered_call_ids(history)
    result: List[BaseMessage] = []
    for message in history:
        calls = getattr(message, "tool_calls", None) or []
        if not calls or not any(call.get("id") in answered for call in calls):
            result.append(message)
            continue
        compacted = [
            {**call, "args": {key: _compact_arg(value) for key, value in (call.get("args") or {}).items()}}
            if call.get("id") in answered else call
            for call in calls
        ]
        result.append(message.model_copy(update={"tool_calls": compacted}))
    return result


def _truncate_tools(history: Sequence[BaseMessage]) -> List[BaseMessage]:
    """第 4 级：把剩余 ToolMessage 内容硬截断到最小长度。"""
    result: List[BaseMessage] = []
    for message in history:
        if isinstance(message, ToolMessage) and len(str(message.content)) > _MIN_TOOL_CHARS:
            result.append(message.model_copy(update={
                "content": str(message.content)[:_MIN_TOOL_CHARS] + "…[已截断]",
            }))
        else:
            result.append(message)
    return result


def _drop_units(history: Sequence[BaseMessage], slices: Sequence[tuple]) -> List[BaseMessage]:
    """第 5 级：从最旧开始整单元丢弃。

    受保护的单元：含最后一个**已执行**工具轮（``_last_answered_tool_index``）
    的单元——finalize 最需要这份证据；以及含最后一条用户消息的单元。
    末尾悬空的未执行调用单元不再受保护，可被整单元丢弃——它是待重发的
    请求而非证据，不该占着地板预算。
    生成器逐步产出候选，调用方一旦放得下即停——避免一次性丢光历史。
    """
    protected: set = set()
    if slices:
        answered_start = _last_answered_tool_index(history)
        human_index = max(
            (index for index, message in enumerate(history)
             if getattr(message, "type", "") == "human"),
            default=-1,
        )
        # 下标越界（-1）时 ``start <= -1`` 恒假，自然不命中任何单元。
        for position, (start, end) in enumerate(slices):
            if start <= answered_start < end or start <= human_index < end:
                protected.add(position)

    working = list(history)
    for position in range(len(slices)):
        if position in protected:
            continue
        start, end = slices[position]
        offset = len(history) - len(working)
        del working[start - offset:end - offset]
        yield working


# ---------------------------------------------------------------------------
# 入口
# ---------------------------------------------------------------------------


def prepare_messages(
    system_prompt: str,
    history: Sequence[BaseMessage],
    *,
    max_chars: int,
    summarizer: Optional[Summarizer] = None,
) -> PreparedContext:
    """准备一次模型调用的消息列表，必要时逐级收缩到 ``max_chars`` 以内。

    ``max_chars <= 0`` 表示关闭预算（``AGENT_CONTEXT_TRIM_ENABLED=false`` 语义），
    此时完全 no-op。``max_chars`` 需由调用方把随行附注（如 finalize 的
    ``TOOL_LIMIT_NOTE``）一并并入 ``system_prompt``。
    """
    system = SystemMessage(content=system_prompt)
    original = [system, *history]
    if max_chars <= 0:
        return PreparedContext(original, messages_chars(original), 0, False)

    summary_fn = summarizer or _default_summarizer
    slices = _unit_slices(history)
    stats: dict = {}

    def fits(messages: Sequence[BaseMessage]) -> bool:
        return len(system_prompt) + messages_chars(messages) <= max_chars

    # 第 1 级是「打开修剪」后的基线行为（M4a E5：早轮压摘要、最近一轮原文），
    # 无论是否超限都执行——它同时是这段历史的确定性上界。
    baseline = _compress_older(history, summary_fn)
    if fits(baseline):
        return _result(system, history, baseline, max_chars, 1, stats)

    candidates = [
        (2, _compress_all(history, summary_fn)),
    ]
    working: List[BaseMessage] = candidates[-1][1]
    working = _compact_historical_args(working)
    candidates.append((3, working))
    working = _truncate_tools(working)
    candidates.append((4, working))

    for level, messages in candidates:
        if fits(messages):
            return _result(system, history, messages, max_chars, level, stats)

    # _drop_units 的偏移删除建立在「各级变换保持条数/顺序不变」的隐含约定上
    # （切片下标来自原始 history，操作对象是第 4 级产物 working）。这里把约定
    # 固化为显式断言：未来某级变换若改变条数会立即显形，而非静默错位。
    assert len(working) == len(history), "context transforms must preserve message count"
    dropped = 0
    for dropped, messages in enumerate(_drop_units(working, slices), start=1):
        if fits(messages):
            stats["dropped_units"] = dropped
            return _result(system, history, messages, max_chars, 5, stats)

    stats["dropped_units"] = dropped
    floor = [system, *working]
    return PreparedContext(
        floor, messages_chars(floor), 5, True, {**stats, "reason": "floor_exceeded"},
    )


def _result(
    system: SystemMessage,
    original: Sequence[BaseMessage],
    history: Sequence[BaseMessage],
    max_chars: int,
    level: int,
    stats: dict,
) -> PreparedContext:
    messages = [system, *history]
    total = messages_chars(messages)
    prepared = PreparedContext(messages, total, level, False, stats)
    # 每一级都记（含第 1 级）。P0.1 时只在 level >= 2 记录，把「L1 就够」这个健康
    # 情形系统性隐掉，使「14/14 都是 L2」无法解释——看不清分级分布就无法判断
    # 压缩粒度是否够用。``applied`` 区分「升级了但什么也没改」与真的发生了收缩。
    # ``_trim`` 各级都保持条数不变（只换内容），故按位置逐个比对象身份即可。
    applied = len(history) != len(original) or any(
        new is not old for new, old in zip(history, original, strict=True)
    )
    logger.warning(json.dumps({
        "event": "agent_context_trimmed", "level": level, "applied": applied,
        "limit": max_chars, "total_chars": total, **stats,
    }, ensure_ascii=False))
    return prepared
