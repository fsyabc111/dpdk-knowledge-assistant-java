"""上游用量与成本记录（P1 全链路用量与成本统计）。

要回答的问题是「一次请求到底花了多少」。此前 `tokens_used` 只出现在 LangGraph
state、chat_trace 日志与一个直方图里，embedding/rewrite/judge/摘要/ingest 的用量
在客户端就被丢弃，全仓没有任何价格或成本口径。

本模块只做**纯逻辑**：采集条目、判定来源、算成本。不碰数据库、不发网络请求，
因此可以完全离线单测；落库与聚合见 ``app/services/usage_service.py``。

三条不可动摇的语义（测试锁死）：

1. **未知不是 0**：上游没返回 usage 时 token 列一律 ``None``，绝不用 0 顶替，
   也不做任何本地 token 估算。
2. **零调用要留痕**：缓存命中与幂等重放显式记 ``call_count=0`` 的条目，
   使「为什么这次没花钱」可审计，而不是消失不见。
3. **未定价不出价**：模型不在价格表里 → 成本 ``None``，只报用量，不编造费用。
"""
from __future__ import annotations

import json
from dataclasses import dataclass
from decimal import ROUND_HALF_UP, Decimal, InvalidOperation
from typing import Any, Dict, List, Mapping, Optional, Sequence

# ---- 用量来源：区分「真的调了上游」与「根本没调」 ----
SOURCE_UPSTREAM = "upstream"      # 上游返回了 usage
SOURCE_UNKNOWN = "unknown"        # 调了，但上游没给 usage（含重试后仍失败）
SOURCE_CACHE_HIT = "cache_hit"    # 命中缓存，零上游调用
SOURCE_REPLAY = "replay"          # 幂等重放，零模型调用
SOURCE_LOCAL = "local"            # 本地规则/启发式直接出结果，零上游调用
SOURCE_OPERATION = "operation"    # 非 token 操作（harness/MCP），token 列不适用

SOURCES = frozenset({
    SOURCE_UPSTREAM, SOURCE_UNKNOWN, SOURCE_CACHE_HIT, SOURCE_REPLAY,
    SOURCE_LOCAL, SOURCE_OPERATION,
})

# 「已知的零调用」：没有 token 数是**事实**而非缺失，不能计进 unknown。
ZERO_CALL_SOURCES = frozenset({SOURCE_CACHE_HIT, SOURCE_REPLAY, SOURCE_LOCAL})

# ---- 用量种类 ----
KIND_LLM = "llm"
KIND_EMBEDDING = "embedding"
KIND_HARNESS = "harness"
KIND_MCP = "mcp"

# 计费的 kind。harness/MCP 是外部服务的算力，本仓无法得知其单价，
# 只记次数与耗时，显式不计费（编造费率比不计量更糟）。
PRICED_KINDS = frozenset({KIND_LLM, KIND_EMBEDDING})

_MICROS = Decimal(1_000_000)


# ---------------------------------------------------------------------------
# 上游 usage 解析
# ---------------------------------------------------------------------------


def _int_or_none(value: Any) -> Optional[int]:
    """把上游字段收成非负整数；任何异常形态一律 None（未知），不猜。"""
    if isinstance(value, bool) or value is None:
        return None
    if isinstance(value, int):
        return value if value >= 0 else None
    if isinstance(value, float):
        return int(value) if value >= 0 and value.is_integer() else None
    if isinstance(value, str) and value.strip().isdigit():
        return int(value.strip())
    return None


def tokens_from_usage(usage: Optional[Mapping[str, Any]]) -> Dict[str, Optional[int]]:
    """取出 prompt/completion/total 三项 token；缺失保持 None。

    同时接受 OpenAI 的 ``prompt_tokens``/``completion_tokens`` 与 LangChain
    ``usage_metadata`` 的 ``input_tokens``/``output_tokens`` 两套键名。

    上游只给分项、没给 total 时才做 ``total = prompt + completion``：这是等价推导，
    不是估算。（任一分项缺失就保持 None——半个数是不可加的。）
    """
    empty = {"prompt_tokens": None, "completion_tokens": None, "total_tokens": None}
    if not isinstance(usage, Mapping):
        return empty
    prompt = _int_or_none(usage.get("prompt_tokens", usage.get("input_tokens")))
    completion = _int_or_none(usage.get("completion_tokens", usage.get("output_tokens")))
    total = _int_or_none(usage.get("total_tokens"))
    if total is None and prompt is not None and completion is not None:
        total = prompt + completion
    return {"prompt_tokens": prompt, "completion_tokens": completion, "total_tokens": total}


def _has_any_token(tokens: Mapping[str, Optional[int]]) -> bool:
    return any(value is not None for value in tokens.values())


# ---------------------------------------------------------------------------
# 价格
# ---------------------------------------------------------------------------


@dataclass(frozen=True)
class ModelPrice:
    """单个模型的单价。单价以「每 1000 token」计价，币种随配置。"""

    input_per_1k: Decimal
    output_per_1k: Decimal
    currency: str


def _decimal_or_none(value: Any) -> Optional[Decimal]:
    if isinstance(value, bool) or value is None:
        return None
    if isinstance(value, (int, float, str)):
        try:
            parsed = Decimal(str(value))
        except (InvalidOperation, ValueError):
            return None
        return parsed if parsed.is_finite() and parsed >= 0 else None
    return None


def parse_prices(raw: Optional[str]) -> Dict[str, ModelPrice]:
    """解析 ``LLM_PRICES_JSON``。空串/未配置 → ``{}``（**不是错误**）。

    值的形态（单价用**字符串**以便 Decimal 精确解析）::

        {"qwen3.8-flash": {"input_per_1k": "0.0008", "output_per_1k": "0.002",
                           "currency": "CNY"}}

    embedding 模型只有输入侧，``output_per_1k`` 可省略（按 0 计）。
    单条格式不合法的项被跳过而不是抛异常：价格是运维数据，一条写错不该让
    整个服务起不来——缺失价格本身是合法状态（只报用量）。
    """
    if not raw or not str(raw).strip():
        return {}
    try:
        parsed = json.loads(raw)
    except (json.JSONDecodeError, TypeError, ValueError):
        return {}
    if not isinstance(parsed, Mapping):
        return {}

    prices: Dict[str, ModelPrice] = {}
    for model, spec in parsed.items():
        if not isinstance(model, str) or not model.strip() or not isinstance(spec, Mapping):
            continue
        input_price = _decimal_or_none(spec.get("input_per_1k"))
        if input_price is None:
            continue
        output_price = _decimal_or_none(spec.get("output_per_1k"))
        currency = spec.get("currency")
        if not isinstance(currency, str) or not currency.strip():
            continue
        prices[model.strip()] = ModelPrice(
            input_per_1k=input_price,
            output_per_1k=output_price if output_price is not None else Decimal(0),
            currency=currency.strip(),
        )
    return prices


def cost_micros(
    prompt_tokens: Optional[int],
    completion_tokens: Optional[int],
    price: Optional[ModelPrice],
) -> Optional[int]:
    """整数微单位成本（1e-6 个币种单位）。

    用整数微单位而不是浮点金额：钱不能用二进制浮点表示。
    任一前提缺失（未配价 / 两个 token 都为 None）→ ``None``，不出价。
    """
    if price is None:
        return None
    if prompt_tokens is None and completion_tokens is None:
        return None
    total = Decimal(0)
    if prompt_tokens is not None:
        total += Decimal(prompt_tokens) * price.input_per_1k / Decimal(1000)
    if completion_tokens is not None:
        total += Decimal(completion_tokens) * price.output_per_1k / Decimal(1000)
    return int((total * _MICROS).quantize(Decimal(1), rounding=ROUND_HALF_UP))


# ---------------------------------------------------------------------------
# 条目与累加器
# ---------------------------------------------------------------------------


@dataclass(frozen=True)
class UsageEntry:
    """一次上游花费（或一次显式的「零调用」）。token 为 None 表示未知。"""

    kind: str
    stage: str
    usage_source: str
    model: Optional[str] = None
    prompt_tokens: Optional[int] = None
    completion_tokens: Optional[int] = None
    total_tokens: Optional[int] = None
    call_count: int = 1
    duration_ms: Optional[int] = None

    @property
    def priced(self) -> bool:
        return self.kind in PRICED_KINDS

    @property
    def usage_known(self) -> bool:
        return self.total_tokens is not None


class UsageRecorder:
    """请求级用量累加器。

    挂在 ``RequestTrace`` 上，由既有的 ``finally: _log_trace`` 统一落库——那条路径
    在取消时也会执行，所以取消场景无需额外的钩子。**只记录，不落库**：
    因此 recording 关闭时数据依然可供 chat_trace 观测。
    """

    def __init__(self, *, subject: Optional[str] = None, session_id: Optional[str] = None) -> None:
        self._entries: List[UsageEntry] = []
        # 归属信息挂在累加器上而不是 trace.metadata 里：chat_trace 日志会整份
        # 打印 metadata，把学生标识塞进去等于把身份写进日志。
        self.subject = subject
        self.session_id = session_id

    def bind(self, *, subject: Optional[str] = None, session_id: Optional[str] = None) -> None:
        """补上记录归属。只填空缺、不覆盖：重放或恢复场景不该被改写归属。"""
        if subject and not self.subject:
            self.subject = subject
        if session_id and not self.session_id:
            self.session_id = session_id

    @property
    def entries(self) -> List[UsageEntry]:
        return list(self._entries)

    def record_llm(
        self,
        stage: str,
        model: Optional[str],
        usage: Optional[Mapping[str, Any]],
        *,
        duration_ms: Optional[int] = None,
        kind: str = KIND_LLM,
        calls: int = 1,
    ) -> Optional[UsageEntry]:
        """记一次真实上游调用。``usage`` 为 None 或字段缺失 → 该字段未知。"""
        tokens = tokens_from_usage(usage)
        source = SOURCE_UPSTREAM if _has_any_token(tokens) else SOURCE_UNKNOWN
        return self._append(
            kind=kind, stage=stage, usage_source=source, model=model,
            duration_ms=duration_ms, call_count=max(int(calls), 0), **tokens,
        )

    def record_skipped(
        self,
        stage: str,
        reason: str,
        *,
        model: Optional[str] = None,
        kind: str = KIND_LLM,
    ) -> Optional[UsageEntry]:
        """缓存命中 / 幂等重放 / 本地规则出结果：**零上游调用**。

        留一条 ``call_count=0`` 的条目，使「这次为什么没花钱」可审计；
        不记 token（没调就没有用量），更不计费。

        ``reason`` 不在已知零调用来源里时降级为 ``unknown``：宁可标成「账没算清」
        也不替调用方编造一个来源。
        """
        source = reason if reason in ZERO_CALL_SOURCES else SOURCE_UNKNOWN
        return self._append(
            kind=kind, stage=stage, usage_source=source, model=model, call_count=0,
        )

    def record_operation(
        self,
        kind: str,
        stage: str,
        *,
        duration_ms: Optional[int] = None,
        calls: int = 1,
    ) -> Optional[UsageEntry]:
        """非 token 用量（harness 评测、MCP 调用）：只记次数与耗时，不计费。"""
        return self._append(
            kind=kind, stage=stage, usage_source=SOURCE_OPERATION,
            duration_ms=duration_ms, call_count=max(int(calls), 0),
        )

    def _append(self, **fields: Any) -> UsageEntry:
        entry = UsageEntry(**fields)
        self._entries.append(entry)
        return entry

    def totals(self) -> Dict[str, Any]:
        """紧凑汇总，供 chat_trace 落日志（明细不进日志，避免灌水）。

        每个 token 合计都配一个 ``*_entries`` 分母：只对**有值**的条目求和，
        未知条目单独计数。这样读到 ``total_tokens=12000`` 时能立刻知道它是
        几条记录贡献的、还有几条是未知的。

        ``unknown_entries`` 只数「调了上游却没拿到用量」的条目。缓存命中、幂等重放
        与本地规则同样没有 token 数，但它们是**已知的零**（``zero_call_entries``），
        混进来会让「有多少账没算清」这个指标虚高。
        """
        priced = [entry for entry in self._entries if entry.priced]

        def _sum(field: str) -> Optional[int]:
            values = [
                getattr(entry, field) for entry in priced
                if getattr(entry, field) is not None
            ]
            return sum(values) if values else None

        def _count(field: str) -> int:
            return sum(1 for entry in priced if getattr(entry, field) is not None)

        return {
            "entries": len(self._entries),
            "calls": sum(entry.call_count for entry in self._entries),
            "prompt_tokens": _sum("prompt_tokens"),
            "completion_tokens": _sum("completion_tokens"),
            "total_tokens": _sum("total_tokens"),
            "total_tokens_entries": _count("total_tokens"),
            "unknown_entries": sum(
                1 for entry in self._entries if entry.usage_source == SOURCE_UNKNOWN
            ),
            "zero_call_entries": sum(
                1 for entry in self._entries if entry.usage_source in ZERO_CALL_SOURCES
            ),
            "stages": sorted({entry.stage for entry in self._entries}),
        }


def record_zero_call(
    usage: Optional["UsageRecorder"],
    stage: str,
    source: str,
    *,
    model: Optional[str] = None,
    kind: str = KIND_LLM,
) -> None:
    """记一条**零上游调用**的条目（缓存命中 / 幂等重放 / 本地规则出结果）。

    ``usage`` 为 None（调用方没要账本）时静默跳过。各检索/生成路径共用，避免每处
    各写一遍「什么时候算没调上游」的判断。
    """
    if usage is not None:
        usage.record_skipped(stage, source, model=model, kind=kind)


def entries_to_rows(
    entries: Sequence[UsageEntry],
    *,
    prices: Optional[Mapping[str, ModelPrice]] = None,
    price_version: str = "",
    request_id: Optional[str] = None,
    subject: Optional[str] = None,
    session_id: Optional[str] = None,
    created_at: str = "",
    id_factory=None,
) -> List[Dict[str, Any]]:
    """把条目摊成待落库的行（纯函数，便于单测；写库在 usage_service）。

    成本在**写入时**算好并盖上 ``price_version``：日后调价不回溯改历史账。
    """
    prices = prices or {}
    rows: List[Dict[str, Any]] = []
    for entry in entries:
        price = prices.get(entry.model) if entry.model else None
        priced = entry.priced
        rows.append({
            "id": (id_factory or _default_row_id)(),
            "request_id": request_id,
            "subject": subject,
            "session_id": session_id,
            "kind": entry.kind,
            "stage": entry.stage,
            "model": entry.model,
            "prompt_tokens": entry.prompt_tokens,
            "completion_tokens": entry.completion_tokens,
            "total_tokens": entry.total_tokens,
            "usage_source": entry.usage_source,
            "call_count": entry.call_count,
            "duration_ms": entry.duration_ms,
            # 非计费 kind 一律不出价；计费 kind 未配价也不出价。
            "cost_micros": cost_micros(
                entry.prompt_tokens, entry.completion_tokens, price,
            ) if priced else None,
            "currency": price.currency if (priced and price is not None) else None,
            "price_version": price_version if (priced and price is not None) else None,
            "created_at": created_at,
        })
    return rows


def _default_row_id() -> str:
    import uuid

    return f"use_{uuid.uuid4().hex[:12]}"
