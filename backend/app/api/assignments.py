"""作业管理路由（/api/assignments，alembic 0007）。

守卫口径与 grading.py 一致：AUTH_MODE != "account" 时整组端点 404（隐藏
存在性）；管理端点角色∩{admin, leader}，成绩/导出端点角色∩{operator,
admin, leader}，学生端点登录即可。树隔离复用 grading.py 的 _scope_user_ids
同款逻辑：老师（admin）只看自己 created_by 名下学生，其余角色/静态 token
不受限。

计分：harness 返回百分制 score，作业得分 = round(score * points / 100, 1)；
截止前可多次提交取最高分。提交落 grading_submissions.assignment_id（聊天
路径落 NULL，天然不计入）。
"""
from __future__ import annotations

import asyncio
import csv
import hashlib
import io
import json
import re
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Dict, List, Optional, Tuple

from fastapi import APIRouter, Depends, HTTPException, Request, status
from fastapi.responses import StreamingResponse
from pydantic import ValidationError

from ..config import get_settings
from ..core.auth import Principal, parse_tokens, require_auth
from ..core.rate_limit import rate_limiter
from ..db import get_conn, get_database_provider
from ..schemas.assignments import (
    AssignmentCreateRequest,
    AssignmentGradeRow,
    AssignmentGradesResponse,
    AssignmentInfo,
    AssignmentProtocol,
    AssignmentProtocolsResponse,
    AssignmentSubmitRequest,
    AssignmentSubmitResponse,
    AssignmentUpdateRequest,
    ExplainResponse,
    ManagerAssignment,
    ManagerAssignmentListResponse,
    StudentAssignment,
    StudentAssignmentListResponse,
)
from ..schemas.chat import ChatRequest
from ..services import harness_grading
from ..services.assignment_service import AssignmentService, calc_earned
from ..services.grading_service import GradingService
from ..services.rag_product_service import (
    RagProductService,
    build_explain_context,
    explain_request_identity,
)
from ..services.session_service import now_iso
from ..services.user_service import UserService
from .chat import start_product

router = APIRouter(prefix="/api/assignments", tags=["assignments"])

# 管理端点（发布/修改/撤回）角色白名单；operator 只有成绩查看与导出。
MANAGER_ROLES = {"admin", "leader"}
GRADES_VIEWER_ROLES = {"operator", "admin", "leader"}

ALLOWED_UPLOAD_SUFFIXES = {".c", ".so"}

_UNASSIGNED_CLASS = "未分配"


def _require_account_mode() -> None:
    # 与 grading.py 同口径：非 account 模式不泄露端点存在性。
    if get_settings().auth_mode != "account":
        raise HTTPException(status_code=404, detail="not found")


def _require_manager(principal: Principal) -> None:
    _require_account_mode()
    if not (set(principal.roles) & MANAGER_ROLES):
        raise HTTPException(status_code=404, detail="not found")


def _require_grades_viewer(principal: Principal) -> None:
    _require_account_mode()
    if not (set(principal.roles) & GRADES_VIEWER_ROLES):
        raise HTTPException(status_code=404, detail="not found")


# 守卫以依赖形式挂在端点上：依赖求值先于请求体校验，保证带 body 的管理
# 端点对无权限角色也返回 404 而非 422（不泄露端点存在性）。
async def manager_principal(principal: Principal = Depends(require_auth)) -> Principal:
    _require_manager(principal)
    return principal


async def grades_principal(principal: Principal = Depends(require_auth)) -> Principal:
    _require_grades_viewer(principal)
    return principal


async def account_principal(principal: Principal = Depends(require_auth)) -> Principal:
    _require_account_mode()
    return principal


async def _scope_user_ids(principal: Principal) -> Optional[List[str]]:
    """树隔离范围：老师 → 自己学生的 id 集；其余角色/静态 token → None（不限）。"""
    if "leader" in principal.roles or "operator" in principal.roles:
        return None
    static_subjects = {p.subject for p in parse_tokens(get_settings()).values()}
    if principal.subject in static_subjects:
        return None
    students = await UserService().list_users(created_by=principal.subject)
    return [user["id"] for user in students]


async def _student_created_by(principal: Principal) -> Optional[str]:
    """学生的班级归属（users.created_by）；静态 token/已删账号为 None（未分配）。"""
    user = await UserService().get_user(principal.subject)
    return user["created_by"] if user else None


def _can_manage(assignment: Dict[str, Any], principal: Principal) -> bool:
    return assignment["created_by"] == principal.subject or "leader" in principal.roles


# ----------------------------------------------------------------------
# 协议清单（发布表单下拉）
# ----------------------------------------------------------------------


@router.get("/protocols", response_model=AssignmentProtocolsResponse)
async def list_assignment_protocols(
    principal: Principal = Depends(manager_principal),
) -> AssignmentProtocolsResponse:
    payload = await harness_grading.list_protocols(settings=get_settings())
    if not payload.get("ok"):
        # harness 不可达降级为空清单，不 500（前端允许手输协议名）。
        return AssignmentProtocolsResponse(
            protocols=[], error=str(payload.get("error") or "list_protocols_failed")
        )
    protocols = [
        AssignmentProtocol(
            name=str(item.get("name") or ""),
            cases=int(item.get("cases") or 0),
            categories=dict(item.get("categories") or {}),
        )
        for item in (payload.get("protocols") or [])
        if isinstance(item, dict) and item.get("name")
    ]
    return AssignmentProtocolsResponse(protocols=protocols)


# ----------------------------------------------------------------------
# 管理端：创建 / 列表 / 修改 / 撤回 / 恢复
# ----------------------------------------------------------------------


@router.post("", response_model=AssignmentInfo, status_code=status.HTTP_201_CREATED)
async def create_assignment(
    req: AssignmentCreateRequest,
    principal: Principal = Depends(manager_principal),
) -> AssignmentInfo:
    assignment = await AssignmentService().create_assignment(
        actor_id=principal.subject,
        actor_roles=list(principal.roles),
        title=req.title,
        description=req.description,
        deadline=req.deadline,
        items=[item.model_dump() for item in req.items],
    )
    return AssignmentInfo(**assignment)


@router.get("", response_model=None)
async def list_assignments(principal: Principal = Depends(account_principal)) -> Any:
    """manager→我创建的（leader/operator 全部）+items+统计；学生→可见作业+我的成绩。"""
    service = AssignmentService()
    if set(principal.roles) & GRADES_VIEWER_ROLES:
        rows = await service.list_for_manager(principal.subject, list(principal.roles))
        return ManagerAssignmentListResponse(assignments=[ManagerAssignment(**row) for row in rows])
    created_by = await _student_created_by(principal)
    rows = await service.list_for_student(principal.subject, created_by)
    return StudentAssignmentListResponse(assignments=[StudentAssignment(**row) for row in rows])


@router.get("/{assignment_id}", response_model=None)
async def get_assignment(
    assignment_id: str,
    principal: Principal = Depends(account_principal),
) -> Any:
    """详情：manager 见完整定义；学生见可见作业 + 我的逐协议成绩。"""
    service = AssignmentService()
    if set(principal.roles) & GRADES_VIEWER_ROLES:
        assignment = await service.get_assignment(assignment_id)
        # admin 只能看自己创建的作业；operator/leader 不受限。
        manageable = assignment is not None and (
            _can_manage(assignment, principal) or "operator" in principal.roles
        )
        if not manageable:
            raise HTTPException(status_code=404, detail="not found")
        return AssignmentInfo(**assignment)
    created_by = await _student_created_by(principal)
    assignment = await service.get_visible_assignment(assignment_id, principal.subject, created_by)
    if assignment is None:
        raise HTTPException(status_code=404, detail="not found")
    return StudentAssignment(**assignment)


@router.patch("/{assignment_id}", response_model=AssignmentInfo)
async def update_assignment(
    assignment_id: str,
    req: AssignmentUpdateRequest,
    principal: Principal = Depends(manager_principal),
) -> AssignmentInfo:
    try:
        assignment = await AssignmentService().update_assignment(
            assignment_id,
            actor_id=principal.subject,
            actor_roles=list(principal.roles),
            title=req.title,
            description=req.description,
            deadline=req.deadline,
            items=[item.model_dump() for item in req.items] if req.items is not None else None,
        )
    except PermissionError as exc:
        raise HTTPException(status_code=403, detail=str(exc)) from exc
    except ValueError as exc:
        raise HTTPException(status_code=400, detail=str(exc)) from exc
    if assignment is None:
        raise HTTPException(status_code=404, detail="not found")
    return AssignmentInfo(**assignment)


async def _set_status(assignment_id: str, status_value: str, principal: Principal) -> AssignmentInfo:
    try:
        assignment = await AssignmentService().set_status(
            assignment_id, status_value,
            actor_id=principal.subject, actor_roles=list(principal.roles),
        )
    except PermissionError as exc:
        raise HTTPException(status_code=403, detail=str(exc)) from exc
    if assignment is None:
        raise HTTPException(status_code=404, detail="not found")
    return AssignmentInfo(**assignment)


@router.post("/{assignment_id}/withdraw", response_model=AssignmentInfo)
async def withdraw_assignment(
    assignment_id: str,
    principal: Principal = Depends(manager_principal),
) -> AssignmentInfo:
    return await _set_status(assignment_id, "withdrawn", principal)


@router.post("/{assignment_id}/reactivate", response_model=AssignmentInfo)
async def reactivate_assignment(
    assignment_id: str,
    principal: Principal = Depends(manager_principal),
) -> AssignmentInfo:
    return await _set_status(assignment_id, "active", principal)


# ----------------------------------------------------------------------
# 学生端：提交
# ----------------------------------------------------------------------


async def _parse_submit_request(request: Request) -> Tuple[str, str, str]:
    """兼容 multipart（.c/.so 文件）与 JSON（粘贴代码）；返回 (protocol, code, origin)。"""
    content_type = request.headers.get("content-type", "")
    if not content_type.startswith("multipart/form-data"):
        try:
            body = await request.json()
        except (json.JSONDecodeError, UnicodeDecodeError):
            raise HTTPException(status_code=422, detail="request body must be valid JSON") from None
        try:
            req = AssignmentSubmitRequest(**body)
        except ValidationError as exc:
            raise HTTPException(
                status_code=422, detail=exc.errors(include_url=False, include_context=False)
            ) from None
        return req.protocol, req.code, "pasted"

    form = await request.form()
    protocol = str(form.get("protocol") or "").strip()
    if not protocol:
        raise HTTPException(status_code=422, detail="protocol must not be empty")
    upload = form.get("file")
    if upload is None or not getattr(upload, "filename", None):
        raise HTTPException(status_code=422, detail="需要上传 .c/.so 文件，或用 JSON 粘贴代码")
    suffix = Path(str(upload.filename)).suffix.lower()
    if suffix not in ALLOWED_UPLOAD_SUFFIXES:
        raise HTTPException(
            status_code=400,
            detail=f"仅支持上传 .c / .so 文件（收到 {suffix or '无扩展名'}）",
        )
    data = await upload.read()
    settings = get_settings()
    if len(data) > settings.grading_max_code_bytes:
        raise HTTPException(
            status_code=413,
            detail=f"文件超过大小上限（{settings.grading_max_code_bytes} 字节）",
        )
    return protocol, data.decode("utf-8", errors="replace"), "upload"


_GRADING_ERROR_STATUS = {
    "code_too_large": 413,
    "quota_exceeded": 429,
    "grading_timeout": 504,
}


@router.post("/{assignment_id}/submit", response_model=AssignmentSubmitResponse)
async def submit_assignment(
    assignment_id: str,
    request: Request,
    principal: Principal = Depends(account_principal),
) -> AssignmentSubmitResponse:
    protocol, code, source_origin = await _parse_submit_request(request)
    service = AssignmentService()
    created_by = await _student_created_by(principal)
    assignment = await service.submission_snapshot(assignment_id, created_by, protocol)
    if assignment is None:
        raise HTTPException(status_code=404, detail="not found")
    if service.is_past_deadline(assignment):
        raise HTTPException(status_code=400, detail="已过截止时间，不能提交")
    points = assignment["points"]
    if points is None:
        raise HTTPException(status_code=400, detail=f"协议 {protocol} 不属于该作业")
    accepted_at = datetime.now(timezone.utc).isoformat()

    payload = await harness_grading.grade_code(
        settings=get_settings(),
        user_id=principal.subject,
        user_roles=list(principal.roles),
        protocol=protocol,
        plugin_source=code,
    )
    if not payload.get("ok"):
        error = str(payload.get("error") or "grading_failed")
        raise HTTPException(
            status_code=_GRADING_ERROR_STATUS.get(error, 502),
            detail=str(payload.get("detail") or "评测服务返回失败"),
        )

    score = int(round(float(payload.get("score") or 0)))
    passed = int(payload.get("passed") or 0)
    total = int(payload.get("cases_total") or 0)
    results = list(payload.get("results") or [])
    # 最小化落库：只存代码 sha256 + 结果 JSON，不存源码全文。
    # submission_id 回传给前端：S3-d「结果讲解」入口按它关联成绩行。
    try:
        submission_id = await GradingService().record_submission(
            user_id=principal.subject,
            session_id=None,
            protocol=str(payload.get("protocol") or protocol),
            code_hash=hashlib.sha256(code.encode("utf-8", errors="replace")).hexdigest(),
            score=score,
            passed=passed,
            total=total,
            results=results,
            source_origin=source_origin,
            assignment_id=assignment_id,
            require_enabled=True,
            accepted_assignment={
                "points": points, "scope": assignment["scope"],
                "created_by": assignment["created_by"], "deadline": assignment["deadline"],
                "assignment_updated_at": assignment["updated_at"], "accepted_at": accepted_at,
            },
        )
    except PermissionError as exc:
        raise HTTPException(status_code=403, detail=str(exc)) from None
    # 多次提交取最高分（含本次，因落库在前）。
    best = await service.best_scores(assignment_id, principal.subject, protocol)
    return AssignmentSubmitResponse(
        score=score,
        passed=passed,
        total=total,
        results=results,
        points=points,
        earned=calc_earned(score, points),
        best_earned=calc_earned(best, points),
        submission_id=submission_id,
    )


# ----------------------------------------------------------------------
# S3-d 正式作业结果按需讲解（N02）
# ----------------------------------------------------------------------


async def _ensure_explain_session(session_id: str, user_id: str, title: str) -> None:
    """预建讲解会话行——确定性 session_id 生效的前提。

    ``SessionService.ensure_session`` 只在会话行已存在且归属匹配时才沿用传入
    id，否则生成随机 ``sess_`` id。若不先建行，受理会把 rag_requests.session_id
    落成随机 id，重复请求按 (request_id, session_id) 就探测不到同一任务行，
    S2 的幂等接续/重放随之失效。
    """
    ph = get_database_provider().placeholder

    def _insert() -> None:
        with get_conn() as conn:
            conn.execute(
                f"INSERT INTO assistant_sessions(id, user_id, title, created_at, updated_at)"
                f" VALUES ({ph}, {ph}, {ph}, {ph}, {ph}) ON CONFLICT DO NOTHING",
                (session_id, user_id, title[:80], now_iso(), now_iso()),
            )

    await asyncio.to_thread(_insert)


@router.post("/{assignment_id}/submissions/{submission_id}/explain", response_model=ExplainResponse)
async def explain_submission(
    assignment_id: str,
    submission_id: str,
    principal: Principal = Depends(account_principal),
) -> ExplainResponse:
    """把一次正式作业评测交给助教讲解（N02：讲解不自动重评、不改成绩）。

    讲解是一次普通的产品化问答任务：session_id/request_id 由 submission_id
    确定性派生，重复调用命中同一 rag_requests 行——pending 时接续同一任务
    （attached），终态后回放（replayed），都不重复扣每日配额、不重复执行。
    作业不做截止期检查：截止后讲解成绩同样是正当需求。
    """
    rate_limiter.check_per_minute(principal.subject)
    service = AssignmentService()
    created_by = await _student_created_by(principal)
    assignment = await service.get_visible_assignment(
        assignment_id, principal.subject, created_by)
    if assignment is None:
        raise HTTPException(status_code=404, detail="not found")
    row = await GradingService().get_submission(submission_id)
    # 不存在、非本人、或不属于该作业：同口径 404，不泄露成绩行存在性。
    if row is None or row["user_id"] != principal.subject or row["assignment_id"] != assignment_id:
        raise HTTPException(status_code=404, detail="not found")

    session_id, request_id = explain_request_identity(submission_id)
    req = ChatRequest(
        session_id=session_id,
        request_id=request_id,
        message=f"请讲解提交 {submission_id} 的评测结果。",
    )
    product = RagProductService(
        principal, req, None, explain=build_explain_context(row, assignment), purpose="explain",
    )
    await product.probe_replay()
    if product.replayed is not None:
        return ExplainResponse(
            session_id=product.replayed.session_id,
            task_id=product.replayed.task_id or "",
            status="replayed",
        )
    if product.attached_row is not None:
        return ExplainResponse(
            session_id=product.attached_row["session_id"] or session_id,
            task_id=product.attached_row["id"],
            status="attached",
        )
    await _ensure_explain_session(
        session_id, principal.subject,
        f"作业讲解·{assignment['title']}·{row['protocol']}",
    )
    managed = await start_product(product)
    if product.replayed is not None:
        return ExplainResponse(session_id=product.replayed.session_id,
                               task_id=product.replayed.task_id or "", status="replayed")
    if product.attached_row is not None:
        return ExplainResponse(session_id=product.attached_row["session_id"] or session_id,
                               task_id=product.attached_row["id"], status="attached")
    return ExplainResponse(
        session_id=product.session_id or session_id,
        task_id=managed.task_id,
        status="started",
    )


# ----------------------------------------------------------------------
# 成绩与导出
# ----------------------------------------------------------------------


async def _scoped_grade_rows(
    assignment_id: str, principal: Principal
) -> Tuple[Dict[str, Any], List[Dict[str, Any]]]:
    """作业定义 + 树隔离后的成绩行（含班级归属）。"""
    service = AssignmentService()
    assignment = await service.get_assignment(assignment_id)
    if assignment is None:
        raise HTTPException(status_code=404, detail="not found")
    rows = await service.grades_for_assignment(assignment_id)
    scope = await _scope_user_ids(principal)
    if scope is not None:
        allowed = set(scope)
        rows = [row for row in rows if row["user_id"] in allowed]
    teachers = await service.class_teachers([row["user_id"] for row in rows])
    for row in rows:
        # 成绩与账号脱钩：学生账号被删时班级归“未分配”，学号仍保留。
        row["class_teacher"] = teachers.get(row["user_id"])
    return assignment, rows


@router.get("/{assignment_id}/grades", response_model=AssignmentGradesResponse)
async def assignment_grades(
    assignment_id: str,
    principal: Principal = Depends(grades_principal),
) -> AssignmentGradesResponse:
    assignment, rows = await _scoped_grade_rows(assignment_id, principal)
    return AssignmentGradesResponse(
        assignment=AssignmentInfo(**assignment),
        rows=[AssignmentGradeRow(**row) for row in rows],
    )


def _csv_safe(text: str) -> str:
    return re.sub(r"[^A-Za-z0-9_.-]+", "_", text)


@router.get("/{assignment_id}/export")
async def export_assignment_grades(
    assignment_id: str,
    principal: Principal = Depends(grades_principal),
) -> StreamingResponse:
    """CSV 导出（UTF-8 BOM 兼容 Excel）。

    admin→只有自己班学生；leader/operator/静态 token→全部学生按班级分组
    （班级间空行分隔），未分配学生归“未分配”组。
    """
    assignment, rows = await _scoped_grade_rows(assignment_id, principal)
    items = assignment["items"]

    buffer = io.StringIO()
    writer = csv.writer(buffer)
    header = (
        ["班级(老师学号)", "学生学号"]
        + [f"{item['protocol']}(满分{item['points']})" for item in items]
        + ["总分", "提交次数", "最近提交时间"]
    )

    def write_row(row: Dict[str, Any]) -> None:
        writer.writerow(
            [row["class_teacher"] or _UNASSIGNED_CLASS, row["user_id"]]
            + [row["scores"].get(item["protocol"], "") for item in items]
            + [row["total_earned"], row["submission_count"], row["last_submitted_at"] or ""]
        )

    scope = await _scope_user_ids(principal)
    if scope is not None:
        # 老师视角：只有自己班，单组输出。
        writer.writerow(header)
        for row in rows:
            write_row(row)
        filename = f"assignment_{assignment_id}_{_csv_safe(principal.subject)}.csv"
    else:
        # 组长/operator 视角：全部按班级分组，班级间空行分隔，未分配排最后。
        writer.writerow(header)
        groups: Dict[str, List[Dict[str, Any]]] = {}
        for row in rows:
            groups.setdefault(row["class_teacher"] or _UNASSIGNED_CLASS, []).append(row)
        ordered = sorted((name for name in groups if name != _UNASSIGNED_CLASS))
        if _UNASSIGNED_CLASS in groups:
            ordered.append(_UNASSIGNED_CLASS)
        for index, name in enumerate(ordered):
            if index:
                writer.writerow([])
            for row in groups[name]:
                write_row(row)
        filename = f"assignment_{assignment_id}_grades.csv"

    # 显式 BOM（\ufeff）让 Excel 按 UTF-8 打开。
    content = "\ufeff" + buffer.getvalue()
    return StreamingResponse(
        iter([content]),
        media_type="text/csv; charset=utf-8-sig",
        headers={"Content-Disposition": f'attachment; filename="{filename}"'},
    )
