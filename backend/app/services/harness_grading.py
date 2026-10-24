"""作业提交路径的评测封装（与 agent/tools.py 的 grade_plugin 同源三步）。

不改 tools.py（agent 黄金场景保护），这里独立实现：大小校验 → 每日配额 →
HarnessMcpClient.call_tool 包 wait_for 超时。配额豁免角色常量复用 tools.py
的 GRADING_QUOTA_EXEMPT_ROLES，两处口径保持一致。

返回统一 ``{"ok": ...}`` 契约，绝不向路由抛穿异常（fallback 哲学）。
"""
from __future__ import annotations

import asyncio
from typing import Any, Dict, List

from fastapi import HTTPException

from ..agent.mcp_client import get_harness_mcp_client
from ..agent.tools import GRADING_QUOTA_EXEMPT_ROLES
from ..config import Settings
from ..core.rate_limit import rate_limiter

__all__ = ["GRADING_QUOTA_EXEMPT_ROLES", "grade_code", "list_protocols"]


async def grade_code(
    *,
    settings: Settings,
    user_id: str,
    user_roles: List[str],
    protocol: str,
    plugin_source: str,
) -> Dict[str, Any]:
    """评测一份插件源码，返回 harness 原始 payload 或结构化错误。"""
    code_bytes = plugin_source.encode("utf-8", errors="replace")
    if len(code_bytes) > settings.grading_max_code_bytes:
        return {
            "ok": False,
            "error": "code_too_large",
            "detail": f"源码大小超过上限（{settings.grading_max_code_bytes} 字节）",
        }
    if not GRADING_QUOTA_EXEMPT_ROLES.intersection(user_roles):
        try:
            await rate_limiter.check_and_count_daily(
                user_id, settings.grading_daily_quota, kind="grading"
            )
        except HTTPException:
            return {
                "ok": False,
                "error": "quota_exceeded",
                "detail": f"今日评测配额已用完（{settings.grading_daily_quota} 次/天），请明天再试",
            }
    try:
        payload = await asyncio.wait_for(
            get_harness_mcp_client(settings.harness_mcp_url).call_tool("grade_plugin", {
                "protocol": protocol,
                "plugin_source": plugin_source,
                "timeout_sec": settings.grading_timeout_sec,
            }),
            timeout=settings.grading_timeout_sec,
        )
    except asyncio.TimeoutError:
        return {
            "ok": False,
            "error": "grading_timeout",
            "detail": f"评测超过 {settings.grading_timeout_sec} 秒未完成",
        }
    if not isinstance(payload, dict) or not payload.get("ok"):
        payload = payload if isinstance(payload, dict) else {}
        return {
            "ok": False,
            "error": str(payload.get("error") or "grading_failed"),
            "detail": str(payload.get("detail") or "评测服务返回失败"),
        }
    return payload


async def list_protocols(*, settings: Settings) -> Dict[str, Any]:
    """包装 MCP list_protocols 供发布表单下拉；harness 不可达时结构化降级。"""
    payload = await get_harness_mcp_client(settings.harness_mcp_url).call_tool("list_protocols", {})
    if not isinstance(payload, dict) or not payload.get("ok"):
        payload = payload if isinstance(payload, dict) else {}
        return {
            "ok": False,
            "error": str(payload.get("error") or "list_protocols_failed"),
            "detail": str(payload.get("detail") or "协议清单查询失败"),
        }
    return payload
