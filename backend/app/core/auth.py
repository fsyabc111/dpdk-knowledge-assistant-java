"""Token authentication for the chat API.

Three modes (Settings.auth_mode):
- "local": single-machine development. Every request is treated as one
  implicit admin principal; no credential is required.
- "token": requests must carry ``Authorization: Bearer <token>`` matching an
  entry in Settings.api_tokens_json. Tokens are compared with
  secrets.compare_digest to avoid leaking which prefix matched.
- "account": static api_tokens_json entries are still honoured first as an
  emergency fallback; any other bearer token is resolved against the
  api_tokens/users tables (login-issued tokens) via UserService.
"""
from __future__ import annotations

import json
import logging
import secrets
from dataclasses import dataclass
from typing import Dict, List

from fastapi import HTTPException, Request, status

from ..config import Settings, get_settings

logger = logging.getLogger("assistant")


@dataclass(frozen=True)
class Principal:
    subject: str
    roles: List[str]
    # 0 means the subject has no daily LLM quota.
    daily_quota: int
    # 信息标志：账号仍在使用初始/管理员设置的密码（内网部署不强制改密）。
    must_change_password: bool = False


def parse_tokens(settings: Settings) -> Dict[str, Principal]:
    raw = json.loads(settings.api_tokens_json or "{}")
    tokens: Dict[str, Principal] = {}
    for token, spec in raw.items():
        tokens[token] = Principal(
            subject=spec["subject"],
            roles=list(spec["roles"]),
            daily_quota=int(spec.get("daily_quota", settings.llm_daily_quota_default)),
        )
    return tokens


def _unauthorized(detail: str) -> HTTPException:
    return HTTPException(
        status_code=status.HTTP_401_UNAUTHORIZED,
        detail=detail,
        headers={"WWW-Authenticate": "Bearer"},
    )


def extract_bearer_token(request: Request) -> str:
    """Return the bearer token from the Authorization header, or ''."""
    authorization = request.headers.get("Authorization", "")
    scheme, _, token = authorization.partition(" ")
    if scheme.lower() != "bearer":
        return ""
    return token.strip()


async def require_auth(request: Request) -> Principal:
    """FastAPI dependency resolving the caller to a Principal."""
    settings = get_settings()
    if settings.auth_mode == "local":
        return Principal(subject="local", roles=["admin"], daily_quota=0)

    token = extract_bearer_token(request)
    if not token:
        logger.warning(json.dumps({"event": "auth_failed", "reason": "missing_or_malformed_token"}))
        raise _unauthorized("missing or malformed bearer token")
    for configured, principal in parse_tokens(settings).items():
        if secrets.compare_digest(token, configured):
            return principal
    if settings.auth_mode == "account":
        # 静态 token 未命中时查 api_tokens 表（登录签发的 token）。
        # 延迟导入避免与 services.user_service 形成循环依赖。
        from ..services.user_service import UserService

        principal = await UserService().resolve_token(token)
        if principal is not None:
            # must_change_password 仅作信息标志透出（登录响应 / 管理面板"待改密"），
            # 内网部署不做强制改密拦截。
            return principal
    logger.warning(json.dumps({"event": "auth_failed", "reason": "invalid_token"}))
    raise _unauthorized("invalid token")
