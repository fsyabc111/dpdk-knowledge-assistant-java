"""用量账本的落库与聚合（P1 §3/§5）。

记录层（``app/core/usage.py``）只产出条目与行，本模块负责写进
``upstream_usage_records`` 并把它读回来聚合。

聚合刻意做成**纯函数** ``aggregate_usage(rows)``：不碰数据库、不依赖 provider，
因此可以拿手写的行直接单测；HTTP 层只做参数解析与序列化，不重复算一遍。

两条不可动摇的语义（与记录层一致）：

1. **未知不是 0**：token 合计只对**有值**的行求和，并同时给出分母
   （``known_entries``）；未知条数单独回报，便于判断这份合计有多可信。
2. **不跨币种求和**：成本按币种分列（``cost_micros_by_currency``）。把人民币和
   美元加在一起是最糟的一种「看起来对了」。
"""
from __future__ import annotations

import json
import logging
from typing import Any, Dict, List, Mapping, Optional, Sequence, Tuple

from ..core.usage import PRICED_KINDS, SOURCE_UNKNOWN
from ..db import get_conn, get_database_provider

logger = logging.getLogger("assistant")

USAGE_COLUMNS: Tuple[str, ...] = (
    "id", "request_id", "subject", "session_id", "kind", "stage", "model",
    "prompt_tokens", "completion_tokens", "total_tokens", "usage_source",
    "call_count", "duration_ms", "cost_micros", "currency", "price_version",
    "created_at",
)

# 单次查询读回的行数上限。聚合在 Python 侧做（保持纯函数是唯一口径），所以必须
# 有界；触顶时如实标记 truncated，避免被截断的合计被当成完整账目。
MAX_QUERY_ROWS = 20000


# ---------------------------------------------------------------------------
# 纯聚合
# ---------------------------------------------------------------------------


def _int_or_none(value: Any) -> Optional[int]:
    if value is None or isinstance(value, bool):
        return None
    try:
        return int(value)
    except (TypeError, ValueError):
        return None


def _sum_field(rows: Sequence[Mapping[str, Any]], field: str) -> Optional[int]:
    """只对**有值**的行求和；一行都没有值时返回 None（而不是 0）。"""
    values = [_int_or_none(row.get(field)) for row in rows]
    present = [value for value in values if value is not None]
    return sum(present) if present else None


def _count_present(rows: Sequence[Mapping[str, Any]], field: str) -> int:
    return sum(1 for row in rows if _int_or_none(row.get(field)) is not None)


def _cost_by_currency(rows: Sequence[Mapping[str, Any]]) -> Dict[str, int]:
    totals: Dict[str, int] = {}
    for row in rows:
        cost = _int_or_none(row.get("cost_micros"))
        currency = row.get("currency")
        if cost is None or not isinstance(currency, str) or not currency:
            continue
        totals[currency] = totals.get(currency, 0) + cost
    return totals


def _bucket(rows: Sequence[Mapping[str, Any]]) -> Dict[str, Any]:
    """一组行的共同口径。token 合计与分母同出，未知条数单列。"""
    return {
        "entries": len(rows),
        "calls": sum(_int_or_none(row.get("call_count")) or 0 for row in rows),
        "prompt_tokens": _sum_field(rows, "prompt_tokens"),
        "completion_tokens": _sum_field(rows, "completion_tokens"),
        "total_tokens": _sum_field(rows, "total_tokens"),
        # 分母：有多少条真的贡献了上面的合计。没有它，合计就是个不可信的孤数。
        "known_entries": _count_present(rows, "total_tokens"),
        "unknown_entries": sum(1 for row in rows if row.get("usage_source") == SOURCE_UNKNOWN),
        "duration_ms": _sum_field(rows, "duration_ms"),
    }


def _operation_bucket(rows: Sequence[Mapping[str, Any]]) -> Dict[str, Any]:
    """非 token 操作（harness/MCP）：只报次数与耗时，**显式不计费**。

    它们的单价在外部服务里，本仓无从得知。编造一个费率比不计量更糟——
    ``priced`` 常量为 False 不是「暂时算不出来」，而是本仓的口径。
    """
    return {
        "entries": len(rows),
        "calls": sum(_int_or_none(row.get("call_count")) or 0 for row in rows),
        "duration_ms": _sum_field(rows, "duration_ms"),
        "priced": False,
    }


def _group(rows: Sequence[Mapping[str, Any]], field: str) -> List[Dict[str, Any]]:
    groups: Dict[str, List[Mapping[str, Any]]] = {}
    for row in rows:
        key = row.get(field)
        groups.setdefault(key if isinstance(key, str) and key else "unknown", []).append(row)
    result: List[Dict[str, Any]] = []
    for key in sorted(groups):
        bucket = _bucket(groups[key])
        bucket["key"] = key
        bucket["cost_micros_by_currency"] = _cost_by_currency(groups[key])
        result.append(bucket)
    return result


def _group_by_day(rows: Sequence[Mapping[str, Any]]) -> List[Dict[str, Any]]:
    groups: Dict[str, List[Mapping[str, Any]]] = {}
    for row in rows:
        created = row.get("created_at")
        # created_at 是 ISO8601 文本，前 10 位即 UTC 日期，两种后端同形。
        day = created[:10] if isinstance(created, str) and len(created) >= 10 else "unknown"
        groups.setdefault(day, []).append(row)
    result: List[Dict[str, Any]] = []
    for key in sorted(groups):
        bucket = _bucket(groups[key])
        bucket["key"] = key
        bucket["cost_micros_by_currency"] = _cost_by_currency(groups[key])
        result.append(bucket)
    return result


def _unpriced_models(rows: Sequence[Mapping[str, Any]]) -> List[str]:
    """有 token 用量、却没算出成本的模型。

    只把**确实消耗了 token** 的模型算进来：缓存命中带 model 但不带用量，
    它没有价格缺口。
    """
    names = set()
    for row in rows:
        model = row.get("model")
        if not isinstance(model, str) or not model:
            continue
        if _int_or_none(row.get("total_tokens")) is None:
            continue
        if _int_or_none(row.get("cost_micros")) is None:
            names.add(model)
    return sorted(names)


def aggregate_usage(rows: Sequence[Mapping[str, Any]]) -> Dict[str, Any]:
    """把账本行聚合成可直接序列化的摘要（纯函数）。

    ``totals`` 只统计计费 kind（生成 + embedding），``operations`` 单列
    harness/MCP —— 用户确认的「token 计费 + 非 token 用量分列」。
    """
    priced = [row for row in rows if row.get("kind") in PRICED_KINDS]
    operations = [row for row in rows if row.get("kind") not in PRICED_KINDS]

    totals = _bucket(priced)
    cost_by_currency = _cost_by_currency(priced)
    totals["cost_micros_by_currency"] = cost_by_currency
    unpriced = _unpriced_models(priced)

    return {
        "totals": totals,
        "operations": _operation_bucket(operations),
        "by_stage": _group(rows, "stage"),
        "by_model": _group(priced, "model"),
        "by_day": _group_by_day(rows),
        "unpriced_models": unpriced,
        # 顶层冗余两个「账没算清」的信号，让调用方不必下钻就能看到。
        "unknown_entries": totals["unknown_entries"],
        "cost_currency_mixed": len(cost_by_currency) > 1,
    }


# ---------------------------------------------------------------------------
# 落库与查询
# ---------------------------------------------------------------------------


def _empty_scoped_summary() -> Dict[str, Any]:
    """可见主体为空时的摘要：空账而不是全量账。"""
    summary = aggregate_usage([])
    summary["truncated"] = False
    summary["row_limit"] = MAX_QUERY_ROWS
    return summary


class UsageService:
    """账本读写。同步 DB 操作，调用方用 ``asyncio.to_thread`` 包裹（同 api/usage.py 惯例）。"""

    @property
    def _placeholder(self) -> str:
        # 惰性解析：测试里中途切换后端仍能生效。
        return get_database_provider().placeholder

    def record_rows(self, rows: Sequence[Mapping[str, Any]]) -> int:
        """批量写入账本行，返回写入条数。

        **不做异常兜底**：写失败就抛，由调用方决定是否降级。账本静默丢数据比
        写失败更危险——「少了几笔」是看不出来的。
        """
        if not rows:
            return 0
        ph = self._placeholder
        columns = ", ".join(USAGE_COLUMNS)
        placeholders = ", ".join([ph] * len(USAGE_COLUMNS))
        sql = f"INSERT INTO upstream_usage_records({columns}) VALUES ({placeholders})"
        with get_conn() as conn:
            for row in rows:
                conn.execute(sql, tuple(row.get(column) for column in USAGE_COLUMNS))
        return len(rows)

    def query(
        self,
        *,
        since: Optional[str] = None,
        subject: Optional[str] = None,
        subjects: Optional[Sequence[str]] = None,
        stage: Optional[str] = None,
        request_id: Optional[str] = None,
    ) -> Dict[str, Any]:
        """读回账本并聚合。``since`` 是 ISO8601 文本（含则取 >= 的行）。

        ``subjects`` 是「一组主体」的过滤（树隔离视图用）：与 ``subject`` 互斥，
        传空集合表示**看不到任何主体**，此时返回空账而不是全量账——范围算错
        会泄露别人的花费，宁可返回空。

        ``request_id`` 精确匹配单次请求的账目（live 评测按题归因 token 用）。
        它**只做收窄**：仍受 ``subject``/``subjects`` 约束，不能让调用方越过
        树隔离看到别人的花费。
        """
        ph = self._placeholder
        clauses: List[str] = []
        params: List[Any] = []
        if since:
            clauses.append(f"created_at >= {ph}")
            params.append(since)
        if subject:
            clauses.append(f"subject = {ph}")
            params.append(subject)
        elif subjects is not None:
            names = [name for name in subjects if isinstance(name, str) and name]
            if not names:
                return _empty_scoped_summary()
            placeholders = ", ".join([ph] * len(names))
            clauses.append(f"subject IN ({placeholders})")
            params.extend(names)
        if stage:
            clauses.append(f"stage = {ph}")
            params.append(stage)
        if request_id:
            clauses.append(f"request_id = {ph}")
            params.append(request_id)
        where = f" WHERE {' AND '.join(clauses)}" if clauses else ""
        # 倒数读取 + 多取一行用于判断是否触顶。
        sql = (
            f"SELECT {', '.join(USAGE_COLUMNS)} FROM upstream_usage_records{where} "
            f"ORDER BY created_at DESC LIMIT {MAX_QUERY_ROWS + 1}"
        )
        with get_conn() as conn:
            rows = [dict(row) for row in conn.execute(sql, tuple(params)).fetchall()]

        truncated = len(rows) > MAX_QUERY_ROWS
        if truncated:
            rows = rows[:MAX_QUERY_ROWS]
        summary = aggregate_usage(rows)
        summary["truncated"] = truncated
        summary["row_limit"] = MAX_QUERY_ROWS
        return summary

    def count_rows(self, *, since: Optional[str] = None) -> int:
        """账本行数（运维/自检用）。"""
        ph = self._placeholder
        where = f" WHERE created_at >= {ph}" if since else ""
        params: Tuple[Any, ...] = (since,) if since else ()
        with get_conn() as conn:
            row = conn.execute(
                f"SELECT COUNT(*) AS total FROM upstream_usage_records{where}", params,
            ).fetchone()
        return int(row["total"])


def flush_usage_recorder(
    usage: Any,
    *,
    request_id: Optional[str] = None,
    subject: Optional[str] = None,
    session_id: Optional[str] = None,
) -> int:
    """把一个累加器的条目落库并折进指标，返回写入条数。

    请求路径（trace）与离线摄取 CLI 共用这一条收口：两条路的花费必须走同一套
    定价、同一张表，否则账又分成两本。

    - **空账不写**：没有条目就没有 INSERT，也不会在表里留空行；
    - **写库失败只告警不抛**：账本问题绝不能让已经生成好的答案或已完成的摄取失败。
    """
    from ..config import get_settings
    from ..core.metrics import record_usage_entries
    from ..core.usage import entries_to_rows
    from .session_service import now_iso

    settings = get_settings()
    entries = usage.entries
    if not entries or not settings.llm_usage_recording_enabled:
        return 0

    rows = entries_to_rows(
        entries,
        prices=settings.llm_prices,
        price_version=settings.llm_price_version,
        request_id=request_id,
        subject=subject,
        session_id=session_id,
        created_at=now_iso(),
    )
    record_usage_entries(rows)
    try:
        return UsageService().record_rows(rows)
    except Exception as exc:  # noqa: BLE001 - 账本失败必须降级，不能影响响应
        logger.warning(json.dumps({
            "event": "usage_record_failed",
            "request_id": request_id,
            "rows": len(rows),
            "error_type": type(exc).__name__,
            "error": str(exc)[:200],
        }, ensure_ascii=False))
        return 0


def flush_trace_usage(trace: Any) -> int:
    """把请求 trace 上的用量落库，并把汇总写进 ``trace.metadata``。

    调用点放在**既有的** ``finally: self._log_trace(trace)`` 里：那条路径在取消时
    也会执行（这正是 P1 要求覆盖的取消场景），且不在热路径上同步写库。

    两条策略：

    - **写库失败只告警不抛**：账本问题绝不能让已经生成好的答案失败（见
      ``flush_usage_recorder``）；
    - **落库失败也要有汇总**：``usage_totals`` 先写进 metadata，因此即使写库失败，
      chat_trace 日志与后续排查仍能看到这次花了多少。
    """
    from ..config import get_settings

    settings = get_settings()
    entries = trace.usage.entries
    if entries:
        totals = trace.usage.totals()
        totals["recording_enabled"] = bool(settings.llm_usage_recording_enabled)
        trace.set_meta(usage_totals=totals)
    return flush_usage_recorder(
        trace.usage,
        request_id=trace.request_id,
        subject=trace.usage.subject,
        session_id=trace.usage.session_id,
    )
