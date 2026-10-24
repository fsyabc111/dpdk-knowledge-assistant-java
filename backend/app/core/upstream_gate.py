"""每事件循环共享的上游并发限额。"""
import asyncio
import weakref
from contextlib import asynccontextmanager

_gates = weakref.WeakKeyDictionary()


@asynccontextmanager
async def upstream_slot():
    from ..config import get_settings
    settings = get_settings()
    loop = asyncio.get_running_loop()
    gate = _gates.get(loop)
    if gate is None:
        gate = asyncio.Semaphore(settings.rag_upstream_concurrency)
        _gates[loop] = gate
    async with gate:
        yield
