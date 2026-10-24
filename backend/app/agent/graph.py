"""LangGraph 图编排（规划 §4.1，M1 最小版 + M4a 运行时护栏）。

结构：planner（LLM + bind_tools）→ 条件边——有 tool_calls 进 tools 节点、
没有则出图；tools 节点执行后回填 ToolMessage 回 planner 循环，硬上限
``max_rounds`` 轮；超限或 token 预算超限进 finalize 节点（无工具直出最终
答复，复用 TOOL_LIMIT_NOTE 兜底，M4a E9——不再以异常终止）。达限转入时
末尾 AIMessage 的 tool_calls 从未执行，finalize 进模型前会先补合成
``not_executed`` ToolMessage 配对（B4），上游收到的序列不产生悬空调用。

无 checkpointer（D11）：图无状态，会话上下文由调用方在入图前构建为
消息列表传入；工具以请求级闭包注入，因此图可按请求重建。

上下文预算（P0.1）：planner 与 finalize 共用 ``context.prepare_messages`` 的
分级收缩管线，够用即停；只有连「系统提示词 + 当前请求」都放不下时才抛
``ContextBudgetExceeded``，由 service 层转成安全降级（保留评分事实、不回显源码）。
"""
from __future__ import annotations

import json
import logging
import time
from typing import Any, Awaitable, Callable, Dict, List, Optional

from langchain_core.messages import AIMessage, BaseMessage, ToolMessage
from langgraph.graph import END, START, StateGraph
from langgraph.graph.message import MessagesState

from ..core.metrics import record_agent_tool_call
from ..core.usage import UsageRecorder
from ..services.task_errors import RetryableTaskFailure
from .context import prepare_messages

logger = logging.getLogger("assistant")

# 系统提示词构造器：按当前图状态动态拼装（检测反馈升级依赖最近的评测结果）。
PromptBuilder = Callable[["AgentGraphState"], Awaitable[str]]


class AgentGraphState(MessagesState):
    # 已执行的工具调用轮数（tools 节点每次 +1），用于循环硬上限。
    tool_rounds: int
    tokens_used: int


TOOL_LIMIT_NOTE = (
    "\n\n注意：本轮对话的工具调用次数已达上限，请基于已获得的信息直接给出最终答复，"
    "不要再尝试调用工具。"
)


class ContextBudgetExceeded(RuntimeError):
    """连「系统提示词 + 当前请求」都放不下（P0.1）。

    继承 ``RuntimeError`` 且消息保留 ``agent_context_budget`` 字样：chat_trace
    的 ``fallback_reason`` 观测口径不变；service 层据类型改走保留评分事实的
    安全降级，而不是把它当作未知上游故障。
    """

    def __init__(self, detail: str = "") -> None:
        super().__init__(f"agent_context_budget{': ' + detail if detail else ''}")
        self.detail = detail


async def _invoke_with_usage(
    model: Any,
    messages: List[BaseMessage],
    usage: Optional[UsageRecorder],
    stage: str,
    model_name: Optional[str],
) -> Any:
    """调一次上游并把用量记进账本。

    失败也留一条（``usage=None`` → unknown）：请求已经发出去，上游可能已经算过钱，
    「没记」不等于「没花」。usage 为 None 时本函数完全透明。
    """
    try:
        response = await model.ainvoke(messages)
    except BaseException:
        if usage is not None:
            usage.record_llm(stage, model_name, None)
        raise
    if usage is not None:
        usage.record_llm(stage, model_name, getattr(response, "usage_metadata", None))
    return response


def _pair_unexecuted_tool_calls(messages: List[BaseMessage]) -> List[BaseMessage]:
    """给末尾悬空的 tool_calls 补合成 ToolMessage（B4），返回新列表。

    达限（``max_rounds`` 或 token 预算）时 ``route_after_planner`` 直接转
    finalize，末尾 AIMessage 的 tool_calls 从未进过 tools 节点——它是最后
    一条消息，其后不可能有配对 ToolMessage，严格校验工具协议的上游会拒绝
    这种序列。这里按 run_tools 的结果形状逐调用补 ``not_executed`` 结果。

    只作用于发给模型的消息副本：``state["messages"]`` 本身不动（它还参与
    路由与落库口径）。末尾不是带 tool_calls 的 AIMessage 时原样返回；
    历史中部的悬空属于输入腐化，不在本函数职责内。
    """
    if not messages:
        return messages
    calls = getattr(messages[-1], "tool_calls", None) or []
    if not calls:
        return messages
    synthesized = [
        ToolMessage(
            content=json.dumps({
                "ok": False,
                "error": "not_executed",
                "detail": "工具调用未执行：已达工具轮数或 token 上限",
            }, ensure_ascii=False),
            tool_call_id=call.get("id") or "",
            name=call.get("name") or "",
        )
        for call in calls
    ]
    logger.warning(json.dumps({
        "event": "agent_tool_calls_not_executed",
        "tool_call_ids": [message.tool_call_id for message in synthesized],
    }, ensure_ascii=False))
    return [*messages, *synthesized]


def build_agent_graph(
    *,
    model: Any,
    tools: List[Any],
    prompt_builder: PromptBuilder,
    max_rounds: int,
    token_budget: int = 0,
    context_max_chars: int = 0,
    usage: Optional[UsageRecorder] = None,
    model_name: Optional[str] = None,
) -> Any:
    """Compile the planner/tools/finalize graph. ``model`` is a LangChain chat model.

    ``usage`` 是请求级用量 sink：闭包进节点，**不进图 state**（state 要保持可序列化，
    将来接 checkpointer 才不用改）。默认 None 时行为与接线前逐字一致。
    """
    tools_by_name: Dict[str, Any] = {item.name: item for item in tools}
    bind_tools = getattr(model, "bind_tools", None)
    try:
        planner_model = bind_tools(tools) if callable(bind_tools) else model
    except NotImplementedError:
        # Fake chat models in tests may not implement tool binding.
        planner_model = model

    async def planner(state: AgentGraphState) -> Dict[str, List[BaseMessage]]:
        system_prompt = await prompt_builder(state)
        from ..core.upstream_gate import upstream_slot
        prepared = prepare_messages(
            system_prompt, state["messages"], max_chars=context_max_chars,
        )
        if prepared.over_budget:
            raise ContextBudgetExceeded(prepared.stats.get("reason", "floor_exceeded"))
        async with upstream_slot():
            response = await _invoke_with_usage(
                planner_model, prepared.messages, usage, "planner", model_name,
            )
        used = int((getattr(response, "usage_metadata", None) or {}).get("total_tokens", 0))
        return {"messages": [response], "tokens_used": state.get("tokens_used", 0) + used}

    async def run_tools(state: AgentGraphState) -> Dict[str, Any]:
        last = state["messages"][-1]
        outputs: List[ToolMessage] = []
        for call in getattr(last, "tool_calls", None) or []:
            name = call.get("name", "")
            tool_impl = tools_by_name.get(name)
            if tool_impl is None:
                result: Dict[str, Any] = {
                    "ok": False, "error": "unknown_tool", "detail": f"未知工具：{name}",
                }
                record_agent_tool_call(name, False, 0.0, 0)
            else:
                started = time.monotonic()
                try:
                    raw = await tool_impl.ainvoke(call.get("args") or {})
                    result = raw if isinstance(raw, dict) else {"ok": True, "data": raw}
                except RetryableTaskFailure:
                    raise
                except Exception as exc:
                    logger.warning(json.dumps({
                        "event": "agent_tool_exception", "tool": name,
                        "error_type": type(exc).__name__, "detail": str(exc)[:300],
                    }, ensure_ascii=False))
                    result = {
                        "ok": False, "error": "tool_exception",
                        "detail": f"工具执行异常：{type(exc).__name__}",
                    }
                record_agent_tool_call(
                    name, bool(result.get("ok")), time.monotonic() - started,
                    len(json.dumps(result, ensure_ascii=False)),
                )
            outputs.append(ToolMessage(
                content=json.dumps(result, ensure_ascii=False),
                tool_call_id=call.get("id") or "",
                name=name,
            ))
        return {
            "messages": outputs,
            "tool_rounds": state.get("tool_rounds", 0) + 1,
        }

    async def finalize(state: AgentGraphState) -> Dict[str, List[BaseMessage]]:
        system_prompt = await prompt_builder(state)
        from ..core.upstream_gate import upstream_slot
        # TOOL_LIMIT_NOTE 并入 system_prompt，因此它自动计入预算。
        # B4：达限转入时末尾 tool_calls 未执行，先补合成 ToolMessage 配对——
        # 只改发给模型的消息副本，state["messages"] 保持不变。
        prepared = prepare_messages(
            system_prompt + TOOL_LIMIT_NOTE,
            _pair_unexecuted_tool_calls(state["messages"]),
            max_chars=context_max_chars,
        )
        if prepared.over_budget:
            raise ContextBudgetExceeded(prepared.stats.get("reason", "floor_exceeded"))
        async with upstream_slot():
            response: AIMessage = await _invoke_with_usage(
                model, prepared.messages, usage, "finalize", model_name,
            )
        used = int((getattr(response, "usage_metadata", None) or {}).get("total_tokens", 0))
        return {"messages": [response], "tokens_used": state.get("tokens_used", 0) + used}

    def route_after_planner(state: AgentGraphState) -> str:
        last = state["messages"][-1]
        if not getattr(last, "tool_calls", None):
            return END
        # token 预算超限：硬转 finalize（E9 兜底同形 TOOL_LIMIT_NOTE），不再抛异常。
        if token_budget and state.get("tokens_used", 0) >= token_budget:
            return "finalize"
        if state.get("tool_rounds", 0) >= max_rounds:
            return "finalize"
        return "tools"

    def route_after_tools(state: AgentGraphState) -> str:
        # tools 回填后若预算已爆，跳过下一次 planner 调用直接 finalize。
        if token_budget and state.get("tokens_used", 0) >= token_budget:
            return "finalize"
        return "planner"

    graph = StateGraph(AgentGraphState)
    graph.add_node("planner", planner)
    graph.add_node("tools", run_tools)
    graph.add_node("finalize", finalize)
    graph.add_edge(START, "planner")
    graph.add_conditional_edges("planner", route_after_planner, {
        "tools": "tools", "finalize": "finalize", END: END,
    })
    graph.add_conditional_edges("tools", route_after_tools, {
        "planner": "planner", "finalize": "finalize",
    })
    graph.add_edge("finalize", END)
    return graph.compile()
