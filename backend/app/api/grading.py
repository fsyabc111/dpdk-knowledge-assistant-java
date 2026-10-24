"""教师成绩视图（M3）：grading_submissions 的只读查询端点。

仅 AUTH_MODE=account 下对 operator/admin/leader 开放；其他认证模式与无权限
角色一律 404（隐藏存在性），与 admin.py / usage.py 的守卫口径一致。
M8 树隔离：老师（admin）只能看到自己创建的学生提交；组长/operator/静态
token 不受限。
"""

from typing import List, Optional

from fastapi import APIRouter, Depends, HTTPException, Query

from ..config import get_settings
from ..core.auth import Principal, parse_tokens, require_auth
from ..schemas.grading import (
    GradingProtocolSummary,
    GradingSubmission,
    GradingSubmissionListResponse,
    GradingSummaryResponse,
    GradingUserSummary,
)
from ..services.grading_service import GradingService
from ..services.user_service import UserService

router = APIRouter(prefix="/api/grading", tags=["grading"])

# 教师角色白名单：与 user_service 的角色白名单对应，viewer 是学生视角，不看成绩视图。
GRADING_VIEWER_ROLES = {"operator", "admin", "leader"}

# 单次查询的 limit 上限，防止一次拉全表。
MAX_LIMIT = 500


def _require_grading_viewer(principal: Principal) -> None:
    # 严格风格守卫：非 account 模式或无权限角色都不泄露该组端点的存在性。
    if get_settings().auth_mode != "account" or not (set(principal.roles) & GRADING_VIEWER_ROLES):
        raise HTTPException(status_code=404, detail="not found")


async def _scope_user_ids(principal: Principal) -> Optional[List[str]]:
    """树隔离范围：老师 → 自己学生的 id 集；其余角色/静态 token → None（不限）。"""
    if "leader" in principal.roles or "operator" in principal.roles:
        return None
    static_subjects = {p.subject for p in parse_tokens(get_settings()).values()}
    if principal.subject in static_subjects:
        return None
    students = await UserService().list_users(created_by=principal.subject)
    return [user["id"] for user in students]


@router.get("/submissions", response_model=GradingSubmissionListResponse)
async def list_grading_submissions(
    user_id: Optional[str] = Query(default=None),
    protocol: Optional[str] = Query(default=None),
    limit: int = Query(default=200, ge=1),
    offset: int = Query(default=0, ge=0),
    principal: Principal = Depends(require_auth),
) -> GradingSubmissionListResponse:
    _require_grading_viewer(principal)
    rows = await GradingService().list_submissions(
        user_id=user_id, protocol=protocol, limit=min(limit, MAX_LIMIT), offset=offset,
        user_ids=await _scope_user_ids(principal),
    )
    return GradingSubmissionListResponse(submissions=[GradingSubmission(**row) for row in rows])


@router.get("/summary", response_model=GradingSummaryResponse)
async def grading_summary(principal: Principal = Depends(require_auth)) -> GradingSummaryResponse:
    _require_grading_viewer(principal)
    service = GradingService()
    scope = await _scope_user_ids(principal)
    by_user = await service.aggregate_by_user(user_ids=scope)
    by_protocol = await service.aggregate_by_protocol(user_ids=scope)
    return GradingSummaryResponse(
        by_user=[GradingUserSummary(**row) for row in by_user],
        by_protocol=[GradingProtocolSummary(**row) for row in by_protocol],
    )
