"""Network-free guards for public-surface and input-bound regressions."""
from __future__ import annotations

import sys

import pytest
from pydantic import ValidationError


def test_reindex_route_not_exposed():
    from app.main import app

    paths = {route.path for route in app.routes}
    assert "/api/admin/knowledge/reindex" not in paths


def test_normal_chat_message_accepted():
    from app.schemas.chat import ChatRequest

    assert ChatRequest(message="normal question").message == "normal question"


def test_oversized_chat_message_rejected():
    from app.schemas.chat import ChatRequest

    with pytest.raises(ValidationError):
        ChatRequest(message="x" * 12001)


if __name__ == "__main__":
    sys.exit(pytest.main([__file__, "-v"]))
