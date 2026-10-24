from typing import Dict, List, Optional

from pydantic import BaseModel


class UsageDay(BaseModel):
    day: str
    chat_count: int


class MyUsageResponse(BaseModel):
    subject: str
    roles: List[str]
    # 0 表示不限额，与 Principal.daily_quota 口径一致。
    daily_quota: int
    today: UsageDay
    # 最近 7 天（含今天），按日期升序，无记录的日子补 0。
    recent_days: List[UsageDay]


class SubjectUsage(BaseModel):
    subject: str
    roles: List[str]
    daily_quota: int
    today_count: int
    total_count: int
    last_active_day: Optional[str] = None


class SubjectUsageResponse(BaseModel):
    subjects: List[SubjectUsage]


class UsageBucket(BaseModel):
    """一组账本行的口径。token 合计与「分母」同出，未知条数单列。

    token 为 ``null`` 表示**未知**（没有一条有值），不是 0：本项目从不用 0 顶替
    「上游没告诉我们」。``known_entries`` 是贡献了上面合计的条数——没有它，
    合计就是一个不可信的孤数。
    """

    key: Optional[str] = None
    entries: int
    calls: int
    prompt_tokens: Optional[int] = None
    completion_tokens: Optional[int] = None
    total_tokens: Optional[int] = None
    known_entries: int = 0
    unknown_entries: int = 0
    duration_ms: Optional[int] = None
    # 成本按币种分列（整数微单位）：把人民币和美元加在一起是最糟的「看起来对了」。
    cost_micros_by_currency: Dict[str, int] = {}


class UsageOperations(BaseModel):
    """非 token 用量（harness/MCP）：只报次数与耗时，显式不计费。"""

    entries: int
    calls: int
    duration_ms: Optional[int] = None
    priced: bool = False


class UsageCostResponse(BaseModel):
    """全链路用量与成本视图（P1）。只读，不触发任何上游调用。"""

    # 实际生效的过滤条件回显：读到一份账目时必须知道它是按什么范围算出来的。
    days: int
    since: str
    subject: Optional[str] = None
    # 树隔离时的实际可见主体集合；不受树约束时为 None（表示「全部」）。
    subjects: Optional[List[str]] = None
    stage: Optional[str] = None
    # 按 request_id 归因时的回显（live 评测逐题看花费）。
    request_id: Optional[str] = None

    totals: UsageBucket
    operations: UsageOperations
    by_stage: List[UsageBucket]
    by_model: List[UsageBucket]
    by_day: List[UsageBucket]
    # 有用量却没配价格的模型：只报用量，不出价，更不编费率。
    unpriced_models: List[str]
    unknown_entries: int
    # 币种不唯一时必须显式告诉调用方：跨币种不可加总。
    cost_currency_mixed: bool
    truncated: bool
    row_limit: int
