"""教师参考答案的带外注入（M2 §6，服务端通道，非模型工具）。

grade_plugin 出现非 PASS 用例时，prompt_builder 经本模块向 harness 拉取
该协议的参考实现，追加到系统提示词末尾，帮助模型理解错因——参考答案
只进系统提示词，配合红线重述，不作为模型可见工具。

降级哲学与 mcp_client 一致：工具不存在、网络失败、错误返回等任何异常
都只记 warning 并返回 None，绝不向编排层抛穿。
"""
from __future__ import annotations

import json
import logging
from typing import Dict, Optional

from .tools import McpCaller

logger = logging.getLogger("assistant")


class ReferenceAnswerProvider:
    """Per-request provider for the teacher reference answer of one protocol.

    构造收 mcp_caller 与长度上限；实例内按协议做 dict 缓存（prompt_builder
    每轮 planner 重建都会调用，缓存避免同请求重复打 harness）。
    """

    def __init__(self, mcp_caller: McpCaller, max_chars: int) -> None:
        self._mcp_caller = mcp_caller
        self._max_chars = max_chars
        self._cache: Dict[str, Optional[str]] = {}

    async def get(self, protocol: str) -> Optional[str]:
        """Return the reference answer source, or None on any failure/empty."""
        if protocol in self._cache:
            return self._cache[protocol]
        answer = await self._fetch(protocol)
        self._cache[protocol] = answer
        return answer

    async def _fetch(self, protocol: str) -> Optional[str]:
        try:
            payload = await self._mcp_caller("get_reference_answer", {"protocol": protocol})
        except Exception as exc:
            logger.warning(json.dumps({
                "event": "reference_answer_failed", "protocol": protocol,
                "error_type": type(exc).__name__, "detail": str(exc)[:300],
            }, ensure_ascii=False))
            return None
        # 契约（teaching_harness get_reference_answer）：{"ok": True, "protocol": ..., "source": 源码}。
        if not isinstance(payload, dict) or not payload.get("ok"):
            payload = payload if isinstance(payload, dict) else {}
            logger.warning(json.dumps({
                "event": "reference_answer_failed", "protocol": protocol,
                "error_type": str(payload.get("error") or "bad_payload"),
                "detail": str(payload.get("detail") or "")[:300],
            }, ensure_ascii=False))
            return None
        answer = payload.get("source")
        if not isinstance(answer, str) or not answer.strip():
            logger.warning(json.dumps({
                "event": "reference_answer_failed", "protocol": protocol,
                "error_type": "empty_answer", "detail": "参考答案为空",
            }, ensure_ascii=False))
            return None
        if len(answer) > self._max_chars:
            answer = answer[: self._max_chars] + "\n[已截断]"
        return answer
