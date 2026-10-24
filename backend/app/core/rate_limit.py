"""Rate limiting for chat endpoints.

Two independent checks, both raised as HTTP 429 with a Retry-After hint:
- a per-minute sliding window kept in process memory (dict of deques);
- a per-day counter persisted in the usage_counters table so the LLM daily
  quota survives restarts and is shared when several workers run.

usage_counters carries a kind dimension (alembic 0004): chat traffic uses the
default kind='chat'; the agent grading path passes kind='grading' so the two
quotas count independently (check_and_count_daily(..., kind=...)).
"""
from __future__ import annotations

import asyncio
import json
import logging
import time
from collections import defaultdict, deque
from datetime import datetime, timezone
from threading import Lock
from typing import Deque, Dict

from fastapi import HTTPException, status

from ..config import get_settings
from ..core.metrics import record_rate_limit_hit
from ..db import get_conn, get_database_provider

logger = logging.getLogger("assistant")


class RateLimiter:
    def __init__(self) -> None:
        self._buckets: Dict[str, Deque[float]] = defaultdict(deque)
        self._lock = Lock()

    def check_per_minute(self, subject: str) -> None:
        limit = get_settings().rate_limit_chat_per_minute
        now = time.monotonic()
        with self._lock:
            bucket = self._buckets[subject]
            while bucket and now - bucket[0] >= 60.0:
                bucket.popleft()
            if len(bucket) >= limit:
                retry_after = max(1, int(60.0 - (now - bucket[0])) + 1)
                logger.warning(json.dumps(
                    {"event": "rate_limit_hit", "kind": "per_minute", "subject": subject}))
                record_rate_limit_hit("per_minute")
                raise HTTPException(
                    status_code=status.HTTP_429_TOO_MANY_REQUESTS,
                    detail=f"chat rate limit exceeded ({limit}/minute)",
                    headers={"Retry-After": str(retry_after)},
                )
            bucket.append(now)

    async def check_and_count_daily(self, subject: str, quota: int, kind: str = "chat") -> None:
        if quota == 0:
            return
        count = await asyncio.to_thread(self._increment_daily_sync, subject, kind)
        self._enforce_daily_quota(subject, quota, kind, count)

    def _enforce_daily_quota(self, subject: str, quota: int, kind: str, count: int) -> None:
        if count > quota:
            logger.warning(json.dumps(
                {"event": "rate_limit_hit", "kind": "daily_quota", "counter_kind": kind, "subject": subject}))
            record_rate_limit_hit("daily_quota")
            raise HTTPException(
                status_code=status.HTTP_429_TOO_MANY_REQUESTS,
                detail=f"daily {kind} quota exceeded ({quota}/day)",
                headers={"Retry-After": str(self._seconds_until_utc_midnight())},
            )

    def _increment_daily_sync(self, subject: str, kind: str = "chat") -> int:
        with get_conn() as conn:
            return self.increment_daily_in_conn(conn, subject, kind)

    def increment_daily_in_conn(self, conn, subject: str, kind: str = "chat") -> int:
        """在调用方事务内记一次日配额；任务受理用它与唯一键共同提交。"""
        ph = get_database_provider().placeholder
        day = datetime.now(timezone.utc).date().isoformat()
        row = conn.execute(
            f"INSERT INTO usage_counters(subject, day, kind, chat_count) VALUES ({ph}, {ph}, {ph}, 1) "
            f"ON CONFLICT(subject, day, kind) DO UPDATE SET chat_count = usage_counters.chat_count + 1 "
            f"RETURNING chat_count",
            (subject, day, kind),
        ).fetchone()
        return int(row["chat_count"])

    def count_chat_admission_in_conn(self, conn, subject: str, quota: int) -> None:
        self.count_kind_in_conn(conn, subject, quota, "chat")

    def count_kind_in_conn(self, conn, subject: str, quota: int, kind: str) -> None:
        if quota:
            count = self.increment_daily_in_conn(conn, subject, kind)
            self._enforce_daily_quota(subject, quota, kind, count)

    @staticmethod
    def _seconds_until_utc_midnight() -> int:
        now = datetime.now(timezone.utc)
        midnight = now.replace(hour=0, minute=0, second=0, microsecond=0)
        return max(1, int((midnight.timestamp() + 86400 - now.timestamp())))


rate_limiter = RateLimiter()
