from __future__ import annotations

from contextlib import AbstractContextManager
from threading import Lock
from typing import Any

from .config import Settings, get_settings
from .storage.interfaces import DatabaseProvider
from .storage.postgres import PostgresProvider
from .storage.sqlite import SQLiteProvider


class UnsupportedDatabaseProvider:
    def __init__(self, settings: Settings) -> None:
        self.settings = settings
        self.name = settings.database_backend

    def init(self) -> None:
        raise ValueError(
            f"Unsupported DATABASE_BACKEND={self.settings.database_backend!r}. "
            "Use postgres for the default runtime, or sqlite for the explicit "
            "local-development fallback."
        )

    def connect(self) -> AbstractContextManager[Any]:
        self.init()
        raise AssertionError("unreachable")

    def close(self) -> None:
        return None


_provider_lock = Lock()
_provider: DatabaseProvider | None = None


def _build_database_provider() -> DatabaseProvider:
    settings = get_settings()
    if settings.database_backend == "sqlite":
        return SQLiteProvider(settings)
    if settings.database_backend in {"postgres", "postgresql"}:
        return PostgresProvider(settings)
    return UnsupportedDatabaseProvider(settings)


def get_database_provider() -> DatabaseProvider:
    """Return exactly one provider while concurrent requests cold-start."""
    global _provider
    with _provider_lock:
        if _provider is None:
            _provider = _build_database_provider()
        return _provider


def close_database_provider() -> None:
    """Release the current provider and its process-lifetime resources."""
    global _provider
    with _provider_lock:
        provider = _provider
        _provider = None
    if provider is not None:
        provider.close()


def _clear_database_provider_cache() -> None:
    close_database_provider()


# Compatibility hook for tests that switch backend settings in one process.
get_database_provider.cache_clear = _clear_database_provider_cache  # type: ignore[attr-defined]


def init_db() -> None:
    get_database_provider().init()


def get_conn() -> AbstractContextManager[Any]:
    return get_database_provider().connect()
