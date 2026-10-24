from dataclasses import dataclass
from typing import Any, Dict, List, Optional
from uuid import UUID

from pydantic import BaseModel, Field


class ChatRequest(BaseModel):
    session_id: Optional[str] = None
    request_id: Optional[UUID] = None
    # Bound request size before it reaches embedding, history and the LLM.
    message: str = Field(..., min_length=1, max_length=12000)
    context: Dict[str, Any] = Field(default_factory=dict)


@dataclass
class UploadedCode:
    """One validated multipart attachment (规划 §4.5，D6)。

    source 只存内存、不落盘持久化（D12）；.so 二进制无法经 MCP 源码入口评测，
    source 为 None，由提示词引导用户改交 .c。
    """

    filename: str
    suffix: str
    source: Optional[str]
    protocol_hint: Optional[str] = None
    content_hash: Optional[str] = None


class Citation(BaseModel):
    chunk_id: str
    source_path: str
    title: str = ""
    section: str = ""
    score: float


class NextStep(BaseModel):
    label: str
    source_path: str = ""


class ChatResponse(BaseModel):
    session_id: str
    message_id: str
    answer: str
    citations: List[Citation]
    next_steps: List[NextStep]
    confidence: str = "medium"
    debug_trace: Optional[Dict[str, Any]] = None
    answer_status: Optional[str] = None
    answer_reason: Optional[str] = None
    issue_id: Optional[str] = None
    request_id: Optional[str] = None
    task_id: Optional[str] = None


class SessionMessage(BaseModel):
    id: str
    role: str
    content: str
    citations: List[Citation] = Field(default_factory=list)
    confidence: str = "medium"
    created_at: str
    answer_status: Optional[str] = None
    answer_reason: Optional[str] = None
    issue_id: Optional[str] = None
    request_id: Optional[str] = None
    task_id: Optional[str] = None


class SessionHistoryResponse(BaseModel):
    session_id: str
    title: str = ""
    messages: List[SessionMessage]
    truncated: bool = False


class SessionSummary(BaseModel):
    id: str
    title: str = ""
    updated_at: str


class SessionListResponse(BaseModel):
    sessions: List[SessionSummary]


class TemplateRequest(BaseModel):
    protocol: str = Field(..., min_length=1)
    template_type: str = "port_text_protocol"
    difficulty: str = "basic"
    requirements: str = ""


class TemplateResponse(BaseModel):
    template: str
    explanation: str
    checklist: List[str]
    references: List[str]


class TaskStatusResponse(BaseModel):
    """GET /api/tasks/{task_id} 的只读任务状态（S3 展示与重连，decisions §8）。"""
    task_id: str
    status: str                    # pending/running/终态 verdict/cancelled/system_error
    reason: str = ""
    request_id: Optional[str] = None
    session_id: Optional[str] = None
    message_id: Optional[str] = None
    created_at: str = ""
    elapsed_ms: int = 0
