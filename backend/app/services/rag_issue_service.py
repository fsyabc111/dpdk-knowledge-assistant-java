"""请求先留存、问题后归类；不保存附件源码，JSON 结构通过标准编码处理。"""
from __future__ import annotations

import hashlib
import json
import re
import unicodedata
import uuid
from datetime import datetime, timedelta, timezone

from ..config import get_settings
from ..db import get_conn, get_database_provider
from .rag_index_service import now


def redact(text: str) -> str:
    # 围栏块认 ``` 与 ~~~ 两种（B8 与 extract_fenced_source 同口径）；反向
    # 引用 \1 保证配对闭合——``` 块不会被 ~~~ 误闭合，反之亦然；尾围栏未
    # 闭合时其后内容整体视为代码。
    text = re.sub(r"(```|~~~)[\s\S]*?(?:\1|$)", "[代码已省略]", text)
    text = re.sub(r"\[上传附件[：:][^\]]*\]", "[附件已省略]", text)
    text = re.sub(r"\bU\d{11}\b", "[学号已省略]", text)
    # 对疑似代码/凭据的整行省略，避免截短后留下秘密的前缀。
    lines = []
    for line in text.splitlines():
        if re.search(r"(?i)(#include|\b(?:api[_-]?key|password|authorization|bearer|token|secret)\b\s*[:= ]|"
                     r"sk-[\w-]{8,}|\b(?:return|typedef|struct)\b.*[;{}]|[;{}]\s*$)", line):
            lines.append("[敏感内容已省略]")
        else:
            line = re.sub(r"[\w.+-]+@[\w.-]+\.[A-Za-z]{2,}", "[邮箱已省略]", line)
            line = re.sub(r"(?<!\d)\d{7,}(?!\d)", "[号码已省略]", line)
            lines.append(line)
    return "\n".join(lines)[:12000]


def input_hash(question, attachment=None):
    """身份只做 NFC/首尾空白归一化；JSON 数组分帧避免拼接歧义。"""
    def normalize(value):
        return unicodedata.normalize("NFC", value).strip()
    digest = None
    filename = None
    if attachment is not None:
        filename = normalize(attachment.filename)
        digest = attachment.content_hash
        if not digest:
            raise ValueError("附件缺少原始字节身份")
    encoded = json.dumps([normalize(question), filename, digest], ensure_ascii=False, separators=(",", ":"))
    return hashlib.sha256(encoded.encode("utf-8")).hexdigest()


class RagIssueService:
    @property
    def ph(self):
        return get_database_provider().placeholder

    def begin(self, request_id, user_id, session_id, question, context, model, *, input_digest=None,
              initial_request=False):
        task_id = uuid.uuid4().hex
        ph = self.ph
        with get_conn() as conn:
            count = conn.execute(
                f"INSERT INTO rag_requests(id,request_id,input_hash,initial_request,user_id,session_id,question,"
                f"context_text,model_version,created_at,updated_at) VALUES ({','.join([ph] * 11)}) "
                "ON CONFLICT DO NOTHING",
                (task_id, request_id, input_digest or input_hash(question), int(initial_request), user_id,
                 session_id, redact(question), redact(context)[-2000:], model, now(), now()),
            ).rowcount
            if count != 1:
                from fastapi import HTTPException
                raise HTTPException(409, "该请求已受理，请读取会话结果后重试")
        return task_id

    def begin_admitted(self, request_id, user_id, session_id, question, context, model, *,
                       input_digest, initial_request, quota, purpose="chat", attachment_name=None,
                       attachment_suffix=None, source_text=None, source_origin=None):
        """原子受理：唯一任务、恢复输入、用户消息与日配额同提交。

        唯一键冲突返回 None，调用方按既有 task 接续；配额/寄存失败回滚全笔，
        不留下无输入的 pending 行或重复扣费。会话归属已由调用方检查。
        """
        from ..core.rate_limit import rate_limiter

        ph = self.ph
        task_id = uuid.uuid4().hex
        message_id = f"msg_{uuid.uuid4().hex[:12]}"
        created = now()
        provider = get_database_provider()
        with get_conn() as conn:
            from .user_service import lock_enabled_account
            if not lock_enabled_account(conn, user_id):
                from fastapi import HTTPException
                raise HTTPException(403, "账号已停用，不能受理新任务")
            changed = conn.execute(
                f"INSERT INTO rag_requests(id,request_id,input_hash,initial_request,purpose,user_id,session_id,question,"
                f"context_text,model_version,created_at,updated_at) VALUES ({','.join([ph] * 12)}) "
                "ON CONFLICT DO NOTHING",
                (task_id, request_id, input_digest, int(initial_request), purpose, user_id,
                 session_id, redact(question), redact(context)[-2000:], model, created, created),
            ).rowcount
            if changed != 1:
                return None
            self._store_task_inputs_in_conn(
                conn, task_id, question, attachment_name=attachment_name,
                attachment_suffix=attachment_suffix, source_text=source_text,
                source_origin=source_origin,
            )
            conn.execute(
                f"INSERT INTO assistant_messages(id,session_id,role,content,metadata_json,created_at) "
                f"VALUES ({ph},{ph},{ph},{ph},{ph}{provider.metadata_cast},{ph})",
                (message_id, session_id, "user", redact(question),
                 json.dumps({"request_id": request_id, "task_id": task_id}, ensure_ascii=False), created),
            )
            conn.execute(f"UPDATE assistant_sessions SET updated_at={ph} WHERE id={ph}", (created, session_id))
            rate_limiter.count_chat_admission_in_conn(conn, user_id, quota)
        return task_id

    def probe(self, request_id, user_id, question, *, session_id=None, input_digest=None, purpose="chat"):
        """三态重放探测（B11 修复的查询侧）：

        - 无行 → ``("miss", None, None)``；
        - user/input_hash 不匹配 → 仍抛 409（原语义保留）；
        - ``status='pending'`` → ``("in_progress", row, None)``，不再 409；
        - 已终态但 ``message_id`` 缺失（取消/错误终态无正文）→ ``("terminal", row, None)``；
        - 正常完成 → ``("replay", row, message)``，与原 ``replay()`` 返回形态一致。
        """
        from fastapi import HTTPException
        ph = self.ph
        with get_conn() as conn:
            if session_id is None:
                row = conn.execute(f"SELECT * FROM rag_requests WHERE request_id={ph} AND user_id={ph} "
                                   "AND initial_request=1", (request_id, user_id)).fetchone()
            else:
                row = conn.execute(f"SELECT * FROM rag_requests WHERE request_id={ph} AND session_id={ph}",
                                   (request_id, session_id)).fetchone()
            if not row:
                return "miss", None, None
            if row["user_id"] != user_id:
                raise HTTPException(409, "request_id 输入身份不匹配或不可验证，请更换 request_id")
            # 讲解由服务端按成绩行生成固定身份。旧讲解的正文含可修改标题，
            # 仅这个经确定性 uuid5 核实的入口允许标题漂移；普通 chat 始终按 hash 拒绝。
            explain_identity = False
            if purpose == "explain" and row["purpose"] == "explain" and row["session_id"] and row["input_hash"]:
                from .rag_product_service import explain_request_identity
                sid = str(row["session_id"])
                if sid.startswith("explain-"):
                    expected_session, expected_request = explain_request_identity(sid[len("explain-"):])
                    explain_identity = sid == expected_session and row["request_id"] == expected_request
            if not explain_identity and (not row["input_hash"] or row["input_hash"] != (input_digest or input_hash(question))):
                raise HTTPException(409, "request_id 输入身份不匹配或不可验证，请更换 request_id")
            if row["status"] == "pending":
                return "in_progress", dict(row), None
            if not row["message_id"]:
                return "terminal", dict(row), None
            message = conn.execute(f"SELECT content,metadata_json FROM assistant_messages WHERE id={ph}",
                                   (row["message_id"],)).fetchone()
            if not message:
                raise HTTPException(409, "该请求的会话已删除，请新建请求")
            return "replay", dict(row), dict(message)

    def replay(self, request_id, user_id, question, *, session_id=None, input_digest=None):
        """原两态接口保留：内部走 ``probe``，非重放命中照旧抛 409，
        以免其他调用方行为突变（当前唯一调用方是 rag_product_service.probe_replay）。"""
        from fastapi import HTTPException
        state, row, message = self.probe(request_id, user_id, question,
                                         session_id=session_id, input_digest=input_digest)
        if state == "miss":
            return None
        if state != "replay":
            raise HTTPException(409, "该请求已受理或不可用，请读取会话结果")
        return row, message

    def get_task(self, task_id, user_id):
        """按 task_id 读任务行；不存在或 user_id 不匹配都返回 None（404 语义，不区分）。"""
        with get_conn() as conn:
            row = conn.execute(f"SELECT * FROM rag_requests WHERE id={self.ph}", (task_id,)).fetchone()
        if not row or row["user_id"] != user_id:
            return None
        return dict(row)

    def active_task_for_session(self, session_id, user_id):
        """会话内最近一条 pending 任务行（updated_at 倒序），供断线重连接续。"""
        ph = self.ph
        with get_conn() as conn:
            row = conn.execute(
                f"SELECT * FROM rag_requests WHERE session_id={ph} AND user_id={ph} AND status='pending' "
                "ORDER BY updated_at DESC LIMIT 1", (session_id, user_id)).fetchone()
        return dict(row) if row else None

    def cancel_task(self, task_id, reason, actor_id):
        """条件更新 pending→cancelled（reason 记 'user_cancelled'/'account_disabled' 等来源）。

        同事务写一条 role='assistant' 的占位消息（metadata 带 stream_status='cancelled'、
        task_id、request_id）并回填 message_id——占位消息让会话重载时取消可见，
        也让 probe 按 replay 有行可读；随后清理 rag_task_inputs。已非 pending 返回 False。
        会话已被清理（session_id 为 NULL）时跳过占位消息，行保持 terminal 无正文。
        """
        ph = self.ph
        provider = get_database_provider()
        message_id = uuid.uuid4().hex
        with get_conn() as conn:
            changed = conn.execute(
                f"UPDATE rag_requests SET status='cancelled',reason={ph},updated_at={ph} "
                f"WHERE id={ph} AND status='pending'", (reason, now(), task_id)).rowcount
            if changed != 1:
                return False
            row = conn.execute(f"SELECT session_id,request_id FROM rag_requests WHERE id={ph}",
                               (task_id,)).fetchone()
            if row["session_id"]:
                metadata = {"stream_status": "cancelled", "task_id": task_id,
                            "request_id": row["request_id"], "actor_id": actor_id,
                            "answer_status": "cancelled", "answer_reason": reason}
                conn.execute(
                    f"INSERT INTO assistant_messages(id,session_id,role,content,metadata_json,created_at) "
                    f"VALUES ({ph},{ph},{ph},{ph},{ph}{provider.metadata_cast},{ph})",
                    (message_id, row["session_id"], "assistant", "（请求已取消）",
                     json.dumps(metadata, ensure_ascii=False), now()),
                )
                conn.execute(f"UPDATE rag_requests SET message_id={ph} WHERE id={ph}",
                             (message_id, task_id))
                conn.execute(f"UPDATE assistant_sessions SET updated_at={ph} WHERE id={ph}",
                             (now(), row["session_id"]))
            conn.execute(f"DELETE FROM rag_task_inputs WHERE task_id={ph}", (task_id,))
        return True

    def orphaned_pending(self, grace_seconds=30):
        """status='pending' 且 updated_at 早于 now-grace 的行（恢复扫描对象，最旧在前）。

        grace 窗口避开「begin 已落库但任务尚未注册」的瞬时态；多副本下在跑行
        由属主心跳拨新 updated_at，本查询天然只命中真孤儿。
        """
        cutoff = (datetime.now(timezone.utc) - timedelta(seconds=grace_seconds)).isoformat()
        with get_conn() as conn:
            rows = conn.execute(
                f"SELECT * FROM rag_requests WHERE status='pending' AND updated_at<{self.ph} "
                "ORDER BY updated_at", (cutoff,)).fetchall()
        return [dict(row) for row in rows]

    def claim_recovery(self, task_id, grace_seconds=30):
        """重驱动认领闸：pending 且仍陈旧才拨新 updated_at；否则 rowcount=0 拒绝。

        多副本下两个副本可能同时相中同一孤儿行；单条原子 UPDATE 保证只有先
        到者认领成功，后到者看到「刚被拨新」直接放弃——防止恢复路径双执行。
        """
        cutoff = (datetime.now(timezone.utc) - timedelta(seconds=grace_seconds)).isoformat()
        with get_conn() as conn:
            changed = conn.execute(
                f"UPDATE rag_requests SET updated_at={self.ph} "
                f"WHERE id={self.ph} AND status='pending' AND updated_at<{self.ph}",
                (now(), task_id, cutoff)).rowcount
        return changed == 1

    def mark_interrupted(self, task_id, reason="interrupted"):
        """条件更新 pending→system_error/reason，登记 rag_issues 并清理临时输入。"""
        ph = self.ph
        with get_conn() as conn:
            changed = conn.execute(
                f"UPDATE rag_requests SET status='system_error',reason={ph},updated_at={ph} "
                f"WHERE id={ph} AND status='pending'", (reason, now(), task_id)).rowcount
            if changed != 1:
                return False
            self._issue(conn, task_id)
            conn.execute(f"DELETE FROM rag_task_inputs WHERE task_id={ph}", (task_id,))
        return True

    # ---- rag_task_inputs：任务执行输入的临时保管（§8，仅归属用户任务 scope）----

    def store_task_inputs(self, task_id, question, *, attachment_name=None,
                          attachment_suffix=None, source_text=None, source_origin=None,
                          ttl_hours=None):
        """寄存执行输入；expires_at = 受理时刻 + ttl（默认取 settings，§8 的 24h 兜底）。"""
        with get_conn() as conn:
            self._store_task_inputs_in_conn(
                conn, task_id, question, attachment_name=attachment_name,
                attachment_suffix=attachment_suffix, source_text=source_text,
                source_origin=source_origin, ttl_hours=ttl_hours,
            )

    def _store_task_inputs_in_conn(self, conn, task_id, question, *, attachment_name=None,
                                   attachment_suffix=None, source_text=None, source_origin=None,
                                   ttl_hours=None):
        ttl = ttl_hours if ttl_hours is not None else get_settings().rag_task_input_ttl_hours
        created = datetime.now(timezone.utc)
        expires = created + timedelta(hours=ttl)
        ph = self.ph
        columns = (task_id, question, attachment_name, attachment_suffix,
                   source_text, source_origin, created.isoformat(), expires.isoformat())
        conn.execute(
            f"INSERT INTO rag_task_inputs(task_id,question,attachment_name,attachment_suffix,"
            f"source_text,source_origin,created_at,expires_at) VALUES ({','.join([ph] * 8)}) "
            f"ON CONFLICT(task_id) DO UPDATE SET question={ph},attachment_name={ph},"
            f"attachment_suffix={ph},source_text={ph},source_origin={ph},created_at={ph},expires_at={ph}",
            columns + columns[1:],
        )

    def load_task_inputs(self, task_id):
        """读回临时输入；过期行视为不存在并顺手删除（周期兜底在 maintain）。"""
        ph = self.ph
        with get_conn() as conn:
            row = conn.execute(f"SELECT * FROM rag_task_inputs WHERE task_id={ph}", (task_id,)).fetchone()
            if not row:
                return None
            if row["expires_at"] <= now():
                conn.execute(f"DELETE FROM rag_task_inputs WHERE task_id={ph}", (task_id,))
                return None
            return dict(row)

    def update_task_source(self, task_id, source_text, source_origin):
        """回填执行源两列（grade_plugin 的 model_args 首评路径用）。

        只写 source_text/source_origin——问题列在受理时已写好，账本层拿不到
        也不该碰原文。
        """
        ph = self.ph
        with get_conn() as conn:
            conn.execute(
                f"UPDATE rag_task_inputs SET source_text={ph},source_origin={ph} WHERE task_id={ph}",
                (source_text, source_origin, task_id))

    def drop_task_inputs(self, task_id):
        with get_conn() as conn:
            conn.execute(f"DELETE FROM rag_task_inputs WHERE task_id={self.ph}", (task_id,))

    # ---- rag_task_jobs：harness 作业账本（任务重启后按账本接续/复用结果）----

    def record_job(self, task_id, job_id, tool, protocol, code_hash):
        """登记评测作业；同 (task_id, job_id) 幂等，重复登记不再产生第二行。"""
        ph = self.ph
        with get_conn() as conn:
            conn.execute(
                f"INSERT INTO rag_task_jobs(id,task_id,job_id,tool,protocol,code_hash,status,created_at,updated_at) "
                f"VALUES ({','.join([ph] * 9)}) ON CONFLICT(task_id, job_id) DO NOTHING",
                (uuid.uuid4().hex, task_id, job_id, tool, protocol, code_hash, "submitted", now(), now()),
            )

    def reserve_grading_job(self, task_id, job_id, protocol, code_hash, user_id, quota):
        """首次登记 job 与评测日配额同事务；已登记 job 不再扣第二次。"""
        from ..core.rate_limit import rate_limiter

        ph = self.ph
        with get_conn() as conn:
            created = conn.execute(
                f"INSERT INTO rag_task_jobs(id,task_id,job_id,tool,protocol,code_hash,status,created_at,updated_at) "
                f"VALUES ({','.join([ph] * 9)}) ON CONFLICT(task_id,job_id) DO NOTHING",
                (uuid.uuid4().hex, task_id, job_id, "grade_plugin", protocol, code_hash,
                 "submitted", now(), now()),
            ).rowcount
            if created:
                rate_limiter.count_kind_in_conn(conn, user_id, quota, "grading")
        return bool(created)

    def finish_job(self, job_id, result):
        ph = self.ph
        with get_conn() as conn:
            conn.execute(
                f"UPDATE rag_task_jobs SET status='finished',result_json={ph},updated_at={ph} WHERE job_id={ph}",
                (json.dumps(result, ensure_ascii=False), now(), job_id),
            )

    def find_job(self, task_id, protocol, code_hash):
        ph = self.ph
        clauses, params = [f"task_id={ph}"], [task_id]
        for column, value in (("protocol", protocol), ("code_hash", code_hash)):
            if value is None:
                clauses.append(f"{column} IS NULL")
            else:
                clauses.append(f"{column}={ph}")
                params.append(value)
        with get_conn() as conn:
            row = conn.execute(f"SELECT * FROM rag_task_jobs WHERE {' AND '.join(clauses)}",
                               tuple(params)).fetchone()
        return dict(row) if row else None

    def context(self, request_id, text):
        with get_conn() as conn:
            conn.execute(f"UPDATE rag_requests SET context_text={self.ph} WHERE id={self.ph}",
                         (redact(text)[-2000:], request_id))

    def _issue(self, conn, request_id):
        ph = self.ph
        conn.execute(f"INSERT INTO rag_issues(id,request_id,updated_at) VALUES ({ph},{ph},{ph}) "
                     "ON CONFLICT(request_id) DO NOTHING", (request_id, request_id, now()))

    def finish(self, request_id, session_id, answer, citations, status, reason, release_id, elapsed_ms):
        ph = self.ph
        message_id = uuid.uuid4().hex
        metadata = {"task_id": request_id, "confidence": "high" if status == "answered" else "low", "answer_status": status,
                    "answer_reason": reason, "request_id": request_id, "stream_status": "completed"}
        evidence = [{"chunk_id": c.chunk_id, "source_path": c.source_path, "section": c.section} for c in citations]
        provider = get_database_provider()
        with get_conn() as conn:
            row = conn.execute(f"SELECT request_id FROM rag_requests WHERE id={ph}", (request_id,)).fetchone()
            if row:
                metadata["request_id"] = row["request_id"]
            changed = conn.execute(
                f"UPDATE rag_requests SET status={ph},reason={ph},release_id={ph},evidence_json={ph},"
                f"elapsed_ms={ph},message_id={ph},updated_at={ph} WHERE id={ph} AND status='pending'",
                (status, reason, release_id, json.dumps(evidence, ensure_ascii=False), elapsed_ms, message_id, now(), request_id),
            ).rowcount
            if changed != 1:
                raise RuntimeError("请求已结束，禁止重复保存回答")
            if status not in {"answered", "not_applicable"}:
                self._issue(conn, request_id)
                metadata["issue_id"] = request_id
            conn.execute(
                f"INSERT INTO assistant_messages(id,session_id,role,content,metadata_json,created_at) "
                f"VALUES ({ph},{ph},{ph},{ph},{ph}{provider.metadata_cast},{ph})",
                (message_id, session_id, "assistant", answer, json.dumps(metadata), now()),
            )
            for rank, citation in enumerate(citations, 1):
                conn.execute(
                    f"INSERT INTO assistant_citations(id,message_id,chunk_id,citation_engine,score,rank) "
                    f"VALUES ({','.join([ph] * 6)})",
                    (uuid.uuid4().hex, message_id, citation.chunk_id,
                     "llama_index" if citation.chunk_id.startswith("li_") else "legacy", citation.score, rank),
                )
            conn.execute(f"UPDATE assistant_sessions SET updated_at={ph} WHERE id={ph}", (now(), session_id))
            # 形参名 request_id 是历史遗留，实际收的是 task_id（rag_requests.id）。
            # 任务到达终态即清理临时输入（§8：24h 只是兜底上限）。
            conn.execute(f"DELETE FROM rag_task_inputs WHERE task_id={ph}", (request_id,))
        return message_id, metadata

    def feedback(self, message_id, user_id, feedback):
        ph = self.ph
        with get_conn() as conn:
            request = conn.execute(f"SELECT id FROM rag_requests WHERE message_id={ph} AND user_id={ph}",
                                   (message_id, user_id)).fetchone()
            if not request:
                raise LookupError("not found")
            self._issue(conn, request["id"])
            conn.execute(f"UPDATE rag_issues SET feedback={ph},status='open',verified_at=NULL,verified_release_id=NULL,updated_at={ph} WHERE request_id={ph}",
                         (redact(feedback), now(), request["id"]))
            self.event(conn, request["id"], user_id, "feedback", redact(feedback))
        return request["id"]

    def event(self, conn, issue_id, actor, action, detail):
        conn.execute(f"INSERT INTO rag_issue_events(id,issue_id,actor_id,action,detail,created_at) "
                     f"VALUES ({','.join([self.ph] * 6)})",
                     (uuid.uuid4().hex, issue_id, actor, action, detail, now()))

    def mark_verified(self, issue_id, release_id, verdict_status, actor_id, expected_updated_at):
        """验证结果落库。验证期间 issue 被改动（如新反馈）则条件更新失败并拒绝，
        防止过期的验证结果覆盖更新的状态；条件更新与事件写入在同一事务。"""
        from fastapi import HTTPException
        ph = self.ph
        with get_conn() as conn:
            count = conn.execute(
                f"UPDATE rag_issues SET verified_release_id={ph},verified_at={ph},updated_at={ph} "
                f"WHERE id={ph} AND updated_at={ph}",
                (release_id, now() if verdict_status == "answered" else None, now(),
                 issue_id, expected_updated_at),
            ).rowcount
            if count != 1:
                raise HTTPException(409, "问题已更新（可能有新反馈），请刷新后重新验证")
            self.event(conn, issue_id, actor_id, "verify", verdict_status)

    def maintain(self, days: int):
        ph = self.ph
        cutoff = (datetime.now(timezone.utc) - timedelta(days=days)).isoformat()
        stale = (datetime.now(timezone.utc) - timedelta(minutes=10)).isoformat()
        with get_conn() as conn:
            # 超龄 pending 兜底判死，但跳过仍持有效临时输入的行——那些留给恢复
            # 扫描接续（§8），只有无有效 inputs 的才判 interrupted。
            pending = conn.execute(
                f"SELECT id FROM rag_requests WHERE status='pending' AND updated_at<{ph} "
                f"AND NOT EXISTS (SELECT 1 FROM rag_task_inputs "
                f"WHERE rag_task_inputs.task_id=rag_requests.id AND rag_task_inputs.expires_at>{ph})",
                (stale, now())).fetchall()
            for row in pending:
                conn.execute(f"UPDATE rag_requests SET status='system_error',reason='interrupted',updated_at={ph} "
                             f"WHERE id={ph} AND status='pending'", (now(), row["id"]))
                self._issue(conn, row["id"])
            # 临时输入兜底清理：过期即删；归属任务已离开 pending 的也删（终态由
            # finish/cancel/mark_interrupted 即时清理，这里兜住漏网与孤儿行）。
            conn.execute(f"DELETE FROM rag_task_inputs WHERE expires_at<{ph} OR task_id NOT IN "
                         "(SELECT id FROM rag_requests WHERE status='pending')", (now(),))
            conn.execute("UPDATE rag_requests SET user_id=NULL WHERE user_id IS NOT NULL "
                         "AND NOT EXISTS (SELECT 1 FROM users WHERE users.id=rag_requests.user_id)")
            conn.execute("UPDATE rag_requests SET session_id=NULL WHERE session_id IS NOT NULL "
                         "AND NOT EXISTS (SELECT 1 FROM assistant_sessions s WHERE s.id=rag_requests.session_id)")
            for table, column in (("rag_issues", "owner_id"), ("rag_issue_events", "actor_id"),
                                  ("rag_knowledge", "created_by"), ("rag_knowledge_revisions", "actor_id")):
                conn.execute(f"UPDATE {table} SET {column}=NULL WHERE {column} IS NOT NULL "
                             f"AND NOT EXISTS (SELECT 1 FROM users WHERE users.id={table}.{column})")
            conn.execute(f"DELETE FROM rag_issue_events WHERE issue_id IN (SELECT id FROM rag_requests WHERE created_at<{ph})", (cutoff,))
            conn.execute(f"DELETE FROM rag_issues WHERE request_id IN (SELECT id FROM rag_requests WHERE created_at<{ph})", (cutoff,))
            # 留存期删除父行前，先删其子行：临时输入与作业账本不带 FK，需显式清理。
            conn.execute(f"DELETE FROM rag_task_inputs WHERE task_id IN (SELECT id FROM rag_requests WHERE created_at<{ph})", (cutoff,))
            conn.execute(f"DELETE FROM rag_task_jobs WHERE task_id IN (SELECT id FROM rag_requests WHERE created_at<{ph})", (cutoff,))
            count = conn.execute(f"DELETE FROM rag_requests WHERE created_at<{ph}", (cutoff,)).rowcount
        return {"interrupted": len(pending), "expired": count}

    def cancel_pending_for_user(self, user_id, reason, actor_id="system"):
        """账号停用等批量取消：单条 UPDATE 把该用户全部 pending→cancelled 并清临时输入。

        与 ``cancel_task`` 的差异：批量路径不写占位消息——账号停用时这些结果本
        就不再回放，逐行补消息的成本与动机都不足；行保持终态无正文，probe 归
        ``terminal``。已终态的行不受影响。返回取消条数。
        """
        ph = self.ph
        with get_conn() as conn:
            ids = [row["id"] for row in conn.execute(
                f"SELECT id FROM rag_requests WHERE user_id={ph} AND status='pending'",
                (user_id,)).fetchall()]
            if not ids:
                return 0
            marks = ",".join([ph] * len(ids))
            conn.execute(
                f"UPDATE rag_requests SET status='cancelled',reason={ph},updated_at={ph} "
                f"WHERE id IN ({marks})", (reason, now(), *ids))
            conn.execute(f"DELETE FROM rag_task_inputs WHERE task_id IN ({marks})", tuple(ids))
        return len(ids)
