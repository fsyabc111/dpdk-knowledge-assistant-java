"""作业管理：assignments / assignment_items 表的读写（alembic 0007）。

模式完全参照 grading_service.py：公开 async 方法 + ``_xxx_sync`` 外包
``asyncio.to_thread``；占位符经 ``_ph`` 惰性解析，dialect 无关；连接走
``get_conn()``。

关键语义：
- scope 由服务端按发布者角色写入（leader→'all' 全体可见，admin→'class'
  仅自己 created_by 名下学生可见），客户端不可指定；
- 成绩取自 grading_submissions（assignment_id 等值），按 (user_id, protocol)
  取 MAX(score)，再按协议分值折算 earned = round(best * points / 100, 1)；
- 学生视图的协议项：无任何提交记录时 earned=None（S4-b W4，decisions §9
  冻结口径——前端对未提交协议统一显示「未提交」），有提交（含 0 分）才
  是数值；total_earned 聚合时 None 按 0 计；
- 「撤回」只翻 status，不删数据，成绩保留、可恢复。
"""
from __future__ import annotations

import asyncio
import uuid
from datetime import datetime, timezone
from typing import Any, Dict, List, Optional

from ..db import get_conn, get_database_provider
from .session_service import now_iso

# status 取值：active=学生可见可提交；withdrawn=学生不可见不可提交，成绩保留。
VALID_STATUS = {"active", "withdrawn"}


def _iso(value: Any) -> str:
    """Normalize a TIMESTAMPTZ (datetime) / TEXT (ISO string) column value."""
    if isinstance(value, datetime):
        return (value if value.tzinfo else value.replace(tzinfo=timezone.utc)).isoformat()
    return str(value)


def _parse_ts(value: Any) -> datetime:
    """Parse a stored deadline back to an aware datetime (naive 按 UTC 处理)。"""
    if isinstance(value, datetime):
        return value if value.tzinfo else value.replace(tzinfo=timezone.utc)
    parsed = datetime.fromisoformat(str(value))
    return parsed if parsed.tzinfo else parsed.replace(tzinfo=timezone.utc)


def _normalize_deadline(value: datetime) -> str:
    """入库前统一为 UTC ISO 文本（naive 输入按 UTC 处理）。"""
    if value.tzinfo is None:
        value = value.replace(tzinfo=timezone.utc)
    return value.astimezone(timezone.utc).isoformat()


def calc_earned(best_score: Optional[float], points: int) -> float:
    """按通过比例折算：earned = round(best_score * points / 100, 1)。"""
    if best_score is None:
        return 0.0
    return round(float(best_score) * points / 100, 1)


class AssignmentService:
    @property
    def _ph(self) -> str:
        # Resolve lazily so backend flips during tests still take effect.
        return get_database_provider().placeholder

    # ------------------------------------------------------------------
    # 行装配
    # ------------------------------------------------------------------

    @staticmethod
    def _assignment_row(row: Any, items: List[Dict[str, Any]]) -> Dict[str, Any]:
        return {
            "id": str(row["id"]),
            "title": str(row["title"]),
            "description": str(row["description"]),
            "created_by": str(row["created_by"]),
            "scope": str(row["scope"]),
            "deadline": _iso(row["deadline"]),
            "status": str(row["status"]),
            "created_at": _iso(row["created_at"]),
            "updated_at": _iso(row["updated_at"]),
            "items": items,
        }

    def _load_items(self, conn: Any, assignment_ids: List[str]) -> Dict[str, List[Dict[str, Any]]]:
        if not assignment_ids:
            return {}
        ph = self._ph
        marks = ", ".join([ph] * len(assignment_ids))
        rows = conn.execute(
            f"SELECT assignment_id, protocol, points FROM assignment_items "
            f"WHERE assignment_id IN ({marks}) ORDER BY protocol",
            tuple(assignment_ids),
        ).fetchall()
        items: Dict[str, List[Dict[str, Any]]] = {}
        for row in rows:
            items.setdefault(str(row["assignment_id"]), []).append(
                {"protocol": str(row["protocol"]), "points": int(row["points"])}
            )
        return items

    # ------------------------------------------------------------------
    # 创建 / 修改 / 状态
    # ------------------------------------------------------------------

    async def create_assignment(
        self,
        *,
        actor_id: str,
        actor_roles: List[str],
        title: str,
        description: str,
        deadline: datetime,
        items: List[Dict[str, Any]],
    ) -> Dict[str, Any]:
        return await asyncio.to_thread(
            self._create_assignment_sync,
            actor_id, actor_roles, title, description, deadline, items,
        )

    def _create_assignment_sync(
        self,
        actor_id: str,
        actor_roles: List[str],
        title: str,
        description: str,
        deadline: datetime,
        items: List[Dict[str, Any]],
    ) -> Dict[str, Any]:
        # scope 服务端按角色写入，客户端不可指定。
        scope = "all" if "leader" in actor_roles else "class"
        assignment_id = f"asg_{uuid.uuid4().hex[:16]}"
        now = now_iso()
        deadline_iso = _normalize_deadline(deadline)
        ph = self._ph
        with get_conn() as conn:
            conn.execute(
                f"INSERT INTO assignments"
                f"(id, title, description, created_by, scope, deadline, status, created_at, updated_at)"
                f" VALUES ({ph}, {ph}, {ph}, {ph}, {ph}, {ph}, 'active', {ph}, {ph})",
                (assignment_id, title, description, actor_id, scope, deadline_iso, now, now),
            )
            self._replace_items_sync(conn, assignment_id, items)
        return self._get_assignment_sync(assignment_id)

    def _replace_items_sync(self, conn: Any, assignment_id: str, items: List[Dict[str, Any]]) -> None:
        ph = self._ph
        conn.execute(f"DELETE FROM assignment_items WHERE assignment_id = {ph}", (assignment_id,))
        for item in items:
            conn.execute(
                f"INSERT INTO assignment_items(id, assignment_id, protocol, points)"
                f" VALUES ({ph}, {ph}, {ph}, {ph})",
                (f"item_{uuid.uuid4().hex[:16]}", assignment_id, str(item["protocol"]), int(item["points"])),
            )

    async def update_assignment(
        self,
        assignment_id: str,
        *,
        actor_id: str,
        actor_roles: List[str],
        title: Optional[str] = None,
        description: Optional[str] = None,
        deadline: Optional[datetime] = None,
        items: Optional[List[Dict[str, Any]]] = None,
    ) -> Optional[Dict[str, Any]]:
        """修改作业；None 表示作业不存在，PermissionError 表示非创建者/组长。"""
        return await asyncio.to_thread(
            self._update_assignment_sync,
            assignment_id, actor_id, actor_roles, title, description, deadline, items,
        )

    def _update_assignment_sync(
        self,
        assignment_id: str,
        actor_id: str,
        actor_roles: List[str],
        title: Optional[str],
        description: Optional[str],
        deadline: Optional[datetime],
        items: Optional[List[Dict[str, Any]]],
    ) -> Optional[Dict[str, Any]]:
        ph = self._ph
        with get_conn() as conn:
            row = conn.execute(
                f"SELECT created_by FROM assignments WHERE id = {ph}", (assignment_id,)
            ).fetchone()
            if row is None:
                return None
            self._check_manage_permission(row["created_by"], actor_id, actor_roles)
            sets: List[str] = []
            params: List[Any] = []
            if title is not None:
                sets.append(f"title = {ph}")
                params.append(title)
            if description is not None:
                sets.append(f"description = {ph}")
                params.append(description)
            if deadline is not None:
                sets.append(f"deadline = {ph}")
                params.append(_normalize_deadline(deadline))
            sets.append(f"updated_at = {ph}")
            params.append(now_iso())
            params.append(assignment_id)
            conn.execute(
                f"UPDATE assignments SET {', '.join(sets)} WHERE id = {ph}",
                tuple(params),
            )
            if items is not None:
                # 协议项整体替换；成绩按当前协议项动态折算，提交记录不动。
                self._replace_items_sync(conn, assignment_id, items)
        return self._get_assignment_sync(assignment_id)

    async def set_status(
        self,
        assignment_id: str,
        status: str,
        *,
        actor_id: str,
        actor_roles: List[str],
    ) -> Optional[Dict[str, Any]]:
        """撤回 / 恢复；None 表示不存在，PermissionError 表示非创建者/组长。"""
        return await asyncio.to_thread(
            self._set_status_sync, assignment_id, status, actor_id, actor_roles
        )

    def _set_status_sync(
        self, assignment_id: str, status: str, actor_id: str, actor_roles: List[str]
    ) -> Optional[Dict[str, Any]]:
        if status not in VALID_STATUS:
            raise ValueError(f"invalid status: {status}")
        ph = self._ph
        with get_conn() as conn:
            row = conn.execute(
                f"SELECT created_by FROM assignments WHERE id = {ph}", (assignment_id,)
            ).fetchone()
            if row is None:
                return None
            self._check_manage_permission(row["created_by"], actor_id, actor_roles)
            conn.execute(
                f"UPDATE assignments SET status = {ph}, updated_at = {ph} WHERE id = {ph}",
                (status, now_iso(), assignment_id),
            )
        return self._get_assignment_sync(assignment_id)

    @staticmethod
    def _check_manage_permission(created_by: str, actor_id: str, actor_roles: List[str]) -> None:
        # 作业修改权限 = 创建者本人或 leader。
        if actor_id != created_by and "leader" not in actor_roles:
            raise PermissionError("只有作业创建者或组长可以修改")

    # ------------------------------------------------------------------
    # 查询
    # ------------------------------------------------------------------

    async def get_assignment(self, assignment_id: str) -> Optional[Dict[str, Any]]:
        return await asyncio.to_thread(self._get_assignment_sync, assignment_id)

    def _get_assignment_sync(self, assignment_id: str) -> Optional[Dict[str, Any]]:
        ph = self._ph
        with get_conn() as conn:
            row = conn.execute(
                f"SELECT * FROM assignments WHERE id = {ph}", (assignment_id,)
            ).fetchone()
            if row is None:
                return None
            items = self._load_items(conn, [assignment_id]).get(assignment_id, [])
        return self._assignment_row(row, items)

    async def list_for_manager(self, actor_id: str, actor_roles: List[str]) -> List[Dict[str, Any]]:
        """admin→自己创建的；leader/operator→全部；各带 items 与提交统计。"""
        return await asyncio.to_thread(self._list_for_manager_sync, actor_id, actor_roles)

    def _list_for_manager_sync(self, actor_id: str, actor_roles: List[str]) -> List[Dict[str, Any]]:
        ph = self._ph
        where = ""
        params: tuple = ()
        if "leader" not in actor_roles and "operator" not in actor_roles:
            where = f" WHERE created_by = {ph}"
            params = (actor_id,)
        with get_conn() as conn:
            rows = conn.execute(
                f"SELECT * FROM assignments{where} ORDER BY created_at DESC, id DESC", params
            ).fetchall()
            ids = [str(row["id"]) for row in rows]
            items_map = self._load_items(conn, ids)
            stats = self._submission_stats_sync(conn, ids)
        return [
            {
                **self._assignment_row(row, items_map.get(str(row["id"]), [])),
                "submission_count": stats.get(str(row["id"]), (0, 0))[0],
                "student_count": stats.get(str(row["id"]), (0, 0))[1],
            }
            for row in rows
        ]

    def _submission_stats_sync(
        self, conn: Any, assignment_ids: List[str]
    ) -> Dict[str, tuple]:
        """每个作业的 (提交总数, 去重学生数)。"""
        if not assignment_ids:
            return {}
        ph = self._ph
        marks = ", ".join([ph] * len(assignment_ids))
        rows = conn.execute(
            f"SELECT assignment_id, COUNT(*) AS cnt, COUNT(DISTINCT user_id) AS students "
            f"FROM grading_submissions WHERE assignment_id IN ({marks}) GROUP BY assignment_id",
            tuple(assignment_ids),
        ).fetchall()
        return {str(row["assignment_id"]): (int(row["cnt"]), int(row["students"])) for row in rows}

    async def list_for_student(
        self, student_id: str, student_created_by: Optional[str]
    ) -> List[Dict[str, Any]]:
        """学生可见作业：active 且（scope='all' 或创建者是自己的老师）。

        未分配学生（created_by NULL）只见 scope='all'。每个作业附带逐协议的
        我的最高分/折算得分/提交次数与总分。
        """
        return await asyncio.to_thread(self._list_for_student_sync, student_id, student_created_by)

    def _list_for_student_sync(
        self, student_id: str, student_created_by: Optional[str]
    ) -> List[Dict[str, Any]]:
        ph = self._ph
        if student_created_by:
            where = f"status = 'active' AND (scope = 'all' OR created_by = {ph})"
            params: tuple = (student_created_by,)
        else:
            where = "status = 'active' AND scope = 'all'"
            params = ()
        with get_conn() as conn:
            rows = conn.execute(
                f"SELECT * FROM assignments WHERE {where} ORDER BY deadline ASC, id ASC", params
            ).fetchall()
            ids = [str(row["id"]) for row in rows]
            items_map = self._load_items(conn, ids)
            mine = self._my_best_sync(conn, ids, student_id)
        return [
            self._student_view(self._assignment_row(row, items_map.get(str(row["id"]), [])), mine)
            for row in rows
        ]

    async def get_visible_assignment(
        self, assignment_id: str, student_id: str, student_created_by: Optional[str]
    ) -> Optional[Dict[str, Any]]:
        """提交与详情共用的可见性判定；不可见返回 None（路由 404）。"""
        return await asyncio.to_thread(
            self._get_visible_assignment_sync, assignment_id, student_id, student_created_by
        )

    async def submission_snapshot(
        self, assignment_id: str, student_created_by: Optional[str], protocol: str,
    ) -> Optional[Dict[str, Any]]:
        """单条 SQL 读取受理时作业与目标协议项，避免 PATCH 夹在两次 SELECT 中。"""
        return await asyncio.to_thread(
            self._submission_snapshot_sync, assignment_id, student_created_by, protocol,
        )

    def _submission_snapshot_sync(
        self, assignment_id: str, student_created_by: Optional[str], protocol: str,
    ) -> Optional[Dict[str, Any]]:
        ph = self._ph
        with get_conn() as conn:
            row = conn.execute(
                f"SELECT a.id,a.title,a.created_by,a.scope,a.deadline,a.status,a.updated_at,"
                f"i.points FROM assignments a LEFT JOIN assignment_items i "
                f"ON i.assignment_id=a.id AND i.protocol={ph} WHERE a.id={ph}",
                (protocol, assignment_id),
            ).fetchone()
        if row is None or row["status"] != "active":
            return None
        if row["scope"] != "all" and row["created_by"] != student_created_by:
            return None
        return {
            "id": str(row["id"]), "title": str(row["title"]),
            "created_by": str(row["created_by"]), "scope": str(row["scope"]),
            "deadline": _iso(row["deadline"]), "updated_at": _iso(row["updated_at"]),
            "points": int(row["points"]) if row["points"] is not None else None,
        }

    def _get_visible_assignment_sync(
        self, assignment_id: str, student_id: str, student_created_by: Optional[str]
    ) -> Optional[Dict[str, Any]]:
        assignment = self._get_assignment_sync(assignment_id)
        if assignment is None or assignment["status"] != "active":
            return None
        if assignment["scope"] != "all" and assignment["created_by"] != student_created_by:
            return None
        with get_conn() as conn:
            mine = self._my_best_sync(conn, [assignment_id], student_id)
        return self._student_view(assignment, mine)

    def _my_best_sync(
        self, conn: Any, assignment_ids: List[str], student_id: str
    ) -> Dict[str, Dict[str, Dict[str, Any]]]:
        """assignment_id -> protocol -> {best_score, submission_count}（当前学生）。"""
        if not assignment_ids:
            return {}
        ph = self._ph
        marks = ", ".join([ph] * len(assignment_ids))
        rows = conn.execute(
            f"SELECT assignment_id, protocol, MAX(score) AS best_score, COUNT(*) AS cnt "
            f"FROM grading_submissions WHERE assignment_id IN ({marks}) AND user_id = {ph} "
            f"GROUP BY assignment_id, protocol",
            (*assignment_ids, student_id),
        ).fetchall()
        result: Dict[str, Dict[str, Dict[str, Any]]] = {}
        for row in rows:
            result.setdefault(str(row["assignment_id"]), {})[str(row["protocol"])] = {
                "best_score": int(row["best_score"]),
                "submission_count": int(row["cnt"]),
            }
        return result

    @staticmethod
    def _student_view(
        assignment: Dict[str, Any], mine: Dict[str, Dict[str, Dict[str, Any]]]
    ) -> Dict[str, Any]:
        my_protocols = mine.get(assignment["id"], {})
        items = []
        total_earned = 0.0
        submission_count = 0
        for item in assignment["items"]:
            stats = my_protocols.get(item["protocol"])
            best = stats["best_score"] if stats else None
            # W4：无提交记录的协议 earned=None（区别于「提交了但 0 分」的 0.0），
            # 前端据此统一显示「未提交」；total_earned 聚合 None 按 0 计。
            earned = calc_earned(best, item["points"]) if stats else None
            count = stats["submission_count"] if stats else 0
            total_earned += earned if earned is not None else 0.0
            submission_count += count
            items.append({
                "protocol": item["protocol"],
                "points": item["points"],
                "best_score": best,
                "earned": earned,
                "submission_count": count,
            })
        return {
            **assignment,
            "items": items,
            "total_points": sum(item["points"] for item in assignment["items"]),
            "total_earned": round(total_earned, 1),
            "submission_count": submission_count,
        }

    # ------------------------------------------------------------------
    # 成绩
    # ------------------------------------------------------------------

    async def grades_for_assignment(self, assignment_id: str) -> List[Dict[str, Any]]:
        """按学生汇总作业成绩（路由层再做树隔离过滤）。

        每协议取 MAX(score) 折算 earned；汇总总分、提交次数、最近提交时间。
        """
        return await asyncio.to_thread(self._grades_for_assignment_sync, assignment_id)

    def _grades_for_assignment_sync(self, assignment_id: str) -> List[Dict[str, Any]]:
        assignment = self._get_assignment_sync(assignment_id)
        if assignment is None:
            return []
        points_map = {item["protocol"]: item["points"] for item in assignment["items"]}
        ph = self._ph
        with get_conn() as conn:
            rows = conn.execute(
                f"SELECT user_id, protocol, MAX(score) AS best_score, COUNT(*) AS cnt, "
                f"MAX(created_at) AS last_at FROM grading_submissions "
                f"WHERE assignment_id = {ph} GROUP BY user_id, protocol",
                (assignment_id,),
            ).fetchall()
        per_user: Dict[str, Dict[str, Any]] = {}
        for row in rows:
            user_id = str(row["user_id"])
            entry = per_user.setdefault(user_id, {"scores": {}, "submission_count": 0, "last_at": None})
            protocol = str(row["protocol"])
            if protocol in points_map:
                entry["scores"][protocol] = calc_earned(int(row["best_score"]), points_map[protocol])
            entry["submission_count"] += int(row["cnt"])
            last_at = _iso(row["last_at"])
            if entry["last_at"] is None or last_at > entry["last_at"]:
                entry["last_at"] = last_at
        return [
            {
                "user_id": user_id,
                "scores": entry["scores"],
                "total_earned": round(sum(entry["scores"].values()), 1),
                "submission_count": entry["submission_count"],
                "last_submitted_at": entry["last_at"],
            }
            for user_id, entry in sorted(per_user.items())
        ]

    async def best_scores(
        self, assignment_id: str, user_id: str, protocol: str
    ) -> Optional[int]:
        """该学生在此作业此协议上的历史最高分（无提交返回 None）。"""
        return await asyncio.to_thread(self._best_score_sync, assignment_id, user_id, protocol)

    def _best_score_sync(self, assignment_id: str, user_id: str, protocol: str) -> Optional[int]:
        ph = self._ph
        with get_conn() as conn:
            row = conn.execute(
                f"SELECT MAX(score) AS best FROM grading_submissions "
                f"WHERE assignment_id = {ph} AND user_id = {ph} AND protocol = {ph}",
                (assignment_id, user_id, protocol),
            ).fetchone()
        return int(row["best"]) if row and row["best"] is not None else None

    async def class_teachers(self, user_ids: List[str]) -> Dict[str, Optional[str]]:
        """批量查学生的班级归属（users.created_by）；账号已删的不出现在结果里。"""
        return await asyncio.to_thread(self._class_teachers_sync, user_ids)

    def _class_teachers_sync(self, user_ids: List[str]) -> Dict[str, Optional[str]]:
        if not user_ids:
            return {}
        ph = self._ph
        marks = ", ".join([ph] * len(user_ids))
        with get_conn() as conn:
            rows = conn.execute(
                f"SELECT id, created_by FROM users WHERE id IN ({marks})", tuple(user_ids)
            ).fetchall()
        return {str(row["id"]): row["created_by"] for row in rows}

    def is_past_deadline(self, assignment: Dict[str, Any]) -> bool:
        return datetime.now(timezone.utc) > _parse_ts(assignment["deadline"])
