"""/api/chat 的 multipart 上传与请求校验测试。

S4 收敛后产品化路径是唯一入口：RagProductService._candidate 被整体 mock，
因此不会触达真实模型或 harness；这里只验证端点的解析、校验与附件透传。
"""
from __future__ import annotations

import sys
from pathlib import Path

import pytest

ROOT = Path(__file__).resolve().parents[1]
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))

DNS_SOURCE = b"/* 2024001 dns plugin */\n#include <stdint.h>\n"


@pytest.fixture
def product_client(sqlite_db, monkeypatch):
    """产品路径（唯一路径），mock 执行层。"""
    monkeypatch.setenv("RATE_LIMIT_CHAT_PER_MINUTE", "1000")
    monkeypatch.setenv("GRADING_MAX_CODE_BYTES", "4096")

    from app.config import get_settings

    get_settings.cache_clear()

    from fastapi.testclient import TestClient

    from app.main import app
    from app.services.rag_product_service import RagProductService, Verdict

    captured = {}

    async def fake_candidate(self, emit):
        captured["question"] = self.request.message
        captured["user_id"] = self.principal.subject
        captured["user_roles"] = list(self.principal.roles)
        captured["attachment"] = self.attachment
        return Verdict("mock answer", "answered", "supported"), [], "release"

    monkeypatch.setattr(RagProductService, "_candidate", fake_candidate)
    client = TestClient(app)
    client.captured = captured
    return client


def test_json_contract_unchanged_without_attachment(product_client):
    response = product_client.post("/api/chat", json={"message": "DPDK 是什么？"})
    assert response.status_code == 200
    assert product_client.captured["question"] == "DPDK 是什么？"
    assert product_client.captured["attachment"] is None


def test_multipart_c_upload_reaches_orchestrator(product_client):
    response = product_client.post(
        "/api/chat",
        data={"message": "帮我看看这个插件", "session_id": ""},
        files={"file": ("2024001_张三_dns.c", DNS_SOURCE, "text/x-c")},
    )
    assert response.status_code == 200
    attachment = product_client.captured["attachment"]
    assert attachment is not None
    assert attachment.filename == "2024001_张三_dns.c"
    assert attachment.suffix == ".c"
    assert attachment.source == DNS_SOURCE.decode("utf-8")
    # 文件名 学号_姓名_协议.c 的第三段作为协议提示（D6）。
    assert attachment.protocol_hint == "dns"


def test_multipart_without_message_uses_default(product_client):
    response = product_client.post(
        "/api/chat",
        files={"file": ("dns.c", DNS_SOURCE, "text/x-c")},
    )
    assert response.status_code == 200
    assert "dns.c" in product_client.captured["question"]


def test_upload_rejects_bad_extension(product_client):
    response = product_client.post(
        "/api/chat",
        data={"message": "hi"},
        files={"file": ("evil.exe", DNS_SOURCE, "application/octet-stream")},
    )
    assert response.status_code == 400
    assert ".c" in response.json()["detail"]


def test_upload_rejects_oversized_file(product_client, monkeypatch):
    monkeypatch.setenv("GRADING_MAX_CODE_BYTES", "16")
    from app.config import get_settings

    get_settings.cache_clear()
    response = product_client.post(
        "/api/chat",
        data={"message": "hi"},
        files={"file": ("big.c", DNS_SOURCE, "text/x-c")},
    )
    assert response.status_code == 413


def test_invalid_body_returns_422_not_500(product_client):
    # 手工解析 JSON 后仍需保持 FastAPI 原有的 422 校验语义。
    response = product_client.post(
        "/api/chat", content=b"{not json", headers={"content-type": "application/json"}
    )
    assert response.status_code == 422
    response = product_client.post("/api/chat", json={"message": ""})
    assert response.status_code == 422


def test_non_utf8_body_returns_422_not_500(product_client):
    # 非 UTF-8 字节的 JSON body 与非法 JSON 同语义：422 而非 500。
    response = product_client.post(
        "/api/chat",
        content=b'{"message": "\xff\xfe\x0e"}',
        headers={"content-type": "application/json"},
    )
    assert response.status_code == 422


if __name__ == "__main__":
    sys.exit(pytest.main([__file__, "-v"]))
