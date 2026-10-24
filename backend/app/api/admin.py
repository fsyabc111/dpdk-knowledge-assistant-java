"""Admin user-management endpoints (M6; M8 起树状层级).

准入角色为 admin（老师）/ leader（组长），且仅存在于 AUTH_MODE=account；
其他调用方一律 404，与 usage.py 的 admin 端点同口径。树隔离规则：
- leader 管理全部用户；admin 只能管理 created_by=自己 的纯 viewer 学生；
- API_TOKENS_JSON 中的静态应急 token 主体不在 users 表中，按 leader 语义放行；
- 任何人不能停用自己或改自己的角色（防最后一个管理员自锁）。
"""
from __future__ import annotations

from fastapi import APIRouter, Depends, HTTPException, Query, Response, status

from ..config import get_settings
from ..core.auth import Principal, parse_tokens, require_auth
from ..schemas.auth import (
    AssignStudentsRequest,
    AssignStudentsResponse,
    AuditLogInfo,
    AuditLogListResponse,
    CreateUsersRequest,
    CreateUsersResponse,
    DeleteUsersRequest,
    DeleteUsersResponse,
    PasswordResetApproveResponse,
    PasswordResetRequestInfo,
    PasswordResetRequestListResponse,
    SetPasswordRequest,
    UpdateUserRequest,
    UserInfo,
    UserListResponse,
    UserStatusRequest,
)
from ..services.user_service import MANAGER_ROLES, UserService

router = APIRouter(prefix="/api/admin", tags=["admin"])


def _require_manager(principal: Principal) -> None:
    # 与跨主体会话读取一致：对非管理角色不泄露该组端点的存在性。
    if get_settings().auth_mode != "account" or not (set(principal.roles) & MANAGER_ROLES):
        raise HTTPException(status_code=404, detail="not found")


def _is_leader_like(principal: Principal) -> bool:
    """组长或不受树约束的静态应急 token 主体。"""
    if "leader" in principal.roles:
        return True
    static_subjects = {p.subject for p in parse_tokens(get_settings()).values()}
    return principal.subject in static_subjects


def _scope_creator(principal: Principal) -> str | None:
    """老师的作用域=自己；组长/静态 token 返回 None 表示不限。"""
    return None if _is_leader_like(principal) else principal.subject


async def _get_manageable_target(principal: Principal, user_id: str) -> dict:
    """取目标用户并强制树隔离；不可见/不可管一律 404。"""
    target = await UserService().get_user(user_id)
    if target is None:
        raise HTTPException(status_code=404, detail="not found")
    if not _is_leader_like(principal) and not UserService.can_manage(principal, target):
        raise HTTPException(status_code=404, detail="not found")
    return target


@router.get("/users", response_model=UserListResponse)
async def list_users(principal: Principal = Depends(require_auth)) -> UserListResponse:
    _require_manager(principal)
    service = UserService()
    scope = _scope_creator(principal)
    if scope is None:
        users = await service.list_users()
    else:
        # 老师视角：自己 + 自己创建的学生。
        users = await service.list_users(created_by=scope)
        self_user = await service.get_user(principal.subject)
        if self_user is not None:
            users = [self_user, *users]
    return UserListResponse(users=[UserInfo(**user) for user in users])


@router.post("/users", response_model=CreateUsersResponse)
async def create_users(
    req: CreateUsersRequest, principal: Principal = Depends(require_auth)
) -> CreateUsersResponse:
    _require_manager(principal)
    roles = req.roles or ["viewer"]
    if _is_leader_like(principal):
        # 防提权：leader 只能由 CLI 引导创建，API 不开放。
        if "leader" in roles:
            raise HTTPException(
                status_code=status.HTTP_400_BAD_REQUEST,
                detail="role 'leader' can only be granted via the CLI bootstrap",
            )
    elif set(roles) - {"viewer"}:
        # 老师只能创建学生。
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail="teachers can only create viewer accounts",
        )
    try:
        result = await UserService().create_users(
            req.ids, roles=roles, daily_quota=req.daily_quota, created_by=principal.subject
        )
    except ValueError as exc:
        raise HTTPException(status_code=status.HTTP_400_BAD_REQUEST, detail=str(exc)) from exc
    return CreateUsersResponse(**result)


@router.post("/users/{user_id}/reset-password", status_code=status.HTTP_204_NO_CONTENT)
async def reset_password(user_id: str, principal: Principal = Depends(require_auth)) -> Response:
    _require_manager(principal)
    await _get_manageable_target(principal, user_id)
    if not await UserService().reset_password(user_id):
        raise HTTPException(status_code=404, detail="not found")
    return Response(status_code=status.HTTP_204_NO_CONTENT)


@router.post("/users/{user_id}/set-password", status_code=status.HTTP_204_NO_CONTENT)
async def set_password(
    user_id: str, req: SetPasswordRequest, principal: Principal = Depends(require_auth)
) -> Response:
    """管理员直接设密（区别于 reset-password 恢复初始密码）；用户下次登录提示改密。"""
    _require_manager(principal)
    await _get_manageable_target(principal, user_id)
    try:
        ok = await UserService().set_password(user_id, req.new_password)
    except ValueError as exc:
        raise HTTPException(status_code=status.HTTP_400_BAD_REQUEST, detail=str(exc)) from exc
    if not ok:
        raise HTTPException(status_code=404, detail="not found")
    return Response(status_code=status.HTTP_204_NO_CONTENT)


@router.get("/password-reset-requests", response_model=PasswordResetRequestListResponse)
async def list_password_reset_requests(
    request_status: str = Query(default="pending", alias="status"),
    principal: Principal = Depends(require_auth),
) -> PasswordResetRequestListResponse:
    _require_manager(principal)
    requests = await UserService().list_reset_requests(
        request_status, scope_creator=_scope_creator(principal)
    )
    return PasswordResetRequestListResponse(
        requests=[PasswordResetRequestInfo(**item) for item in requests]
    )


@router.post(
    "/password-reset-requests/{request_id}/approve",
    response_model=PasswordResetApproveResponse,
)
async def approve_password_reset_request(
    request_id: str, principal: Principal = Depends(require_auth)
) -> PasswordResetApproveResponse:
    """审批通过后签发一次性重置令牌；明文仅此一次返回，由管理员线下转交。"""
    _require_manager(principal)
    result = await UserService().approve_reset_request(
        request_id, principal.subject, scope_creator=_scope_creator(principal)
    )
    if result is None:
        raise HTTPException(status_code=404, detail="not found")
    token, expires_at = result
    return PasswordResetApproveResponse(reset_token=token, expires_at=expires_at)


@router.post("/password-reset-requests/{request_id}/reject", status_code=status.HTTP_204_NO_CONTENT)
async def reject_password_reset_request(
    request_id: str, principal: Principal = Depends(require_auth)
) -> Response:
    _require_manager(principal)
    if not await UserService().reject_reset_request(
        request_id, principal.subject, scope_creator=_scope_creator(principal)
    ):
        raise HTTPException(status_code=404, detail="not found")
    return Response(status_code=status.HTTP_204_NO_CONTENT)


@router.post("/users/{user_id}/status", status_code=status.HTTP_204_NO_CONTENT)
async def set_user_status(
    user_id: str, req: UserStatusRequest, principal: Principal = Depends(require_auth)
) -> Response:
    _require_manager(principal)
    if user_id == principal.subject and not req.enabled:
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST, detail="cannot disable your own account"
        )
    await _get_manageable_target(principal, user_id)
    if not await UserService().set_enabled(user_id, req.enabled):
        raise HTTPException(status_code=404, detail="not found")
    return Response(status_code=status.HTTP_204_NO_CONTENT)


@router.patch("/users/{user_id}", response_model=UserInfo)
async def update_user(
    user_id: str, req: UpdateUserRequest, principal: Principal = Depends(require_auth)
) -> UserInfo:
    _require_manager(principal)
    leader_like = _is_leader_like(principal)
    if not leader_like and (req.roles is not None or req.created_by is not None):
        # 老师只能调配额，不能改角色与归属。
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail="only leaders can change roles or ownership",
        )
    if req.roles is not None and "leader" in req.roles:
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail="role 'leader' can only be granted via the CLI bootstrap",
        )
    if user_id == principal.subject and req.roles is not None:
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST, detail="cannot change your own roles"
        )
    await _get_manageable_target(principal, user_id)
    try:
        user = await UserService().update_user(
            user_id, roles=req.roles, daily_quota=req.daily_quota, created_by=req.created_by
        )
    except ValueError as exc:
        raise HTTPException(status_code=status.HTTP_400_BAD_REQUEST, detail=str(exc)) from exc
    if user is None:
        raise HTTPException(status_code=404, detail="not found")
    return UserInfo(**user)


@router.post("/users/delete", response_model=DeleteUsersResponse)
async def delete_users(
    req: DeleteUsersRequest, principal: Principal = Depends(require_auth)
) -> DeleteUsersResponse:
    """批量删除下层账号；每次操作都必须附操作者自己的密码（M9）。

    自己/不存在/不可管的 id 进 skipped 不打断批量；静态应急 token 主体不在
    users 表中，无法通过密码校验，天然被挡在门外。成功删除落 admin_audit_log。
    """
    _require_manager(principal)
    try:
        result = await UserService().delete_users(principal, req.ids, req.password)
    except PermissionError:
        raise HTTPException(
            status_code=status.HTTP_403_FORBIDDEN, detail="password verification failed"
        ) from None
    return DeleteUsersResponse(**result)


@router.post("/users/assign", response_model=AssignStudentsResponse)
async def assign_students(
    req: AssignStudentsRequest, principal: Principal = Depends(require_auth)
) -> AssignStudentsResponse:
    """组长把未分配学生批量指派给某位老师；老师不能改归属（M9）。"""
    _require_manager(principal)
    if not _is_leader_like(principal):
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail="only leaders can change ownership",
        )
    try:
        result = await UserService().assign_students(req.ids, req.created_by)
    except ValueError as exc:
        raise HTTPException(status_code=status.HTTP_400_BAD_REQUEST, detail=str(exc)) from exc
    return AssignStudentsResponse(**result)


@router.get("/audit-log", response_model=AuditLogListResponse)
async def list_audit_log(
    principal: Principal = Depends(require_auth),
) -> AuditLogListResponse:
    """删除操作留痕：组长看全部，老师只看自己的操作。"""
    _require_manager(principal)
    scope = None if _is_leader_like(principal) else principal.subject
    entries = await UserService().list_audit_log(scope_actor=scope)
    return AuditLogListResponse(entries=[AuditLogInfo(**item) for item in entries])
