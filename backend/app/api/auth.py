"""Account login endpoints (M6): login / logout / change-password.

Only available when AUTH_MODE=account; other modes hide them behind 404 so
their existence is not advertised where accounts do not exist.
"""
from __future__ import annotations

import json
import logging
import time
from collections import defaultdict, deque
from threading import Lock
from typing import Deque, Dict

from fastapi import APIRouter, Depends, HTTPException, Request, Response, status

from ..config import get_settings
from ..core.auth import Principal, extract_bearer_token, require_auth
from ..schemas.auth import (
    ChangePasswordRequest,
    LoginRequest,
    LoginResponse,
    PasswordResetConfirm,
    PasswordResetRequestCreate,
)
from ..services.user_service import USER_ID_PATTERN, UserService

logger = logging.getLogger("assistant")

router = APIRouter(prefix="/api/auth", tags=["auth"])

# 登录限流：每 IP 每分钟 10 次，防学号即初始密码的弱凭证被爆破。
# 滑动窗口思路与 core/rate_limit.RateLimiter 一致，但独立成桶、不消耗聊天配额。
LOGIN_RATE_LIMIT_PER_MINUTE = 10

# 忘记密码两个匿名接口的限流：每 IP 每分钟 5 次，与登录限流独立成桶。
RESET_REQUEST_RATE_LIMIT_PER_MINUTE = 5

# 账号锁定：同学号 15 分钟内失败 5 次锁 15 分钟。对存在/不存在的学号
# 一视同仁地计数与应答，不泄露账号是否存在；进程内存实现，重启清零。
LOGIN_MAX_FAILURES = 5
LOGIN_LOCKOUT_MINUTES = 15


class _LoginRateLimiter:
    def __init__(self, limit: int = LOGIN_RATE_LIMIT_PER_MINUTE, kind: str = "login") -> None:
        self._limit = limit
        self._kind = kind
        self._buckets: Dict[str, Deque[float]] = defaultdict(deque)
        self._lock = Lock()

    def check(self, key: str) -> None:
        now = time.monotonic()
        with self._lock:
            bucket = self._buckets[key]
            while bucket and now - bucket[0] >= 60.0:
                bucket.popleft()
            if len(bucket) >= self._limit:
                retry_after = max(1, int(60.0 - (now - bucket[0])) + 1)
                logger.warning(json.dumps({"event": "rate_limit_hit", "kind": self._kind, "key": key}))
                raise HTTPException(
                    status_code=status.HTTP_429_TOO_MANY_REQUESTS,
                    detail=f"{self._kind} rate limit exceeded ({self._limit}/minute)",
                    headers={"Retry-After": str(retry_after)},
                )
            bucket.append(now)


class _LoginLockout:
    """按学号的进程内登录锁定：失败计数滑窗 + 锁定期。

    check 在验证密码之前调用，因此锁定时无论账号是否存在都返回相同的
    429 + Retry-After；登录成功清零该学号的计数。
    """

    def __init__(self) -> None:
        self._failures: Dict[str, Deque[float]] = defaultdict(deque)
        self._locked_until: Dict[str, float] = {}
        self._lock = Lock()

    def check(self, username: str) -> None:
        now = time.monotonic()
        with self._lock:
            until = self._locked_until.get(username)
            if until is None:
                return
            if now < until:
                retry_after = max(1, int(until - now) + 1)
                logger.warning(json.dumps({"event": "login_locked", "user_id": username}))
                raise HTTPException(
                    status_code=status.HTTP_429_TOO_MANY_REQUESTS,
                    detail="too many failed login attempts, try again later",
                    headers={"Retry-After": str(retry_after)},
                )
            del self._locked_until[username]

    def record_failure(self, username: str) -> None:
        now = time.monotonic()
        with self._lock:
            bucket = self._failures[username]
            while bucket and now - bucket[0] >= LOGIN_LOCKOUT_MINUTES * 60.0:
                bucket.popleft()
            bucket.append(now)
            if len(bucket) >= LOGIN_MAX_FAILURES:
                self._locked_until[username] = now + LOGIN_LOCKOUT_MINUTES * 60.0
                bucket.clear()

    def record_success(self, username: str) -> None:
        with self._lock:
            self._failures.pop(username, None)
            self._locked_until.pop(username, None)


_login_rate_limiter = _LoginRateLimiter()
_reset_rate_limiter = _LoginRateLimiter(
    limit=RESET_REQUEST_RATE_LIMIT_PER_MINUTE, kind="password_reset"
)
_login_lockout = _LoginLockout()


def _require_account_mode() -> None:
    # 与 usage.py 的 admin 隐藏惯例一致：非 account 模式不暴露该组端点。
    if get_settings().auth_mode != "account":
        raise HTTPException(status_code=404, detail="not found")


@router.post("/login", response_model=LoginResponse)
async def login(req: LoginRequest, request: Request) -> LoginResponse:
    _require_account_mode()
    client_ip = request.client.host if request.client else "unknown"
    _login_rate_limiter.check(f"login:{client_ip}")
    # 锁定检查在密码验证之前：锁定期内存在/不存在的学号应答完全一致。
    _login_lockout.check(req.username)
    users = UserService()
    if not await users.verify_credentials(req.username, req.password):
        # 用户不存在 / 密码错误 / 已停用统一 401，不区分原因。
        _login_lockout.record_failure(req.username)
        logger.warning(json.dumps({"event": "auth_failed", "reason": "login_failed"}))
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="invalid username or password",
        )
    _login_lockout.record_success(req.username)
    token, expires_at = await users.issue_token(req.username)
    user = await users.get_user(req.username)
    logger.info(json.dumps({"event": "login_success", "user_id": req.username}))
    return LoginResponse(
        token=token,
        subject=req.username,
        roles=user["roles"],
        expires_at=expires_at,
        must_change_password=user["must_change_password"],
    )


@router.post("/logout", status_code=status.HTTP_204_NO_CONTENT)
async def logout(request: Request, principal: Principal = Depends(require_auth)) -> Response:
    _require_account_mode()
    token = extract_bearer_token(request)
    if token:
        # 静态兜底 token 不在 api_tokens 表中，吊销对其为空操作。
        await UserService().revoke_token(token)
    return Response(status_code=status.HTTP_204_NO_CONTENT)


@router.post("/change-password", status_code=status.HTTP_204_NO_CONTENT)
async def change_password(
    req: ChangePasswordRequest,
    request: Request,
    principal: Principal = Depends(require_auth),
) -> Response:
    _require_account_mode()
    try:
        ok = await UserService().change_password(
            principal.subject, req.old_password, req.new_password,
            keep_token=extract_bearer_token(request) or None,
        )
    except ValueError as exc:
        raise HTTPException(status_code=status.HTTP_400_BAD_REQUEST, detail=str(exc)) from exc
    if not ok:
        # 旧密码错误（或静态兜底 token 的 subject 不是账号）。
        raise HTTPException(status_code=status.HTTP_400_BAD_REQUEST, detail="old password is incorrect")
    return Response(status_code=status.HTTP_204_NO_CONTENT)


@router.post("/password-reset-requests", status_code=status.HTTP_202_ACCEPTED)
async def create_password_reset_request(
    req: PasswordResetRequestCreate, request: Request
) -> Response:
    """匿名提交忘记密码申请；无论学号是否存在都统一 202（防枚举）。"""
    _require_account_mode()
    client_ip = request.client.host if request.client else "unknown"
    _reset_rate_limiter.check(f"reset-request:{client_ip}")
    if not USER_ID_PATTERN.match(req.username):
        # 只校验格式，不泄露账号是否存在。
        raise HTTPException(status_code=status.HTTP_400_BAD_REQUEST, detail="invalid username format")
    await UserService().create_reset_request(req.username)
    return Response(status_code=status.HTTP_202_ACCEPTED)


@router.post("/password-reset", status_code=status.HTTP_204_NO_CONTENT)
async def confirm_password_reset(req: PasswordResetConfirm, request: Request) -> Response:
    """凭一次性重置令牌自助改密；失败统一 400，不区分原因。"""
    _require_account_mode()
    client_ip = request.client.host if request.client else "unknown"
    _reset_rate_limiter.check(f"reset-confirm:{client_ip}")
    if not await UserService().consume_reset_token(req.token, req.new_password):
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail="invalid or expired reset token",
        )
    return Response(status_code=status.HTTP_204_NO_CONTENT)
