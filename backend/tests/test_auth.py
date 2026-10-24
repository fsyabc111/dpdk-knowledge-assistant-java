"""Auth regression tests for token mode (M1).

Runs the ASGI app in-process with a throwaway SQLite database and a mocked
RagProductService._candidate, so no LLM/embedding call ever leaves the process.
"""
from __future__ import annotations

import asyncio
import json

import pytest

VIEWER_TOKEN = "v" * 32
ADMIN_TOKEN = "a" * 32

VIEWER = {"subject": "alice", "roles": ["viewer"], "daily_quota": 0}
ADMIN = {"subject": "bob", "roles": ["admin"], "daily_quota": 0}


@pytest.fixture
def client(sqlite_db, monkeypatch):
    """TestClient in token auth mode with the model chain mocked out."""
    monkeypatch.setenv("AUTH_MODE", "token")
    monkeypatch.setenv(
        "API_TOKENS_JSON",
        json.dumps({VIEWER_TOKEN: VIEWER, ADMIN_TOKEN: ADMIN}),
    )
    # Keep the per-minute limiter out of the way of auth assertions.
    monkeypatch.setenv("RATE_LIMIT_CHAT_PER_MINUTE", "1000")

    from app.config import get_settings

    get_settings.cache_clear()

    from fastapi.testclient import TestClient

    from app.main import app
    from app.services.rag_product_service import RagProductService, Verdict

    captured = {}

    async def fake_candidate(self, emit):
        # 产品路径的请求上下文挂在 service 实例上；principal 即认证主体。
        captured["user_id"] = self.principal.subject
        return Verdict("mock answer", "answered", "supported"), [], "release"

    monkeypatch.setattr(RagProductService, "_candidate", fake_candidate)
    test_client = TestClient(app)
    test_client.captured = captured
    return test_client


def bearer(token: str) -> dict:
    return {"Authorization": f"Bearer {token}"}


def test_missing_token_returns_401(client):
    response = client.post("/api/chat", json={"message": "hi"})
    assert response.status_code == 401
    assert response.headers.get("WWW-Authenticate") == "Bearer"


def test_wrong_token_returns_401(client):
    response = client.post("/api/chat", json={"message": "hi"}, headers=bearer("x" * 32))
    assert response.status_code == 401


def test_valid_token_accepted_and_user_id_propagated(client):
    response = client.post("/api/chat", json={"message": "hi"}, headers=bearer(VIEWER_TOKEN))
    assert response.status_code == 200
    assert client.captured.get("user_id") == "alice"


def test_cross_subject_session_read_returns_404(client):
    from app.services.session_service import SessionService

    session_id = asyncio.run(SessionService().ensure_session(None, "owned", user_id="alice"))
    response = client.get(f"/api/chat/sessions/{session_id}", headers=bearer(ADMIN_TOKEN))
    assert response.status_code == 404
    response = client.get(f"/api/chat/sessions/{session_id}", headers=bearer(VIEWER_TOKEN))
    assert response.status_code == 200


def test_debug_trace_not_in_product_response(client):
    """S4 收敛后产品路径不在响应里回传 debug_trace（trace 走 chat_trace 日志/指标）。"""
    response = client.post(
        "/api/chat", json={"message": "hi", "context": {"debug_trace": True}},
        headers=bearer(VIEWER_TOKEN),
    )
    assert response.status_code == 200
    assert response.json().get("debug_trace") is None

    response = client.post(
        "/api/chat", json={"message": "hi", "context": {"debug_trace": True}},
        headers=bearer(ADMIN_TOKEN),
    )
    assert response.status_code == 200
    assert response.json().get("debug_trace") is None


def test_health_and_stream_auth_contract(client):
    response = client.get("/api/health")
    assert response.status_code == 200
    response = client.post("/api/chat/stream", json={"message": "hi"})
    assert response.status_code == 401


if __name__ == "__main__":
    import sys

    sys.exit(pytest.main([__file__, "-v"]))
