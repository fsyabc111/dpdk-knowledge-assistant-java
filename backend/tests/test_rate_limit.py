"""Rate-limit regression tests (M1): per-minute window and daily quota.

Runs the ASGI app in-process with a throwaway SQLite database and a mocked
RagProductService._candidate, so no LLM/embedding call ever leaves the process.
"""
from __future__ import annotations

import json

import pytest

MINUTE_TOKEN = "m" * 32
DAILY_TOKEN = "d" * 32


@pytest.fixture
def client(sqlite_db, monkeypatch):
    """TestClient in token auth mode with the model chain mocked out.

    Per-minute limit is 3 so it trips quickly; the daily-quota subject must
    not hit this first, so it stays above the number of daily-test requests.
    """
    monkeypatch.setenv("AUTH_MODE", "token")
    monkeypatch.setenv(
        "API_TOKENS_JSON",
        json.dumps({
            MINUTE_TOKEN: {"subject": "minute-user", "roles": ["viewer"]},
            DAILY_TOKEN: {"subject": "daily-user", "roles": ["viewer"], "daily_quota": 1},
        }),
    )
    monkeypatch.setenv("RATE_LIMIT_CHAT_PER_MINUTE", "3")

    from app.config import get_settings

    get_settings.cache_clear()

    from fastapi.testclient import TestClient

    from app.main import app
    from app.services.rag_product_service import RagProductService, Verdict

    async def fake_candidate(self, emit):
        return Verdict("mock answer", "answered", "supported"), [], "release"

    monkeypatch.setattr(RagProductService, "_candidate", fake_candidate)

    # The per-minute buckets live in a module-level singleton, so reset them
    # to the empty state the standalone script used to start from.
    from app.core.rate_limit import rate_limiter

    rate_limiter._buckets.clear()

    return TestClient(app)


def bearer(token: str) -> dict:
    return {"Authorization": f"Bearer {token}"}


def chat(client, token: str):
    return client.post("/api/chat", json={"message": "hi"}, headers=bearer(token))


def test_first_3_requests_within_minute_limit_pass(client):
    codes = [chat(client, MINUTE_TOKEN).status_code for _ in range(4)]
    assert codes[:3] == [200, 200, 200]


def test_4th_request_returns_429(client):
    codes = [chat(client, MINUTE_TOKEN).status_code for _ in range(4)]
    assert codes[3] == 429


def test_429_carries_retry_after(client):
    for _ in range(4):
        chat(client, MINUTE_TOKEN)
    response = chat(client, MINUTE_TOKEN)
    assert response.status_code == 429
    assert "Retry-After" in response.headers


def test_first_daily_quota_request_passes(client):
    # daily-user is limited to 1 chat per day, well under the per-minute window.
    response = chat(client, DAILY_TOKEN)
    assert response.status_code == 200


def test_second_daily_quota_request_returns_429(client):
    chat(client, DAILY_TOKEN)
    response = chat(client, DAILY_TOKEN)
    assert response.status_code == 429
    assert "Retry-After" in response.headers


def test_stream_shares_per_minute_bucket(client):
    for _ in range(4):
        chat(client, MINUTE_TOKEN)
    response = client.post("/api/chat/stream", json={"message": "hi"}, headers=bearer(MINUTE_TOKEN))
    assert response.status_code == 429


if __name__ == "__main__":
    import sys

    sys.exit(pytest.main([__file__, "-v"]))
