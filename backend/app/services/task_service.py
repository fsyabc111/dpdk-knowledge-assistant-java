"""S3-c 运行时装配：进程级任务注册表 + SSE 续传 + 显式取消 + 重启恢复。

设计锚点（docs/decisions.md §8）：

- **断线≠取消**：SSE 订阅者断开不再取消执行（替代 B13 的 cancel-on-exit），
  run 收口落库照常；取消只来自显式动作（``cancel`` / 账号停用批量取消）。
- **任务身份按 task_id**：pending 行 + ``rag_task_inputs`` 寄存输入可重驱动；
  终态行按 ``assistant_messages`` / ``evidence_json`` 重建回放帧。
- **harness 作业接续**靠 ``rag_task_jobs`` 账本（接线在 agent/tools.py 的
  grade_plugin），本模块只负责把执行体重跑起来。
- **容量护栏复用 admission**：正常受理走 ``acquire``（可排队/429），恢复扫描
  走 ``try_acquire``（满员留待下拍，绝不挤占实时受理队列）。

注册表 ``TASKS`` 只在事件循环线程读写（asyncio 单线程语义，无需锁）。
"""
from __future__ import annotations

import asyncio
import json
import logging
import time
from dataclasses import dataclass, field
from datetime import datetime, timezone
from typing import Any, AsyncIterator, Dict, List, Optional

from fastapi import HTTPException

from ..config import get_settings
from ..core.auth import Principal
from ..db import get_conn, get_database_provider
from ..schemas.chat import ChatResponse
from .rag_issue_service import RagIssueService
from .rag_product_service import RagProductService, admission, chat_response_from_row

logger = logging.getLogger("assistant")

_EVENT_LOG_CAP = 500             # 事件回放日志上限：超出丢最旧（工具帧只作进度展示）
_FINISHED_TTL_SECONDS = 300.0    # 终态条目在注册表的留存窗口：迟到重连仍可读终态帧
_ORPHAN_GRACE_SECONDS = 30       # 与 RagIssueService.orphaned_pending 同口径的宽限期
_HEARTBEAT_SECONDS = 10.0        # 属主心跳周期：<< grace，允许连续漏两拍才被当孤儿
_TASK_DONE = object()            # 订阅者队列的收口哨兵


@dataclass
class ManagedTask:
    """一条在执行/已收口任务的进程内句柄。"""

    task_id: str
    service: RagProductService
    run_task: Optional[asyncio.Task] = None
    # 执行过程事件回放日志（cap 500，丢最旧）；start/终态帧不入日志，按订阅合成。
    events: List[dict] = field(default_factory=list)
    subscribers: List[asyncio.Queue] = field(default_factory=list)
    result: Optional[ChatResponse] = None     # run() 的返回
    error: Optional[BaseException] = None
    terminal: bool = False                    # run 收口后置位
    finished_at: float = 0.0
    done: asyncio.Event = field(default_factory=asyncio.Event)


TASKS: Dict[str, ManagedTask] = {}

# 正在装配的恢复集合：prepare_for_recovery 有 await，_spawn 注册之前存在窗口；
# 没有它，并发接续/两拍扫描可能把同一 pending 行重驱动两次。
_RECOVERING: set = set()


def is_running(task_id: str) -> bool:
    """注册表命中且未收口。pending 行 + 在跑 → 对外展示 running。"""
    managed = TASKS.get(task_id)
    return managed is not None and not managed.terminal


def sweep_finished() -> None:
    """惰性清理终态条目（TTL 内保留供迟到重连读终态帧）。"""
    cutoff = time.monotonic() - _FINISHED_TTL_SECONDS
    for task_id, managed in list(TASKS.items()):
        if managed.terminal and managed.finished_at <= cutoff:
            TASKS.pop(task_id, None)


# ---------------------------------------------------------------------------
# 执行体生命周期
# ---------------------------------------------------------------------------


async def start_task(service: RagProductService) -> ManagedTask:
    """占槽 → 登记注册表 → 起执行协程；admission 429 原样抛出。

    幂等：同一 task_id 已注册直接返回既有句柄——acquire 前后各查一次，
    后者到 _spawn 之间没有 await，同一事件循环内不存在双起步窗口。
    """
    existing = TASKS.get(service.task_id)
    if existing is not None:
        return existing
    await admission.acquire()
    existing = TASKS.get(service.task_id)
    if existing is not None:
        await admission.release()
        return existing
    try:
        return _spawn(service)
    except BaseException:
        await admission.release()
        raise


def _spawn(service: RagProductService) -> ManagedTask:
    managed = ManagedTask(task_id=service.task_id, service=service)
    TASKS[managed.task_id] = managed
    managed.run_task = asyncio.create_task(_run_with_deadline(service, managed))
    return managed


def start_reserved_task(service: RagProductService) -> ManagedTask:
    """调用方已占 admission 槽且已原子受理；只注册并启动执行体。"""
    if not service.task_id or service.task_id in TASKS:
        raise RuntimeError("任务未受理或已启动")
    return _spawn(service)


async def ensure_started(service: RagProductService) -> ManagedTask:
    """events() 薄壳用：同一 service 已注册则复用，防止重复起步。"""
    managed = TASKS.get(service.task_id)
    if managed is not None:
        return managed
    return await start_task(service)


async def _run_with_deadline(service: RagProductService, managed: ManagedTask) -> None:
    """执行体收口总管。

    - 事件扇出：回放日志 + 各订阅者无界队列（订阅者再慢/断开都不反压执行）；
    - 超 ``rag_task_max_seconds`` → ``mark_interrupted('timeout')`` 判死；
    - ``CancelledError`` 由 cancel 路径落库，此处不吞也不重复写；
    - 任何终态兜底清 ``rag_task_inputs``（finish/cancel 已删则幂等）、释放占槽、
      置 terminal 并唤醒订阅者；条目本体留给 ``sweep_finished`` 惰性清理。
    """
    issues = RagIssueService()

    async def emit(event: Dict[str, Any]) -> None:
        managed.events.append(event)
        if len(managed.events) > _EVENT_LOG_CAP:
            del managed.events[: len(managed.events) - _EVENT_LOG_CAP]
        for queue in managed.subscribers:
            queue.put_nowait(event)

    try:
        managed.result = await asyncio.wait_for(
            service.run(emit), timeout=get_settings().rag_task_max_seconds)
    except asyncio.TimeoutError as exc:
        managed.error = exc
        try:
            await asyncio.to_thread(issues.mark_interrupted, managed.task_id, "timeout")
        except Exception:
            logger.exception(json.dumps({"event": "task_mark_timeout_failed",
                                         "task_id": managed.task_id}, ensure_ascii=False))
    except asyncio.CancelledError as exc:
        managed.error = exc
        raise
    except Exception as exc:
        managed.error = exc
        logger.warning(json.dumps({"event": "task_run_failed", "task_id": managed.task_id,
                                   "error_type": type(exc).__name__}, ensure_ascii=False))
    finally:
        # 只有库行已终态时才能丢执行输入。finish 的 DB 故障可能留下 pending；
        # 保留输入使下一次孤儿扫描可重驱动，而不是把可恢复任务判死。
        pending = True
        try:
            row = await asyncio.to_thread(
                issues.get_task, managed.task_id, service.principal.subject)
            pending = row is None or row["status"] == "pending"
            if not pending:
                await asyncio.to_thread(issues.drop_task_inputs, managed.task_id)
        except Exception:
            logger.warning(json.dumps({"event": "task_terminal_check_failed",
                                       "task_id": managed.task_id}, ensure_ascii=False))
        try:
            await admission.release()
        except Exception:
            logger.exception(json.dumps({"event": "admission_release_failed",
                                         "task_id": managed.task_id}, ensure_ascii=False))
        # terminal/done/哨兵之间不做任何 await：订阅者观察到的状态变化是原子的。
        managed.finished_at = time.monotonic()
        managed.terminal = True
        managed.done.set()
        for queue in managed.subscribers:
            queue.put_nowait(_TASK_DONE)
        managed.subscribers.clear()
        if pending:
            TASKS.pop(managed.task_id, None)


# ---------------------------------------------------------------------------
# 订阅 / 续传
# ---------------------------------------------------------------------------


def _start_frame(managed: ManagedTask) -> Dict[str, Any]:
    service = managed.service
    return {"event": "start", "data": {
        "session_id": service.session_id,
        "request_id": service.request_id,
        "task_id": managed.task_id,
    }}


def response_frames(result: ChatResponse, start: bool = True) -> List[Dict[str, Any]]:
    """结果 → SSE 帧序列，与原 ``events()`` 尾部同形：sources → delta → done。"""
    frames: List[Dict[str, Any]] = []
    if start:
        frames.append({"event": "start", "data": {
            "session_id": result.session_id,
            "request_id": result.request_id,
            "task_id": result.task_id,
        }})
    frames.append({"event": "sources", "data": {
        "citations": [c.model_dump() for c in result.citations]}})
    frames.append({"event": "delta", "data": {"delta": result.answer}})
    frames.append({"event": "done", "data": result.model_dump(exclude={"answer", "citations"})})
    return frames


def terminal_row_frames(row: Dict[str, Any], message: Optional[Dict[str, Any]]) -> List[Dict[str, Any]]:
    """终态行 → start/sources/delta/done 重建帧；done 附加 status/terminal 宽松字段。"""
    response = chat_response_from_row(row, message)
    frames = response_frames(response)
    frames[-1]["data"] = {**frames[-1]["data"], "status": row["status"], "terminal": True}
    return frames


def load_row_message(row: Dict[str, Any]) -> Optional[Dict[str, Any]]:
    """终态行正文（含取消占位消息）；无 message_id → None。"""
    if not row.get("message_id"):
        return None
    ph = get_database_provider().placeholder
    with get_conn() as conn:
        message = conn.execute(
            f"SELECT content,metadata_json FROM assistant_messages WHERE id={ph}",
            (row["message_id"],)).fetchone()
    return dict(message) if message else None


async def subscribe(managed: ManagedTask) -> AsyncIterator[Dict[str, Any]]:
    """订阅任务事件：start → 回放日志快照 → 实时帧 → 终态帧。

    订阅方退出（断线/客户端取消）**绝不**取消 run_task——断线续跑是本层语义。
    快照与订阅注册之间无 await，同一事件循环内不存在漏帧窗口。
    """
    queue: asyncio.Queue = asyncio.Queue()   # 无界：detached 订阅者不得反压执行
    snapshot = list(managed.events)
    live = not managed.terminal
    if live:
        managed.subscribers.append(queue)
    try:
        yield _start_frame(managed)
        for event in snapshot:
            yield event
        if live:
            while True:
                try:
                    event = await asyncio.wait_for(queue.get(), 1.0)
                except asyncio.TimeoutError:
                    if managed.terminal:
                        break  # 哨兵兜底：极端竞态下也能正常收口
                    yield {"event": "status", "data": {"stage": "processing"}}
                    continue
                if event is _TASK_DONE:
                    break
                yield event
        async for frame in _terminal_frames(managed):
            yield frame
    finally:
        try:
            managed.subscribers.remove(queue)
        except ValueError:
            pass


async def _terminal_frames(managed: ManagedTask) -> AsyncIterator[Dict[str, Any]]:
    """收口后补终态帧：有结果按原 events() 尾部形态，否则 done{status,terminal}。"""
    if managed.result is not None:
        for frame in response_frames(managed.result, start=False):
            yield frame
        return
    status = None
    try:
        row = await asyncio.to_thread(
            RagIssueService().get_task, managed.task_id, managed.service.principal.subject)
        if row and row["status"] != "pending":
            status = row["status"]
    except Exception:
        status = None
    if status == "pending":
        yield {"event": "done", "data": {
            "task_id": managed.task_id, "status": "pending", "terminal": False}}
        return
    if status is None:
        status = "cancelled" if isinstance(managed.error, asyncio.CancelledError) else "system_error"
    yield {"event": "done", "data": {
        "task_id": managed.task_id, "status": status, "terminal": True}}


async def task_events(task_id: str, user_id: str) -> AsyncIterator[Dict[str, Any]]:
    """按 task_id 续传 SSE 帧：注册表 → subscribe；终态行 → 重建；pending → 重驱动。

    越权/不存在的 task_id 一律 404（不区分，不泄露任务存在性）。
    """
    sweep_finished()
    issues = RagIssueService()
    managed = TASKS.get(task_id)
    if managed is not None and managed.service.principal.subject != user_id:
        managed = None   # 归属不符 → 走库行校验，大概率 404
    if managed is None:
        row = await asyncio.to_thread(issues.get_task, task_id, user_id)
        if row is None:
            raise HTTPException(404, "task not found")
        if row["status"] == "pending":
            managed = await ensure_managed(row)
            if managed is None:
                fresh = await asyncio.to_thread(issues.get_task, task_id, user_id)
                if fresh is not None:
                    row = fresh
        if managed is None:
            if row["status"] == "pending":
                # 暂不可恢复（太新/占槽满）：如实回报进行中终帧，客户端稍后重连即可。
                yield {"event": "start", "data": {
                    "session_id": row["session_id"], "request_id": row["request_id"],
                    "task_id": row["id"]}}
                yield {"event": "done", "data": {
                    "task_id": row["id"], "status": "pending", "terminal": False}}
            else:
                message = await asyncio.to_thread(load_row_message, row)
                for frame in terminal_row_frames(row, message):
                    yield frame
            return
    async for event in subscribe(managed):
        yield event


# ---------------------------------------------------------------------------
# 等待 / 接续（非流式）
# ---------------------------------------------------------------------------


async def wait_managed_result(managed: ManagedTask, timeout_sec: float) -> Optional[ChatResponse]:
    """等执行收口并返回 ChatResponse；超时抛 409（detail 带 task_id 供轮询/续传）。"""
    try:
        await asyncio.wait_for(managed.done.wait(), timeout_sec)
    except asyncio.TimeoutError:
        raise HTTPException(409, detail={
            "task_id": managed.task_id, "status": "running",
            "message": "任务仍在执行，可通过 /api/tasks/{task_id} 查询或续传",
        }) from None
    return managed.result


async def attached_response(row: Dict[str, Any], user_id: str) -> ChatResponse:
    """probe 命中 in_progress/terminal 时的非流式接续响应。"""
    issues = RagIssueService()
    if row["status"] == "pending":
        managed = await ensure_managed(row)
        if managed is not None:
            result = await wait_managed_result(managed, get_settings().rag_request_timeout_sec)
            if result is not None:
                return result
            fresh = await asyncio.to_thread(issues.get_task, row["id"], user_id)
            if fresh is not None:
                row = fresh
    if row["status"] == "pending":
        raise HTTPException(409, detail={
            "task_id": row["id"], "status": "pending",
            "message": "任务等待重驱动，可稍后重试或通过 /api/tasks/{task_id} 续传",
        })
    message = await asyncio.to_thread(load_row_message, row)
    return chat_response_from_row(row, message)


# ---------------------------------------------------------------------------
# 显式取消（断线不走到这里）
# ---------------------------------------------------------------------------


async def cancel(task_id: str, user_id: str, origin: str = "user_cancelled") -> Dict[str, Any]:
    """显式取消：注册表在跑 → cancel 执行协程并等收口；库行 pending→cancelled。

    幂等：重复调用/已终态的 cancel_task 条件更新返回 False，直接回读当前状态。
    越权/不存在 → 404。
    """
    issues = RagIssueService()
    row = await asyncio.to_thread(issues.get_task, task_id, user_id)
    if row is None:
        raise HTTPException(404, "task not found")
    # 库内终态先于本地协程取消，成绩事务据此拒绝迟到的写入。
    await asyncio.to_thread(issues.cancel_task, task_id, origin, user_id)
    managed = TASKS.get(task_id)
    if managed is not None and managed.run_task is not None and not managed.run_task.done():
        managed.run_task.cancel()
        await asyncio.gather(managed.run_task, return_exceptions=True)
    fresh = await asyncio.to_thread(issues.get_task, task_id, user_id)
    return fresh or row


async def cancel_pending_for_user(user_id: str, origin: str = "account_disabled") -> int:
    """账号停用等批量取消：库行批量终态 + 注册表在跑协程逐个取消收口。"""
    issues = RagIssueService()
    count = await asyncio.to_thread(issues.cancel_pending_for_user, user_id, origin)
    await cancel_local_for_user(user_id)
    return count


async def cancel_local_for_user(user_id: str) -> None:
    """库事务已把该用户任务终态化后，取消本进程残留的执行协程。"""
    for managed in list(TASKS.values()):
        run_task = managed.run_task
        if managed.service.principal.subject == user_id and run_task is not None and not run_task.done():
            run_task.cancel()
    for managed in list(TASKS.values()):
        run_task = managed.run_task
        if managed.service.principal.subject == user_id and run_task is not None:
            await asyncio.gather(run_task, return_exceptions=True)


# ---------------------------------------------------------------------------
# 重启 / 孤儿恢复
# ---------------------------------------------------------------------------


def _row_age_seconds(row: Dict[str, Any]) -> float:
    try:
        updated = datetime.fromisoformat(str(row["updated_at"]))
        if updated.tzinfo is None:
            updated = updated.replace(tzinfo=timezone.utc)
        return max(0.0, (datetime.now(timezone.utc) - updated).total_seconds())
    except (TypeError, ValueError):
        return float("inf")   # 解析失败按最老处理


async def ensure_managed(row: Dict[str, Any]) -> Optional[ManagedTask]:
    """pending 行的执行体：注册表命中直接返回；过 grace 窗口的孤儿按恢复口径重驱动。

    太新的行不抢跑——begin→start_task 之间本来就有窗口，grace 内不重复驱动。
    """
    managed = TASKS.get(row["id"])
    if managed is not None:
        return managed
    if _row_age_seconds(row) < _ORPHAN_GRACE_SECONDS:
        return None
    return await _recover_row(row)


def heartbeat_owned() -> int:
    """把本进程在跑任务的 pending 行 updated_at 拨到当前时刻，返回刷新条数。

    多副本/共享库下，其他副本的孤儿扫描与接续路径只凭 ``updated_at`` 判活；
    属主持续心跳即声明「这行归我管」，防止在跑 >grace 的任务被跨副本双执行。
    进程真死则心跳停、行转陈旧，另一副本照常接管——恢复语义不变。
    终态行（cancelled/answered）由 ``status='pending'`` 条件天然排除。
    """
    ids = [task_id for task_id, managed in TASKS.items() if not managed.terminal]
    if not ids:
        return 0
    ph = get_database_provider().placeholder
    marks = ",".join([ph] * len(ids))
    with get_conn() as conn:
        return conn.execute(
            f"UPDATE rag_requests SET updated_at={ph} WHERE status='pending' AND id IN ({marks})",
            (datetime.now(timezone.utc).isoformat(), *ids)).rowcount


async def heartbeat_loop() -> None:
    """周期心跳：单拍失败不致命，下一拍再来；进程退出随任务取消结束。"""
    while True:
        try:
            await asyncio.to_thread(heartbeat_owned)
        except Exception:
            logger.warning(json.dumps({"event": "task_heartbeat_failed"}, ensure_ascii=False))
        await asyncio.sleep(_HEARTBEAT_SECONDS)


def _load_enabled_user(user_id: str) -> Optional[Dict[str, Any]]:
    """users 表直查：不存在或 enabled=0 → None（恢复权限口径，decisions §8）。"""
    if not user_id:
        return None
    ph = get_database_provider().placeholder
    with get_conn() as conn:
        row = conn.execute(
            f"SELECT roles, enabled, daily_quota FROM users WHERE id={ph}",
            (user_id,)).fetchone()
    if not row or not row["enabled"]:
        return None
    try:
        roles = json.loads(row["roles"])
    except (TypeError, ValueError):
        roles = ["viewer"]
    return {"roles": roles if isinstance(roles, list) else ["viewer"],
            "daily_quota": row["daily_quota"] or 0}


async def _recover_row(row: Dict[str, Any]) -> Optional[ManagedTask]:
    """重驱动一条 pending 行；不可恢复时判 interrupted / cancelled 并返回 None。"""
    issues = RagIssueService()
    task_id = row["id"]
    existing = TASKS.get(task_id)
    if existing is not None:
        return existing
    if task_id in _RECOVERING:
        return None   # 另一处正在恢复同一行：接续方拿到 pending 终帧，稍后重连即可
    _RECOVERING.add(task_id)
    try:
        inputs = await asyncio.to_thread(issues.load_task_inputs, task_id)
        if inputs is None:
            # 输入缺失（24h TTL 清理或 schema 漂移）→ interrupted，terminal 即不再回收。
            await asyncio.to_thread(issues.mark_interrupted, task_id, "interrupted")
            return None
        if get_settings().auth_mode == "local":
            # local 模式没有账号体系（subject 恒为 "local"、users 表无对应行），
            # 恢复主体与 require_auth 的本地 principal 同形。
            user = {"roles": ["admin"], "daily_quota": 0}
        else:
            user = await asyncio.to_thread(_load_enabled_user, row["user_id"])
        if user is None:
            # 账号不存在或已停用：按取消语义终止（decisions §8），不回收结果回放。
            await asyncio.to_thread(issues.cancel_task, task_id, "account_disabled", "system")
            return None
        if not await asyncio.to_thread(issues.claim_recovery, task_id, _ORPHAN_GRACE_SECONDS):
            # 行已被别处终态化/被另一副本抢先认领：放弃，接续方拿 pending 终帧。
            return None
        if not await admission.try_acquire():
            return None   # 占槽满：留待下一拍/下次接续
        try:
            principal = Principal(subject=row["user_id"], roles=user["roles"],
                                  daily_quota=user["daily_quota"])
            service = RagProductService(principal, resume_from={"row": row, "inputs": inputs})
            await service.prepare_for_recovery()
            managed = _spawn(service)
            logger.info(json.dumps({"event": "task_recovered", "task_id": task_id},
                                   ensure_ascii=False))
            return managed
        except BaseException:
            await admission.release()
            raise
    finally:
        _RECOVERING.discard(task_id)


async def recover_orphans() -> int:
    """恢复扫描一拍：孤儿 pending 行重驱动；单行失败不拖垮整拍。"""
    sweep_finished()
    issues = RagIssueService()
    rows = await asyncio.to_thread(issues.orphaned_pending, _ORPHAN_GRACE_SECONDS)
    recovered = 0
    for row in rows:
        try:
            managed = await _recover_row(row)
            recovered += int(managed is not None)
        except Exception as exc:
            logger.warning(json.dumps({
                "event": "task_recover_failed", "task_id": row["id"],
                "error_type": type(exc).__name__}, ensure_ascii=False))
    return recovered
