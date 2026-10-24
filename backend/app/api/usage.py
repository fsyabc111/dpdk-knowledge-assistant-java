"""Usage dashboards (M5): per-subject quota view and admin-wide counters.

Reads the same usage_counters table maintained by rate_limit.py, so the
numbers shown here match exactly what the daily quota check enforces.
"""
from __future__ import annotations

import asyncio
from datetime import datetime, timedelta, timezone
from typing import Dict, List, Optional, Tuple

from fastapi import APIRouter, Depends, HTTPException, Query

from ..config import Settings, get_settings
from ..core.auth import Principal, parse_tokens, require_auth
from ..db import get_conn, get_database_provider
from ..schemas.usage import (
    MyUsageResponse,
    SubjectUsage,
    SubjectUsageResponse,
    UsageCostResponse,
    UsageDay,
)
from ..services.user_service import UserService

router = APIRouter(prefix="/api/usage", tags=["usage"])

RECENT_DAYS = 7


def _fetch_counters_sync(subject: Optional[str] = None) -> List[Tuple[str, str, int]]:
    """Sync DB read; always called via asyncio.to_thread off the event loop.

    只统计 kind='chat' 的计数：grading 配额（usage_counters.kind，0004 起）
    与聊天用量分开，不进本面板。
    """
    ph = get_database_provider().placeholder
    with get_conn() as conn:
        if subject is None:
            rows = conn.execute(
                "SELECT subject, day, chat_count FROM usage_counters WHERE kind = 'chat'"
            ).fetchall()
        else:
            rows = conn.execute(
                f"SELECT subject, day, chat_count FROM usage_counters WHERE subject = {ph} AND kind = 'chat'",
                (subject,),
            ).fetchall()
    return [(str(r["subject"]), str(r["day"]), int(r["chat_count"])) for r in rows]


@router.get("/me", response_model=MyUsageResponse)
async def my_usage(principal: Principal = Depends(require_auth)) -> MyUsageResponse:
    rows = await asyncio.to_thread(_fetch_counters_sync, principal.subject)
    counts = {day: count for _, day, count in rows}
    today = datetime.now(timezone.utc).date()
    days = [(today - timedelta(days=offset)).isoformat() for offset in range(RECENT_DAYS - 1, -1, -1)]
    return MyUsageResponse(
        subject=principal.subject,
        roles=principal.roles,
        daily_quota=principal.daily_quota,
        today=UsageDay(day=today.isoformat(), chat_count=counts.get(today.isoformat(), 0)),
        recent_days=[UsageDay(day=day, chat_count=counts.get(day, 0)) for day in days],
    )


def _configured_subjects(settings: Settings) -> Dict[str, Dict]:
    """Merge token config into one entry per subject.

    Roles are the union across tokens; daily_quota is the max, except that 0
    (unlimited) wins as soon as any token grants it.
    """
    specs: Dict[str, Dict] = {}
    for principal in parse_tokens(settings).values():
        spec = specs.setdefault(principal.subject, {"roles": set(), "quotas": []})
        spec["roles"] |= set(principal.roles)
        spec["quotas"].append(principal.daily_quota)
    for spec in specs.values():
        spec["daily_quota"] = 0 if 0 in spec["quotas"] else max(spec["quotas"])
        del spec["quotas"]
    return specs


def _account_configured_subjects_sync(settings: Settings) -> Dict[str, Dict]:
    """Account mode: the users table is the source of configured subjects.

    daily_quota 为 NULL 时取 LLM_DAILY_QUOTA_DEFAULT；已停用账号仍列出，
    便于管理员对照其历史用量。
    """
    specs: Dict[str, Dict] = {}
    for user in UserService()._list_users_sync():
        specs[user["id"]] = {
            "roles": set(user["roles"]),
            "daily_quota": settings.llm_daily_quota_default if user["daily_quota"] is None else user["daily_quota"],
        }
    return specs


@router.get("/subjects", response_model=SubjectUsageResponse)
async def subject_usage(principal: Principal = Depends(require_auth)) -> SubjectUsageResponse:
    # 与跨主体会话读取一致：对非管理角色不泄露该端点的存在性。
    if not ({"admin", "leader"} & set(principal.roles)):
        raise HTTPException(status_code=404, detail="not found")
    settings = get_settings()
    rows = await asyncio.to_thread(_fetch_counters_sync)

    today = datetime.now(timezone.utc).date().isoformat()
    stats: Dict[str, Dict] = {}
    for subject, day, count in rows:
        entry = stats.setdefault(subject, {"total": 0, "today": 0, "last": None})
        entry["total"] += count
        if day == today:
            entry["today"] += count
        if entry["last"] is None or day > entry["last"]:
            entry["last"] = day

    if settings.auth_mode == "local":
        # local 模式只有单一隐式主体，历史遗留 subject 不并入。
        specs: Dict[str, Dict] = {"local": {"roles": {"admin"}, "daily_quota": 0}}
        stats = {"local": stats.get("local", {"total": 0, "today": 0, "last": None})}
    elif settings.auth_mode == "account":
        specs = await asyncio.to_thread(_account_configured_subjects_sync, settings)
        # usage_counters 里的历史遗留 subject（不在 users 表中）也列出来，
        # roles 未知置空，配额取全局默认值。
        for subject in stats:
            specs.setdefault(subject, {"roles": set(), "daily_quota": settings.llm_daily_quota_default})
    else:
        specs = _configured_subjects(settings)
        # usage_counters 里的历史遗留 subject（不在 token 配置中）也列出来，
        # roles 未知置空，配额取全局默认值。
        for subject in stats:
            specs.setdefault(subject, {"roles": set(), "daily_quota": settings.llm_daily_quota_default})

    subjects = [
        SubjectUsage(
            subject=subject,
            roles=sorted(spec["roles"]),
            daily_quota=spec["daily_quota"],
            today_count=stats.get(subject, {}).get("today", 0),
            total_count=stats.get(subject, {}).get("total", 0),
            last_active_day=stats.get(subject, {}).get("last"),
        )
        for subject, spec in specs.items()
    ]
    if settings.auth_mode == "account" and not _is_leader_like(principal, settings):
        # 树隔离：老师（admin）只看自己与自己创建的学生。
        own = await UserService().list_users(created_by=principal.subject)
        visible = {principal.subject, *(user["id"] for user in own)}
        subjects = [item for item in subjects if item.subject in visible]
    subjects.sort(key=lambda item: (-item.today_count, item.subject))
    return SubjectUsageResponse(subjects=subjects)


def _fetch_cost_sync(
    since: str,
    subject: Optional[str],
    subjects: Optional[List[str]],
    stage: Optional[str],
    request_id: Optional[str],
) -> Dict:
    from ..services.usage_service import UsageService

    return UsageService().query(
        since=since, subject=subject, subjects=subjects, stage=stage, request_id=request_id,
    )


@router.get("/cost", response_model=UsageCostResponse)
async def cost_usage(
    days: int = Query(7, ge=1, le=365),
    subject: Optional[str] = Query(None, max_length=128),
    stage: Optional[str] = Query(None, max_length=64),
    request_id: Optional[str] = Query(None, max_length=128),
    principal: Principal = Depends(require_auth),
) -> UsageCostResponse:
    """全链路用量与成本（P1）。只读账本，不触发任何上游调用。

    与 ``/subjects`` 同口径的门禁：非 admin/leader 返回 **404**（不泄露端点存在性），
    account 模式下老师只看自己与自己创建的学生。

    ``days`` 是**滚动窗口**（最近 N×24 小时），不是自然日：账本按 UTC 文本比较，
    这样跨时区也不会把同一天切成两半。窗口只影响读，账本行本身永不删除。

    ``request_id`` 精确匹配单次请求的账目（live 评测逐题归因花费）。它只做收窄，
    仍受上面的树隔离约束——过滤别人的 request_id 只会得到空账。
    """
    if not ({"admin", "leader"} & set(principal.roles)):
        raise HTTPException(status_code=404, detail="not found")

    settings = get_settings()
    visible: Optional[List[str]] = None
    if settings.auth_mode == "account" and not _is_leader_like(principal, settings):
        own = await UserService().list_users(created_by=principal.subject)
        allowed = {principal.subject, *(user["id"] for user in own)}
        if subject:
            if subject not in allowed:
                # 与自己无树关系的主体：不透露存在性，也不返回空账暗示「此人没花钱」。
                raise HTTPException(status_code=404, detail="not found")
        else:
            visible = sorted(allowed)

    since = (datetime.now(timezone.utc) - timedelta(days=days)).isoformat()
    summary = await asyncio.to_thread(
        _fetch_cost_sync, since, subject or None, visible, stage or None, request_id or None,
    )
    return UsageCostResponse(
        days=days,
        since=since,
        subject=subject or None,
        subjects=visible,
        stage=stage or None,
        request_id=request_id or None,
        **summary,
    )


def _is_leader_like(principal: Principal, settings: Settings) -> bool:
    """组长或不受树约束的静态应急 token 主体（与 admin.py 口径一致）。"""
    if "leader" in principal.roles:
        return True
    static_subjects = {p.subject for p in parse_tokens(settings).values()}
    return principal.subject in static_subjects
