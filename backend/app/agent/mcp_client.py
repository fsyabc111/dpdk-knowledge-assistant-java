"""teaching_harness MCP server 的 streamable HTTP 客户端（规划 §3.3，D2/D10；M4a E7）。

短连接：每次工具调用建立一次连接，用完即关（不做连接池——M4 E7 压测证伪了
池化收益）。建连/initialize 阶段失败有限重试（2 次退避）；调用一旦提交
（session.call_tool 开始）绝不重试——已提交的评测重试会重复消耗配额与工时。
所有传输层/协议层异常都被收敛为 ``{"ok": False, ...}`` 结构化错误，
绝不向调用方抛穿（沿用全系统的 fallback 哲学）。

两条调用路径（agent 工具层 grade_plugin、作业提交 harness_grading）经
``get_harness_mcp_client`` 收敛到同一进程级客户端实例（E7）。
"""
from __future__ import annotations

import asyncio
import json
import logging
import threading
from typing import Any, Dict, Optional

from mcp import ClientSession
from mcp.client.streamable_http import streamablehttp_client

logger = logging.getLogger("assistant")

# 建连/initialize 阶段的重试次数（不含首次尝试）与退避基数（秒）。
_CONNECT_RETRIES = 2
_CONNECT_RETRY_BASE_SECONDS = 0.5


class HarnessMcpClient:
    def __init__(self, url: str) -> None:
        self.url = url

    async def _call_once(self, tool_name: str, arguments: Dict[str, Any]):
        """单次尝试。返回 (result, error, submitted)——submitted=True 表示异常
        发生在调用提交之后，禁止重试。"""
        submitted = False
        try:
            async with streamablehttp_client(self.url) as (read_stream, write_stream, _):
                async with ClientSession(read_stream, write_stream) as session:
                    await session.initialize()
                    submitted = True
                    result = await session.call_tool(tool_name, arguments)
        except Exception as exc:
            return None, exc, submitted
        return result, None, submitted

    async def call_tool(self, tool_name: str, arguments: Dict[str, Any]) -> Dict[str, Any]:
        """Call one harness tool and normalize the result to a plain dict."""
        for attempt in range(_CONNECT_RETRIES + 1):
            result, exc, submitted = await self._call_once(tool_name, arguments)
            if exc is None:
                return self._parse_result(tool_name, result)
            if submitted or attempt >= _CONNECT_RETRIES:
                logger.warning(json.dumps({
                    "event": "mcp_call_failed", "tool": tool_name,
                    "error_type": type(exc).__name__, "detail": str(exc)[:500],
                    "attempts": attempt + 1, "submitted": submitted,
                }, ensure_ascii=False))
                return {
                    "ok": False,
                    "error": "harness_unavailable",
                    "detail": f"评测服务不可达：{type(exc).__name__}",
                }
            logger.warning(json.dumps({
                "event": "mcp_call_retry", "tool": tool_name,
                "error_type": type(exc).__name__, "attempt": attempt + 1,
            }, ensure_ascii=False))
            await asyncio.sleep(_CONNECT_RETRY_BASE_SECONDS * (2 ** attempt))
        raise AssertionError("unreachable")

    @staticmethod
    def _parse_result(tool_name: str, result: Any) -> Dict[str, Any]:
        # FastMCP 对 dict 返回值同时给出 structuredContent 与文本化 JSON。
        structured: Optional[Dict[str, Any]] = getattr(result, "structuredContent", None)
        if isinstance(structured, dict):
            return structured
        texts = [
            getattr(item, "text", "")
            for item in (getattr(result, "content", None) or [])
            if getattr(item, "text", "")
        ]
        payload = "\n".join(texts)
        if getattr(result, "isError", False):
            return {"ok": False, "error": "harness_tool_error", "detail": payload[:2000]}
        try:
            parsed = json.loads(payload)
        except (json.JSONDecodeError, ValueError):
            logger.warning(json.dumps({
                "event": "mcp_bad_response", "tool": tool_name, "payload": payload[:500],
            }, ensure_ascii=False))
            return {"ok": False, "error": "harness_bad_response", "detail": payload[:2000]}
        if isinstance(parsed, dict):
            return parsed
        return {"ok": True, "data": parsed}


_clients: Dict[str, HarnessMcpClient] = {}
_clients_lock = threading.Lock()


def get_harness_mcp_client(url: str) -> HarnessMcpClient:
    """进程级客户端缓存（E7 入口收敛）：同一 url 复用同一实例。"""
    with _clients_lock:
        client = _clients.get(url)
        if client is None:
            client = HarnessMcpClient(url)
            _clients[url] = client
        return client
