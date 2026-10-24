"""Shared pytest fixtures for the backend test suite.

All tests historically ran as standalone scripts that mutated ``os.environ``
and reset the settings/database caches by hand. These fixtures provide the
same isolation guarantees so tests can run in any order under pytest.
"""
from __future__ import annotations

import os
import sys
from pathlib import Path

import pytest

ROOT = Path(__file__).resolve().parents[1]
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))

# Tests construct AgentOrchestrator/RetrievalService for real (only the network
# calls are mocked), and the constructors require LLM/embedding settings.
# 默认集一律指向死地址（127.0.0.1:9 保留端口必然拒连）；只有 live/postgres
# mark 的用例才允许从 .env 或显式环境变量取真实上游配置。
_DUMMY_UPSTREAM_ENV = {
    "LLM_API_BASE": "http://127.0.0.1:9",
    "LLM_API_KEY": "ci-dummy-key",
    "EMBEDDING_API_BASE": "http://127.0.0.1:9",
    "EMBEDDING_API_KEY": "ci-dummy-key",
    "HARNESS_MCP_URL": "http://127.0.0.1:9/mcp",
}

# 声明了真实外部依赖的 mark：live（真实模型/embedding/已入库索引）与
# postgres（运行中的 PG + pgvector）。autouse 中和对带这些 mark 的用例
# 不生效——这是旧「.env 存在则豁免」分支的真实意图，改为按 mark 显式表达，
# 不再让无 mark 用例吃到开发库与真实上游（B0）。
_NEEDS_REAL_UPSTREAM_MARKS = {"live", "postgres"}


@pytest.fixture(autouse=True)
def _isolate_env_and_caches(request, tmp_path):
    """Snapshot os.environ around each test and reset app caches afterwards.

    Tests freely set DATABASE_BACKEND / AUTH_MODE / API_TOKENS_JSON etc.;
    this fixture guarantees nothing leaks between tests and that the cached
    Settings / database provider never straddles a test boundary.

    无 live/postgres mark 的用例强制落临时 SQLite + 死上游：本机
    backend/.env 含 DATABASE_BACKEND=postgres（开发库）与真实模型/harness
    地址，不中和的话任何忘记 sqlite_db 的用例都会写进真实库（B0）。
    """
    previous = dict(os.environ)
    # 本机 .env 可能把 AUTH_MODE 翻成 account/token（联调账号体系），而环境变量
    # 优先于 .env：测试一律默认在 local 模式下运行，需要其他模式的用例自行
    # monkeypatch.setenv("AUTH_MODE", ...)。
    os.environ["AUTH_MODE"] = "local"
    # 测试集不验证哈希强度，bcrypt cost 降到下限 4（默认 12 单次 ~0.3s，
    # 账号/认证用例会成百倍放大成分钟级）。
    os.environ["BCRYPT_ROUNDS"] = "4"
    if not {mark.name for mark in request.node.iter_markers()} & _NEEDS_REAL_UPSTREAM_MARKS:
        os.environ["DATABASE_BACKEND"] = "sqlite"
        os.environ["DATABASE_URL"] = f"sqlite:///{tmp_path / 'test.db'}"
        os.environ.update(_DUMMY_UPSTREAM_ENV)
    yield
    os.environ.clear()
    os.environ.update(previous)
    from app.config import get_settings
    from app.db import close_database_provider, get_database_provider

    try:
        close_database_provider()
    except Exception:
        pass
    get_settings.cache_clear()
    get_database_provider.cache_clear()


@pytest.fixture
def sqlite_db(tmp_path, monkeypatch):
    """Point the app at a throwaway SQLite database and initialise the schema.

    Returns the database file path. Settings and provider caches are reset
    before init so the new environment takes effect.
    """
    db_path = tmp_path / "test.db"
    monkeypatch.setenv("DATABASE_BACKEND", "sqlite")
    monkeypatch.setenv("DATABASE_URL", f"sqlite:///{db_path}")
    from app.config import get_settings
    from app.db import get_database_provider, init_db

    get_settings.cache_clear()
    get_database_provider.cache_clear()
    init_db()
    return db_path
