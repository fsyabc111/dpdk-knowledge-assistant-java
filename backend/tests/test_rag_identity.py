"""S2 原始输入身份、会话幂等与服务端任务身份的确定性回归。"""
import hashlib
from uuid import uuid4

import pytest
from fastapi import HTTPException

from app.core.auth import Principal
from app.db import get_conn
from app.schemas.chat import ChatRequest, UploadedCode
from app.services.rag_issue_service import RagIssueService, input_hash
from app.services.rag_product_service import RagProductService, Verdict


def attachment(data, name="plugin.c"):
    return UploadedCode(name, ".c", data.decode("utf-8", errors="replace"),
                        content_hash=hashlib.sha256(data).hexdigest())


async def complete(request, upload=None):
    product = RagProductService(Principal(subject="student", roles=["viewer"], daily_quota=200), request, upload)
    await product.prepare()
    async def candidate(emit):
        return Verdict("正文", "answered", "supported"), [], "release"
    async def discard(event):
        pass
    product._candidate = candidate
    return product, await product.run(discard)


@pytest.mark.parametrize("first,second", [
    ("问题```c\nreturn 1;\n```", "问题```c\nreturn 2;\n```"),
    ("问题 Authorization: Bearer aaa", "问题 Authorization: Bearer bbb"),
])
async def test_original_text_conflicts_after_redaction(sqlite_db, first, second):
    key = uuid4()
    product, result = await complete(ChatRequest(message=first, request_id=key))
    with pytest.raises(HTTPException) as error:
        await complete(ChatRequest(message=second, request_id=key))
    assert error.value.status_code == 409
    with get_conn() as conn:
        row = conn.execute("SELECT * FROM rag_requests WHERE id=?", (result.task_id,)).fetchone()
    assert row["request_id"] == str(key) and result.task_id != str(key)
    assert row["input_hash"] == input_hash(first)
    assert "return 1" not in row["question"]
    _, replay = await complete(ChatRequest(message=first, request_id=key, session_id=product.session_id))
    assert replay.task_id == result.task_id and replay.message_id == result.message_id


@pytest.mark.parametrize("changed", [attachment(b"return 2;"), attachment(b"return 1;", "other.c")])
async def test_attachment_bytes_and_filename_participate(sqlite_db, changed):
    request = ChatRequest(message="评测", request_id=uuid4())
    _, original = await complete(request, attachment(b"return 1;"))
    _, replay = await complete(request, attachment(b"return 1;"))
    assert replay.task_id == original.task_id
    with pytest.raises(HTTPException) as error:
        await complete(request, changed)
    assert error.value.status_code == 409


def test_normalization_framing_and_raw_bytes():
    assert input_hash(" e\u0301 \n") == input_hash("é")
    assert input_hash("a  b") != input_hash("a b")
    assert input_hash("a", attachment(b"x", "bc")) != input_hash("ab", attachment(b"x", "c"))
    assert attachment(b"\xff").source == attachment(b"\xfe").source
    assert input_hash("x", attachment(b"\xff")) != input_hash("x", attachment(b"\xfe"))
    assert input_hash("x") != input_hash("x", attachment(b""))


async def test_key_is_session_scoped_and_distinct_requests_can_be_pending(sqlite_db):
    from app.services.session_service import SessionService
    sessions = SessionService()
    one = await sessions.ensure_session(None, "one", "student")
    two = await sessions.ensure_session(None, "two", "student")
    issues = RagIssueService()
    a = issues.begin("key", "student", one, "q", "", "model")
    b = issues.begin("key", "student", two, "q", "", "model")
    c = issues.begin("other", "student", one, "q", "", "model")
    assert len({a, b, c}) == 3
    with pytest.raises(HTTPException):
        issues.begin("key", "student", one, "q", "", "model")


async def test_historical_unknown_hash_fails_closed(sqlite_db):
    product, result = await complete(ChatRequest(message="q", request_id=uuid4()))
    with get_conn() as conn:
        conn.execute("UPDATE rag_requests SET input_hash=NULL WHERE id=?", (result.task_id,))
    for session in (None, product.session_id):
        with pytest.raises(HTTPException) as error:
            await complete(product.request.model_copy(update={"session_id": session}))
        assert error.value.status_code == 409
