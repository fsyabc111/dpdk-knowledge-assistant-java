"""grading_submissions 表的读写（docs/archive/2026-09-18/agent_upgrade_plan.md §6，D12）。

只存最小集：学生/会话/协议/分数/用例结果 JSON/代码 sha256，不存源码全文。
评测配额计数在 rate_limit.py（usage_counters kind='grading'），本模块只管成绩记录。
"""
from __future__ import annotations

import asyncio
import json
import uuid
from collections import Counter
from typing import Any, Dict, List, Optional

from ..db import get_conn, get_database_provider
from .session_service import now_iso
from .user_service import lock_enabled_account

# “近期 FAIL 次数”统计的回看窗口（§4.3 升级条件的 M1 简化实现）。
FAIL_SAMPLE_SIZE = 10


class GradingService:
    @property
    def _ph(self) -> str:
        # Resolve lazily so backend flips during tests still take effect.
        return get_database_provider().placeholder

    async def record_submission(
        self,
        *,
        user_id: str,
        session_id: Optional[str],
        protocol: str,
        code_hash: str,
        score: int,
        passed: int,
        total: int,
        results: List[Dict[str, Any]],
        source_origin: str = "pasted",
        assignment_id: Optional[str] = None,
        require_enabled: bool = False,
        accepted_assignment: Optional[Dict[str, Any]] = None,
    ) -> str:
        """Persist one grading outcome and return its ``grade_*`` id.

        assignment_id 仅作业提交路径传入；聊天路径（grade_plugin 工具）不传，
        落 NULL，天然不计入作业成绩（脱钩原则，无外键）。
        """
        return await asyncio.to_thread(
            self._record_submission_sync,
            user_id, session_id, protocol, code_hash, score, passed, total, results,
            source_origin, assignment_id, require_enabled, accepted_assignment,
        )

    def _record_submission_sync(
        self,
        user_id: str,
        session_id: Optional[str],
        protocol: str,
        code_hash: str,
        score: int,
        passed: int,
        total: int,
        results: List[Dict[str, Any]],
        source_origin: str,
        assignment_id: Optional[str],
        require_enabled: bool,
        accepted_assignment: Optional[Dict[str, Any]],
    ) -> str:
        with get_conn() as conn:
            if require_enabled and not self._lock_enabled_user(conn, user_id):
                raise PermissionError("账号已停用，本次评测不记成绩")
            return self._insert_submission_in_conn(
                conn, user_id, session_id, protocol, code_hash, score, passed, total,
                results, source_origin, assignment_id, accepted_assignment=accepted_assignment,
            )

    def _lock_enabled_user(self, conn, user_id: str) -> bool:
        """与停号事务争用同一 users 行；先提交者决定这次成绩能否落库。"""
        return lock_enabled_account(conn, user_id)

    def _insert_submission_in_conn(
        self, conn, user_id, session_id, protocol, code_hash, score, passed, total,
        results, source_origin, assignment_id, *, task_job_id=None, accepted_assignment=None,
    ) -> str:
        submission_id = f"grade_{uuid.uuid4().hex[:16]}"
        ph = self._ph
        conn.execute(
            f"INSERT INTO grading_submissions"
            f"(id,user_id,session_id,protocol,code_hash,score,passed,total,results_json,"
            f"assignment_id,task_job_id,created_at) VALUES ({','.join([ph] * 12)})",
            (submission_id, user_id, session_id, protocol, code_hash, score, passed, total,
             json.dumps({"source_origin": source_origin, "results": results,
                         **({"accepted_assignment": accepted_assignment} if accepted_assignment is not None else {})},
                        ensure_ascii=False),
             assignment_id, task_job_id, now_iso()),
        )
        return submission_id

    async def record_task_job_submission(
        self, *, task_id: str, job_id: str, user_id: str, session_id: str,
        protocol: str, code_hash: str, score: int, passed: int, total: int,
        results: List[Dict[str, Any]], source_origin: str,
    ) -> str:
        """返回 persisted / legacy_unlinked / cancelled；插入与账本标志同事务。"""
        return await asyncio.to_thread(
            self._record_task_job_submission_sync, task_id, job_id, user_id, session_id,
            protocol, code_hash, score, passed, total, results, source_origin,
        )

    def _record_task_job_submission_sync(
        self, task_id, job_id, user_id, session_id, protocol, code_hash,
        score, passed, total, results, source_origin,
    ) -> str:
        ph = self._ph
        with get_conn() as conn:
            if not self._lock_enabled_user(conn, user_id):
                return "cancelled"
            task = conn.execute(
                f"UPDATE rag_requests SET status=status WHERE id={ph} AND user_id={ph} "
                "AND status='pending' RETURNING id", (task_id, user_id),
            ).fetchone()
            if not task:
                return "cancelled"
            job = conn.execute(
                f"UPDATE rag_task_jobs SET updated_at=updated_at WHERE task_id={ph} AND job_id={ph} "
                "RETURNING submission_done", (task_id, job_id),
            ).fetchone()
            if not job:
                raise RuntimeError("评测作业账本缺行，拒绝无账本记成绩")
            existing = conn.execute(
                f"SELECT id FROM grading_submissions WHERE task_job_id={ph}", (job_id,),
            ).fetchone()
            if existing:
                return "persisted"
            if job["submission_done"]:
                # 0014 以前的成绩没有 job 身份，不能靠协议/hash 猜测补写。
                return "legacy_unlinked"
            self._insert_submission_in_conn(
                conn, user_id, session_id, protocol, code_hash, score, passed, total,
                results, source_origin, None, task_job_id=job_id,
            )
            conn.execute(
                f"UPDATE rag_task_jobs SET submission_done=1,updated_at={ph} WHERE job_id={ph}",
                (now_iso(), job_id),
            )
        return "persisted"

    async def get_submission(self, submission_id: str) -> Optional[Dict[str, Any]]:
        """按 id 取整行；results_json 反序列化出 ``results`` 列表字段返回。

        S3-d「结果讲解」按提交 id 关联成绩行。归属/作业匹配的判定在路由层
        （不存在与越权同口径 404），本方法只做忠实读取；JSON 损坏时 results
        退化为空列表而不是抛错（读取路径不引入新的失败模式）。
        """
        return await asyncio.to_thread(self._get_submission_sync, submission_id)

    def _get_submission_sync(self, submission_id: str) -> Optional[Dict[str, Any]]:
        ph = self._ph
        with get_conn() as conn:
            row = conn.execute(
                f"SELECT id, user_id, session_id, protocol, code_hash, score, passed, total,"
                f" results_json, assignment_id, created_at FROM grading_submissions"
                f" WHERE id = {ph}",
                (submission_id,),
            ).fetchone()
        if row is None:
            return None
        # results_json 包一层 {"source_origin", "results"}；PG JSONB 可能直接
        # 返回 dict，SQLite 是 TEXT，两种形态都兼容。
        payload = row["results_json"]
        if isinstance(payload, str):
            try:
                payload = json.loads(payload)
            except (json.JSONDecodeError, TypeError):
                payload = {}
        if not isinstance(payload, dict):
            payload = {}
        return {
            "id": str(row["id"]),
            "user_id": str(row["user_id"]),
            "session_id": row["session_id"],
            "protocol": str(row["protocol"]),
            "code_hash": str(row["code_hash"]),
            "score": int(row["score"]),
            "passed": int(row["passed"]),
            "total": int(row["total"]),
            "results": payload.get("results") or [],
            "source_origin": payload.get("source_origin"),
            "accepted_assignment": payload.get("accepted_assignment"),
            "assignment_id": row["assignment_id"],
            "created_at": str(row["created_at"]),
        }

    async def list_submissions(
        self,
        *,
        user_id: Optional[str] = None,
        protocol: Optional[str] = None,
        limit: int = 200,
        offset: int = 0,
        user_ids: Optional[List[str]] = None,
        assignment_id: Optional[str] = None,
    ) -> List[Dict[str, Any]]:
        """List submissions newest-first for the teacher view (M3).

        明细只返回最小字段集（不含 results_json/code_hash），过滤条件
        user_id/protocol 可叠加；ORDER BY 加 id DESC 兜底同时间戳的稳定序。
        user_ids 为树隔离范围（老师=自己学生）；None 表示不限。
        assignment_id 为作业成绩的等值过滤（作业管理）。
        """
        return await asyncio.to_thread(
            self._list_submissions_sync, user_id, protocol, limit, offset, user_ids, assignment_id
        )

    def _list_submissions_sync(
        self,
        user_id: Optional[str],
        protocol: Optional[str],
        limit: int,
        offset: int,
        user_ids: Optional[List[str]] = None,
        assignment_id: Optional[str] = None,
    ) -> List[Dict[str, Any]]:
        if user_ids is not None and not user_ids:
            return []
        ph = self._ph
        clauses: List[str] = []
        params: List[Any] = []
        if user_id is not None:
            clauses.append(f"user_id = {ph}")
            params.append(user_id)
        if protocol is not None:
            clauses.append(f"protocol = {ph}")
            params.append(protocol)
        if assignment_id is not None:
            clauses.append(f"assignment_id = {ph}")
            params.append(assignment_id)
        if user_ids is not None:
            marks = ", ".join([ph] * len(user_ids))
            clauses.append(f"user_id IN ({marks})")
            params.extend(user_ids)
        where = f" WHERE {' AND '.join(clauses)}" if clauses else ""
        params.extend([limit, offset])
        with get_conn() as conn:
            rows = conn.execute(
                f"SELECT id, user_id, protocol, score, passed, total, created_at "
                f"FROM grading_submissions{where} "
                f"ORDER BY created_at DESC, id DESC LIMIT {ph} OFFSET {ph}",
                tuple(params),
            ).fetchall()
        return [
            {
                "id": str(row["id"]),
                "user_id": str(row["user_id"]),
                "protocol": str(row["protocol"]),
                "score": int(row["score"]),
                "passed": int(row["passed"]),
                "total": int(row["total"]),
                "created_at": str(row["created_at"]),
            }
            for row in rows
        ]

    async def aggregate_by_user(self, user_ids: Optional[List[str]] = None) -> List[Dict[str, Any]]:
        """Per-student rollup for the teacher view: 提交数/平均分/最高分/最近提交时间。

        user_ids 为树隔离范围（老师=自己学生）；None 表示不限。
        """
        return await asyncio.to_thread(self._aggregate_by_user_sync, user_ids)

    def _aggregate_by_user_sync(self, user_ids: Optional[List[str]] = None) -> List[Dict[str, Any]]:
        if user_ids is not None and not user_ids:
            return []
        ph = self._ph
        where = ""
        params: tuple = ()
        if user_ids is not None:
            marks = ", ".join([ph] * len(user_ids))
            where = f" WHERE user_id IN ({marks})"
            params = tuple(user_ids)
        with get_conn() as conn:
            rows = conn.execute(
                "SELECT user_id, COUNT(*) AS submission_count, AVG(score) AS avg_score, "
                "MAX(score) AS best_score, MAX(created_at) AS last_submitted_at "
                f"FROM grading_submissions{where} GROUP BY user_id ORDER BY user_id",
                params,
            ).fetchall()
        return [
            {
                "user_id": str(row["user_id"]),
                "submission_count": int(row["submission_count"]),
                "avg_score": float(row["avg_score"]),
                "best_score": int(row["best_score"]),
                "last_submitted_at": str(row["last_submitted_at"]),
            }
            for row in rows
        ]

    async def aggregate_by_protocol(self, user_ids: Optional[List[str]] = None) -> List[Dict[str, Any]]:
        """Per-protocol rollup: 提交数/平均分/全量用例 passed/total 聚合/去重学生数。

        user_ids 为树隔离范围（老师=自己学生）；None 表示不限。
        """
        return await asyncio.to_thread(self._aggregate_by_protocol_sync, user_ids)

    def _aggregate_by_protocol_sync(self, user_ids: Optional[List[str]] = None) -> List[Dict[str, Any]]:
        if user_ids is not None and not user_ids:
            return []
        ph = self._ph
        where = ""
        params: tuple = ()
        if user_ids is not None:
            marks = ", ".join([ph] * len(user_ids))
            where = f" WHERE user_id IN ({marks})"
            params = tuple(user_ids)
        with get_conn() as conn:
            rows = conn.execute(
                "SELECT protocol, COUNT(*) AS submission_count, AVG(score) AS avg_score, "
                "SUM(passed) AS passed_total, SUM(total) AS total_total, "
                "COUNT(DISTINCT user_id) AS student_count "
                f"FROM grading_submissions{where} GROUP BY protocol ORDER BY protocol",
                params,
            ).fetchall()
        return [
            {
                "protocol": str(row["protocol"]),
                "submission_count": int(row["submission_count"]),
                "avg_score": float(row["avg_score"]),
                "passed_total": int(row["passed_total"]),
                "total_total": int(row["total_total"]),
                "student_count": int(row["student_count"]),
            }
            for row in rows
        ]

    async def recent_fail_count(self, user_id: str, protocol: str, sample: int = FAIL_SAMPLE_SIZE) -> int:
        """Count failed submissions among the user's most recent ones for a protocol.

        Drives the §4.3 feedback-escalation condition: reaching
        ``feedback_escalation_threshold`` failed attempts unlocks
        location-level advice in the system prompt.
        """
        return await asyncio.to_thread(self._recent_fail_count_sync, user_id, protocol, sample)

    # 学习者画像（M4a E8）：采样最近 N 条提交在 Python 侧聚合（高频失败用例名
    # 埋在 results JSON 里，SQL 聚合不划算），输出总预算 PROFILE_MAX_CHARS。
    PROFILE_SAMPLE_SIZE = 50
    PROFILE_MAX_CHARS = 300

    async def learner_profile(self, user_id: str) -> str:
        """各协议尝试次数/最高分/高频失败用例名（每协议至多 2 个），分号连接。
        无提交记录返回空串（调用方据此跳过注入）。"""
        return await asyncio.to_thread(self._learner_profile_sync, user_id)

    def _learner_profile_sync(self, user_id: str) -> str:
        ph = self._ph
        with get_conn() as conn:
            rows = conn.execute(
                f"SELECT protocol, score, results_json FROM grading_submissions "
                f"WHERE user_id = {ph} ORDER BY created_at DESC, id DESC LIMIT {ph}",
                (user_id, self.PROFILE_SAMPLE_SIZE),
            ).fetchall()
        if not rows:
            return ""
        stats: Dict[str, Dict[str, Any]] = {}
        for row in rows:
            entry = stats.setdefault(str(row["protocol"]), {"attempts": 0, "best": 0, "fails": Counter()})
            entry["attempts"] += 1
            entry["best"] = max(entry["best"], int(row["score"]))
            try:
                results = (json.loads(row["results_json"] or "{}") or {}).get("results") or []
            except (json.JSONDecodeError, TypeError):
                results = []
            for case in results:
                if isinstance(case, dict) and case.get("result") != "PASS" and case.get("name"):
                    entry["fails"][str(case["name"])] += 1
        parts: List[str] = []
        for protocol in sorted(stats):
            entry = stats[protocol]
            text = f"{protocol}：尝试{entry['attempts']}次、最高{entry['best']}分"
            top_fails = [name for name, _ in entry["fails"].most_common(2)]
            if top_fails:
                text += "、常挂用例：" + "、".join(top_fails)
            parts.append(text)
        return "；".join(parts)[: self.PROFILE_MAX_CHARS]

    def _recent_fail_count_sync(self, user_id: str, protocol: str, sample: int) -> int:
        ph = self._ph
        with get_conn() as conn:
            rows = conn.execute(
                f"SELECT passed, total FROM grading_submissions "
                f"WHERE user_id = {ph} AND protocol = {ph} "
                f"ORDER BY created_at DESC, id DESC LIMIT {ph}",
                (user_id, protocol, sample),
            ).fetchall()
        return sum(1 for row in rows if int(row["passed"]) < int(row["total"]))
