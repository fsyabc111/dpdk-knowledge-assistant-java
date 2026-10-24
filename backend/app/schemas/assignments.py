"""作业管理（alembic 0007）的请求/响应模型。

响应模型只含必要字段（不含 code_hash/results_json 等评测细节），命名沿用
grading.py schemas 的约定。
"""
from __future__ import annotations

from datetime import datetime
from typing import Any, Dict, List, Literal, Optional

from pydantic import BaseModel, Field


class AssignmentItemIn(BaseModel):
    protocol: str = Field(min_length=1, max_length=64)
    points: int = Field(ge=1, le=1000)


class AssignmentCreateRequest(BaseModel):
    title: str = Field(min_length=1, max_length=200)
    description: str = ""
    deadline: datetime
    items: List[AssignmentItemIn] = Field(min_length=1, max_length=50)


class AssignmentUpdateRequest(BaseModel):
    title: Optional[str] = Field(default=None, min_length=1, max_length=200)
    description: Optional[str] = None
    deadline: Optional[datetime] = None
    items: Optional[List[AssignmentItemIn]] = Field(default=None, min_length=1, max_length=50)


class AssignmentSubmitRequest(BaseModel):
    """JSON 粘贴代码路径（multipart 上传路径在路由层解析）。"""

    protocol: str = Field(min_length=1, max_length=64)
    code: str = Field(min_length=1)


class AssignmentItem(BaseModel):
    protocol: str
    points: int


class AssignmentInfo(BaseModel):
    id: str
    title: str
    description: str
    created_by: str
    scope: str
    deadline: str
    status: str
    created_at: str
    updated_at: str
    items: List[AssignmentItem]


class ManagerAssignment(AssignmentInfo):
    submission_count: int
    student_count: int


class ManagerAssignmentListResponse(BaseModel):
    assignments: List[ManagerAssignment]


class StudentItemGrade(BaseModel):
    protocol: str
    points: int
    best_score: Optional[int] = None
    # S4-b W4：无提交记录时为 None（前端统一显示「未提交」）；0.0 表示
    # 「提交了但最高 0 分」，与未提交区分。
    earned: Optional[float] = None
    submission_count: int


class StudentAssignment(BaseModel):
    id: str
    title: str
    description: str
    created_by: str
    deadline: str
    status: str
    items: List[StudentItemGrade]
    total_points: int
    total_earned: float
    submission_count: int


class StudentAssignmentListResponse(BaseModel):
    assignments: List[StudentAssignment]


class AssignmentProtocol(BaseModel):
    name: str
    cases: int = 0
    categories: Dict[str, Any] = Field(default_factory=dict)


class AssignmentProtocolsResponse(BaseModel):
    protocols: List[AssignmentProtocol]
    error: Optional[str] = None


class AssignmentSubmitResponse(BaseModel):
    score: int
    passed: int
    total: int
    results: List[Dict[str, Any]]
    points: int
    earned: float
    best_earned: float
    # 本次提交落库的 grade_* 成绩行 id（S3-d：供「结果讲解」入口引用）。
    submission_id: str


class ExplainResponse(BaseModel):
    """POST /{assignment_id}/submissions/{submission_id}/explain 的受理回执（S3-d）。

    讲解是普通的产品化问答任务：started 表示新起步，attached 表示接续了
    执行中的同一任务（确定性身份命中），replayed 表示终态结果回放。
    进度与正文经 /api/tasks/{task_id} 查询或 SSE 续传。
    """

    session_id: str
    task_id: str
    status: Literal["started", "attached", "replayed"]


class AssignmentGradeRow(BaseModel):
    user_id: str
    # 班级归属（users.created_by，即老师学号）；账号已删/未分配为 None。
    class_teacher: Optional[str] = None
    # 协议 -> 折算得分（仅当前协议项内且已有提交的协议计入；未提交协议
    # 不在字典里，前端显示「—」）。
    scores: Dict[str, float]
    total_earned: float
    submission_count: int
    last_submitted_at: Optional[str] = None


class AssignmentGradesResponse(BaseModel):
    assignment: AssignmentInfo
    rows: List[AssignmentGradeRow]
