"""Regression test for concurrent database-provider and pool lifecycle setup.

This test is intentionally network-free: it verifies the cold-start singleton
contract without opening a PostgreSQL connection.
"""
from __future__ import annotations

from concurrent.futures import ThreadPoolExecutor

import pytest


def test_concurrent_lookup_creates_single_provider():
    from app.db import get_database_provider

    with ThreadPoolExecutor(max_workers=16) as executor:
        providers = list(executor.map(lambda _: get_database_provider(), range(64)))
    assert providers, "expected at least one provider lookup"
    assert all(
        provider is providers[0] for provider in providers
    ), "concurrent lookup created more than one database provider"


if __name__ == "__main__":
    import sys

    sys.exit(pytest.main([__file__, "-v"]))
