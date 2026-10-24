"""分层系统提示词与反馈升级条件（§4.3）的确定性测试。"""
from __future__ import annotations

import json
import sys
from pathlib import Path

from langchain_core.messages import ToolMessage

ROOT = Path(__file__).resolve().parents[1]
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))

from app.agent.prompts import (
    BASE_PROMPT,
    FEEDBACK_PROMPT_DEFAULT,
    REDLINE_PROMPT,
    build_system_prompt,
    detect_explicit_ask,
    infer_protocol_from_text,
)
from app.agent.service import _last_graded_protocol


def test_default_prompt_uses_guided_feedback_layer():
    prompt = build_system_prompt(escalated=False)
    assert BASE_PROMPT in prompt
    assert FEEDBACK_PROMPT_DEFAULT in prompt
    assert REDLINE_PROMPT in prompt
    assert "不提供修复代码" in prompt
    assert "定位级" not in prompt


def test_escalated_prompt_allows_location_level_advice():
    prompt = build_system_prompt(escalated=True)
    assert "定位级" in prompt
    # 即使升级，完整实现仍被禁止（红线层恒在）。
    assert "不得输出完整实现" in prompt
    assert REDLINE_PROMPT in prompt


def test_base_prompt_teaches_pasted_code_entry():
    assert "plugin_source" in BASE_PROMPT
    assert "grade_plugin" in BASE_PROMPT


def test_base_prompt_lists_five_tools_with_routing_rules():
    for name in ("kb_search", "list_protocols", "list_cases", "plugin_template", "grade_plugin"):
        assert name in BASE_PROMPT
    assert "五个工具" in BASE_PROMPT
    # M2 路由规则：用例/场景→list_cases，实验清单→list_protocols，骨架→plugin_template。
    assert "测哪些用例" in BASE_PROMPT
    assert "有哪些实验/协议" in BASE_PROMPT
    assert "起手代码" in BASE_PROMPT
    assert "学习骨架、不是作业答案" in BASE_PROMPT


# ---------------------------------------------------------------------------
# 粘贴路径协议推断（M2 §5）
# ---------------------------------------------------------------------------


def test_infer_protocol_unique_hit_by_name_or_alias():
    assert infer_protocol_from_text("帮我看看 dns 插件哪里错了") == "dns"
    assert infer_protocol_from_text("帮我看看 dns插件") == "dns"
    assert infer_protocol_from_text("这段域名解析代码不通") == "dns"
    assert infer_protocol_from_text("远程桌面插件的握手怎么解析") == "rdp"
    assert infer_protocol_from_text("HTTPS 的证书校验") == "https"
    assert infer_protocol_from_text("WireGuard 握手") == "wireguard"


def test_infer_protocol_ascii_token_boundary():
    # http 不得命中 https；tls 不得命中 tlsinfra。
    assert infer_protocol_from_text("https插件") == "https"
    assert infer_protocol_from_text("tlsinfra 的用例有哪些") == "tlsinfra"


def test_infer_protocol_multiple_hits_returns_none():
    # 多命中宁可不给 hint，避免误导。
    assert infer_protocol_from_text("http 和 https 有什么区别") is None
    assert infer_protocol_from_text("网页和文件共享插件都要做吗") is None


def test_infer_protocol_no_hit_returns_none():
    assert infer_protocol_from_text("帮我看看这段代码") is None
    assert infer_protocol_from_text("") is None


def test_detect_explicit_ask():
    assert detect_explicit_ask("别绕了，直接告诉我怎么改")
    assert detect_explicit_ask("please just tell me the fix")
    assert not detect_explicit_ask("dns_basic_3 挂了，期望和实际差在哪？")


def test_last_graded_protocol_reads_newest_grade_result():
    messages = [
        ToolMessage(
            content=json.dumps({"ok": True, "data": {"protocol": "dns", "score": 62}}),
            tool_call_id="c1", name="grade_plugin",
        ),
        ToolMessage(
            content=json.dumps({"ok": True, "data": {"protocol": "http", "score": 100}}),
            tool_call_id="c2", name="grade_plugin",
        ),
        ToolMessage(
            content=json.dumps({"ok": True, "data": {"count": 3}}),
            tool_call_id="c3", name="kb_search",
        ),
    ]
    assert _last_graded_protocol(messages) == "http"
    assert _last_graded_protocol(messages[:0]) is None
    failed = [ToolMessage(
        content=json.dumps({"ok": False, "error": "compile_error"}),
        tool_call_id="c4", name="grade_plugin",
    )]
    assert _last_graded_protocol(failed) is None
