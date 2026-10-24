import asyncio
import hashlib
import json
import logging
import secrets
from pathlib import Path
from typing import Optional, Tuple

from fastapi import APIRouter, Depends, Header, HTTPException, Query, Request, status
from fastapi.responses import StreamingResponse
from pydantic import ValidationError

from ..config import get_settings
from ..core.auth import Principal, require_auth
from ..core.rate_limit import rate_limiter
from ..schemas.chat import (
    ChatRequest,
    ChatResponse,
    Citation,
    SessionHistoryResponse,
    SessionListResponse,
    SessionMessage,
    SessionSummary,
    UploadedCode,
)
from ..services import task_service
from ..services.rag_product_service import RagProductService, admission, chat_response_from_row
from ..services.session_service import SessionService
from ..storage.retrieval_store import get_historical_chunk

logger = logging.getLogger("assistant")

router = APIRouter(prefix="/api", tags=["chat"])

ALLOWED_UPLOAD_SUFFIXES = {".c", ".so"}


async def _parse_chat_request(request: Request) -> Tuple[ChatRequest, Optional[UploadedCode]]:
    """Accept both the legacy JSON body and multipart/form-data with one file.

    JSON 契约保持不变（前端旧版无感）；multipart 可携带一个 .c/.so 附件，
    校验扩展名与大小后把 .c 读为文本作为 grade_plugin 的 plugin_source 候选
    （内存传递，不落盘持久化）。
    """
    content_type = request.headers.get("content-type", "")
    if not content_type.startswith("multipart/form-data"):
        try:
            return ChatRequest(**(await request.json())), None
        # 非 UTF-8 body 与非法 JSON 同语义：客户端输入错误，422 而非 500。
        except (json.JSONDecodeError, UnicodeDecodeError):
            raise HTTPException(status_code=422, detail="request body must be valid JSON") from None
        except ValidationError as exc:
            raise HTTPException(
                status_code=422, detail=exc.errors(include_url=False, include_context=False)
            ) from None

    settings = get_settings()
    form = await request.form()
    message = str(form.get("message") or "").strip()
    session_id = str(form.get("session_id") or "").strip() or None
    try:
        context = json.loads(str(form.get("context") or "{}"))
    except json.JSONDecodeError:
        raise HTTPException(status_code=400, detail="context must be a JSON object") from None
    if not isinstance(context, dict):
        raise HTTPException(status_code=400, detail="context must be a JSON object")

    attachment: Optional[UploadedCode] = None
    upload = form.get("file")
    if upload is not None and getattr(upload, "filename", None):
        filename = str(upload.filename)
        suffix = Path(filename).suffix.lower()
        if suffix not in ALLOWED_UPLOAD_SUFFIXES:
            raise HTTPException(
                status_code=400,
                detail=f"仅支持上传 .c / .so 文件（收到 {suffix or '无扩展名'}）",
            )
        data = await upload.read()
        if len(data) > settings.grading_max_code_bytes:
            raise HTTPException(
                status_code=413,
                detail=f"文件超过大小上限（{settings.grading_max_code_bytes} 字节）",
            )
        # 文件名约定 学号_姓名_协议.c（D6），第三个分段作为协议提示。
        stem_parts = Path(filename).stem.split("_")
        protocol_hint = stem_parts[2].lower() if len(stem_parts) >= 3 and stem_parts[2] else None
        attachment = UploadedCode(
            content_hash=hashlib.sha256(data).hexdigest(),
            filename=filename,
            suffix=suffix,
            source=data.decode("utf-8", errors="replace") if suffix == ".c" else None,
            protocol_hint=protocol_hint,
        )
    if not message and attachment is not None:
        message = f"请帮我评测这个插件（{attachment.filename}）"
    if not message:
        raise HTTPException(status_code=422, detail="message must not be empty")
    try:
        req = ChatRequest(session_id=session_id, message=message, context=context, request_id=form.get("request_id"))
    except ValidationError as exc:
        raise HTTPException(422, detail=exc.errors(include_url=False, include_context=False)) from None
    return req, attachment


@router.post("/chat", response_model=ChatResponse)
async def chat(request: Request, principal: Principal = Depends(require_auth)) -> ChatResponse:
    req, attachment = await _parse_chat_request(request)
    rate_limiter.check_per_minute(principal.subject)
    # S4 收敛后唯一生产路径：产品化问答（请求登记 → 编排 → 证据校验 → 落库）。
    product = RagProductService(principal, req, attachment)
    # S3-c：probe 前移到占槽之前——重放/接续不占执行槽，admission 的
    # 获取与释放整体收进 task_service 的任务生命周期（路由不再持槽）。
    await product.probe_replay()
    if product.replayed is not None:
        return product.replayed
    if product.attached_row is not None:
        # 同 request_id 重提交：接续既有任务；pending 且等不到 → 409 带 task_id。
        return await task_service.attached_response(product.attached_row, principal.subject)
    managed = await start_product(product)
    if product.replayed is not None:
        return product.replayed
    if product.attached_row is not None:
        return await task_service.attached_response(product.attached_row, principal.subject)
    result = await task_service.wait_managed_result(
        managed, get_settings().rag_request_timeout_sec)
    if result is not None:
        return result
    # run 无结果收口（取消/内部错误）：按库行重建最小响应，不让接口 500。
    row = await asyncio.to_thread(
        product.issues.get_task, managed.task_id, principal.subject)
    if row is not None and row["status"] != "pending":
        message = await asyncio.to_thread(task_service.load_row_message, row)
        return chat_response_from_row(row, message)
    raise HTTPException(503, "服务暂时不可用，请保留问题后重试") from None


@router.post("/chat/stream")
async def chat_stream(request: Request, principal: Principal = Depends(require_auth)) -> StreamingResponse:
    req, attachment = await _parse_chat_request(request)
    rate_limiter.check_per_minute(principal.subject)
    product = RagProductService(principal, req, attachment)
    # S3-c：probe 前移到占槽之前——重放/接续不占执行槽（B13：断线≠取消）。
    await product.probe_replay()
    if product.replayed is not None:
        stream = product.events()
    elif product.attached_row is not None:
        # 同 request_id 重提交：订阅既有任务的事件流（含已发帧回放）。
        stream = task_service.task_events(product.attached_row["id"], principal.subject)
    else:
        managed = await start_product(product)
        if product.replayed is not None:
            stream = product.events()
        elif product.attached_row is not None:
            stream = task_service.task_events(product.attached_row["id"], principal.subject)
        else:
            stream = task_service.subscribe(managed)

    async def product_events():
        try:
            async for item in stream:
                yield f"event: {item['event']}\ndata: {json.dumps(item['data'], ensure_ascii=False)}\n\n"
        except asyncio.CancelledError:
            raise
        except Exception:
            yield 'event: error\ndata: {"message":"服务暂时不可用，请稍后重试","retryable":true}\n\n'
    return StreamingResponse(product_events(), media_type="text/event-stream", headers={
        "Cache-Control": "no-cache", "X-Accel-Buffering": "no",
    })


async def _citation_source(engine: str, chunk_id: str):
    if engine == "llama_index" or chunk_id.startswith("li_"):
        from ..services.llama_index_service import get_llama_index_source
        return await get_llama_index_source(chunk_id)
    # 非 li_ 前缀的历史引用（legacy 引擎时期写入的 knowledge_chunks 行）直查回读。
    return await asyncio.to_thread(get_historical_chunk, chunk_id)


async def prepare_product(product, quota: int = 0):
    """原子受理的超时兜底；配额只在新任务事务中记一次。"""
    try:
        await asyncio.wait_for(product.prepare(quota=quota), get_settings().rag_prepare_timeout_sec)
    except HTTPException:
        raise
    except Exception:
        logger.exception("问答受理失败")
        raise HTTPException(503, "服务暂时不可用，请保留问题后重试") from None


async def start_product(product):
    """先占槽再受理；冲突、配额或寄存失败均释放槽且不启动第二执行体。"""
    await admission.acquire()
    try:
        await prepare_product(product, quota=product.principal.daily_quota)
        if product.replayed is not None or product.attached_row is not None:
            await admission.release()
            return None
        return task_service.start_reserved_task(product)
    except BaseException:
        await admission.release()
        raise


@router.get("/chat/sessions", response_model=SessionListResponse)
async def list_sessions(
    limit: int = Query(50, ge=1, le=200),
    offset: int = Query(0, ge=0),
    principal: Principal = Depends(require_auth),
) -> SessionListResponse:
    """List the caller's own sessions, most recently updated first."""
    sessions = await SessionService().list_sessions(principal.subject, limit=limit, offset=offset)
    return SessionListResponse(sessions=[SessionSummary(**item) for item in sessions])


@router.get("/chat/sessions/{session_id}", response_model=SessionHistoryResponse)
async def load_session(
    session_id: str,
    limit: int = Query(100, ge=1, le=200),
    principal: Principal = Depends(require_auth),
) -> SessionHistoryResponse:
    """Load a prior conversation by its server-issued session id."""
    sessions = SessionService()
    session = await sessions.get_session(session_id)
    if not session:
        raise HTTPException(status_code=404, detail="session not found")
    # Outside local mode a subject may only read its own sessions; a foreign
    # session id is indistinguishable from a missing one.
    if get_settings().auth_mode != "local" and session["user_id"] != principal.subject:
        raise HTTPException(status_code=404, detail="session not found")
    all_messages = await sessions.get_messages(session_id, include_cancelled=True)
    truncated = len(all_messages) > limit
    messages = all_messages[-limit:]
    citations_by_message = await sessions.get_citations([item["id"] for item in messages])
    source_keys = list({
        (citation["engine"], citation["chunk_id"])
        for citations in citations_by_message.values()
        for citation in citations
    })
    resolved = await asyncio.gather(*(
        _citation_source(engine, chunk_id) for engine, chunk_id in source_keys
    ))
    source_map = dict(zip(source_keys, resolved, strict=False))
    history = []
    for message in messages:
        citations = []
        for stored in citations_by_message.get(message["id"], []):
            source = source_map.get((stored["engine"], stored["chunk_id"])) or {}
            citations.append(Citation(
                chunk_id=stored["chunk_id"],
                source_path=source.get("source_path", ""),
                title=source.get("title", ""),
                section=source.get("section", ""),
                score=stored["score"],
            ))
        history.append(SessionMessage(
            id=message["id"], role=message["role"], content=message["content"],
            citations=citations,
            confidence=message["metadata"].get("confidence", "medium"),
            answer_status=message["metadata"].get("answer_status"),
            answer_reason=message["metadata"].get("answer_reason"),
            issue_id=message["metadata"].get("issue_id"),
            request_id=message["metadata"].get("request_id"),
            task_id=message["metadata"].get("task_id"),
            created_at=str(message["created_at"]),
        ))
    return SessionHistoryResponse(
        session_id=session_id, title=session["title"], messages=history, truncated=truncated,
    )


@router.delete("/chat/sessions/{session_id}", status_code=status.HTTP_204_NO_CONTENT)
async def delete_session(
    session_id: str,
    principal: Principal = Depends(require_auth),
):
    """Delete the caller's own session. Foreign/missing ids both answer 404."""
    subject = None if get_settings().auth_mode == "local" else principal.subject
    if not await SessionService().delete_session(session_id, subject):
        raise HTTPException(status_code=404, detail="session not found")
    return None


def _check_maintenance_key(x_maintenance_key: Optional[str]) -> None:
    configured_key = get_settings().maintenance_api_key
    if not configured_key:
        raise HTTPException(
            status_code=status.HTTP_503_SERVICE_UNAVAILABLE,
            detail="maintenance endpoint is not configured",
        )
    if not x_maintenance_key or not secrets.compare_digest(x_maintenance_key, configured_key):
        raise HTTPException(status_code=status.HTTP_403_FORBIDDEN, detail="forbidden")


@router.get("/internal/session-storage", include_in_schema=False)
async def session_storage(
    x_maintenance_key: Optional[str] = Header(default=None),
):
    """Read-only maintenance endpoint; intentionally absent from the frontend/API docs."""
    _check_maintenance_key(x_maintenance_key)
    return await SessionService().get_storage_usage()


@router.post("/internal/purge-sessions", include_in_schema=False)
async def purge_sessions(
    x_maintenance_key: Optional[str] = Header(default=None),
    retention_days: Optional[int] = Query(default=None, ge=1),
    dry_run: bool = Query(default=True),
):
    """Purge sessions idle past the retention cutoff. Dry-run unless told otherwise."""
    _check_maintenance_key(x_maintenance_key)
    days = retention_days or get_settings().session_retention_days
    return await SessionService().purge_expired_sessions(days, dry_run=dry_run)
