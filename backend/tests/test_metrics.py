"""Metrics endpoint regression tests (M3).

Runs the ASGI app in-process with a throwaway SQLite database; no external
LLM/embedding calls. Verifies /metrics is exposed and domain metrics appear
after their recording helpers run.
"""
from __future__ import annotations

import re

import pytest


@pytest.fixture
def client(sqlite_db, monkeypatch):
    """TestClient with local auth and domain metrics pre-recorded."""
    monkeypatch.setenv("AUTH_MODE", "local")

    from app.config import get_settings

    get_settings.cache_clear()

    from fastapi.testclient import TestClient

    from app.core.metrics import (
        record_chat_trace,
        record_llm_call,
        record_rag_validation,
        record_rate_limit_hit,
    )
    from app.core.tracing import RequestTrace, timed_stage
    from app.main import app

    record_llm_call("chat_model", "success", 0.5)
    record_rate_limit_hit("per_minute")
    record_rag_validation("answered", 0.25)
    trace = RequestTrace()
    trace.set_meta(rag_engine="llama_index")
    trace.mark_fallback("test fallback")
    with timed_stage(trace, "retrieval_total_ms"):
        pass
    with timed_stage(trace, "rewrite_ms"):
        pass
    with timed_stage(trace, "embedding_ms"):
        pass
    record_chat_trace(trace)

    return TestClient(app)


def test_metrics_endpoint_returns_200(client):
    response = client.get("/metrics")
    assert response.status_code == 200


def test_http_instrumentator_metrics_present(client):
    body = client.get("/metrics").text
    assert "http_requests_total" in body


def test_llm_counter_present(client):
    body = client.get("/metrics").text
    assert 'assistant_llm_requests_total{engine="chat_model",result="success"}' in body


def test_rate_limit_counter_present(client):
    body = client.get("/metrics").text
    assert 'assistant_rate_limit_hits_total{kind="per_minute"}' in body


def test_fallback_counter_present(client):
    body = client.get("/metrics").text
    # The Prometheus registry is process-global and the fixture records one
    # fallback per test, so assert the counter exists with a positive value
    # rather than an exact "1.0" (which only held in the standalone script).
    match = re.search(r"^assistant_chat_fallbacks_total (\S+)$", body, re.M)
    assert match is not None
    assert float(match.group(1)) >= 1.0


def test_retrieval_histogram_present(client):
    body = client.get("/metrics").text
    assert "assistant_chat_retrieval_duration_seconds_count" in body


def test_metrics_hidden_from_openapi(client):
    body = client.get("/openapi.json").text
    assert "/metrics" not in body


if __name__ == "__main__":
    import sys

    sys.exit(pytest.main([__file__, "-v"]))
