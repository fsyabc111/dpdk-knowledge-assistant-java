import asyncio
import json
import logging
import threading
import uuid
from datetime import datetime, timedelta, timezone
from typing import Any, Dict, List, Optional

from ..config import get_settings
from ..db import get_conn, get_database_provider

logger = logging.getLogger("assistant")

# Messages are ordered by (created_at, id), but ids are random, so two rows
# sharing a created_at would order arbitrarily. On Windows the system clock
# behind datetime.now() has ~15.6 ms granularity, making same-timestamp
# inserts common in rapid bursts (and possible under load anywhere). Keep a
# process-local monotone guard so timestamps always strictly increase.
_now_lock = threading.Lock()
_last_now_iso = ""


def now_iso() -> str:
    global _last_now_iso
    with _now_lock:
        ts = datetime.now(timezone.utc).isoformat()
        if ts <= _last_now_iso:
            ts = (datetime.fromisoformat(_last_now_iso) + timedelta(microseconds=1)).isoformat()
        _last_now_iso = ts
        return ts


class SessionService:
    @property
    def _ph(self) -> str:
        # Resolve lazily so backend flips during tests still take effect.
        return get_database_provider().placeholder

    async def ensure_session(
        self, session_id: Optional[str], title: str, user_id: str = "anonymous"
    ) -> str:
        # Offload the blocking DB work to a worker thread so the asyncio event
        # loop stays free to run other concurrent requests (LLM calls, other
        # sessions). This is the difference between 50 concurrent requests
        # serializing on the event loop vs. overlapping.
        return await asyncio.to_thread(self._ensure_session_sync, session_id, title, user_id)

    def _ensure_session_sync(self, session_id: Optional[str], title: str, user_id: str = "anonymous") -> str:
        ph = self._ph
        if session_id:
            with get_conn() as conn:
                row = conn.execute(
                    f"SELECT id, user_id FROM assistant_sessions WHERE id = {ph}", (session_id,)
                ).fetchone()
                # A session id that belongs to another user is treated as
                # unknown: the caller gets a fresh session under their own id
                # instead of attaching to someone else's history.
                if row and (row["user_id"] or "anonymous") == user_id:
                    conn.execute(
                        f"UPDATE assistant_sessions SET updated_at = {ph} WHERE id = {ph}",
                        (now_iso(), session_id),
                    )
                    return session_id
        new_id = f"sess_{uuid.uuid4().hex[:12]}"
        with get_conn() as conn:
            conn.execute(
                f"INSERT INTO assistant_sessions(id, user_id, title, created_at, updated_at) VALUES ({ph}, {ph}, {ph}, {ph}, {ph})",
                (new_id, user_id, title[:80], now_iso(), now_iso()),
            )
        return new_id

    def resolve_release(self, session_id: str) -> Optional[str]:
        """首次有可用发布时持久绑定；并发首查由数据库条件更新决定唯一赢家。"""
        from .rag_index_service import active_release

        ph = self._ph
        with get_conn() as conn:
            row = conn.execute(
                f"SELECT rag_release_id FROM assistant_sessions WHERE id = {ph}", (session_id,)
            ).fetchone()
        if row is None:
            raise ValueError("会话不存在，无法绑定索引版本")
        if row["rag_release_id"]:
            return row["rag_release_id"]
        candidate = active_release()
        if not candidate:
            return None
        # UPDATE 先获取写锁，随后 SELECT 读取赢家；不在 SQLite 读事务内升级写锁。
        with get_conn() as conn:
            conn.execute(
                f"UPDATE assistant_sessions SET rag_release_id = {ph} "
                f"WHERE id = {ph} AND rag_release_id IS NULL", (candidate, session_id),
            )
            row = conn.execute(
                f"SELECT rag_release_id FROM assistant_sessions WHERE id = {ph}", (session_id,)
            ).fetchone()
        if row is None:
            raise ValueError("会话已删除，无法绑定索引版本")
        return row["rag_release_id"]

    async def add_message(self, session_id: str, role: str, content: str, metadata: Optional[Dict[str, Any]] = None) -> str:
        return await asyncio.to_thread(self._add_message_sync, session_id, role, content, metadata)

    async def discard_empty_session(self, session_id: str, user_id: str) -> None:
        """并发首问败方清理自己刚建的空会话，不触碰已有消息或任务。"""
        await asyncio.to_thread(self._discard_empty_session_sync, session_id, user_id)

    def _discard_empty_session_sync(self, session_id: str, user_id: str) -> None:
        ph = self._ph
        with get_conn() as conn:
            conn.execute(
                f"DELETE FROM assistant_sessions WHERE id={ph} AND user_id={ph} "
                f"AND NOT EXISTS (SELECT 1 FROM assistant_messages WHERE session_id={ph}) "
                f"AND NOT EXISTS (SELECT 1 FROM rag_requests WHERE session_id={ph})",
                (session_id, user_id, session_id, session_id),
            )

    def _add_message_sync(self, session_id: str, role: str, content: str, metadata: Optional[Dict[str, Any]]) -> str:
        ph = self._ph
        msg_id = f"msg_{uuid.uuid4().hex[:12]}"
        with get_conn() as conn:
            conn.execute(
                f"INSERT INTO assistant_messages(id, session_id, role, content, metadata_json, created_at) VALUES ({ph}, {ph}, {ph}, {ph}, {ph}, {ph})",
                (msg_id, session_id, role, content, json.dumps(metadata or {}, ensure_ascii=False), now_iso()),
            )
            conn.execute(
                f"UPDATE assistant_sessions SET updated_at = {ph} WHERE id = {ph}",
                (now_iso(), session_id),
            )
        return msg_id

    async def get_messages(self, session_id: str, *, include_cancelled: bool = False) -> List[Dict[str, Any]]:
        """Return a session's messages in stable chronological order."""
        return await asyncio.to_thread(self._get_messages_sync, session_id, include_cancelled)

    def _get_messages_sync(self, session_id: str, include_cancelled: bool = False) -> List[Dict[str, Any]]:
        ph = self._ph
        with get_conn() as conn:
            rows = conn.execute(
                f"SELECT id, role, content, metadata_json, created_at "
                f"FROM assistant_messages WHERE session_id = {ph} "
                "ORDER BY created_at ASC, id ASC",
                (session_id,),
            ).fetchall()
        def _metadata(value: Any) -> Dict[str, Any]:
            if isinstance(value, dict):
                return value
            return json.loads(value or "{}")

        messages = [
            {
                "id": row["id"],
                "role": row["role"],
                "content": row["content"],
                "metadata": _metadata(row["metadata_json"]),
                "created_at": row["created_at"],
            }
            for row in rows
        ]
        # Interrupted stream output is visible to the originating browser, but
        # must not become an authoritative turn in later conversation memory.
        # include_cancelled=True（历史展示路径）保留取消占位消息——用户刷新后
        # 仍能看到「已取消」终态；会话记忆维持过滤口径不变。
        if include_cancelled:
            return messages
        return [item for item in messages if not (
            item["role"] == "assistant" and item["metadata"].get("stream_status") == "cancelled"
        )]

    async def get_session(self, session_id: str) -> Optional[Dict[str, Any]]:
        return await asyncio.to_thread(self._get_session_sync, session_id)

    def _get_session_sync(self, session_id: str) -> Optional[Dict[str, Any]]:
        ph = self._ph
        with get_conn() as conn:
            row = conn.execute(
                f"SELECT id, user_id, title, created_at, updated_at FROM assistant_sessions WHERE id = {ph}",
                (session_id,),
            ).fetchone()
        if not row:
            return None
        return {
            "id": row["id"],
            "user_id": row["user_id"] or "anonymous",
            "title": row["title"] or "",
            "created_at": str(row["created_at"]),
            "updated_at": str(row["updated_at"]),
        }

    async def list_sessions(self, user_id: str, limit: int = 50, offset: int = 0) -> List[Dict[str, Any]]:
        """List one subject's sessions, most recently updated first."""
        return await asyncio.to_thread(self._list_sessions_sync, user_id, limit, offset)

    def _list_sessions_sync(self, user_id: str, limit: int, offset: int) -> List[Dict[str, Any]]:
        ph = self._ph
        with get_conn() as conn:
            rows = conn.execute(
                f"SELECT id, title, updated_at FROM assistant_sessions "
                f"WHERE COALESCE(user_id, 'anonymous') = {ph} "
                f"ORDER BY updated_at DESC, id DESC LIMIT {ph} OFFSET {ph}",
                (user_id, limit, offset),
            ).fetchall()
        return [
            {"id": row["id"], "title": row["title"] or "", "updated_at": str(row["updated_at"])}
            for row in rows
        ]

    async def assign_anonymous_sessions(self, user_id: str, dry_run: bool = True) -> Dict[str, Any]:
        """Reassign legacy anonymous sessions to an account (M6 transition)."""
        return await asyncio.to_thread(self._assign_anonymous_sessions_sync, user_id, dry_run)

    def _assign_anonymous_sessions_sync(self, user_id: str, dry_run: bool) -> Dict[str, Any]:
        ph = self._ph
        with get_conn() as conn:
            matched = int(conn.execute(
                "SELECT COUNT(*) AS n FROM assistant_sessions "
                "WHERE COALESCE(user_id, 'anonymous') = 'anonymous'"
            ).fetchone()["n"])
            updated = 0
            if not dry_run and matched:
                cursor = conn.execute(
                    f"UPDATE assistant_sessions SET user_id = {ph} "
                    f"WHERE COALESCE(user_id, 'anonymous') = 'anonymous'",
                    (user_id,),
                )
                updated = cursor.rowcount
                logger.info(json.dumps({
                    "event": "anonymous_sessions_assigned",
                    "user_id": user_id,
                    "sessions": updated,
                }, ensure_ascii=False))
        return {"dry_run": dry_run, "user_id": user_id, "matched": matched, "updated": updated}

    async def get_citations(self, message_ids: List[str]) -> Dict[str, List[Dict[str, Any]]]:
        return await asyncio.to_thread(self._get_citations_sync, message_ids)

    def _get_citations_sync(self, message_ids: List[str]) -> Dict[str, List[Dict[str, Any]]]:
        if not message_ids:
            return {}
        ph = self._ph
        placeholders = ",".join(ph for _ in message_ids)
        with get_conn() as conn:
            rows = conn.execute(
                f"SELECT message_id, chunk_id, citation_engine, score, rank "
                f"FROM assistant_citations WHERE message_id IN ({placeholders}) "
                "ORDER BY message_id ASC, rank ASC",
                tuple(message_ids),
            ).fetchall()
        grouped: Dict[str, List[Dict[str, Any]]] = {}
        for row in rows:
            grouped.setdefault(row["message_id"], []).append({
                "chunk_id": row["chunk_id"],
                "engine": row["citation_engine"] or "legacy",
                "score": float(row["score"]),
                "rank": int(row["rank"]),
            })
        return grouped

    async def get_memory_state(self, session_id: str) -> Dict[str, Optional[str]]:
        return await asyncio.to_thread(self._get_memory_state_sync, session_id)

    def _get_memory_state_sync(self, session_id: str) -> Dict[str, Optional[str]]:
        ph = self._ph
        with get_conn() as conn:
            row = conn.execute(
                f"SELECT memory_summary, memory_summary_through_message_id "
                f"FROM assistant_sessions WHERE id = {ph}",
                (session_id,),
            ).fetchone()
        return {
            "summary": (row["memory_summary"] if row else "") or "",
            "through_message_id": row["memory_summary_through_message_id"] if row else None,
        }

    async def update_memory_summary(self, session_id: str, summary: str, through_message_id: str) -> None:
        await asyncio.to_thread(
            self._update_memory_summary_sync, session_id, summary, through_message_id
        )

    def _update_memory_summary_sync(self, session_id: str, summary: str, through_message_id: str) -> None:
        ph = self._ph
        with get_conn() as conn:
            conn.execute(
                f"UPDATE assistant_sessions SET memory_summary = {ph}, "
                f"memory_summary_through_message_id = {ph}, updated_at = {ph} "
                f"WHERE id = {ph}",
                (summary, through_message_id, now_iso(), session_id),
            )

    async def add_citation(
        self, message_id: str, chunk_id: str, score: float, rank: int, engine: str = "legacy",
    ) -> None:
        await asyncio.to_thread(self._add_citation_sync, message_id, chunk_id, score, rank, engine)

    def _add_citation_sync(
        self, message_id: str, chunk_id: str, score: float, rank: int, engine: str,
    ) -> None:
        ph = self._ph
        with get_conn() as conn:
            conn.execute(
                f"INSERT INTO assistant_citations(id, message_id, chunk_id, citation_engine, score, rank) "
                f"VALUES ({ph}, {ph}, {ph}, {ph}, {ph}, {ph})",
                (f"cit_{uuid.uuid4().hex[:12]}", message_id, chunk_id, engine, score, rank),
            )

    async def delete_session(self, session_id: str, subject: Optional[str] = None) -> bool:
        """Delete one session and its messages/citations.

        When subject is given the session must belong to it; a foreign or
        missing id returns False so the route can answer a plain 404 without
        distinguishing "not found" from "someone else's". Callers in local
        auth mode pass subject=None to skip the ownership check, mirroring
        load_session's exemption. Grading rows survive with session_id
        detached (NULL) instead of being deleted.
        """
        return await asyncio.to_thread(self._delete_session_sync, session_id, subject)

    def _delete_session_sync(self, session_id: str, subject: Optional[str]) -> bool:
        ph = self._ph
        with get_conn() as conn:
            row = conn.execute(
                f"SELECT user_id FROM assistant_sessions WHERE id = {ph}", (session_id,)
            ).fetchone()
            if not row:
                return False
            if subject is not None and (row["user_id"] or "anonymous") != subject:
                return False
            message_ids = [
                item["id"]
                for item in conn.execute(
                    f"SELECT id FROM assistant_messages WHERE session_id = {ph}", (session_id,)
                ).fetchall()
            ]
            if message_ids:
                message_ph = ",".join(ph for _ in message_ids)
                conn.execute(
                    f"DELETE FROM assistant_citations WHERE message_id IN ({message_ph})",
                    tuple(message_ids),
                )
            conn.execute(
                f"DELETE FROM assistant_messages WHERE session_id = {ph}", (session_id,)
            )
            conn.execute(
                f"UPDATE grading_submissions SET session_id = NULL WHERE session_id = {ph}",
                (session_id,),
            )
            conn.execute(
                f"DELETE FROM assistant_sessions WHERE id = {ph}", (session_id,)
            )
        logger.info(json.dumps({
            "event": "session_deleted",
            "session_id": session_id,
            "subject": subject or "local",
        }, ensure_ascii=False))
        return True

    async def purge_expired_sessions(self, retention_days: int, dry_run: bool = True) -> Dict[str, Any]:
        return await asyncio.to_thread(self._purge_expired_sessions_sync, retention_days, dry_run)

    def _purge_expired_sessions_sync(self, retention_days: int, dry_run: bool) -> Dict[str, Any]:
        """Delete (or count, when dry_run) sessions idle longer than the cutoff.

        Deletion is explicit in citations -> messages -> sessions order on both
        dialects: PostgreSQL's ON DELETE CASCADE is not relied upon because
        SQLite has no enforced FK and historical data may contain orphans.
        """
        cutoff = (datetime.now(timezone.utc) - timedelta(days=retention_days)).isoformat()
        ph = self._ph
        with get_conn() as conn:
            expired = [
                row["id"]
                for row in conn.execute(
                    f"SELECT id FROM assistant_sessions WHERE updated_at < {ph}", (cutoff,)
                ).fetchall()
            ]
            result: Dict[str, Any] = {
                "dry_run": dry_run,
                "retention_days": retention_days,
                "cutoff": cutoff,
                "sessions": len(expired),
                "messages": 0,
                "citations": 0,
            }
            if not expired:
                return result
            session_ph = ",".join(ph for _ in expired)
            message_ids = [
                row["id"]
                for row in conn.execute(
                    f"SELECT id FROM assistant_messages WHERE session_id IN ({session_ph})",
                    tuple(expired),
                ).fetchall()
            ]
            result["messages"] = len(message_ids)
            if message_ids:
                message_ph = ",".join(ph for _ in message_ids)
                result["citations"] = int(conn.execute(
                    f"SELECT COUNT(*) AS n FROM assistant_citations WHERE message_id IN ({message_ph})",
                    tuple(message_ids),
                ).fetchone()["n"])
                if not dry_run:
                    conn.execute(
                        f"DELETE FROM assistant_citations WHERE message_id IN ({message_ph})",
                        tuple(message_ids),
                    )
            if not dry_run:
                conn.execute(
                    f"DELETE FROM assistant_messages WHERE session_id IN ({session_ph})",
                    tuple(expired),
                )
                conn.execute(
                    f"DELETE FROM assistant_sessions WHERE id IN ({session_ph})",
                    tuple(expired),
                )
                logger.info(json.dumps({
                    "event": "session_purge",
                    "retention_days": retention_days,
                    "cutoff": cutoff,
                    "sessions": result["sessions"],
                    "messages": result["messages"],
                    "citations": result["citations"],
                }, ensure_ascii=False))
        return result

    async def get_storage_usage(self) -> Dict[str, Any]:
        """Return session-record storage for an authenticated maintenance call.

        This method is read-only. Retention is deliberately manual so normal
        chat traffic can never delete user conversation history.
        """
        return await asyncio.to_thread(self._get_storage_usage_sync)

    def _session_storage_bytes_sync(self) -> int:
        provider = get_database_provider()
        if provider.name == "postgres":
            with get_conn() as conn:
                row = conn.execute(
                    "SELECT COALESCE(SUM(pg_total_relation_size(c.oid)), 0) AS storage_bytes "
                    "FROM pg_class AS c "
                    "JOIN pg_namespace AS n ON n.oid = c.relnamespace "
                    "WHERE n.nspname = current_schema() "
                    "AND c.relname IN ('assistant_sessions', 'assistant_messages', 'assistant_citations')"
                ).fetchone()
            return int(row["storage_bytes"] or 0)

        # SQLite is an explicit local fallback. Include WAL files because
        # recent message writes can reside there before a checkpoint.
        db_path = get_settings().db_path
        paths = (db_path, db_path.with_name(f"{db_path.name}-wal"), db_path.with_name(f"{db_path.name}-shm"))
        return sum(path.stat().st_size for path in paths if path.exists())

    def _session_count_sync(self) -> int:
        with get_conn() as conn:
            row = conn.execute("SELECT COUNT(*) AS session_count FROM assistant_sessions").fetchone()
        return int(row["session_count"])

    def _get_storage_usage_sync(self) -> Dict[str, Any]:
        return {
            "storage_bytes": self._session_storage_bytes_sync(),
            "session_count": self._session_count_sync(),
            "database_backend": get_database_provider().name,
        }
