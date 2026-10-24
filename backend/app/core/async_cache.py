from __future__ import annotations

import asyncio
import time
from collections import OrderedDict
from typing import Awaitable, Callable, Dict, Generic, TypeVar

K = TypeVar("K")
V = TypeVar("V")


class AsyncTTLCache(Generic[K, V]):
    """Small async TTL cache with single-flight dedup for concurrent callers."""

    def __init__(self, ttl_seconds: float, maxsize: int = 256) -> None:
        self.ttl_seconds = ttl_seconds
        self.maxsize = maxsize
        self._values: "OrderedDict[K, tuple[float, V]]" = OrderedDict()
        self._inflight: Dict[K, asyncio.Task[V]] = {}
        self._lock = asyncio.Lock()

    async def get_or_create(self, key: K, factory: Callable[[], Awaitable[V]]) -> V:
        now = time.monotonic()

        async with self._lock:
            cached = self._values.get(key)
            if cached and cached[0] > now:
                self._values.move_to_end(key)
                return cached[1]
            if cached:
                self._values.pop(key, None)

            task = self._inflight.get(key)
            if task is None:
                # Keep cache bookkeeping inside the shared task.  A caller is
                # allowed to cancel its own request, but that must neither
                # cancel work other callers are waiting on nor leave a
                # cancelled task stuck in ``_inflight`` forever.
                async def populate() -> V:
                    try:
                        value = await factory()
                    except BaseException:
                        async with self._lock:
                            if self._inflight.get(key) is asyncio.current_task():
                                self._inflight.pop(key, None)
                        raise
                    async with self._lock:
                        if self._inflight.get(key) is asyncio.current_task():
                            self._inflight.pop(key, None)
                            self._values[key] = (time.monotonic() + self.ttl_seconds, value)
                            self._values.move_to_end(key)
                            while len(self._values) > self.maxsize:
                                self._values.popitem(last=False)
                    return value

                task = asyncio.create_task(populate())
                self._inflight[key] = task

        # ``shield`` prevents cancellation of one HTTP request from
        # propagating into this process-wide single-flight task.
        return await asyncio.shield(task)

    async def clear(self) -> None:
        async with self._lock:
            self._values.clear()
            self._inflight.clear()
