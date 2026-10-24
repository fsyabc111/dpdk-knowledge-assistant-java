"""S1-b：autouse fixture 中和契约的金丝雀断言（B0）。

无 live/postgres mark 的用例在本机 backend/.env 存在时也必须拿不到
真实数据库与真实上游地址。若中和失效（例如 .env 豁免分支回归），
本文件的用例会立刻失败，而不是等某条用例误写开发库后才被发现。
"""
from __future__ import annotations

import os


def test_database_env_is_neutralized(tmp_path):
    assert os.environ["DATABASE_BACKEND"] == "sqlite"
    url = os.environ["DATABASE_URL"]
    assert url.startswith("sqlite:///")
    assert "assistant_dev" not in url
    assert str(tmp_path) in url


def test_upstreams_point_at_dead_address():
    for key in ("LLM_API_BASE", "EMBEDDING_API_BASE", "HARNESS_MCP_URL"):
        assert os.environ[key].startswith("http://127.0.0.1:9"), key
    assert os.environ["LLM_API_KEY"] == "ci-dummy-key"
    assert os.environ["EMBEDDING_API_KEY"] == "ci-dummy-key"


def test_settings_reflect_sqlite_backend():
    from app.config import get_settings

    get_settings.cache_clear()
    assert get_settings().database_backend == "sqlite"
