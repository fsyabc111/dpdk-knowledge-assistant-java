"""Account-based user management and login-token issuance (M6).

Mirrors SessionService's structure: every public method is an async wrapper
that offloads blocking DB work via asyncio.to_thread, and all SQL uses the
provider placeholder so the same code runs on PostgreSQL and SQLite.

Security notes:
- passwords are bcrypt-hashed; verify_credentials always runs one bcrypt
  comparison (against a dummy hash when the user is missing/disabled) so the
  response time does not reveal whether an account exists;
- login tokens are secrets.token_urlsafe(32) and only their sha256 hash is
  persisted (high-entropy random strings need no slow hash);
- resolve_token keeps a 60s in-process cache; every mutation that can
  invalidate a token (revoke / password change / disable / role change)
  clears it so revocation takes effect immediately.
"""
from __future__ import annotations

import asyncio
import hashlib
import json
import logging
import re
import secrets
import threading
import time
import uuid
from datetime import datetime, timedelta, timezone
from typing import Any, Dict, List, Optional, Tuple

import bcrypt

from ..config import get_settings
from ..core.auth import Principal
from ..db import get_conn, get_database_provider
from .session_service import now_iso


def lock_enabled_account(conn, user_id: str) -> bool:
    """在业务写入事务内锁账号行；停号与成绩/新任务按提交顺序裁决。"""
    settings = get_settings()
    if settings.auth_mode != "account":
        return True
    ph = get_database_provider().placeholder
    row = conn.execute(
        f"UPDATE users SET enabled=enabled WHERE id={ph} AND enabled={ph} RETURNING id",
        (user_id, True),
    ).fetchone()
    if row:
        return True
    existing = conn.execute(f"SELECT id FROM users WHERE id={ph}", (user_id,)).fetchone()
    if existing:
        return False
    # 静态应急 token 可以没有 users 行，但不能替已停用的同名账号绕过检查。
    from ..core.auth import parse_tokens
    return user_id in {p.subject for p in parse_tokens(settings).values()}

logger = logging.getLogger("assistant")

# 学号格式：U + 4 位年份 + 7 位序号。
USER_ID_PATTERN = re.compile(r"^U\d{11}$")
ALLOWED_ROLES = {"viewer", "operator", "admin", "leader"}
# 可进入用户管理面板的角色：admin=老师（只管自己创建的学生），leader=组长（管全部）。
MANAGER_ROLES = {"admin", "leader"}

# 忘记密码一次性重置令牌的有效期（分钟），审批通过时签发。
RESET_TOKEN_TTL_MINUTES = 30

# resolve_token 的进程内缓存：token_hash -> (写入时刻 monotonic, Principal)。
_TOKEN_CACHE_TTL_SECONDS = 60.0
_token_cache: Dict[str, Tuple[float, Principal]] = {}
_token_cache_lock = threading.Lock()

# 用户不存在 / 已停用时用于保持响应时间一致的 dummy 哈希（按需算一次，
# cost factor 跟随 BCRYPT_ROUNDS，测试环境降到 4 避免拖慢用例）。
_dummy_hash_cache: Optional[bytes] = None


def _dummy_hash() -> bytes:
    global _dummy_hash_cache
    if _dummy_hash_cache is None:
        _dummy_hash_cache = bcrypt.hashpw(
            b"kimi-assistant-dummy", bcrypt.gensalt(rounds=get_settings().bcrypt_rounds)
        )
    return _dummy_hash_cache


def clear_token_cache() -> None:
    """Drop all cached Principal entries (revocation must be immediate)."""
    with _token_cache_lock:
        _token_cache.clear()


def _token_digest(token: str) -> str:
    return hashlib.sha256(token.encode("utf-8")).hexdigest()


def _as_utc(value: Any) -> datetime:
    """Normalize a TIMESTAMPTZ (datetime) / TEXT (ISO string) column value."""
    if isinstance(value, datetime):
        return value if value.tzinfo else value.replace(tzinfo=timezone.utc)
    return datetime.fromisoformat(str(value))


def _hash_password(password: str) -> str:
    salt = bcrypt.gensalt(rounds=get_settings().bcrypt_rounds)
    return bcrypt.hashpw(password.encode("utf-8"), salt).decode("ascii")


class UserService:
    @property
    def _ph(self) -> str:
        # Resolve lazily so backend flips during tests still take effect.
        return get_database_provider().placeholder

    # ------------------------------------------------------------------
    # 查询
    # ------------------------------------------------------------------

    async def get_user(self, user_id: str) -> Optional[Dict[str, Any]]:
        return await asyncio.to_thread(self._get_user_sync, user_id)

    def _get_user_sync(self, user_id: str) -> Optional[Dict[str, Any]]:
        ph = self._ph
        with get_conn() as conn:
            row = conn.execute(
                f"SELECT id, roles, daily_quota, enabled, must_change_password, created_at, "
                f"created_by FROM users WHERE id = {ph}",
                (user_id,),
            ).fetchone()
        if not row:
            return None
        return self._user_row(row)

    @staticmethod
    def _user_row(row: Any) -> Dict[str, Any]:
        return {
            "id": row["id"],
            "roles": json.loads(row["roles"]),
            "daily_quota": row["daily_quota"],
            "enabled": bool(row["enabled"]),
            "must_change_password": bool(row["must_change_password"]),
            "created_at": str(row["created_at"]),
            "created_by": row["created_by"],
        }

    async def list_users(self, created_by: Optional[str] = None) -> List[Dict[str, Any]]:
        return await asyncio.to_thread(self._list_users_sync, created_by)

    def _list_users_sync(self, created_by: Optional[str] = None) -> List[Dict[str, Any]]:
        ph = self._ph
        with get_conn() as conn:
            if created_by is None:
                rows = conn.execute(
                    "SELECT id, roles, daily_quota, enabled, must_change_password, created_at, "
                    "created_by FROM users ORDER BY id ASC"
                ).fetchall()
            else:
                rows = conn.execute(
                    f"SELECT id, roles, daily_quota, enabled, must_change_password, created_at, "
                    f"created_by FROM users WHERE created_by = {ph} ORDER BY id ASC",
                    (created_by,),
                ).fetchall()
        return [self._user_row(row) for row in rows]

    @staticmethod
    def can_manage(actor: Principal, target: Dict[str, Any]) -> bool:
        """树状管理权限：leader 管全部；admin（老师）只管自己创建的纯 viewer 学生。

        静态 token 主体不在 users 表中、roles 里也没有 leader——按应急凭证语义
        由端点层另行放行，不进入本判定。
        """
        if "leader" in actor.roles:
            return True
        if "admin" not in actor.roles:
            return False
        if target.get("created_by") != actor.subject:
            return False
        target_roles = set(target.get("roles") or [])
        return bool(target_roles) and target_roles <= {"viewer"}

    # ------------------------------------------------------------------
    # 创建 / 管理
    # ------------------------------------------------------------------

    async def create_users(
        self,
        ids: List[str],
        roles: Optional[List[str]] = None,
        daily_quota: Optional[int] = None,
        created_by: Optional[str] = None,
    ) -> Dict[str, List[str]]:
        """Batch-create accounts; initial password equals the id itself.

        Returns {"created": [...], "skipped": [...]}; malformed ids and
        duplicates land in skipped so CLI imports can report them verbatim.
        created_by 记录树状管理中的归属（CLI 引导的根账号为 None）。
        """
        return await asyncio.to_thread(self._create_users_sync, ids, roles, daily_quota, created_by)

    def _create_users_sync(
        self,
        ids: List[str],
        roles: Optional[List[str]],
        daily_quota: Optional[int],
        created_by: Optional[str] = None,
    ) -> Dict[str, List[str]]:
        if roles is None:
            roles = ["viewer"]
        if not roles or not set(roles) <= ALLOWED_ROLES:
            raise ValueError(f"roles must be a non-empty subset of {sorted(ALLOWED_ROLES)}")
        if daily_quota is not None and daily_quota < 0:
            raise ValueError("daily_quota must be a non-negative integer or None")
        ph = self._ph
        roles_json = json.dumps(roles)
        result: Dict[str, List[str]] = {"created": [], "skipped": []}
        with get_conn() as conn:
            for user_id in ids:
                if not USER_ID_PATTERN.match(user_id):
                    result["skipped"].append(user_id)
                    continue
                exists = conn.execute(
                    f"SELECT 1 FROM users WHERE id = {ph}", (user_id,)
                ).fetchone()
                if exists:
                    result["skipped"].append(user_id)
                    continue
                # 初始密码 = 学号本身；must_change_password=True 提示首次登录后改密。
                conn.execute(
                    f"INSERT INTO users(id, password_hash, roles, daily_quota, enabled, "
                    f"must_change_password, created_at, created_by) "
                    f"VALUES ({ph}, {ph}, {ph}, {ph}, {ph}, {ph}, {ph}, {ph})",
                    (user_id, _hash_password(user_id), roles_json, daily_quota, True, True,
                     now_iso(), created_by),
                )
                result["created"].append(user_id)
        logger.info(json.dumps({
            "event": "users_created",
            "created": len(result["created"]),
            "skipped": len(result["skipped"]),
        }))
        return result

    async def reset_password(self, user_id: str) -> bool:
        """Reset the password back to the id itself; existing tokens die."""
        return await asyncio.to_thread(self._reset_password_sync, user_id)

    def _reset_password_sync(self, user_id: str) -> bool:
        ph = self._ph
        with get_conn() as conn:
            # 重置回初始密码后强制用户下次登录改密。
            cursor = conn.execute(
                f"UPDATE users SET password_hash = {ph}, must_change_password = {ph} WHERE id = {ph}",
                (_hash_password(user_id), True, user_id),
            )
            if cursor.rowcount == 0:
                return False
            self._revoke_user_tokens_sync(conn, user_id)
        clear_token_cache()
        logger.warning(json.dumps({"event": "password_reset", "user_id": user_id}))
        return True

    async def set_enabled(self, user_id: str, enabled: bool) -> bool:
        """Enable/disable an account; disabling revokes all its tokens and
        cancels the account's pending RAG tasks (S3-c：停用按取消语义终止在跑问答)。"""
        changed = await asyncio.to_thread(self._set_enabled_sync, user_id, enabled)
        if changed and not enabled:
            # 库行与成绩已在同一事务终态化；此处只取消本进程执行协程。
            try:
                from . import task_service
                await task_service.cancel_local_for_user(user_id)
            except Exception:
                logger.warning(json.dumps({
                    "event": "task_cancel_on_disable_failed", "user_id": user_id}))
        return changed

    def _set_enabled_sync(self, user_id: str, enabled: bool) -> bool:
        ph = self._ph
        with get_conn() as conn:
            cursor = conn.execute(
                f"UPDATE users SET enabled = {ph} WHERE id = {ph}",
                (enabled, user_id),
            )
            if cursor.rowcount == 0:
                return False
            if not enabled:
                self._revoke_user_tokens_sync(conn, user_id)
                ids = [row["id"] for row in conn.execute(
                    f"SELECT id FROM rag_requests WHERE user_id={ph} AND status='pending'",
                    (user_id,),
                ).fetchall()]
                if ids:
                    marks = ",".join([ph] * len(ids))
                    conn.execute(
                        f"UPDATE rag_requests SET status='cancelled',reason='account_disabled',updated_at={ph} "
                        f"WHERE id IN ({marks}) AND status='pending'", (now_iso(), *ids),
                    )
                    conn.execute(f"DELETE FROM rag_task_inputs WHERE task_id IN ({marks})", tuple(ids))
                    # 已完成工具评测但问答仍在生成时，停号需撤销该任务的练习成绩；
                    # 只用 0014 的稳定 job 身份关联，不猜测历史 NULL 关联行。
                    conn.execute(
                        f"DELETE FROM grading_submissions WHERE task_job_id IN "
                        f"(SELECT job_id FROM rag_task_jobs WHERE task_id IN ({marks}))",
                        tuple(ids),
                    )
        if not enabled:
            clear_token_cache()
        logger.warning(json.dumps({"event": "user_status_changed", "user_id": user_id, "enabled": enabled}))
        return True

    async def update_user(
        self,
        user_id: str,
        roles: Optional[List[str]] = None,
        daily_quota: Optional[int] = None,
        created_by: Optional[str] = None,
    ) -> Optional[Dict[str, Any]]:
        """Patch roles / daily_quota / created_by; returns the updated user or None."""
        return await asyncio.to_thread(self._update_user_sync, user_id, roles, daily_quota, created_by)

    def _update_user_sync(
        self,
        user_id: str,
        roles: Optional[List[str]],
        daily_quota: Optional[int],
        created_by: Optional[str] = None,
    ) -> Optional[Dict[str, Any]]:
        if roles is not None and (not roles or not set(roles) <= ALLOWED_ROLES):
            raise ValueError(f"roles must be a non-empty subset of {sorted(ALLOWED_ROLES)}")
        if daily_quota is not None and daily_quota < 0:
            raise ValueError("daily_quota must be a non-negative integer or None")
        ph = self._ph
        with get_conn() as conn:
            row = conn.execute(
                f"SELECT id FROM users WHERE id = {ph}", (user_id,)
            ).fetchone()
            if not row:
                return None
            if roles is not None:
                conn.execute(
                    f"UPDATE users SET roles = {ph} WHERE id = {ph}",
                    (json.dumps(roles), user_id),
                )
            if daily_quota is not None:
                conn.execute(
                    f"UPDATE users SET daily_quota = {ph} WHERE id = {ph}",
                    (daily_quota, user_id),
                )
            if created_by is not None:
                # 归属调整（仅组长）：目标创建者必须存在且不能指向自己。
                if created_by == user_id:
                    raise ValueError("created_by must differ from the user id")
                creator = conn.execute(
                    f"SELECT 1 FROM users WHERE id = {ph}", (created_by,)
                ).fetchone()
                if not creator:
                    raise ValueError("created_by user does not exist")
                conn.execute(
                    f"UPDATE users SET created_by = {ph} WHERE id = {ph}",
                    (created_by, user_id),
                )
            updated = conn.execute(
                f"SELECT id, roles, daily_quota, enabled, must_change_password, created_at, "
                f"created_by FROM users WHERE id = {ph}",
                (user_id,),
            ).fetchone()
        # roles/配额变化必须对已签发 token 立即生效。
        clear_token_cache()
        return self._user_row(updated)

    # ------------------------------------------------------------------
    # 删除 / 分配（M9）
    # ------------------------------------------------------------------

    async def delete_users(
        self, actor: Principal, ids: List[str], password: str
    ) -> Dict[str, List[str]]:
        """Batch-delete manageable accounts; requires the actor's own password.

        PermissionError 表示密码校验失败（路由层转 403）；自己/不可管/不存在
        的 id 进 skipped，不打断批量。每次成功删除写一行 admin_audit_log。
        """
        return await asyncio.to_thread(self._delete_users_sync, actor, ids, password)

    def _delete_users_sync(
        self, actor: Principal, ids: List[str], password: str
    ) -> Dict[str, List[str]]:
        if not self._verify_credentials_sync(actor.subject, password):
            raise PermissionError("actor password verification failed")
        ph = self._ph
        result: Dict[str, List[str]] = {"deleted": [], "skipped": []}
        with get_conn() as conn:
            for user_id in ids:
                if user_id == actor.subject:
                    result["skipped"].append(user_id)
                    continue
                row = conn.execute(
                    f"SELECT id, roles, created_by FROM users WHERE id = {ph}", (user_id,)
                ).fetchone()
                if not row:
                    result["skipped"].append(user_id)
                    continue
                target = {
                    "id": row["id"],
                    "roles": json.loads(row["roles"]),
                    "created_by": row["created_by"],
                }
                if "leader" not in actor.roles and not self.can_manage(actor, target):
                    result["skipped"].append(user_id)
                    continue
                self._delete_user_rows_sync(conn, user_id)
                result["deleted"].append(user_id)
            if result["deleted"]:
                conn.execute(
                    f"INSERT INTO admin_audit_log(id, actor, action, targets_json, created_at) "
                    f"VALUES ({ph}, {ph}, {ph}, {ph}, {ph})",
                    (f"aud_{uuid.uuid4().hex[:12]}", actor.subject, "delete_users",
                     json.dumps(result["deleted"], ensure_ascii=False), now_iso()),
                )
        if result["deleted"]:
            clear_token_cache()
        logger.warning(json.dumps({
            "event": "users_deleted",
            "actor": actor.subject,
            "deleted": len(result["deleted"]),
            "skipped": len(result["skipped"]),
        }, ensure_ascii=False))
        return result

    def _delete_user_rows_sync(self, conn: Any, user_id: str) -> None:
        """Delete one account and its data inside the caller's transaction.

        顺序受 PostgreSQL FK 约束：token 先于 users 行；会话三表显式级联
        （不依赖 ON DELETE CASCADE）；成绩行保留、session_id 置 NULL 脱钩；
        名下学生保留账号，created_by 置 NULL 归入未分配组。
        """
        ph = self._ph
        conn.execute(f"DELETE FROM api_tokens WHERE user_id = {ph}", (user_id,))
        session_ids = [
            row["id"]
            for row in conn.execute(
                f"SELECT id FROM assistant_sessions WHERE user_id = {ph}", (user_id,)
            ).fetchall()
        ]
        if session_ids:
            session_ph = ",".join(ph for _ in session_ids)
            message_ids = [
                row["id"]
                for row in conn.execute(
                    f"SELECT id FROM assistant_messages WHERE session_id IN ({session_ph})",
                    tuple(session_ids),
                ).fetchall()
            ]
            if message_ids:
                message_ph = ",".join(ph for _ in message_ids)
                conn.execute(
                    f"DELETE FROM assistant_citations WHERE message_id IN ({message_ph})",
                    tuple(message_ids),
                )
            conn.execute(
                f"DELETE FROM assistant_messages WHERE session_id IN ({session_ph})",
                tuple(session_ids),
            )
            conn.execute(
                f"UPDATE grading_submissions SET session_id = NULL WHERE session_id IN ({session_ph})",
                tuple(session_ids),
            )
            conn.execute(
                f"DELETE FROM assistant_sessions WHERE id IN ({session_ph})",
                tuple(session_ids),
            )
        conn.execute(f"DELETE FROM password_reset_requests WHERE user_id = {ph}", (user_id,))
        conn.execute(f"DELETE FROM usage_counters WHERE subject = {ph}", (user_id,))
        conn.execute(f"UPDATE users SET created_by = NULL WHERE created_by = {ph}", (user_id,))
        conn.execute(f"DELETE FROM users WHERE id = {ph}", (user_id,))

    async def assign_students(self, ids: List[str], created_by: str) -> Dict[str, List[str]]:
        """组长把（未分配）学生批量指派给某位老师。"""
        return await asyncio.to_thread(self._assign_students_sync, ids, created_by)

    def _assign_students_sync(self, ids: List[str], created_by: str) -> Dict[str, List[str]]:
        ph = self._ph
        result: Dict[str, List[str]] = {"assigned": [], "skipped": []}
        with get_conn() as conn:
            owner = conn.execute(
                f"SELECT roles, enabled FROM users WHERE id = {ph}", (created_by,)
            ).fetchone()
            if (
                not owner
                or not owner["enabled"]
                or not (set(json.loads(owner["roles"])) & MANAGER_ROLES)
            ):
                raise ValueError("assign target must be an enabled teacher account")
            for user_id in ids:
                row = conn.execute(
                    f"SELECT roles FROM users WHERE id = {ph}", (user_id,)
                ).fetchone()
                target_roles = set(json.loads(row["roles"])) if row else set()
                if not row or not target_roles or not target_roles <= {"viewer"}:
                    result["skipped"].append(user_id)
                    continue
                conn.execute(
                    f"UPDATE users SET created_by = {ph} WHERE id = {ph}",
                    (created_by, user_id),
                )
                result["assigned"].append(user_id)
        logger.info(json.dumps({
            "event": "students_assigned",
            "created_by": created_by,
            "assigned": len(result["assigned"]),
            "skipped": len(result["skipped"]),
        }, ensure_ascii=False))
        return result

    async def list_audit_log(
        self, scope_actor: Optional[str] = None, limit: int = 100
    ) -> List[Dict[str, Any]]:
        """最近的审计记录，新的在前；scope_actor 限定只看某操作者（老师视角）。"""
        return await asyncio.to_thread(self._list_audit_log_sync, scope_actor, limit)

    def _list_audit_log_sync(
        self, scope_actor: Optional[str], limit: int
    ) -> List[Dict[str, Any]]:
        ph = self._ph
        with get_conn() as conn:
            if scope_actor is None:
                rows = conn.execute(
                    f"SELECT id, actor, action, targets_json, created_at FROM admin_audit_log "
                    f"ORDER BY created_at DESC, id DESC LIMIT {ph}",
                    (limit,),
                ).fetchall()
            else:
                rows = conn.execute(
                    f"SELECT id, actor, action, targets_json, created_at FROM admin_audit_log "
                    f"WHERE actor = {ph} ORDER BY created_at DESC, id DESC LIMIT {ph}",
                    (scope_actor, limit),
                ).fetchall()

        def _targets(value: Any) -> List[str]:
            # PostgreSQL JSONB 列驱动可能直接返回已解析的 list。
            if isinstance(value, list):
                return value
            return json.loads(value or "[]")

        return [
            {
                "id": row["id"],
                "actor": row["actor"],
                "action": row["action"],
                "targets": _targets(row["targets_json"]),
                "created_at": str(row["created_at"]),
            }
            for row in rows
        ]

    # ------------------------------------------------------------------
    # 密码
    # ------------------------------------------------------------------

    async def verify_credentials(self, user_id: str, password: str) -> bool:
        return await asyncio.to_thread(self._verify_credentials_sync, user_id, password)

    def _verify_credentials_sync(self, user_id: str, password: str) -> bool:
        ph = self._ph
        with get_conn() as conn:
            row = conn.execute(
                f"SELECT password_hash, enabled FROM users WHERE id = {ph}",
                (user_id,),
            ).fetchone()
        if not row or not row["enabled"]:
            # 用户不存在或已停用也跑一次 dummy 比对，避免响应时间泄露账号是否存在。
            bcrypt.checkpw(password.encode("utf-8"), _dummy_hash())
            return False
        return bool(bcrypt.checkpw(password.encode("utf-8"), row["password_hash"].encode("ascii")))

    def _validate_new_password(self, conn: Any, user_id: str, new_password: str) -> None:
        """新密码规则：长度达标、不得等于学号、不得等于旧密码。

        用户改密、管理员设密、令牌改密三处共用；规则不满足时抛 ValueError。
        """
        min_length = get_settings().password_min_length
        if len(new_password) < min_length:
            raise ValueError(f"new password must be at least {min_length} characters")
        if new_password == user_id:
            raise ValueError("new password must differ from the user id")
        ph = self._ph
        row = conn.execute(
            f"SELECT password_hash FROM users WHERE id = {ph}", (user_id,)
        ).fetchone()
        if row and bcrypt.checkpw(new_password.encode("utf-8"), row["password_hash"].encode("ascii")):
            raise ValueError("new password must differ from the old password")

    async def change_password(
        self,
        user_id: str,
        old_password: str,
        new_password: str,
        keep_token: Optional[str] = None,
    ) -> bool:
        """Change password; revokes the user's other tokens (keeps the caller's)."""
        return await asyncio.to_thread(
            self._change_password_sync, user_id, old_password, new_password, keep_token
        )

    def _change_password_sync(
        self,
        user_id: str,
        old_password: str,
        new_password: str,
        keep_token: Optional[str],
    ) -> bool:
        if not self._verify_credentials_sync(user_id, old_password):
            return False
        ph = self._ph
        keep_hash = _token_digest(keep_token) if keep_token else None
        with get_conn() as conn:
            self._validate_new_password(conn, user_id, new_password)
            conn.execute(
                f"UPDATE users SET password_hash = {ph}, must_change_password = {ph} WHERE id = {ph}",
                (_hash_password(new_password), False, user_id),
            )
            self._revoke_user_tokens_sync(conn, user_id, except_token_hash=keep_hash)
        clear_token_cache()
        logger.info(json.dumps({"event": "password_changed", "user_id": user_id}))
        return True

    async def set_password(self, user_id: str, new_password: str) -> bool:
        """Admin-set password; user must change it at next login, tokens die."""
        return await asyncio.to_thread(self._set_password_sync, user_id, new_password)

    def _set_password_sync(self, user_id: str, new_password: str) -> bool:
        ph = self._ph
        with get_conn() as conn:
            row = conn.execute(
                f"SELECT 1 FROM users WHERE id = {ph}", (user_id,)
            ).fetchone()
            if not row:
                return False
            self._validate_new_password(conn, user_id, new_password)
            # 管理员知道该密码，用户下次登录强制改密。
            conn.execute(
                f"UPDATE users SET password_hash = {ph}, must_change_password = {ph} WHERE id = {ph}",
                (_hash_password(new_password), True, user_id),
            )
            self._revoke_user_tokens_sync(conn, user_id)
        clear_token_cache()
        logger.warning(json.dumps({"event": "password_set_by_admin", "user_id": user_id}))
        return True

    # ------------------------------------------------------------------
    # 忘记密码：申请-审批-一次性令牌改密
    # ------------------------------------------------------------------

    async def create_reset_request(self, user_id: str) -> None:
        """Submit a password-reset request; idempotent per pending request.

        不检查用户是否存在（防枚举）；学号格式校验在端点层做。
        """
        await asyncio.to_thread(self._create_reset_request_sync, user_id)

    def _create_reset_request_sync(self, user_id: str) -> None:
        ph = self._ph
        with get_conn() as conn:
            row = conn.execute(
                f"SELECT 1 FROM password_reset_requests WHERE user_id = {ph} AND status = 'pending'",
                (user_id,),
            ).fetchone()
            if row:
                return
            conn.execute(
                f"INSERT INTO password_reset_requests(id, user_id, status, created_at) "
                f"VALUES ({ph}, {ph}, 'pending', {ph})",
                (uuid.uuid4().hex, user_id, now_iso()),
            )
        logger.info(json.dumps({"event": "password_reset_requested", "user_id": user_id}))

    async def list_reset_requests(
        self, status: str = "pending", scope_creator: Optional[str] = None
    ) -> List[Dict[str, Any]]:
        return await asyncio.to_thread(self._list_reset_requests_sync, status, scope_creator)

    def _list_reset_requests_sync(
        self, status: str, scope_creator: Optional[str] = None
    ) -> List[Dict[str, Any]]:
        ph = self._ph
        with get_conn() as conn:
            if scope_creator is None:
                rows = conn.execute(
                    f"SELECT id, user_id, created_at FROM password_reset_requests "
                    f"WHERE status = {ph} ORDER BY created_at ASC",
                    (status,),
                ).fetchall()
            else:
                # 树隔离：老师只看到自己学生的申请。
                rows = conn.execute(
                    f"SELECT r.id, r.user_id, r.created_at FROM password_reset_requests AS r "
                    f"JOIN users AS u ON u.id = r.user_id "
                    f"WHERE r.status = {ph} AND u.created_by = {ph} ORDER BY r.created_at ASC",
                    (status, scope_creator),
                ).fetchall()
        return [
            {"id": row["id"], "user_id": row["user_id"], "created_at": str(row["created_at"])}
            for row in rows
        ]

    async def approve_reset_request(
        self, request_id: str, admin_id: str, scope_creator: Optional[str] = None
    ) -> Optional[Tuple[str, str]]:
        """Approve a pending request; returns (plaintext token, expires_at).

        明文令牌仅在此返回一次，库里只存 sha256 哈希，30 分钟内有效。
        申请不存在 / 非 pending / 用户不存在 / 超出 scope_creator 归属范围时返回 None。
        """
        return await asyncio.to_thread(
            self._approve_reset_request_sync, request_id, admin_id, scope_creator
        )

    def _approve_reset_request_sync(
        self, request_id: str, admin_id: str, scope_creator: Optional[str] = None
    ) -> Optional[Tuple[str, str]]:
        ph = self._ph
        with get_conn() as conn:
            row = conn.execute(
                f"SELECT id, user_id, status FROM password_reset_requests WHERE id = {ph}",
                (request_id,),
            ).fetchone()
            if not row or row["status"] != "pending":
                return None
            user = conn.execute(
                f"SELECT created_by FROM users WHERE id = {ph}", (row["user_id"],)
            ).fetchone()
            if not user:
                return None
            if scope_creator is not None and user["created_by"] != scope_creator:
                return None
            token = secrets.token_urlsafe(32)
            expires_at = _as_utc(now_iso()) + timedelta(minutes=RESET_TOKEN_TTL_MINUTES)
            conn.execute(
                f"UPDATE password_reset_requests SET status = 'approved', reset_token_hash = {ph}, "
                f"reset_expires_at = {ph}, decided_at = {ph}, decided_by = {ph} WHERE id = {ph}",
                (_token_digest(token), expires_at.isoformat(), now_iso(), admin_id, request_id),
            )
        logger.info(json.dumps({
            "event": "password_reset_approved",
            "request_id": request_id,
            "user_id": row["user_id"],
            "admin_id": admin_id,
        }))
        return token, expires_at.isoformat()

    async def reject_reset_request(
        self, request_id: str, admin_id: str, scope_creator: Optional[str] = None
    ) -> bool:
        return await asyncio.to_thread(self._reject_reset_request_sync, request_id, admin_id, scope_creator)

    def _reject_reset_request_sync(
        self, request_id: str, admin_id: str, scope_creator: Optional[str] = None
    ) -> bool:
        ph = self._ph
        with get_conn() as conn:
            if scope_creator is not None:
                # 树隔离：老师只能驳回自己学生的申请。
                owner = conn.execute(
                    f"SELECT u.created_by FROM password_reset_requests AS r "
                    f"JOIN users AS u ON u.id = r.user_id WHERE r.id = {ph}",
                    (request_id,),
                ).fetchone()
                if not owner or owner["created_by"] != scope_creator:
                    return False
            cursor = conn.execute(
                f"UPDATE password_reset_requests SET status = 'rejected', decided_at = {ph}, "
                f"decided_by = {ph} WHERE id = {ph} AND status = 'pending'",
                (now_iso(), admin_id, request_id),
            )
            if cursor.rowcount == 0:
                return False
        logger.info(json.dumps({
            "event": "password_reset_rejected",
            "request_id": request_id,
            "admin_id": admin_id,
        }))
        return True

    async def consume_reset_token(self, token: str, new_password: str) -> bool:
        """Redeem a one-time reset token and set a new password.

        任何一步失败（令牌无效 / 过期 / 已用 / 新密码不合规）都返回 False，
        由端点统一 400，不区分原因。
        """
        return await asyncio.to_thread(self._consume_reset_token_sync, token, new_password)

    def _consume_reset_token_sync(self, token: str, new_password: str) -> bool:
        ph = self._ph
        token_hash = _token_digest(token)
        with get_conn() as conn:
            row = conn.execute(
                f"SELECT id, user_id, reset_expires_at FROM password_reset_requests "
                f"WHERE reset_token_hash = {ph} AND status = 'approved'",
                (token_hash,),
            ).fetchone()
            if not row or row["reset_expires_at"] is None:
                return False
            if _as_utc(row["reset_expires_at"]) <= datetime.now(timezone.utc):
                return False
            user = conn.execute(
                f"SELECT 1 FROM users WHERE id = {ph}", (row["user_id"],)
            ).fetchone()
            if not user:
                return False
            try:
                self._validate_new_password(conn, row["user_id"], new_password)
            except ValueError:
                return False
            conn.execute(
                f"UPDATE users SET password_hash = {ph}, must_change_password = {ph} WHERE id = {ph}",
                (_hash_password(new_password), False, row["user_id"]),
            )
            conn.execute(
                f"UPDATE password_reset_requests SET status = 'used' WHERE id = {ph}",
                (row["id"],),
            )
            self._revoke_user_tokens_sync(conn, row["user_id"])
        clear_token_cache()
        logger.info(json.dumps({"event": "password_reset_consumed", "user_id": row["user_id"]}))
        return True

    # ------------------------------------------------------------------
    # 登录 token
    # ------------------------------------------------------------------

    async def issue_token(self, user_id: str) -> Tuple[str, str]:
        """Issue a login token; returns (plaintext token, expires_at ISO)."""
        return await asyncio.to_thread(self._issue_token_sync, user_id)

    def _issue_token_sync(self, user_id: str) -> Tuple[str, str]:
        settings = get_settings()
        ph = self._ph
        token = secrets.token_urlsafe(32)
        issued_at = _as_utc(now_iso())
        expires_at = issued_at + timedelta(days=settings.auth_token_ttl_days)
        with get_conn() as conn:
            conn.execute(
                f"INSERT INTO api_tokens(token_hash, user_id, issued_at, expires_at) "
                f"VALUES ({ph}, {ph}, {ph}, {ph})",
                (_token_digest(token), user_id, issued_at.isoformat(), expires_at.isoformat()),
            )
        return token, expires_at.isoformat()

    async def resolve_token(self, token: str) -> Optional[Principal]:
        """Map a bearer token to a Principal, or None when invalid/expired."""
        return await asyncio.to_thread(self._resolve_token_sync, token)

    def _resolve_token_sync(self, token: str) -> Optional[Principal]:
        token_hash = _token_digest(token)
        now = time.monotonic()
        with _token_cache_lock:
            cached = _token_cache.get(token_hash)
            if cached is not None and now - cached[0] < _TOKEN_CACHE_TTL_SECONDS:
                return cached[1]
        principal = self._resolve_token_db(token_hash)
        if principal is not None:
            with _token_cache_lock:
                _token_cache[token_hash] = (now, principal)
        return principal

    def _resolve_token_db(self, token_hash: str) -> Optional[Principal]:
        ph = self._ph
        with get_conn() as conn:
            row = conn.execute(
                f"SELECT t.expires_at, t.revoked_at, u.id, u.roles, u.daily_quota, u.enabled, "
                f"u.must_change_password "
                f"FROM api_tokens AS t JOIN users AS u ON u.id = t.user_id "
                f"WHERE t.token_hash = {ph}",
                (token_hash,),
            ).fetchone()
        if not row or row["revoked_at"] is not None or not row["enabled"]:
            return None
        if _as_utc(row["expires_at"]) <= datetime.now(timezone.utc):
            return None
        daily_quota = row["daily_quota"]
        return Principal(
            subject=row["id"],
            roles=json.loads(row["roles"]),
            daily_quota=int(daily_quota) if daily_quota is not None else get_settings().llm_daily_quota_default,
            must_change_password=bool(row["must_change_password"]),
        )

    async def revoke_token(self, token: str) -> None:
        await asyncio.to_thread(self._revoke_token_sync, token)

    def _revoke_token_sync(self, token: str) -> None:
        ph = self._ph
        with get_conn() as conn:
            conn.execute(
                f"UPDATE api_tokens SET revoked_at = {ph} WHERE token_hash = {ph} AND revoked_at IS NULL",
                (now_iso(), _token_digest(token)),
            )
        clear_token_cache()

    async def revoke_user_tokens(self, user_id: str) -> None:
        await asyncio.to_thread(self._revoke_user_tokens_standalone_sync, user_id)

    def _revoke_user_tokens_standalone_sync(self, user_id: str) -> None:
        with get_conn() as conn:
            self._revoke_user_tokens_sync(conn, user_id)
        clear_token_cache()

    def _revoke_user_tokens_sync(
        self, conn: Any, user_id: str, except_token_hash: Optional[str] = None
    ) -> None:
        """Revoke all live tokens of a user inside an existing transaction.

        The caller is responsible for clear_token_cache() afterwards.
        """
        ph = self._ph
        if except_token_hash is None:
            conn.execute(
                f"UPDATE api_tokens SET revoked_at = {ph} WHERE user_id = {ph} AND revoked_at IS NULL",
                (now_iso(), user_id),
            )
        else:
            conn.execute(
                f"UPDATE api_tokens SET revoked_at = {ph} "
                f"WHERE user_id = {ph} AND revoked_at IS NULL AND token_hash <> {ph}",
                (now_iso(), user_id, except_token_hash),
            )
