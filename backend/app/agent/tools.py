"""Agent 工具层（规划 §4.2）：kb_search / list_protocols / list_cases / plugin_template / grade_plugin。

统一约定：每个工具返回 ``{"ok": True, "data": ...}`` 或
``{"ok": False, "error": <code>, "detail": <可读原因>}``，不向外抛异常——
工具的失败应当变成模型可读、可转述给学生的信息，而不是请求级 500。

工具以闭包形式绑定一次请求的上下文（用户、会话、检索器、MCP 调用方），
图本身无状态（D11），可安全地按请求重建。账本/成绩持久化失败抛
RetryableTaskFailure，由产品任务保留输入并重驱动；普通工具业务失败仍返回结构化结果。
"""
from __future__ import annotations

import asyncio
import hashlib
import json
import logging
import re
import time
from dataclasses import dataclass, field
from typing import Any, Awaitable, Callable, Dict, List, Optional

from fastapi import HTTPException
from langchain_core.tools import BaseTool, tool

from ..config import Settings
from ..core.rate_limit import rate_limiter
from ..core.usage import KIND_MCP
from ..rag.interfaces import KnowledgeRetriever
from ..rag.types import RetrievedChunk
from ..schemas.chat import TemplateRequest
from ..services.grading_service import GradingService
from ..services.task_errors import RetryableTaskFailure
from ..services.template_service import TemplateService

logger = logging.getLogger("assistant")

McpCaller = Callable[[str, Dict[str, Any]], Awaitable[Dict[str, Any]]]

# 评测配额豁免角色（D13：教师/管理员/组长不计学生配额）。
GRADING_QUOTA_EXEMPT_ROLES = {"operator", "admin", "leader"}

# 回填给模型的单个知识片段长度上限，控制 token 体积。
_KB_CHUNK_CONTENT_LIMIT = 1500


@dataclass
class AgentToolContext:
    """Per-request state shared by the tool closures of one agent run."""

    settings: Settings
    user_id: str
    user_roles: List[str]
    session_id: Optional[str]
    retriever: KnowledgeRetriever
    mcp_caller: McpCaller
    grading: GradingService = field(default_factory=GradingService)
    # plugin_template 的骨架生成器（无状态，service.py 装配）。
    template_service: TemplateService = field(default_factory=TemplateService)
    # kb_search 命中的原始 chunk，供 orchestrator 生成 citations / sources 事件。
    collected_chunks: List[RetrievedChunk] = field(default_factory=list)
    # 成功执行的 grade_plugin 结果（harness 原始 payload），供反馈层引用。
    grading_results: List[Dict[str, Any]] = field(default_factory=list)
    # 上传路径的 .c 原件源码（chat.py multipart 分支读到的原文）。落库
    # code_hash 以原件为准；None 表示非上传路径（再看 pasted_source）。
    uploaded_source: Optional[str] = None
    # B6 粘贴路径的执行源：后端从当前用户消息抽取的最长围栏代码块
    # （extract_fenced_source，由 _build_run 装配）。与 uploaded_source 同理，
    # 模型在 tool args 里复述的源码可能与原件字节不一致——执行与 hash 都以
    # 服务端寄存的这份为准。None 表示消息里没有围栏块。
    pasted_source: Optional[str] = None
    # B7 同请求评测去重表：key = f"{protocol}:{sha256(执行源字节)}"，
    # value = 首次成功返回给模型的 data 载荷。只缓存成功结果，
    # 失败/超时/配额拦截不进入此表，允许模型重试。
    graded_sources: Dict[str, Dict[str, Any]] = field(default_factory=dict)
    conversation_context: Optional[str] = None
    search_attempted: bool = False
    request_trace: Any = None
    # S3-c：任务级评测账本锚点。仅产品任务上下文非空；普通聊天路径为 None，
    # grade_plugin 的账本接线整体跳过、行为与旧版一致。
    task_id: Optional[str] = None
    # S3-c 恢复寄存源：origin=model_args 的执行源由 rag_task_inputs 还原，
    # 恢复重驱动时替代模型复述参数（复述字节可能漂移）。
    fallback_source: Optional[str] = None
    # S3-d 讲解模式：关联的正式作业评测结果元数据（作业标题/协议/得分/逐用例
    # 结果等，由 RagProductService 从 grading_submissions 行装配）。非 None 时
    # prompt_builder 追加讲解块——该成绩已落库，讲解不自动重评、不改成绩。
    explain_context: Optional[Dict[str, Any]] = None


def _job_result_payload(job: Dict[str, Any]) -> Optional[Dict[str, Any]]:
    """账本行 result_json → dict 载荷；损坏/缺行按 None（交回重投路径）。"""
    try:
        payload = json.loads(job["result_json"]) if job.get("result_json") else None
    except (TypeError, ValueError):
        payload = None
    return payload if isinstance(payload, dict) else None


async def _harness_job_status(context: AgentToolContext, job_id: str,
                              call_mcp) -> Optional[Dict[str, Any]]:
    """get_task_status 探测：失败/超时/形态异常一律 None（按不可接续处理，主流程重投）。

    状态查询给短一点的超时：它只是接续探测，不该把请求耗在 grading_timeout 上。
    """
    try:
        payload = await asyncio.wait_for(
            call_mcp("get_task_status", {"job_id": job_id}),
            timeout=min(context.settings.grading_timeout_sec, 30),
        )
    except Exception:
        return None
    if not isinstance(payload, dict) or not payload.get("ok"):
        return None
    return payload


async def _resume_grading_job(context: AgentToolContext, protocol: str,
                              exec_bytes: bytes, call_mcp) -> Optional[Dict[str, Any]]:
    """S3-c 评测作业账本接续（只在 task_id 上下文启用）。

    返回 None 表示账本层不可用（降级为旧行为）；否则返回：
    - ``job_id``：账本行已记的值，缺省为确定性 ``{task_id}:{protocol}:{sha256[:16]}``；
    - ``resumed``：账本已有该行 → 恢复接续，主流程不再二次扣评测配额；
    - ``replayed``：harness/账本已有终态结果的载荷（非 None → 直接复用，不打 MCP）。

    接续顺序：账本行 finished → 直接复用；submitted → get_task_status 接续
    （finished 复用、running/unknown 交回主流程以同一 job_id 调 grade_plugin——
    harness 侧同 job_id 语义即「加入既有执行/幂等重投」）；账本缺行 →
    get_task_status 兜底，兜住「harness 有结果而账本漏写」的场景。
    """
    try:
        from ..services.rag_issue_service import RagIssueService
        issues = RagIssueService()
        code_hash = hashlib.sha256(exec_bytes).hexdigest()
        job_id = f"{context.task_id}:{protocol}:{code_hash[:16]}"
        job = await asyncio.to_thread(issues.find_job, context.task_id, protocol, code_hash)
        replayed = None
        if job is not None:
            job_id = job["job_id"]
            if job["status"] == "finished":
                replayed = _job_result_payload(job)
            else:
                status = await _harness_job_status(context, job_id, call_mcp)
                if status is not None and status.get("status") == "finished" \
                        and isinstance(status.get("result"), dict):
                    await asyncio.to_thread(issues.finish_job, job_id, status["result"])
                    replayed = status["result"]
        else:
            status = await _harness_job_status(context, job_id, call_mcp)
            if status is not None and status.get("status") == "finished" \
                    and isinstance(status.get("result"), dict):
                await asyncio.to_thread(
                    issues.record_job, context.task_id, job_id, "grade_plugin", protocol, code_hash)
                await asyncio.to_thread(issues.finish_job, job_id, status["result"])
                replayed = status["result"]
        return {"job_id": job_id, "code_hash": code_hash, "issues": issues,
                "resumed": job is not None or replayed is not None, "replayed": replayed}
    except Exception as exc:
        # 产品任务不得降级为无账本评测：否则恢复会重扣配额或重复记成绩。
        logger.warning(json.dumps({
            "event": "task_job_ledger_failed", "tool": "grade_plugin",
            "error_type": type(exc).__name__, "detail": str(exc)[:300],
        }, ensure_ascii=False))
        raise RuntimeError("task_job_ledger_unavailable") from exc


async def _record_task_job_grade(context: AgentToolContext, ledger: Dict[str, Any],
                                 protocol: str, exec_source: str, source_origin: str,
                                 payload: Dict[str, Any]) -> str:
    """任务成绩与账本标志同事务；DB 故障抛出可重驱动异常。"""
    try:
        result = await context.grading.record_task_job_submission(
            task_id=context.task_id, job_id=ledger["job_id"], user_id=context.user_id,
            session_id=context.session_id, protocol=str(payload.get("protocol") or protocol),
            code_hash=ledger["code_hash"], score=int(round(float(payload.get("score") or 0))),
            passed=int(payload.get("passed") or 0), total=int(payload.get("cases_total") or 0),
            results=list(payload.get("results") or []), source_origin=source_origin,
        )
        if result == "legacy_unlinked":
            logger.warning(json.dumps({
                "event": "task_job_legacy_grade_unlinked", "job_id": ledger["job_id"],
            }, ensure_ascii=False))
        return result
    except Exception as exc:
        logger.warning(json.dumps({
            "event": "task_job_grade_save_failed", "job_id": ledger["job_id"],
            "error_type": type(exc).__name__,
        }, ensure_ascii=False))
        raise RetryableTaskFailure("task_job_grade_save_failed") from exc


def _backfill_task_source(task_id: str, source_text: str, source_origin: str) -> None:
    """把 model_args 首次提交的执行源回填进 rag_task_inputs（只写两列）。

    复述路径的执行源在首次提交时才确定——受理时 inputs 里还没有它。
    恢复重驱动时 fallback_source 从这一列取回**同一份字节**，模型复述漂移
    才不会把同一次评测变成另一份 code_hash 的作业。
    """
    from ..services.rag_issue_service import RagIssueService
    RagIssueService().update_task_source(task_id, source_text, source_origin)


def _record_mcp_operation(context: AgentToolContext, stage: str, started: float) -> None:
    """记一次非 token 用量（harness/MCP）：只记次数与耗时，**显式不计费**。

    这些算力的单价在外部服务里，本仓无从得知——编一个费率比不计量更糟。
    ``getattr`` 是为了容忍只实现 ``set_meta`` 的 trace 替身（既有测试里就有）。
    """
    usage = getattr(context.request_trace, "usage", None)
    if usage is None:
        return
    usage.record_operation(
        KIND_MCP, stage, duration_ms=int((time.monotonic() - started) * 1000),
    )


# 围栏代码块：``` 与 ~~~ 两种开围栏 + 可选语言标注行；尾围栏未闭合时
# 其后内容整体视为源码。反向引用 \1 保证 ``` 块不会被 ~~~ 误闭合（反之亦然）。
_FENCED_SOURCE_RE = re.compile(r"(```|~~~)[^\n]*\n([\s\S]*?)(?:\1|$)")


def extract_fenced_source(text: str) -> Optional[str]:
    """从用户消息中抽取围栏源码（B6 带外执行源）。

    同时认 ``` 与 ~~~ 围栏（含语言标注行与未闭合尾围栏），多个块取
    **内容最长**者。抽取内容剥掉首尾空行并补回行尾换行，与 .c 原件按文本
    读取的字节口径对齐（原件源码通常以换行结尾）。无围栏块返回 None。
    """
    best: Optional[str] = None
    for match in _FENCED_SOURCE_RE.finditer(text or ""):
        content = match.group(2).strip("\n")
        if not content.strip():
            continue
        if best is None or len(content) > len(best):
            best = content
    return best + "\n" if best is not None else None


def build_tools(context: AgentToolContext) -> List[BaseTool]:
    """Build the request-scoped tool set bound to ``context``."""
    settings = context.settings

    async def call_mcp(tool: str, args: Dict[str, Any]) -> Dict[str, Any]:
        """所有 harness/MCP 调用的唯一出口，顺带记一条用量。

        ``finally`` 而不是成功分支：超时/失败同样占用了外部算力，账上不能当没发生。
        """
        started = time.monotonic()
        try:
            return await context.mcp_caller(tool, args)
        finally:
            _record_mcp_operation(context, tool, started)

    @tool
    async def kb_search(query: str) -> Dict[str, Any]:
        """检索项目知识库，回答协议原理、插件接口、项目结构等事实性问题。

        参数 query：检索关键词或问题。返回编号片段列表，作答时用 [编号] 引用。
        """
        context.search_attempted = True
        try:
            kwargs = {"conversation_context": context.conversation_context} if context.conversation_context else {}
            if context.request_trace is not None:
                kwargs["trace"] = context.request_trace
            chunks = await context.retriever.search(query, limit=8, **kwargs)
        except Exception as exc:
            logger.warning(json.dumps({
                "event": "agent_tool_error", "tool": "kb_search",
                "error_type": type(exc).__name__, "detail": str(exc)[:300],
            }, ensure_ascii=False))
            return {"ok": False, "error": "retrieval_failed", "detail": "知识库检索暂时不可用"}
        # 同一片段只入账一次，重复命中复用**原编号**。
        #
        # 多轮检索会反复召回同一片段（实测 9 轮堆到 72 条、证据 69958 字符 = 预算 2.9 倍，
        # 15 题里 9 题因此被硬判「资料不足」）。重复召回不是更多证据：同一片段第 8 次
        # 出现，支撑力还是那一条。
        #
        # 编号必须按 ``collected_chunks`` 的**位置**发：``validate_answer`` 按位置校验
        # （``ref > len(chunks)`` 即 invalid_citation），而模型答案里的 ``[n]`` 是按这个
        # 顺序写的。所以去重只能发生在追加之前——删掉中间片段会让 ``[n]`` 静默指向
        # 另一条片段，那比「资料不足」更糟。
        refs = {chunk.id: index for index, chunk in enumerate(context.collected_chunks, start=1)}
        data_chunks = []
        for chunk in chunks:
            ref = refs.get(chunk.id)
            if ref is None:
                context.collected_chunks.append(chunk)
                ref = len(context.collected_chunks)
                refs[chunk.id] = ref
            data_chunks.append({
                "ref": ref,
                "source_path": chunk.source_path,
                "section": chunk.section,
                "title": chunk.title,
                "content": chunk.content[:_KB_CHUNK_CONTENT_LIMIT],
            })
        return {"ok": True, "data": {"query": query, "chunks": data_chunks, "count": len(data_chunks)}}

    @tool
    async def list_protocols() -> Dict[str, Any]:
        """列出评测环境（harness）支持的协议清单，含每个协议的用例数与用例类别分布。

        无参数。学生问"有哪些实验/协议可以做"时使用。
        """
        payload = await call_mcp("list_protocols", {})
        if not isinstance(payload, dict) or not payload.get("ok"):
            payload = payload if isinstance(payload, dict) else {}
            return {
                "ok": False,
                "error": str(payload.get("error") or "list_protocols_failed"),
                "detail": str(payload.get("detail") or "协议清单查询失败"),
            }
        protocol_list = payload.get("protocols") or []
        return {
            "ok": True,
            "data": {
                "protocols": [
                    {
                        "name": item.get("name"),
                        "cases": item.get("cases", 0),
                        "categories": item.get("categories") or {},
                    }
                    for item in protocol_list
                    if isinstance(item, dict)
                ],
                "count": len(protocol_list),
            },
        }

    @tool
    async def list_cases(protocol: str) -> Dict[str, Any]:
        """列出某协议的全部评测用例（名称 + 类别标签）。

        参数 protocol：协议名（如 dns、http、ssh）。学生问"某协议测哪些用例/场景"时使用。
        """
        payload = await call_mcp("list_cases", {"protocol": protocol})
        if not isinstance(payload, dict) or not payload.get("ok"):
            payload = payload if isinstance(payload, dict) else {}
            return {
                "ok": False,
                "error": str(payload.get("error") or "list_cases_failed"),
                "detail": str(payload.get("detail") or "用例清单查询失败"),
            }
        cases = payload.get("cases") or []
        return {
            "ok": True,
            "data": {
                "protocol": payload.get("protocol", protocol),
                "cases": [
                    {"name": item.get("name"), "category": item.get("category", "")}
                    for item in cases
                    if isinstance(item, dict)
                ],
                "count": len(cases),
            },
        }

    @tool
    async def plugin_template(protocol: str) -> Dict[str, Any]:
        """生成某协议的学习用插件骨架（保留 TODO 与 checklist），是起手代码而非作业答案。

        参数 protocol：协议名（如 dns、http、ssh）。学生要"骨架/模板/起手代码"时使用；
        返回骨架代码、解释、检查清单与参考文件路径。
        """
        try:
            generated = context.template_service.generate(TemplateRequest(protocol=protocol))
        except Exception as exc:
            logger.warning(json.dumps({
                "event": "agent_tool_error", "tool": "plugin_template",
                "error_type": type(exc).__name__, "detail": str(exc)[:300],
            }, ensure_ascii=False))
            return {"ok": False, "error": "template_failed", "detail": "插件骨架生成暂时不可用"}
        return {
            "ok": True,
            "data": {
                "protocol": protocol,
                "template": generated.template,
                "explanation": generated.explanation,
                "checklist": generated.checklist,
                "references": generated.references,
            },
        }

    @tool
    async def grade_plugin(protocol: str, plugin_source: str) -> Dict[str, Any]:
        """评测学生的协议检测插件源码，返回逐用例结果与分数。

        参数 protocol：协议名（如 dns、http、ssh）。
        参数 plugin_source：插件 .c 源码全文（用户消息中的 C 代码块或上传的 .c 文件内容）。
        若本请求已有上传/粘贴的源码，以服务端寄存的为准——模型在参数里
        复述的源码可能与原件字节不一致（B6 执行源带外寄存）。
        """
        # B6：执行源解析顺序 = 上传原件 > 消息里抽取的粘贴块 > 恢复寄存源 >
        # 模型参数复述。fallback_source 只在 S3-c 恢复上下文里非空——它是
        # origin=model_args 首次提交时寄存的权威字节，恢复后模型复述可能漂移。
        # 大小校验、MCP 入参与落库 code_hash 全部以这份字节为准。
        if context.uploaded_source is not None:
            exec_source, source_origin = context.uploaded_source, "upload"
        elif context.pasted_source is not None:
            exec_source, source_origin = context.pasted_source, "pasted"
        elif context.fallback_source is not None:
            exec_source, source_origin = context.fallback_source, "model_args"
        else:
            exec_source, source_origin = plugin_source, "model_args"
        exec_bytes = exec_source.encode("utf-8", errors="replace")
        # B7：同请求内同 (protocol, 执行源) 的重复评测直接复用首次结果——
        # 先于配额扣减与 MCP 调用，不重复记账、不重复落库。表里只有成功
        # 载荷，失败/超时不会命中，模型可照常重试。
        dedup_key = f"{protocol}:{hashlib.sha256(exec_bytes).hexdigest()}"
        cached = context.graded_sources.get(dedup_key)
        if cached is not None:
            return {"ok": True, "data": {**cached, "deduplicated": True}}

        # ---- S3-c 作业账本：仅任务上下文（task_id 非空）启用 ----
        # 接续先于配额扣减：账本已有 submitted 行的作业属同一提交，恢复不二次扣。
        try:
            ledger = await _resume_grading_job(
                context, protocol, exec_bytes, call_mcp) if context.task_id else None
        except Exception as exc:
            raise RetryableTaskFailure("task_job_ledger_unavailable") from exc
        if ledger is not None and ledger.get("replayed") is not None:
            payload = ledger["replayed"]
            saved = await _record_task_job_grade(context, ledger, protocol, exec_source, source_origin, payload)
            if saved == "cancelled":
                return {"ok": False, "error": "task_cancelled", "detail": "任务已取消"}
            context.grading_results.append(payload)
            data = {
                "protocol": payload.get("protocol", protocol),
                "score": payload.get("score"),
                "passed": payload.get("passed"),
                "cases_total": payload.get("cases_total"),
                "results": payload.get("results") or [],
                "source_origin": source_origin,
                "resumed": True,
            }
            context.graded_sources[dedup_key] = data
            return {"ok": True, "data": data}

        if len(exec_bytes) > settings.grading_max_code_bytes:
            return {
                "ok": False,
                "error": "code_too_large",
                "detail": f"源码大小超过上限（{settings.grading_max_code_bytes} 字节）",
            }
        if ledger is None:
            if not GRADING_QUOTA_EXEMPT_ROLES.intersection(context.user_roles):
                try:
                    await rate_limiter.check_and_count_daily(
                        context.user_id, settings.grading_daily_quota, kind="grading"
                    )
                except HTTPException:
                    return {
                        "ok": False,
                        "error": "quota_exceeded",
                        "detail": f"今日评测配额已用完（{settings.grading_daily_quota} 次/天），请明天再试",
                    }
        if ledger is not None:
            try:
                # 首次 job 登记与评测日配额同事务；恢复命中旧行则不二次扣。
                await asyncio.to_thread(
                    ledger["issues"].reserve_grading_job, context.task_id, ledger["job_id"],
                    protocol, ledger["code_hash"], context.user_id,
                    0 if GRADING_QUOTA_EXEMPT_ROLES.intersection(context.user_roles) or ledger["resumed"]
                    else settings.grading_daily_quota,
                )
                if source_origin == "model_args":
                    # 复述路径的执行源首评才确定：回填 inputs，恢复拿到同一份字节。
                    await asyncio.to_thread(
                        _backfill_task_source, context.task_id, exec_source, source_origin)
            except HTTPException:
                return {
                    "ok": False, "error": "quota_exceeded",
                    "detail": f"今日评测配额已用完（{settings.grading_daily_quota} 次/天），请明天再试",
                }
            except Exception as exc:
                logger.warning(json.dumps({
                    "event": "task_job_record_failed", "tool": "grade_plugin",
                    "error_type": type(exc).__name__, "detail": str(exc)[:300],
                }, ensure_ascii=False))
                raise RetryableTaskFailure("task_job_record_failed") from exc
        mcp_args = {
            "protocol": protocol,
            "plugin_source": exec_source,
            "timeout_sec": settings.grading_timeout_sec,
        }
        if ledger is not None:
            # 同 job_id 幂等语义在 harness 侧：运行中加入既有执行、已完成回放结果。
            mcp_args["job_id"] = ledger["job_id"]
        try:
            payload = await asyncio.wait_for(
                call_mcp("grade_plugin", mcp_args),
                timeout=settings.grading_timeout_sec,
            )
        except asyncio.TimeoutError:
            # 超时只影响本次返回；账本行保持 submitted，恢复扫描可接续。
            return {
                "ok": False,
                "error": "grading_timeout",
                "detail": f"评测超过 {settings.grading_timeout_sec} 秒未完成",
            }
        if not isinstance(payload, dict) or not payload.get("ok"):
            payload = payload if isinstance(payload, dict) else {}
            return {
                "ok": False,
                "error": str(payload.get("error") or "grading_failed"),
                "detail": str(payload.get("detail") or "评测服务返回失败"),
            }
        if ledger is not None:
            try:
                await asyncio.to_thread(ledger["issues"].finish_job, ledger["job_id"], payload)
            except Exception as exc:
                logger.warning(json.dumps({
                    "event": "task_job_finish_failed", "tool": "grade_plugin",
                    "error_type": type(exc).__name__, "detail": str(exc)[:300],
                }, ensure_ascii=False))
                raise RetryableTaskFailure("task_job_finish_failed") from exc
            saved = await _record_task_job_grade(context, ledger, protocol, exec_source, source_origin, payload)
            if saved == "cancelled":
                return {"ok": False, "error": "task_cancelled", "detail": "任务已取消"}
        else:
            await _record_submission(context, protocol, exec_source, source_origin, payload)
        context.grading_results.append(payload)
        data = {
            "protocol": payload.get("protocol", protocol),
            "score": payload.get("score"),
            "passed": payload.get("passed"),
            "cases_total": payload.get("cases_total"),
            "results": payload.get("results") or [],
            # 让模型能如实说明评的是哪份源（上传原件/粘贴块/参数复述）。
            "source_origin": source_origin,
        }
        context.graded_sources[dedup_key] = data
        return {"ok": True, "data": data}

    return [kb_search, list_protocols, list_cases, plugin_template, grade_plugin]


async def _record_submission(
    context: AgentToolContext, protocol: str, exec_source: str,
    source_origin: str, payload: Dict[str, Any],
) -> None:
    """Best-effort persistence; a DB hiccup must not fail the grading reply.

    code_hash 恒等于**实际送去执行的源码字节**（B6）：上传原件、消息里
    抽取的粘贴块、或模型在 tool args 里复述的源码，source_origin 记录
    三者之一（upload / pasted / model_args）。复述可能与原件字节不一致
    （live 冒烟实测确实如此），因此 hash 以执行源而非参数文本为准。
    """
    hash_source = exec_source.encode("utf-8", errors="replace")
    try:
        await context.grading.record_submission(
            user_id=context.user_id,
            session_id=context.session_id,
            protocol=str(payload.get("protocol") or protocol),
            code_hash=hashlib.sha256(hash_source).hexdigest(),
            score=int(round(float(payload.get("score") or 0))),
            passed=int(payload.get("passed") or 0),
            total=int(payload.get("cases_total") or 0),
            results=list(payload.get("results") or []),
            source_origin=source_origin,
        )
    except Exception as exc:
        logger.warning(json.dumps({
            "event": "grading_record_failed",
            "error_type": type(exc).__name__, "detail": str(exc)[:300],
        }, ensure_ascii=False))


def tool_event_label(tool_name: str, args: Dict[str, Any]) -> str:
    """Human-readable tool_start label（§4.4，前端工具卡片标题）。"""
    if tool_name == "grade_plugin":
        protocol = str(args.get("protocol") or "").upper() or "插件"
        return f"正在评测 {protocol} 插件…"
    if tool_name == "kb_search":
        query = str(args.get("query") or "").strip()
        return f"正在检索：{query[:30]}" if query else "正在检索知识库…"
    if tool_name == "list_protocols":
        return "正在查询协议列表…"
    if tool_name == "list_cases":
        protocol = str(args.get("protocol") or "").upper()
        return f"正在查询 {protocol} 的用例…" if protocol else "正在查询协议用例…"
    if tool_name == "plugin_template":
        protocol = str(args.get("protocol") or "").upper()
        return f"正在生成 {protocol} 插件骨架…" if protocol else "正在生成插件骨架…"
    return f"正在调用工具 {tool_name}…"


# 上下文修剪（E5）时 kb_search 摘要的形态。
#
# P2 之前这里是「前 2 名片段 × 前 300 字符」——摘要因此是**位置函数**：任何落在
# 第 300 字符之后、或第 3 名之后的证据都不可能恢复。线上 `route_kb_engine_ops`
# 即因 `can_handle` 在片段第 938 字符（检索可达、可见窗口 1500 之内）而丢失。
# 现在改成**覆盖式**摘要：头部 + 中段关键行 + 尾部，位置不再决定去留。
_TOOL_CONTEXT_KB_TOP_CHUNKS = 3
_TOOL_CONTEXT_KB_CHUNK_CHARS = 320
_TOOL_CONTEXT_KB_HEAD_CHARS = 120
_TOOL_CONTEXT_KB_TAIL_CHARS = 60
# 中段（头尾之外）的关键行额度；CHUNK_CHARS = HEAD + SIGNAL + TAIL。
_TOOL_CONTEXT_KB_SIGNAL_CHARS = 140
# 单条关键行保留长度（签名行通常短于此，够定位即可）。
_TOOL_CONTEXT_KB_LINE_CHARS = 64
# 形如声明/签名/字段/宏的行：C 源码与头文件里的函数签名、结构体字段、宏定义。
_TOOL_CONTEXT_KB_DECL_MARKS = ("(", "_", ":", ";", "{")
# 纯散文中段的兜底字符窗口宽度。
_TOOL_CONTEXT_KB_WINDOW_CHARS = 40

# grade_plugin 压缩形态的边界：保留的失败用例数、单个失败描述字段长度。
# 逐用例结果原文可达数千字符，压缩必须同时「留住评分事实」且「有界」。
_TOOL_CONTEXT_GRADE_MAX_CASES = 5
_TOOL_CONTEXT_GRADE_FIELD_CHARS = 120


def grade_result_facts(payload: Any) -> Optional[Dict[str, Any]]:
    """从 grade_plugin 载荷提取教学反馈所需的评分事实。

    同时接受工具返回形态（``{"ok": True, "data": {...}}``）与 harness 原始
    载荷（``AgentToolContext.grading_results`` 的条目，字段在顶层），
    让压缩摘要与降级答案共用同一套解析，避免两处口径漂移。
    无法识别时返回 None。
    """
    if not isinstance(payload, dict):
        return None
    data = payload.get("data") if isinstance(payload.get("data"), dict) else payload
    if not isinstance(data, dict):
        return None
    results = [item for item in (data.get("results") or []) if isinstance(item, dict)]
    if data.get("cases_total") is None and data.get("score") is None and not results:
        return None
    failing = [item for item in results if item.get("result") != "PASS"]
    return {
        "protocol": data.get("protocol"),
        "score": data.get("score"),
        "passed": data.get("passed"),
        "cases_total": data.get("cases_total", len(results)),
        "failing_total": len(failing),
        "failing": failing,
    }


def _failure_lines(failures: Any, limit: int = 2) -> List[str]:
    """把 failures[] 渲染成「描述；期望 X；实际 Y」短行（逐字段截断）。"""
    lines: List[str] = []
    for item in (failures or [])[:limit]:
        if not isinstance(item, dict):
            continue
        parts = [str(item.get("desc") or "").strip()]
        expected = str(item.get("expected") or "").strip()
        actual = str(item.get("actual") or "").strip()
        if expected:
            parts.append(f"期望 {expected}")
        if actual:
            parts.append(f"实际 {actual}")
        text = "；".join(part for part in parts if part)
        if text:
            lines.append(text[:_TOOL_CONTEXT_GRADE_FIELD_CHARS])
    return lines


def grade_facts_lines(facts: Dict[str, Any]) -> List[str]:
    """评分事实的可读文本行；压缩摘要与降级答案共用，输出有界。"""
    score = facts.get("score")
    passed = facts.get("passed")
    total = facts.get("cases_total")
    head = f"通过 {passed}/{total}，得分 {score}"
    lines = [head]
    for case in (facts.get("failing") or [])[:_TOOL_CONTEXT_GRADE_MAX_CASES]:
        detail = _failure_lines(case.get("failures"))
        lines.append(
            f"{case.get('name')}：" + ("；".join(detail) if detail else "未通过")
        )
    shown = min(len(facts.get("failing") or []), _TOOL_CONTEXT_GRADE_MAX_CASES)
    remaining = facts.get("failing_total", 0) - shown
    if remaining > 0:
        lines.append(f"另有 {remaining} 个未通过用例")
    return lines


def _kb_query_terms(query: str) -> List[str]:
    """查询里的标识符式词元（小写、去重保序、长度 >= 3）。

    `can_handle` / `engine_ops_t` 这类词元正是这次检索要找的证据，用来在压缩时
    优先保住命中它们的行。
    """
    terms: Dict[str, None] = {}
    for token in re.findall(r"[A-Za-z_][A-Za-z0-9_]{2,}", query or ""):
        terms.setdefault(token.lower(), None)
    return list(terms)


def _kb_pick_lines(lines: List[str], budget: int) -> List[str]:
    """在 ``budget`` 内保留 ``lines``；放不下时按等距步长抽。

    等距而非「取够就停」：后者会把靠前的行系统性地留下，重新引入位置偏置。
    """
    if not lines:
        return []
    if sum(len(line) + 1 for line in lines) <= budget:
        return list(lines)
    average = max(sum(len(line) + 1 for line in lines) // len(lines), 1)
    count = max(1, budget // average)
    step = max(1, len(lines) // count)
    picked: List[str] = []
    used = 0
    for index in range(0, len(lines), step):
        line = lines[index]
        if used + len(line) + 1 > budget:
            continue
        picked.append(line)
        used += len(line) + 1
    return picked


def _kb_stride_windows(text: str, budget: int) -> List[str]:
    """中段没有可识别结构（纯散文）时的兜底：等距取若干字符窗口。

    仍然覆盖整个中段而不是只看开头——这是「覆盖优先」的最小保证。
    """
    width = _TOOL_CONTEXT_KB_WINDOW_CHARS
    if not text:
        return []
    if len(text) <= budget:
        return [text]
    count = max(1, budget // (width + 1))
    if count <= 1:
        return [text[:width]]
    span = len(text) - width
    step = max(1, span // (count - 1))
    return [text[index:index + width] for index in range(0, span + 1, step)][:count]


def _kb_chunk_digest(content: str, terms: List[str]) -> str:
    """单个片段的覆盖式摘要：头 + 中段关键行 + 尾，总长以 CHUNK_CHARS 为界。

    中段先取**含检索词的行**（这次检索真正要找的证据），再取形如声明/签名/字段
    的行（语料以 C 源码与头文件为主，`can_handle` 就在这类行里），都没有时退回
    等距字符窗口。任何一步都不偏向靠前的位置。
    """
    if len(content) <= _TOOL_CONTEXT_KB_CHUNK_CHARS:
        return content
    head = content[:_TOOL_CONTEXT_KB_HEAD_CHARS]
    tail_start = len(content) - _TOOL_CONTEXT_KB_TAIL_CHARS
    tail = content[tail_start:]
    middle = content[len(head):tail_start]
    lines = [line.strip()[:_TOOL_CONTEXT_KB_LINE_CHARS] for line in middle.splitlines()]
    lines = [line for line in lines if line]

    picked = _kb_pick_lines(
        [line for line in lines if any(term in line.lower() for term in terms)],
        _TOOL_CONTEXT_KB_SIGNAL_CHARS,
    )
    if not picked:
        picked = _kb_pick_lines(
            [line for line in lines
             if any(mark in line for mark in _TOOL_CONTEXT_KB_DECL_MARKS)],
            _TOOL_CONTEXT_KB_SIGNAL_CHARS,
        )
    if not picked:
        picked = _kb_stride_windows(middle, _TOOL_CONTEXT_KB_SIGNAL_CHARS)

    dropped = len(picked) < len(lines)
    body = ["…", *picked, "…"] if dropped and picked else picked
    return "\n".join([head, *[part for part in body if part], tail])


def tool_context_summary(tool_name: str, result_json: str) -> str:
    """工具结果的上下文压缩形态（M4a E5）：替代进入历史较久的 ToolMessage 原文。

    kb_search 保留 top-3 片段的**覆盖式**摘要（头/尾/关键行，含检索词或声明行的
    优先），让后续轮次仍能引用关键证据——P2 之前是「前 2 名 × 前 300 字符」，
    深层与靠后的证据不可能恢复；grade_plugin 保留得分与失败用例的期望/实际差异
    （教学反馈的事实基础，P0.1 前这里退化成一句话，评分事实被整体丢弃）；其余
    工具退为 tool_event_summary 的一句话摘要。
    """
    try:
        result = json.loads(result_json)
    except (json.JSONDecodeError, TypeError):
        result = None
    if not isinstance(result, dict) or not result.get("ok"):
        return json.dumps(tool_event_summary(tool_name, result_json), ensure_ascii=False)
    if tool_name == "grade_plugin":
        facts = grade_result_facts(result)
        if facts is not None:
            # 「可重新评测」与 B7 去重不矛盾：同 (protocol, 执行源) 重调会命中
            # 去重表并原样返回完整 data（含逐用例 results），不重复扣配额——
            # 模型拿回的就是完整结果，文案不改。
            return json.dumps({
                "tool": "grade_plugin", "ok": True,
                "protocol": facts.get("protocol"),
                "score": facts.get("score"), "passed": facts.get("passed"),
                "cases_total": facts.get("cases_total"),
                "failing_total": facts.get("failing_total"),
                "facts": grade_facts_lines(facts),
                "note": "评测结果已压缩为评分事实；需要完整逐用例结果可重新评测",
            }, ensure_ascii=False)
        return json.dumps(tool_event_summary(tool_name, result_json), ensure_ascii=False)
    if tool_name != "kb_search":
        return json.dumps(tool_event_summary(tool_name, result_json), ensure_ascii=False)
    data = result.get("data") or {}
    query = str(data.get("query") or "")
    terms = _kb_query_terms(query)
    kept = [
        {"ref": item.get("ref"), "source_path": item.get("source_path"),
         "section": item.get("section"), "title": item.get("title"),
         "content": _kb_chunk_digest(str(item.get("content") or ""), terms)}
        for item in (data.get("chunks") or [])[:_TOOL_CONTEXT_KB_TOP_CHUNKS]
        if isinstance(item, dict)
    ]
    return json.dumps({
        "tool": "kb_search", "ok": True, "query": query,
        "total": data.get("count", 0), "top_chunks": kept,
        "note": (
            f"早期检索结果已压缩：保留前 {_TOOL_CONTEXT_KB_TOP_CHUNKS} 条片段的"
            "头部、尾部与关键行；需要完整内容可重新检索"
        ),
    }, ensure_ascii=False)


def tool_event_summary(tool_name: str, result_json: str) -> Dict[str, Any]:
    """Parse a ToolMessage payload into the tool_end event body (ok + summary)。"""
    try:
        result = json.loads(result_json)
    except (json.JSONDecodeError, TypeError):
        return {"tool": tool_name, "ok": False, "summary": "工具返回格式异常"}
    ok = bool(isinstance(result, dict) and result.get("ok"))
    if not ok:
        detail = str((result or {}).get("detail") or (result or {}).get("error") or "未知错误")
        return {"tool": tool_name, "ok": False, "summary": detail[:120]}
    data = result.get("data") or {}
    if tool_name == "grade_plugin":
        passed = data.get("passed", 0)
        total = data.get("cases_total", 0)
        score = data.get("score", 0)
        return {"tool": tool_name, "ok": True, "summary": f"{passed}/{total} 通过，得分 {score}"}
    if tool_name == "kb_search":
        query = str(data.get("query") or "").strip()
        prefix = f"「{query[:30]}」" if query else ""
        return {"tool": tool_name, "ok": True, "summary": f"{prefix}命中 {data.get('count', 0)} 条资料"}
    if tool_name == "list_protocols":
        return {"tool": tool_name, "ok": True, "summary": f"{data.get('count', 0)} 个协议"}
    if tool_name == "list_cases":
        return {"tool": tool_name, "ok": True, "summary": f"{data.get('count', 0)} 个用例"}
    if tool_name == "plugin_template":
        return {"tool": tool_name, "ok": True, "summary": "已生成骨架"}
    return {"tool": tool_name, "ok": True, "summary": "完成"}
