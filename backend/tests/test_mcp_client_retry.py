"""MCP 客户端建连重试与入口收敛（M4a E7）的默认集单测。

语义：建连/initialize 阶段失败重试 2 次（退避）；调用一旦提交
（session.call_tool 开始）绝不重试——已提交的评测重试会重复消耗配额。
"""
from __future__ import annotations

from types import SimpleNamespace

import pytest

from app.agent import mcp_client as mcp_mod
from app.agent.mcp_client import HarnessMcpClient, get_harness_mcp_client


class _FakeTransport:
    def __init__(self, state):
        self.state = state

    async def __aenter__(self):
        self.state["connect_attempts"] += 1
        if self.state["fail_connect"] > 0:
            self.state["fail_connect"] -= 1
            raise ConnectionError("connect refused")
        return None, None, None

    async def __aexit__(self, *args):
        return False


class _FakeSession:
    def __init__(self, state):
        self.state = state

    async def __aenter__(self):
        return self

    async def __aexit__(self, *args):
        return False

    async def initialize(self):
        if self.state["fail_initialize"] > 0:
            self.state["fail_initialize"] -= 1
            raise ConnectionError("handshake reset")

    async def call_tool(self, name, arguments):
        self.state["calls"] += 1
        if self.state["fail_call"]:
            raise TimeoutError("submitted call lost")
        return SimpleNamespace(structuredContent={"ok": True, "data": {"echo": name}})


def _harness(monkeypatch, state):
    state.setdefault("calls", 0)
    state.setdefault("connect_attempts", 0)
    monkeypatch.setattr(mcp_mod, "streamablehttp_client", lambda url: _FakeTransport(state))
    monkeypatch.setattr(mcp_mod, "ClientSession", lambda read, write: _FakeSession(state))

    async def _no_sleep(_seconds):
        return None

    monkeypatch.setattr(mcp_mod, "asyncio", SimpleNamespace(sleep=_no_sleep))


async def test_handshake_failures_retry_then_succeed(monkeypatch):
    state = {"fail_connect": 1, "fail_initialize": 1, "fail_call": False}
    _harness(monkeypatch, state)
    result = await HarnessMcpClient("http://harness:8100/mcp").call_tool("list_protocols", {})
    assert result["ok"] is True and result["data"] == {"echo": "list_protocols"}
    assert state["connect_attempts"] == 3  # 首次 + 2 次重试都用满后成功
    assert state["calls"] == 1


async def test_persistent_connect_failure_gives_up_after_two_retries(monkeypatch):
    state = {"fail_connect": 99, "fail_initialize": 0, "fail_call": False}
    _harness(monkeypatch, state)
    result = await HarnessMcpClient("http://harness:8100/mcp").call_tool("list_protocols", {})
    assert result["ok"] is False and result["error"] == "harness_unavailable"
    assert state["connect_attempts"] == 3  # 首次 + 2 次退避重试，不多不少
    assert state["calls"] == 0


async def test_submitted_call_failure_never_retries(monkeypatch):
    state = {"fail_connect": 0, "fail_initialize": 0, "fail_call": True}
    _harness(monkeypatch, state)
    result = await HarnessMcpClient("http://harness:8100/mcp").call_tool("grade_plugin", {})
    assert result["ok"] is False and result["error"] == "harness_unavailable"
    assert state["calls"] == 1, "已提交的调用不得重试（避免重复评测）"


def test_client_factory_converges_on_same_instance(monkeypatch):
    mcp_mod._clients.clear()
    first = get_harness_mcp_client("http://harness:8100/mcp")
    second = get_harness_mcp_client("http://harness:8100/mcp")
    other = get_harness_mcp_client("http://other:8100/mcp")
    assert first is second and first is not other


if __name__ == "__main__":
    raise SystemExit(pytest.main([__file__, "-v"]))
