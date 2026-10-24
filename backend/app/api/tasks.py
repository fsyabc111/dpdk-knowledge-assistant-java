"""任务状态查询 / SSE 续传 / 显式取消（S3-c 展示与重连，decisions §8）。

- ``GET /{task_id}``：行状态原样返回；pending + 注册表在跑 → ``running``，
  pending 孤儿 → ``pending``。他人/不存在一律 404，不泄露任务存在性。
- ``GET /active?session_id=``：会话内最近一条 pending 任务（刷新找回入口）。
- ``POST /{task_id}/cancel``：显式取消，幂等——重复调用与终态行都返回当前状态。
- ``GET /{task_id}/events``：SSE 续传——进行中订阅实时帧（含已发帧回放），
  终态按库行重建回放帧。
"""
import asyncio
import json

from fastapi import APIRouter, Depends, HTTPException, Query
from fastapi.responses import StreamingResponse

from ..core.auth import Principal, require_auth
from ..schemas.chat import TaskStatusResponse
from ..services import task_service
from ..services.rag_issue_service import RagIssueService

router = APIRouter(prefix="/api/tasks", tags=["tasks"])


def _status_response(row) -> TaskStatusResponse:
    status = row["status"]
    if status == "pending" and task_service.is_running(row["id"]):
        status = "running"   # 注册表在跑的 pending 才是「进行中」
    return TaskStatusResponse(
        task_id=row["id"], status=status, reason=row.get("reason") or "",
        request_id=row["request_id"], session_id=row["session_id"],
        message_id=row["message_id"], created_at=str(row["created_at"] or ""),
        elapsed_ms=int(row["elapsed_ms"] or 0))


async def _get_owned_task(task_id: str, user_id: str):
    row = await asyncio.to_thread(RagIssueService().get_task, task_id, user_id)
    if row is None:
        raise HTTPException(404, "task not found")
    return row


# 声明顺序即匹配顺序：/active 必须先于 /{task_id}，否则被当作 task_id="active"。
@router.get("/active")
async def active_task(session_id: str = Query(..., min_length=1),
                      principal: Principal = Depends(require_auth)):
    """会话内最近一条活动任务（页面刷新找回入口）。无 → {"task": null}。"""
    row = await asyncio.to_thread(
        RagIssueService().active_task_for_session, session_id, principal.subject)
    return {"task": _status_response(row) if row else None}


@router.get("/{task_id}", response_model=TaskStatusResponse)
async def task_status(task_id: str, principal: Principal = Depends(require_auth)):
    return _status_response(await _get_owned_task(task_id, principal.subject))


@router.post("/{task_id}/cancel", response_model=TaskStatusResponse)
async def cancel_task(task_id: str, principal: Principal = Depends(require_auth)):
    """显式取消：执行协程 + 库行一体终止；重复调用/已终态返回当前状态（幂等）。"""
    row = await task_service.cancel(task_id, principal.subject)
    return _status_response(row)


@router.get("/{task_id}/events")
async def task_events_stream(task_id: str, principal: Principal = Depends(require_auth)):
    """SSE 续传：进行中订阅实时帧，终态重建回放帧。"""
    await _get_owned_task(task_id, principal.subject)   # 404 必须在流开始前判

    async def stream():
        try:
            async for item in task_service.task_events(task_id, principal.subject):
                yield (f"event: {item['event']}\n"
                       f"data: {json.dumps(item['data'], ensure_ascii=False)}\n\n")
        except asyncio.CancelledError:
            raise
        except Exception:
            yield ('event: error\n'
                   'data: {"message":"服务暂时不可用，请稍后重试","retryable":true}\n\n')

    return StreamingResponse(stream(), media_type="text/event-stream", headers={
        "Cache-Control": "no-cache", "X-Accel-Buffering": "no",
    })
