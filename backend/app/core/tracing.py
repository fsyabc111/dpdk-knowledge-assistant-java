from __future__ import annotations

import time
import uuid
from dataclasses import dataclass, field
from typing import Any, Dict, Optional

from .usage import UsageRecorder


def now_ms() -> float:
    return time.perf_counter() * 1000.0


@dataclass
class RequestTrace:
    request_id: str = field(default_factory=lambda: f"trace_{uuid.uuid4().hex[:12]}")
    started_ms: float = field(default_factory=now_ms)
    stages_ms: Dict[str, float] = field(default_factory=dict)
    metadata: Dict[str, Any] = field(default_factory=dict)
    fallback_reason: str = ""
    # 用量累加器：与 stages_ms 同构（请求级、只累加），不进 metadata，
    # 避免把逐条明细灌进 chat_trace 日志——日志里只落 totals() 汇总。
    usage: UsageRecorder = field(default_factory=UsageRecorder)

    def add_stage(self, name: str, elapsed_ms: float) -> None:
        self.stages_ms[name] = round(self.stages_ms.get(name, 0.0) + elapsed_ms, 2)

    def set_meta(self, **items: Any) -> None:
        for key, value in items.items():
            if value is not None:
                self.metadata[key] = value

    def mark_fallback(self, reason: str) -> None:
        self.fallback_reason = reason[:300]

    def as_dict(self) -> Dict[str, Any]:
        total_ms = round(now_ms() - self.started_ms, 2)
        return {
            "request_id": self.request_id,
            "total_ms": total_ms,
            "stages_ms": dict(self.stages_ms),
            "metadata": dict(self.metadata),
            "fallback_reason": self.fallback_reason,
        }


class StageTimer:
    def __init__(self, trace: Optional[RequestTrace], name: str) -> None:
        self.trace = trace
        self.name = name
        self.started_ms = 0.0

    def __enter__(self) -> "StageTimer":
        self.started_ms = now_ms()
        return self

    def __exit__(self, exc_type: object, exc: object, tb: object) -> None:
        if self.trace is not None:
            self.trace.add_stage(self.name, now_ms() - self.started_ms)


def timed_stage(trace: Optional[RequestTrace], name: str) -> StageTimer:
    return StageTimer(trace, name)
