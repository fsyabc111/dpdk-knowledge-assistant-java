"""教师成绩视图（M3）的响应模型。

只暴露最小集：明细不含 results_json/code_hash（避免 JSONB/TEXT 方言分叉，
也不向教师端泄露评测细节），聚合口径与 grading_submissions 表直接对应。
"""
from typing import List

from pydantic import BaseModel


class GradingSubmission(BaseModel):
    id: str
    user_id: str
    protocol: str
    score: int
    passed: int
    total: int
    created_at: str


class GradingSubmissionListResponse(BaseModel):
    submissions: List[GradingSubmission]


class GradingUserSummary(BaseModel):
    user_id: str
    submission_count: int
    avg_score: float
    best_score: int
    last_submitted_at: str


class GradingProtocolSummary(BaseModel):
    protocol: str
    submission_count: int
    avg_score: float
    # 全量用例通过比例用 passed_total/total_total 表达（分子分母分别聚合，
    # 避免“各次提交比例的平均”这种失真口径）。
    passed_total: int
    total_total: int
    # 有提交记录的去重学生数，便于教师看每协议的参与面。
    student_count: int


class GradingSummaryResponse(BaseModel):
    by_user: List[GradingUserSummary]
    by_protocol: List[GradingProtocolSummary]
