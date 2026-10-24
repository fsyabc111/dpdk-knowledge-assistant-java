"""产品化主路径回归：使用真实服务与数据库，只替换模型/网络。"""
import asyncio
import copy
import json
from types import SimpleNamespace
from uuid import UUID, uuid4

import pytest
from fastapi import HTTPException

from app.core.auth import Principal
from app.db import get_conn
from app.schemas.chat import ChatRequest
from app.services.rag_index_service import plan_nodes
from app.services.rag_issue_service import RagIssueService, redact
from app.services.rag_product_service import Admission, RagProductService, Verdict, validate_answer
from app.services.session_service import SessionService


class Judge:
    def __init__(self, result):
        self.result = result
        self.calls = 0

    async def generate(self, messages, *, usage=None, stage=""):
        self.calls += 1
        return self.result


@pytest.mark.parametrize("answer,evidence,result,status,reason", [
    ("事实 [1]", {"chunks": [{"content": "事实"}]}, '{"status":"answered","supported":true}', "answered", "supported"),
    ("事实 [2]", {"chunks": [{"content": "事实"}]}, '{}', "unanswered", "invalid_citation"),
    ("事实", {"chunks": [{"content": "事实"}]}, '{"status":"answered","supported":true}', "unanswered", "missing_citation"),
    ("事实", {"chunks": []}, '{"status":"answered","supported":true}', "unanswered", "no_evidence"),
    ("事实 [1]", {"chunks": [{}]}, '{"status":"unanswered","supported":false,"reason":"conflicting_evidence"}', "unanswered", "conflicting_evidence"),
    ("事实 [1]", {"chunks": [{}]}, '```json\n{}\n```', "system_error", "validation_failed"),
    ("部分事实 [1]，其他资料不足", {"chunks": [{}]}, '{"status":"partial","supported":true}', "partial", "insufficient_evidence"),
    ("你好", {"chunks": []}, '{"status":"not_applicable","supported":true}', "not_applicable", "non_knowledge"),
])
async def test_evidence_gate(answer, evidence, result, status, reason):
    verdict = await validate_answer("问题", answer, evidence, Judge(result), 1)
    assert (verdict.status, verdict.reason) == (status, reason)
    if status in {"unanswered", "system_error"}:
        assert verdict.answer != answer


def _validation_spy(monkeypatch):
    """替换 metrics.record_rag_validation 为间谍，返回 (result, seconds) 调用记录。"""
    from app.core import metrics
    calls = []
    monkeypatch.setattr(metrics, "record_rag_validation", lambda result, seconds: calls.append((result, seconds)))
    return calls


async def test_validate_answer_records_duration(monkeypatch):
    calls = _validation_spy(monkeypatch)
    verdict = await validate_answer("问题", "事实 [1]", {"chunks": [{}]}, Judge('{"status":"answered","supported":true}'), 1)
    assert verdict.status == "answered"
    assert [call[0] for call in calls] == ["answered"]
    assert calls[0][1] >= 0


async def test_validate_answer_records_failure_and_early_return(monkeypatch):
    calls = _validation_spy(monkeypatch)
    verdict = await validate_answer("问题", "事实 [1]", {"chunks": [{}]}, Judge("非 JSON"), 1)
    assert verdict.status == "system_error"
    verdict = await validate_answer("问题", "  ", {"chunks": [{}]}, Judge(""), 1)
    assert verdict.status == "unanswered"
    assert [call[0] for call in calls] == ["system_error", "unanswered"]


async def test_validate_answer_records_cancel(monkeypatch):
    calls = _validation_spy(monkeypatch)

    class HangingJudge:
        async def generate(self, messages, *, usage=None, stage=""):
            await asyncio.Event().wait()

    task = asyncio.create_task(validate_answer("问题", "事实 [1]", {"chunks": [{}]}, HangingJudge(), 30))
    await asyncio.sleep(0)
    task.cancel()
    with pytest.raises(asyncio.CancelledError):
        await task
    assert [call[0] for call in calls] == ["cancelled"]


def test_index_identity_uses_exact_content_and_configuration():
    old = {"id": "li_existing", "content": "原始内容", "metadata": {"source_key": "repo/a.c", "ordinal": 0}}
    desired, changed = plan_nodes([copy.deepcopy(old)], [old], True)
    assert not changed and desired[0]["id"] == old["id"]
    desired, changed = plan_nodes([{**old, "content": "修改后"}], [old], True)
    assert len(changed) == 1 and desired[0]["id"] != old["id"]
    assert plan_nodes([copy.deepcopy(old)], [old], False)[1]


def test_redaction_excludes_code_and_credentials():
    source = "如何解析 DNS？\n```c\nint secret = 12;\n```\nAuthorization: Bearer hidden\n12345678901"
    cleaned = redact(source)
    assert "如何解析 DNS" in cleaned
    assert "secret" not in cleaned and "hidden" not in cleaned and "12345678901" not in cleaned


async def test_finish_is_atomic_feedback_is_unique(sqlite_db):
    sessions, issues = SessionService(), RagIssueService()
    session = await sessions.ensure_session(None, "问题", "student")
    task_id = issues.begin("request", "student", session, "问题", "", "model")
    message, metadata = issues.finish(task_id, session, "资料不足", [], "unanswered", "no_evidence", "", 1)
    assert metadata["issue_id"] == task_id
    issues.feedback(message, "student", "incorrect")
    issues.feedback(message, "student", "unresolved")
    with get_conn() as conn:
        assert conn.execute("SELECT COUNT(*) FROM rag_issues").fetchone()[0] == 1
        assert conn.execute("SELECT COUNT(*) FROM assistant_messages").fetchone()[0] == 1
    with pytest.raises(RuntimeError):
        issues.finish(task_id, session, "第二次", [], "answered", "supported", "", 1)
    with pytest.raises(LookupError):
        issues.feedback(message, "another", "incorrect")


async def test_issue_teacher_scope_and_resolution_gate(sqlite_db, monkeypatch):
    from app.api.rag import IssueUpdate, _get, update_issue
    from app.services.user_service import UserService
    await UserService().create_users(["U20250000001", "U20250000002"], ["admin"], 200)
    await UserService().create_users(["U20250000003"], ["viewer"], 200, created_by="U20250000001")
    session = await SessionService().ensure_session(None, "问题", "U20250000003")
    issues = RagIssueService()
    task_id = issues.begin("request", "U20250000003", session, "问题", "", "model")
    issues.finish(task_id, session, "未解答", [], "unanswered", "no_evidence", "", 1)
    teacher = Principal(subject="U20250000001", roles=["admin"], daily_quota=200)
    other = Principal(subject="U20250000002", roles=["admin"], daily_quota=200)
    assert _get(task_id, teacher)["question"] == "问题"
    with pytest.raises(HTTPException) as exc:
        _get(task_id, other)
    assert exc.value.status_code == 404
    with pytest.raises(HTTPException) as exc:
        await update_issue(task_id, IssueUpdate(status="resolved"), teacher)
    assert exc.value.status_code == 409


async def test_product_stream_persists_before_exposing_body(sqlite_db, monkeypatch):
    product = RagProductService(Principal(subject="student", roles=["viewer"], daily_quota=200), ChatRequest(message="问题"))
    await product.prepare()
    async def candidate(emit):
        await emit({"event": "status", "data": {"stage": "validation"}})
        return Verdict("经过校验的正文", "answered", "supported"), [], "release"
    monkeypatch.setattr(product, "_candidate", candidate)
    seen = []
    async for event in product.events():
        seen.append(event["event"])
        if event["event"] == "delta":
            assert product.persisted
            assert (await product.sessions.get_messages(product.session_id))[-1]["content"] == event["data"]["delta"]
    assert seen.index("status") < seen.index("delta") < seen.index("done")


async def test_http_retry_replays_without_second_candidate(sqlite_db, monkeypatch):
    from uuid import uuid4
    request = ChatRequest(message="问题", request_id=uuid4())
    principal = Principal(subject="student", roles=["viewer"], daily_quota=200)
    first = RagProductService(principal, request)
    await first.prepare()
    async def candidate(emit):
        return Verdict("可信正文", "answered", "supported"), [], "release"
    async def discard(event):
        pass
    monkeypatch.setattr(first, "_candidate", candidate)
    original = await first.run(discard)
    second = RagProductService(principal, request)
    await second.prepare()
    replay = await second.run(discard)
    assert replay.message_id == original.message_id and replay.answer == original.answer
    with get_conn() as conn:
        assert conn.execute("SELECT COUNT(*) FROM rag_requests").fetchone()[0] == 1
    # 幂等键不再全局唯一；跨用户新会话可复用，显式他人会话仍拒绝。
    outsider = RagProductService(Principal(subject="other", roles=["viewer"], daily_quota=200),
                                 request.model_copy(update={"session_id": first.session_id}))
    with pytest.raises(HTTPException) as exc:
        await outsider.prepare()
    assert exc.value.status_code == 409


async def test_queue_is_bounded_and_releases_on_timeout(monkeypatch):
    monkeypatch.setattr("app.services.rag_product_service.get_settings", lambda: SimpleNamespace(
        rag_max_active_requests=1, rag_max_waiting_requests=1, rag_queue_timeout_sec=0.01))
    gate = Admission()
    await gate.acquire()
    with pytest.raises(HTTPException) as exc:
        await gate.acquire()
    assert exc.value.status_code == 429 and gate.waiting == 0
    await gate.release()
    assert gate.active == 0


def test_bounded_chunks_preserve_long_tail():
    from app.services.knowledge_sources import chunk_code
    source = "#include <stdio.h>\nint parse(void) {\n" + "x" * 9000 + "\nreturn 0;\n}\n"
    chunks = chunk_code(source, "parse.c")
    assert all(len(c.content) <= 2600 for c in chunks)
    assert "#include" in chunks[0].content
    assert "return 0" in chunks[-1].content


async def test_real_graph_to_validation_and_issue_storage(sqlite_db, monkeypatch):
    from langchain_core.language_models.fake_chat_models import FakeMessagesListChatModel
    from langchain_core.messages import AIMessage

    from app.config import get_settings
    from app.rag.types import RetrievedChunk
    get_settings.cache_clear()
    model = FakeMessagesListChatModel(responses=[
        AIMessage(content="此中间内容不能显示", tool_calls=[{"name": "kb_search", "args": {"query": "DNS"}, "id": "search"}]),
        AIMessage(content="DNS 解析报文中的域名 [1]。"),
    ])
    monkeypatch.setattr("app.agent.service.build_agent_chat_model", lambda settings: model)
    async def search(self, query, limit=6, trace=None, conversation_context=None):
        return [RetrievedChunk(id="li_test", source_path="dns.c", title="DNS", section="parse",
                               content="DNS 解析报文中的域名", metadata={}, score=1)]
    monkeypatch.setattr("app.services.llama_index_service.LlamaIndexRetrievalAdapter.search", search)
    product = RagProductService(Principal(subject="student", roles=["viewer"], daily_quota=200),
                                ChatRequest(message="DNS 做什么"),
                                judge=Judge('{"status":"answered","supported":true}'))
    await product.prepare()
    events = [event async for event in product.events()]
    assert [e["data"]["delta"] for e in events if e["event"] == "delta"] == ["DNS 解析报文中的域名 [1]。"]
    assert any(e["event"] == "tool_start" for e in events)
    assert events[-1]["data"]["answer_status"] == "answered"


def _product_env(monkeypatch):
    """打开产品化开关并刷新 Settings 缓存（产品模式要求 llama_index 引擎）。"""
    from app.config import get_settings
    get_settings.cache_clear()


async def _post_chat(payload):
    from httpx import ASGITransport, AsyncClient

    from app.main import app
    async with AsyncClient(transport=ASGITransport(app=app), base_url="http://testserver") as client:
        return await client.post("/api/chat", json=payload)


def _seed_release(conn, release, configuration, members, physical, settings):
    from app.services.rag_index_service import data_table_name
    table = data_table_name(settings)
    conn.execute("INSERT INTO rag_releases(id,configuration,created_at,node_count) VALUES (?,?,?,?)",
                 (release, configuration, "2026-01-01T00:00:00+00:00", len(members)))
    for node in members:
        conn.execute("INSERT INTO rag_release_nodes(release_id,node_id) VALUES (?,?)", (release, node))
    for node in physical:
        conn.execute(f'INSERT INTO "{table}"(node_id) VALUES (?)', (node,))


async def test_replay_skips_daily_quota_and_rejects_foreign_question(sqlite_db, monkeypatch):
    """幂等重放不扣每日配额；同 request_id 携带不同问题返回 409。"""
    _product_env(monkeypatch)
    from app.core.auth import require_auth
    from app.db import get_conn
    from app.main import app

    monkeypatch.setitem(app.dependency_overrides, require_auth,
                        lambda: Principal(subject="student", roles=["viewer"], daily_quota=2))

    def charged():
        with get_conn() as conn:
            row = conn.execute("SELECT chat_count FROM usage_counters WHERE subject='student' AND kind='chat'").fetchone()
        return row[0] if row else 0

    async def candidate(self, emit):
        return Verdict("可信正文", "answered", "supported"), [], "release"
    monkeypatch.setattr(RagProductService, "_candidate", candidate)

    payload = {"message": "问题", "request_id": str(uuid4())}
    first = await _post_chat(payload)
    assert first.status_code == 200 and charged() == 1
    replay = await _post_chat(payload)
    assert replay.status_code == 200 and charged() == 1
    assert replay.json()["answer"] == "可信正文"
    conflict = await _post_chat({"message": "另一个问题", "request_id": payload["request_id"]})
    assert conflict.status_code == 409 and charged() == 1


@pytest.mark.parametrize("initial", [False, True])
async def test_concurrent_first_admission_charges_once(sqlite_db, initial):
    """两个首发同时通过早期 probe，最终仍由唯一键只放行一次配额。"""
    session = None if initial else await SessionService().ensure_session(None, "并发", "student")
    request_id = uuid4()
    principal = Principal(subject="student", roles=["viewer"], daily_quota=1)
    first = RagProductService(principal, ChatRequest(session_id=session, message="同一个问题", request_id=request_id))
    second = RagProductService(principal, ChatRequest(session_id=session, message="同一个问题", request_id=request_id))
    await asyncio.gather(first.probe_replay(), second.probe_replay())
    assert first.attached_row is None and second.attached_row is None

    await asyncio.gather(first.prepare(quota=1), second.prepare(quota=1))
    assert int(first.attached_row is None) + int(second.attached_row is None) == 1
    assert first.task_id == second.task_id
    with get_conn() as conn:
        charge = conn.execute("SELECT chat_count FROM usage_counters WHERE subject='student' AND kind='chat'").fetchone()
        requests = conn.execute("SELECT COUNT(*) FROM rag_requests").fetchone()[0]
        inputs = conn.execute("SELECT COUNT(*) FROM rag_task_inputs").fetchone()[0]
        turns = conn.execute("SELECT COUNT(*) FROM assistant_messages WHERE role='user'").fetchone()[0]
        sessions = conn.execute("SELECT COUNT(*) FROM assistant_sessions").fetchone()[0]
    assert charge[0] == 1 and (requests, inputs, turns) == (1, 1, 1)
    assert sessions == 1, "败方首问不留下空会话"


async def test_concurrent_start_releases_losing_admission_slot(sqlite_db, monkeypatch):
    from app.api.chat import start_product
    from app.services import task_service

    session = await SessionService().ensure_session(None, "并发", "student")
    request_id = uuid4()
    principal = Principal(subject="student", roles=["viewer"], daily_quota=1)
    products = [RagProductService(principal, ChatRequest(
        session_id=session, message="同一问题", request_id=request_id)) for _ in range(2)]
    gate = asyncio.Event()

    async def candidate(self, emit):
        await gate.wait()
        return Verdict("正文", "answered", "supported"), [], "release"

    monkeypatch.setattr(RagProductService, "_candidate", candidate)
    results = await asyncio.gather(*(start_product(product) for product in products))
    assert sum(managed is not None for managed in results) == 1
    assert task_service.admission.active == 1
    assert products[0].task_id == products[1].task_id
    gate.set()
    managed = next(managed for managed in results if managed is not None)
    await asyncio.wait_for(managed.done.wait(), 5)
    assert task_service.admission.active == 0


async def test_admission_input_failure_rolls_back_task_and_quota(sqlite_db, monkeypatch):
    def fail_store(self, conn, task_id, question, **kwargs):
        raise RuntimeError("synthetic input write failure")

    monkeypatch.setattr(RagIssueService, "_store_task_inputs_in_conn", fail_store)
    product = RagProductService(Principal(subject="student", roles=["viewer"], daily_quota=1),
                                ChatRequest(message="问题", request_id=uuid4()))
    with pytest.raises(RuntimeError, match="synthetic input"):
        await product.prepare(quota=1)
    with get_conn() as conn:
        assert conn.execute("SELECT COUNT(*) FROM rag_requests").fetchone()[0] == 0
        assert conn.execute("SELECT COUNT(*) FROM usage_counters").fetchone()[0] == 0
        assert conn.execute("SELECT COUNT(*) FROM assistant_messages").fetchone()[0] == 0


async def test_admission_quota_rejection_leaves_no_second_task(sqlite_db):
    principal = Principal(subject="student", roles=["viewer"], daily_quota=1)
    first = RagProductService(principal, ChatRequest(message="第一问", request_id=uuid4()))
    await first.prepare(quota=1)
    second = RagProductService(principal, ChatRequest(message="第二问", request_id=uuid4()))
    with pytest.raises(HTTPException) as exc:
        await second.prepare(quota=1)
    assert exc.value.status_code == 429
    with get_conn() as conn:
        assert conn.execute("SELECT COUNT(*) FROM rag_requests").fetchone()[0] == 1
        assert conn.execute("SELECT COUNT(*) FROM rag_task_inputs").fetchone()[0] == 1
        assert conn.execute("SELECT chat_count FROM usage_counters WHERE subject='student'").fetchone()[0] == 1


async def test_finish_failure_keeps_pending_inputs_for_recovery(sqlite_db, monkeypatch):
    from app.services import task_service

    product = RagProductService(Principal(subject="student", roles=["viewer"], daily_quota=1),
                                ChatRequest(message="问题", request_id=uuid4()))
    await product.prepare(quota=1)

    async def candidate(emit):
        return Verdict("正文", "answered", "supported"), [], "release"

    def fail_finish(*args, **kwargs):
        raise RuntimeError("synthetic finish failure")

    monkeypatch.setattr(product, "_candidate", candidate)
    monkeypatch.setattr(product.issues, "finish", fail_finish)
    managed = await task_service.start_task(product)
    await asyncio.wait_for(managed.done.wait(), 5)
    assert RagIssueService().get_task(product.task_id, "student")["status"] == "pending"
    assert RagIssueService().load_task_inputs(product.task_id) is not None
    assert product.task_id not in task_service.TASKS
    assert task_service.admission.active == 0


async def test_prepare_timeout_returns_503(sqlite_db, monkeypatch):
    """受理阶段整体超时返回 503，不提前登记 pending 行。"""
    import asyncio
    _product_env(monkeypatch)
    monkeypatch.setenv("RAG_PREPARE_TIMEOUT_SEC", "0.01")
    from app.config import get_settings
    get_settings.cache_clear()

    async def slow_prepare(self, quota=0):
        await asyncio.sleep(5)
    monkeypatch.setattr(RagProductService, "prepare", slow_prepare)
    response = await _post_chat({"message": "问题", "request_id": str(uuid4())})
    assert response.status_code == 503
    with get_conn() as conn:
        assert conn.execute("SELECT COUNT(*) FROM rag_requests").fetchone()[0] == 0


async def test_verify_conflicts_with_concurrent_feedback(sqlite_db):
    """验证期间到达的反馈使条件更新失败（409），verify 事件不落库。"""
    service = RagIssueService()
    request_id = str(uuid4())
    product = RagProductService(Principal(subject="student", roles=["viewer"], daily_quota=200),
                                ChatRequest(message="问题", request_id=UUID(request_id)))
    await product.prepare()

    async def candidate(emit):
        return Verdict("资料不足", "unanswered", "insufficient_evidence"), [], "release"

    async def discard(event):
        pass
    product._candidate = candidate
    result = await product.run(discard)
    with get_conn() as conn:
        issue = conn.execute("SELECT * FROM rag_issues WHERE request_id=?", (product.task_id,)).fetchone()
    stale_token = issue["updated_at"]
    # 反馈先于验证落库：推进 updated_at 并清空既有验证
    service.feedback(result.message_id, "student", "unresolved: 不对")
    with pytest.raises(HTTPException) as exc:
        service.mark_verified(product.task_id, "release-x", "answered", "teacher", stale_token)
    assert exc.value.status_code == 409
    with get_conn() as conn:
        assert conn.execute("SELECT COUNT(*) AS c FROM rag_issue_events WHERE action='verify'").fetchone()["c"] == 0
        row = conn.execute("SELECT verified_at,feedback FROM rag_issues WHERE request_id=?",
                           (product.task_id,)).fetchone()
        assert row["verified_at"] is None and row["feedback"]
    # 读取最新 updated_at 后验证可以落库
    with get_conn() as conn:
        fresh = conn.execute("SELECT updated_at FROM rag_issues WHERE request_id=?",
                             (product.task_id,)).fetchone()["updated_at"]
    service.mark_verified(product.task_id, "release-x", "answered", "teacher", fresh)
    with get_conn() as conn:
        row = conn.execute("SELECT verified_at FROM rag_issues WHERE request_id=?", (product.task_id,)).fetchone()
        assert row["verified_at"]


def test_release_health_states(sqlite_db):
    """就绪核对的四种状态：健康 / 物理缺失 / 配置错配 / 版本不存在。"""
    from app.config import get_settings
    from app.services.rag_index_service import data_table_name, index_configuration, release_health
    settings = get_settings()
    with get_conn() as conn:
        conn.execute(f'CREATE TABLE "{data_table_name(settings)}" (node_id TEXT PRIMARY KEY)')
        _seed_release(conn, "r-ok", index_configuration(settings), ["ok1", "ok2"], ["ok1", "ok2"], settings)
        _seed_release(conn, "r-broken", index_configuration(settings), ["bk1", "bk2"], ["bk1"], settings)
        _seed_release(conn, "r-stale", "{}", ["st1"], ["st1"], settings)
    assert release_health("r-ok", settings) == {"declared": 2, "members": 2, "physical": 2, "config_match": True}
    broken = release_health("r-broken", settings)
    assert (broken["declared"], broken["members"], broken["physical"]) == (2, 2, 1)
    assert not release_health("r-stale", settings)["config_match"]
    assert release_health("r-missing", settings)["declared"] == 0


def test_rollback_validates_configuration_and_completeness(sqlite_db):
    """回滚强制校验：版本存在、配置匹配、物理节点完整，缺一不可。"""
    from app.config import get_settings
    from app.services.rag_index_service import (
        active_release,
        data_table_name,
        index_configuration,
        rollback_release,
    )
    settings = get_settings()
    with get_conn() as conn:
        conn.execute(f'CREATE TABLE "{data_table_name(settings)}" (node_id TEXT PRIMARY KEY)')
        _seed_release(conn, "r-ok", index_configuration(settings), ["ok1"], ["ok1"], settings)
        _seed_release(conn, "r-stale", "{}", ["st1"], ["st1"], settings)
        _seed_release(conn, "r-broken", index_configuration(settings), ["bk1", "bk2"], ["bk1"], settings)
    with pytest.raises(ValueError, match="不存在"):
        rollback_release("r-missing")
    with pytest.raises(ValueError, match="不匹配"):
        rollback_release("r-stale")
    with pytest.raises(ValueError, match="不完整"):
        rollback_release("r-broken")
    rollback_release("r-ok")
    assert active_release() == "r-ok"


async def test_retrieval_fails_closed_on_config_mismatch(sqlite_db):
    """活动版本配置指纹与当前 Settings 不匹配时检索 fail-closed 返回空。"""
    from app.config import get_settings
    from app.services.llama_index_service import HybridDpdkRetriever
    from app.services.rag_index_service import data_table_name
    settings = get_settings()
    with get_conn() as conn:
        conn.execute(f'CREATE TABLE "{data_table_name(settings)}" (node_id TEXT PRIMARY KEY)')
        _seed_release(conn, "r-stale", "{}", ["n1"], ["n1"], settings)
        conn.execute("UPDATE rag_index_state SET release_id='r-stale' WHERE name='active'")
    retriever = HybridDpdkRetriever()
    assert await retriever.retrieve("问题", 3) == []
    assert retriever.release_blocked
    assert await retriever.retrieve("问题", 3) == []


def _product_trace_request_id(product):
    return product.trace.request_id


async def test_product_trace_reuses_the_api_request_id(sqlite_db):
    """D2：``trace.request_id`` 必须等于 API 给的 request_id，不是随机 ``trace_xxx``。

    P2 的 live 验收按 ``request_id`` 回读账本（``/api/usage/cost?request_id=``）。
    只要 trace 自己另起一个随机 id，落库行的 request_id 就与调用方手里的对不上，
    **逐题归因在真实后端永远读不到**——账本里只会留一堆无人认领的 ``trace_ab12…``。
    离线工具侧早就把 id 派生好了，缺的是产品路径把它接上。
    """
    request_id = uuid4()
    product = RagProductService(Principal(subject="student", roles=["viewer"], daily_quota=200),
                                ChatRequest(message="问题", request_id=request_id))
    assert _product_trace_request_id(product) == str(request_id)


async def test_product_path_writes_ledger_keyed_by_api_request_id(sqlite_db, monkeypatch):
    """D1：产品路径必须自己收口落账，且归属/会话可对账。

    收口在产品服务自身的 ``_log_trace``（``run()`` 的 finally），
    不经编排层的同名方法 ⇒ ``chat_trace`` 与账本都由产品路径自己落。
    本用例按**交付口径**断言：POST /api/chat 之后，用响应里的 request_id
    调读账本必须能读到这次的花费——而不是断言「某个函数被调过」。
    """
    _product_env(monkeypatch)

    async def candidate(self, emit):
        # 像真实适配器那样记一笔上游用量：fake 不调模型，但接线必须与线上一致。
        self.trace.usage.record_llm("answer", "qwen3.8-flash",
                                    {"prompt_tokens": 120, "completion_tokens": 30, "total_tokens": 150})
        return Verdict("可信正文 [1]", "answered", "supported"), [], "release"
    monkeypatch.setattr(RagProductService, "_candidate", candidate)

    request_id = str(uuid4())
    response = await _post_chat({"message": "问题", "request_id": request_id})
    assert response.status_code == 200 and response.json()["request_id"] == request_id

    from app.services.usage_service import UsageService
    summary = await asyncio.to_thread(UsageService().query, request_id=request_id)
    assert summary["totals"]["total_tokens"] == 150
    assert summary["totals"]["entries"] == 1

    with get_conn() as conn:
        row = conn.execute(
            "SELECT subject, session_id, usage_source FROM upstream_usage_records WHERE request_id = ?",
            (request_id,)).fetchone()
    assert row is not None, "产品路径没有落账（D1）"
    # 归属为空时 account 模式下的树隔离会把这条账藏起来：钱花了却查不到。
    assert row["subject"], "账本行必须带主体归属"
    assert row["session_id"], "账本行必须带会话归属，供按会话对账"
    assert row["usage_source"] == "upstream"


async def test_product_path_flushes_usage_on_failure_and_cancel(sqlite_db, monkeypatch):
    """生成抛异常、客户端断流都必须落账：花掉的 token 不会因为失败而消失。

    这条锁的是收口位置——只有把 flush 放进 ``run()`` 的 finally 才覆盖得到，
    放在成功返回之后会漏掉异常与取消两条路径。
    """
    _product_env(monkeypatch)
    from app.services.usage_service import UsageService

    async def failing(self, emit):
        self.trace.usage.record_llm("planner", "qwen3.8-flash", {"total_tokens": 42})
        raise RuntimeError("boom")
    monkeypatch.setattr(RagProductService, "_candidate", failing)
    failed_id = str(uuid4())
    response = await _post_chat({"message": "问题", "request_id": failed_id})
    assert response.status_code == 200 and response.json()["answer_status"] == "system_error"
    assert (await asyncio.to_thread(UsageService().query, request_id=failed_id))["totals"]["total_tokens"] == 42

    started = asyncio.Event()

    async def hanging(self, emit):
        self.trace.usage.record_llm("planner", "qwen3.8-flash", {"total_tokens": 7})
        started.set()
        await asyncio.sleep(30)
        return Verdict("正文", "answered", "supported"), [], "release"
    monkeypatch.setattr(RagProductService, "_candidate", hanging)
    cancelled_id = str(uuid4())
    product = RagProductService(Principal(subject="student", roles=["viewer"], daily_quota=200),
                                ChatRequest(message="问题", request_id=UUID(cancelled_id)))
    await product.prepare()
    stream = product.events()
    async for _ in stream:      # 只看首帧：模拟客户端拿到 start 就断开
        break
    # 先等这笔钱真的花出去，否则「落账了」和「压根没跑」两种情况分不开。
    await asyncio.wait_for(started.wait(), 5)
    await stream.aclose()
    # S3-c 契约变更（B13）：订阅方断开不再取消执行——断线≠取消，run 由注册表
    # 继续托管。这条用例要锁的是「取消路径也落账」，现在取消的唯一入口是
    # task_service.cancel 显式取消。
    from app.services import task_service
    await task_service.cancel(product.task_id, "student", "user_cancelled")
    assert (await asyncio.to_thread(UsageService().query, request_id=cancelled_id))["totals"]["total_tokens"] == 7


def test_bound_evidence_keeps_every_ref_and_fits_the_budget():
    """超预算时**片段一个不少**，只缩短正文；引用编号与顺序原样保留。

    为什么不去掉片段：``validate_answer`` 按**位置**校验引用编号
    （``ref > len(chunks)`` 即 invalid_citation），而模型答案里的 ``[n]`` 是按累积
    顺序发的。删掉片段会让 ``[n]`` 静默指向另一条片段——那比「资料不足」更糟。
    """
    from app.services.rag_product_service import bound_evidence

    chunks = [{"ref": i, "source": f"src/{i}.c", "content": "y" * 1500} for i in range(1, 21)]
    bounded = bound_evidence({"chunks": chunks, "tools": []}, 24000)
    assert len(bounded["chunks"]) == 20, "片段不能因为超预算被丢掉"
    assert [c["ref"] for c in bounded["chunks"]] == list(range(1, 21)), "编号与顺序必须原样"
    assert all(c["content"] for c in bounded["chunks"]), "每片段都要留下可核对的正文"
    assert all(c["content"] == bounded["chunks"][0]["content"] for c in bounded["chunks"]), "分配是确定的"
    assert len(json.dumps(bounded, ensure_ascii=False)) <= 24000
    assert bounded["chunks"][0]["source"] == "src/1.c", "元数据不因裁剪丢失"

    # 不超预算时逐字不动：这条守卫防止「顺手截短」变成默认行为。
    small = {"chunks": [{"ref": 1, "source": "a.c", "content": "短正文"}], "tools": []}
    assert bound_evidence(small, 24000) == small


async def test_oversized_evidence_still_reaches_the_judge(sqlite_db, monkeypatch):
    """证据超预算时必须**收窄后照常送审**，而不是直接判「资料不足」。

    这是 P2.1 的头号失败模式：15 题里 9 题死在 ``evidence_budget``——检索明明成功了
    （实测召回 72 条 / 67651 字符），却因为证据装不进 24000 字符预算而硬判拒答。
    旧行为还有第二处不对称：``citations`` 由**全部** chunks 生成、evidence 只装得下
    一部分，两边不同源。修好后断言的是「题目真的走到了校验」+「送审证据有界」+
    「引用编号与送审片段一一对应」，比「有没有打日志」强。
    """
    import json as _json
    import logging

    from langchain_core.language_models.fake_chat_models import FakeMessagesListChatModel
    from langchain_core.messages import AIMessage

    from app.config import get_settings
    from app.rag.types import RetrievedChunk
    get_settings.cache_clear()
    model = FakeMessagesListChatModel(responses=[
        AIMessage(content="中间内容", tool_calls=[{"name": "kb_search", "args": {"query": "DNS"}, "id": "search"}]),
        AIMessage(content="DNS 解析报文中的域名 [1]。"),
    ])
    monkeypatch.setattr("app.agent.service.build_agent_chat_model", lambda settings: model)

    async def search(self, query, limit=6, trace=None, conversation_context=None):
        # 真实机制是「每轮 kb_search（limit=8）把结果全量追加」：一次搜索多回来几条
        # 就足以顶过预算，不需要动设置项。
        return [RetrievedChunk(id=f"li_test_{i}", source_path="dns.c", title="DNS", section="parse",
                               content="x" * 1500, metadata={}, score=1) for i in range(20)]
    monkeypatch.setattr("app.services.llama_index_service.LlamaIndexRetrievalAdapter.search", search)

    seen = {}

    class RecordingJudge:
        async def generate(self, messages, *, usage=None, stage=""):
            seen["evidence"] = _json.loads(messages[1]["content"])["evidence"]
            return '{"status":"answered","supported":true}'

    captured = []

    class _Sink(logging.Handler):
        def emit(self, record):
            try:
                captured.append(_json.loads(record.getMessage()))
            except (ValueError, TypeError):
                pass

    sink = _Sink()
    logger = logging.getLogger("assistant")
    logger.addHandler(sink)
    try:
        product = RagProductService(Principal(subject="student", roles=["viewer"], daily_quota=200),
                                    ChatRequest(message="DNS 做什么"), judge=RecordingJudge())
        await product.prepare()
        response = await product.run(lambda event: asyncio.sleep(0))
    finally:
        logger.removeHandler(sink)

    assert response.answer_status == "answered", "超预算不等于资料不足，必须照常送审"
    evidence = seen["evidence"]
    limit = product.settings.rag_context_max_chars
    assert len(_json.dumps(evidence, ensure_ascii=False)) <= limit, "送审证据必须有界"
    assert [c["ref"] for c in evidence["chunks"]] == list(range(1, 21)), "编号与顺序不变"
    # citations 与送审 evidence 同源：长度一致，且逐条对应同一个 chunk。
    assert len(response.citations) == len(evidence["chunks"])
    assert [c.source_path for c in response.citations] == [c["source"] for c in evidence["chunks"]]

    events = [e for e in captured if e.get("event") == "rag_evidence_bounded"]
    assert len(events) == 1, f"收窄过就必须留下一条可诊断的日志，实际 {captured}"
    assert events[0]["chunks"] == 20 and events[0]["distinct_chunks"] == 20
    assert events[0]["evidence_chars"] > limit and events[0]["limit"] == limit
    assert events[0]["kept_chars"] <= limit
    assert events[0]["request_id"] == response.request_id


def test_judge_budget_is_the_remainder_not_a_fresh_wall():
    """judge 的预算是**请求剩余**，不是一个独立常数；且永远活不过请求墙。"""
    from app.services.rag_product_service import judge_budget

    # 预算充裕 → 上限说了算
    assert judge_budget(120, 10, 60) == 60
    # 已经用掉大半 → 剩余说了算（旧写法这里仍是 60，于是 judge 可活到 110+60 > 120）
    assert judge_budget(120, 90, 60) == 30
    # 已经超预算 → 给下限做一次**快速可诊断**的失败，而不是 0 秒或负数
    assert judge_budget(120, 200, 60) == 1.0
    assert judge_budget(120, 120, 60) == 1.0

    # 不变式：judge 的死线不得晚于请求墙——这正是旧配置丢失的性质
    # （20 秒固定 + 120 秒请求墙 ⇒ 最坏 140 秒，两个墙同时到期、归因看竞态）。
    for request_budget, elapsed, cap in ((120, 0, 60), (120, 90, 60), (120, 200, 60), (2, 0.2, 60)):
        assert elapsed + judge_budget(request_budget, elapsed, cap) <= max(request_budget, elapsed + 1.0)


async def test_judge_wall_fires_before_the_request_wall(sqlite_db, monkeypatch):
    """judge 卡住时必须**稳定**归因为 ``validation_failed``，不与请求墙抢跑。

    修前：judge 拿独立的 20 秒、请求墙 120 秒，两者相差 100 秒，同一个「慢」报成哪种
    reason 取决于生成花了多久——P2.1 的 8 次 system_error 全部是我们的墙（6 次 judge 墙、
    2 次请求墙），没有一次是上游故障，却因为标签随机而被记成两类不同的问题。
    这里把请求预算压到 2 秒、judge 上限留 60 秒：按剩余预算发牌后，judge 的死线必然
    早于请求墙，结论必须是 ``validation_failed`` 而不是 ``generation_failed``。
    """
    import logging

    from langchain_core.language_models.fake_chat_models import FakeMessagesListChatModel
    from langchain_core.messages import AIMessage

    from app.config import get_settings
    from app.rag.types import RetrievedChunk
    monkeypatch.setenv("RAG_REQUEST_TIMEOUT_SEC", "2")
    get_settings.cache_clear()
    # 一轮检索 + 一句带引用的答案：生成阶段很短，judge 能拿到近乎完整的剩余预算。
    model = FakeMessagesListChatModel(responses=[
        AIMessage(content="中间内容", tool_calls=[{"name": "kb_search", "args": {"query": "DNS"}, "id": "search"}]),
        AIMessage(content="DNS 解析域名 [1]。"),
    ])
    monkeypatch.setattr("app.agent.service.build_agent_chat_model", lambda settings: model)

    async def search(self, query, limit=6, trace=None, conversation_context=None):
        return [RetrievedChunk(id="li_test_1", source_path="dns.c", title="DNS", section="parse",
                               content="dns 入口", metadata={}, score=1)]
    monkeypatch.setattr("app.services.llama_index_service.LlamaIndexRetrievalAdapter.search", search)

    class HangingJudge:
        async def generate(self, messages, *, usage=None, stage=""):
            await asyncio.Event().wait()

    captured = []
    # ``rag_validation_failed`` 走 ``extra=`` 而不是把 JSON 塞进 message，所以按
    # JsonFormatter 白名单里的键读 record——这正是生产日志里真正落下去的那几个字段。
    _KEYS = ("event", "request_id", "error_type", "reason", "answer_status", "stage", "duration_ms")

    class _Sink(logging.Handler):
        def emit(self, record):
            captured.append({k: record.__dict__[k] for k in _KEYS if k in record.__dict__})

    sink = _Sink()
    logger = logging.getLogger("assistant")
    logger.addHandler(sink)
    try:
        product = RagProductService(Principal(subject="student", roles=["viewer"], daily_quota=200),
                                    ChatRequest(message="DNS 做什么"), judge=HangingJudge())
        await product.prepare()
        response = await product.run(lambda event: asyncio.sleep(0))
    finally:
        logger.removeHandler(sink)

    assert response.answer_status == "system_error"
    assert response.answer_reason == "validation_failed", \
        f"judge 墙必须先于请求墙到期，实际 {response.answer_reason}"

    failures = [e for e in captured if e.get("event") == "rag_validation_failed"]
    assert len(failures) == 1, f"校验失败必须留下恰好一条日志，实际 {captured}"
    assert failures[0]["error_type"] == "TimeoutError"
    # 归因：修前这 7 条日志没有 request_id，只能靠时间顺序猜是哪一次请求。
    assert failures[0]["request_id"] == response.request_id

    # 阶段耗时在**失败**路径也必须留下——这正是用 timed_stage 而不是循环后补一行的理由。
    stages = product.trace.stages_ms
    assert stages.get("validation_ms", 0) >= 1000, f"judge 阶段耗时缺失或过小：{stages}"
    assert "answer_generation_ms" in stages, f"生成阶段耗时缺失：{stages}"


async def test_ready_endpoint_checks_physical_nodes(sqlite_db, monkeypatch):
    """/api/ready：无活动版本 503 → 健康 200 → 物理节点缺失 503。"""
    _product_env(monkeypatch)
    from httpx import ASGITransport, AsyncClient

    from app.config import get_settings
    from app.main import app
    from app.services.rag_index_service import data_table_name, index_configuration
    settings = get_settings()
    with get_conn() as conn:
        conn.execute(f'CREATE TABLE "{data_table_name(settings)}" (node_id TEXT PRIMARY KEY)')
    async with AsyncClient(transport=ASGITransport(app=app), base_url="http://testserver") as client:
        assert (await client.get("/api/ready")).status_code == 503
        with get_conn() as conn:
            _seed_release(conn, "r-ok", index_configuration(settings), ["n1"], ["n1"], settings)
            conn.execute("UPDATE rag_index_state SET release_id='r-ok' WHERE name='active'")
        assert (await client.get("/api/ready")).status_code == 200
        with get_conn() as conn:
            conn.execute(f'DELETE FROM "{data_table_name(settings)}" WHERE node_id=\'n1\'')
        assert (await client.get("/api/ready")).status_code == 503
