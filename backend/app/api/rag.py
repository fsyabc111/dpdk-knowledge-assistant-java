"""未解答问题工作台、人工知识发布及原题回归。"""
from __future__ import annotations

import asyncio
import json
import time
import uuid
from typing import Literal

from fastapi import APIRouter, Depends, HTTPException, Query
from pydantic import BaseModel, Field

from ..config import get_settings
from ..core.auth import Principal, require_auth
from ..db import get_conn, get_database_provider
from ..services.rag_index_service import active_release, now
from ..services.rag_issue_service import RagIssueService, redact

router = APIRouter(prefix="/api/rag", tags=["rag"])


def manager(principal: Principal = Depends(require_auth)):
    if get_settings().auth_mode != "account" or not {"admin", "leader"}.intersection(principal.roles):
        raise HTTPException(404, "not found")
    return principal


def scope(principal):
    if "leader" in principal.roles:
        return "1=1", ()
    ph = get_database_provider().placeholder
    return f"r.user_id IN (SELECT id FROM users WHERE created_by={ph})", (principal.subject,)


def _get(issue_id, principal):
    ph = get_database_provider().placeholder
    clause, args = scope(principal)
    with get_conn() as conn:
        row = conn.execute(
            "SELECT i.*,r.question,r.context_text,r.reason,r.status AS answer_status,r.evidence_json,r.release_id,"
            "r.created_at,r.model_version,r.elapsed_ms FROM rag_issues i JOIN rag_requests r ON r.id=i.request_id "
            f"WHERE i.id={ph} AND ({clause})", (issue_id, *args),
        ).fetchone()
        if not row:
            raise HTTPException(404, "not found")
        result = dict(row)
        result["events"] = [dict(x) for x in conn.execute(
            f"SELECT action,detail,actor_id,created_at FROM rag_issue_events WHERE issue_id={ph} ORDER BY created_at LIMIT 200",
            (issue_id,),
        ).fetchall()]
    result["evidence"] = json.loads(result.pop("evidence_json"))
    return result


@router.get("/issues")
async def issues(status: str = "", limit: int = Query(50, ge=1, le=100), offset: int = Query(0, ge=0),
                 principal: Principal = Depends(manager)):
    def read():
        ph = get_database_provider().placeholder
        clause, args = scope(principal)
        if status:
            clause += f" AND i.status={ph}"
            args += (status,)
        with get_conn() as conn:
            rows = conn.execute(
                "SELECT i.id,i.status,i.owner_id,i.feedback,r.question,r.reason,r.created_at,r.status AS answer_status "
                f"FROM rag_issues i JOIN rag_requests r ON r.id=i.request_id WHERE {clause} "
                f"ORDER BY r.created_at DESC,i.id LIMIT {ph} OFFSET {ph}", (*args, limit, offset),
            ).fetchall()
        return {"items": [dict(row) for row in rows], "offset": offset, "limit": limit}
    return await asyncio.to_thread(read)


@router.get("/issues/{issue_id}")
async def issue(issue_id: str, principal: Principal = Depends(manager)):
    return await asyncio.to_thread(_get, issue_id, principal)


class IssueUpdate(BaseModel):
    status: Literal["open", "in_progress", "awaiting_verification", "resolved", "ignored"]
    note: str = Field(default="", max_length=4000)
    claim: bool = False
    merged_into: str | None = None
    knowledge_id: str | None = None


@router.patch("/issues/{issue_id}")
async def update_issue(issue_id: str, body: IssueUpdate, principal: Principal = Depends(manager)):
    def update():
        old = _get(issue_id, principal)
        if body.merged_into:
            target = _get(body.merged_into, principal)
            if target["id"] == issue_id or target["merged_into"]:
                raise HTTPException(400, "不能循环合并或合并到已合并记录")
            with get_conn() as conn:
                if conn.execute(f"SELECT id FROM rag_issues WHERE merged_into={get_database_provider().placeholder}", (issue_id,)).fetchone():
                    raise HTTPException(409, "该记录已有合并来源，不能再合并")
        ph = get_database_provider().placeholder
        with get_conn() as conn:
            current = conn.execute("SELECT release_id FROM rag_index_state WHERE name='active'").fetchone()
            if body.status == "resolved" and (not old["verified_at"] or not current or
                                               old["verified_release_id"] != current["release_id"]):
                raise HTTPException(409, "请先在当前知识版本上通过原题验证")
            if body.knowledge_id and not conn.execute(f"SELECT id FROM rag_knowledge WHERE id={ph}", (body.knowledge_id,)).fetchone():
                raise HTTPException(404, "知识条目不存在")
            owner = principal.subject if body.claim else old["owner_id"]
            count = conn.execute(
                f"UPDATE rag_issues SET status={ph},note={ph},owner_id={ph},merged_into={ph},knowledge_id={ph},updated_at={ph} "
                f"WHERE id={ph} AND updated_at={ph}",
                (body.status, redact(body.note), owner, body.merged_into, body.knowledge_id, now(), issue_id, old["updated_at"]),
            ).rowcount
            if count != 1:
                raise HTTPException(409, "记录已更新，请刷新")
            RagIssueService().event(conn, issue_id, principal.subject, body.status, redact(body.note))
        return _get(issue_id, principal)
    return await asyncio.to_thread(update)


class Feedback(BaseModel):
    message_id: str
    reason: Literal["unresolved", "incorrect"]
    detail: str = Field(default="", max_length=2000)


@router.post("/feedback")
async def feedback(body: Feedback, principal: Principal = Depends(require_auth)):
    try:
        issue_id = await asyncio.to_thread(RagIssueService().feedback, body.message_id, principal.subject,
                                           f"{body.reason}: {body.detail}")
    except LookupError:
        raise HTTPException(404, "not found") from None
    return {"issue_id": issue_id}


class KnowledgeWrite(BaseModel):
    title: str = Field(min_length=1, max_length=200)
    body: str = Field(min_length=1, max_length=20000)
    revision: int = Field(default=0, ge=0)
    status: Literal["published", "withdrawn"] = "published"
    shared_confirmed: bool = False


@router.get("/knowledge")
async def knowledge(principal: Principal = Depends(manager)):
    def read():
        with get_conn() as conn:
            rows = conn.execute("SELECT * FROM rag_knowledge ORDER BY updated_at DESC LIMIT 200").fetchall()
        return {"items": [dict(row) for row in rows], "active_release": active_release()}
    return await asyncio.to_thread(read)


def _write_knowledge(body, principal, knowledge_id=None):
    if body.status == "published" and not body.shared_confirmed:
        raise HTTPException(400, "请确认内容已人工检查且对全体学生可见")
    ph = get_database_provider().placeholder
    identity = knowledge_id or uuid.uuid4().hex
    with get_conn() as conn:
        if knowledge_id:
            old = conn.execute(f"SELECT * FROM rag_knowledge WHERE id={ph}", (identity,)).fetchone()
            if not old or (old["created_by"] != principal.subject and "leader" not in principal.roles):
                raise HTTPException(404, "not found")
            revision = body.revision + 1
            count = conn.execute(
                f"UPDATE rag_knowledge SET title={ph},body={ph},revision={ph},status={ph},updated_at={ph} "
                f"WHERE id={ph} AND revision={ph}",
                (body.title, body.body, revision, body.status, now(), identity, body.revision),
            ).rowcount
            if count != 1:
                raise HTTPException(409, "知识条目已更新，请刷新")
        else:
            revision = 1
            conn.execute(f"INSERT INTO rag_knowledge(id,created_by,title,body,revision,status,updated_at) "
                         f"VALUES ({','.join([ph] * 7)})",
                         (identity, principal.subject, body.title, body.body, revision, body.status, now()))
        conn.execute(f"INSERT INTO rag_knowledge_revisions(id,knowledge_id,revision,actor_id,title,body,status,created_at) "
                     f"VALUES ({','.join([ph] * 8)})",
                     (uuid.uuid4().hex, identity, revision, principal.subject, body.title, body.body, body.status, now()))
    return {"id": identity, "revision": revision, "status": body.status, "index_status": "pending"}


@router.post("/knowledge")
async def publish(body: KnowledgeWrite, principal: Principal = Depends(manager)):
    return await asyncio.to_thread(_write_knowledge, body, principal)


@router.patch("/knowledge/{knowledge_id}")
async def revise(knowledge_id: str, body: KnowledgeWrite, principal: Principal = Depends(manager)):
    return await asyncio.to_thread(_write_knowledge, body, principal, knowledge_id)


@router.get("/knowledge/{knowledge_id}/revisions")
async def revisions(knowledge_id: str, principal: Principal = Depends(manager)):
    def read():
        ph = get_database_provider().placeholder
        with get_conn() as conn:
            return {"items": [dict(r) for r in conn.execute(
                f"SELECT revision,actor_id,title,body,status,created_at FROM rag_knowledge_revisions "
                f"WHERE knowledge_id={ph} ORDER BY revision DESC LIMIT 100", (knowledge_id,),
            ).fetchall()]}
    return await asyncio.to_thread(read)


@router.post("/issues/{issue_id}/verify")
async def verify(issue_id: str, principal: Principal = Depends(manager)):
    from ..core.rate_limit import rate_limiter
    from ..rag.providers import DirectOpenAIChatModel
    from ..services.agent_service import GroundedAnswerGenerator
    from ..services.llama_index_service import LlamaIndexRetrievalAdapter
    from ..services.rag_product_service import admission, judge_budget, validate_answer
    rate_limiter.check_per_minute(principal.subject)
    await rate_limiter.check_and_count_daily(principal.subject, principal.daily_quota)
    record = await asyncio.to_thread(_get, issue_id, principal)
    await admission.acquire()
    try:
        async def check():
            started = time.monotonic()
            retriever = LlamaIndexRetrievalAdapter()
            chunks = await retriever.search(record["question"], 8, conversation_context=record["context_text"])
            model = DirectOpenAIChatModel(get_settings())
            draft = await GroundedAnswerGenerator(model).generate(record["question"], chunks)
            # 与产品路径同一口径：judge 用请求剩余预算，不另开独立超时。
            budget = judge_budget(get_settings().rag_request_timeout_sec, time.monotonic() - started,
                                  get_settings().rag_validation_timeout_sec)
            verdict = await validate_answer(record["question"], draft.answer,
                                             {"chunks": [{"ref": i, "content": c.content} for i, c in enumerate(chunks, 1)]},
                                             model, budget)
            return verdict, retriever._retriever.release_id
        verdict, release = await asyncio.wait_for(check(), get_settings().rag_request_timeout_sec)
        def save():
            _get(issue_id, principal)
            # 验证期间到达的新反馈/编辑会推进 updated_at，条件更新随即失败：
            # 旧验证结果不允许覆盖更新的问题状态。
            RagIssueService().mark_verified(issue_id, release, verdict.status,
                                            principal.subject, record["updated_at"])
        await asyncio.to_thread(save)
        return {"status": verdict.status, "answer": verdict.answer, "release_id": release}
    except HTTPException:
        raise
    except Exception:
        raise HTTPException(503, "原题验证暂时不可用") from None
    finally:
        await admission.release()
