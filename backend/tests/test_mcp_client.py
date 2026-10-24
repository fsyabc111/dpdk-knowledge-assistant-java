"""mcp_client 的 mock 级单测：不发起任何真实网络连接。

通过 monkeypatch 替换模块内的 streamablehttp_client / ClientSession，
覆盖：正常 JSON 解析、structuredContent 优先、isError、坏响应、不可达。
"""
from __future__ import annotations

import json
import sys
from contextlib import asynccontextmanager
from pathlib import Path
from types import SimpleNamespace

import pytest

ROOT = Path(__file__).resolve().parents[1]
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))

from app.agent import mcp_client as mcp_module
from app.agent.mcp_client import HarnessMcpClient


class FakeSession:
    def __init__(self, result):
        self._result = result

    async def __aenter__(self):
        return self

    async def __aexit__(self, *args):
        return False

    async def initialize(self):
        return None

    async def call_tool(self, tool_name, arguments):
        return self._result


def install_fake_transport(monkeypatch, result=None, connect_error=None):
    @asynccontextmanager
    async def fake_streamablehttp_client(url):
        if connect_error is not None:
            raise connect_error
        yield (None, None, None)

    monkeypatch.setattr(mcp_module, "streamablehttp_client", fake_streamablehttp_client)
    monkeypatch.setattr(
        mcp_module, "ClientSession", lambda read, write: FakeSession(result)
    )


def make_result(*, payload: dict, is_error: bool = False, structured=None):
    return SimpleNamespace(
        structuredContent=structured,
        content=[SimpleNamespace(text=json.dumps(payload, ensure_ascii=False))],
        isError=is_error,
    )


async def test_call_tool_parses_json_payload(monkeypatch):
    install_fake_transport(monkeypatch, result=make_result(payload={"ok": True, "score": 100}))
    client = HarnessMcpClient("http://harness:8100/mcp")
    result = await client.call_tool("grade_plugin", {"protocol": "dns"})
    assert result == {"ok": True, "score": 100}


async def test_call_tool_prefers_structured_content(monkeypatch):
    structured = {"ok": True, "protocol": "dns", "score": 62}
    install_fake_transport(
        monkeypatch,
        result=make_result(payload={"ok": False}, structured=structured),
    )
    client = HarnessMcpClient("http://harness:8100/mcp")
    result = await client.call_tool("grade_plugin", {"protocol": "dns"})
    assert result == structured


async def test_call_tool_is_error_becomes_structured_error(monkeypatch):
    install_fake_transport(
        monkeypatch, result=make_result(payload={"error": "boom"}, is_error=True)
    )
    client = HarnessMcpClient("http://harness:8100/mcp")
    result = await client.call_tool("grade_plugin", {"protocol": "dns"})
    assert result["ok"] is False
    assert result["error"] == "harness_tool_error"
    assert "boom" in result["detail"]


async def test_call_tool_bad_payload_becomes_structured_error(monkeypatch):
    result = SimpleNamespace(
        structuredContent=None,
        content=[SimpleNamespace(text="<not json>")],
        isError=False,
    )
    install_fake_transport(monkeypatch, result=result)
    client = HarnessMcpClient("http://harness:8100/mcp")
    parsed = await client.call_tool("grade_plugin", {"protocol": "dns"})
    assert parsed["ok"] is False
    assert parsed["error"] == "harness_bad_response"


async def test_call_tool_unreachable_harness_does_not_raise(monkeypatch):
    install_fake_transport(monkeypatch, connect_error=ConnectionRefusedError("refused"))
    client = HarnessMcpClient("http://harness:8100/mcp")
    result = await client.call_tool("grade_plugin", {"protocol": "dns"})
    assert result["ok"] is False
    assert result["error"] == "harness_unavailable"
    assert "ConnectionRefusedError" in result["detail"]


if __name__ == "__main__":
    sys.exit(pytest.main([__file__, "-v"]))
