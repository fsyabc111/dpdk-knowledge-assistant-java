# 14 任务、并发、超时、取消与 to_thread

## 学习目标

- 会用 `create_task` 把"顺序 await"变成并发；知道必须保留 Task 引用（事件循环只持弱引用）；
- `gather`/`asyncio.wait`/`TaskGroup` 的定位（`gather` 的取消语义见 05 篇实验，不重做）；
- 超时：`wait_for` 取消内部任务并抛 `TimeoutError`；3.11 起 `asyncio.TimeoutError is TimeoutError`；`asyncio.timeout()` 上下文管理器；
- 取消：`task.cancel()` 是请求、`CancelledError` 在当前 await 点抛出；必须重抛；吞掉的后果；`uncancel` 简介；
- `shield`：保护内部任务不被外层取消；
- `to_thread`：默认线程池、contextvars、GIL、"取消 await 不停线程"；
- 能把这些机制对到项目里的真实取舍。

**本主题在项目里出现在哪**

| 位置 | 用到什么 |
| --- | --- |
| `backend/app/services/task_service.py` `_run_with_deadline` | `wait_for(service.run(emit), rag_task_max_seconds)` 总墙；`TimeoutError`/`CancelledError` 分支；finally 收口 |
| `backend/app/services/task_service.py` `wait_managed_result` / `cancel` | `wait_for(managed.done.wait(), timeout)` 409；`run_task.cancel()` + `gather(return_exceptions=True)` |
| `backend/app/services/rag_product_service.py` `_run_candidate_once` | `create_task` + `wait_for(asyncio.shield(candidate), rag_request_timeout_sec)` |
| `backend/app/agent/graph.py` `planner` | `remaining` 剩余预算 + `wait_for(_bounded_invoke(), remaining)`；wall-clock 区分软截止与上游超时 |
| `backend/app/core/async_cache.py` `get_or_create` | `await asyncio.shield(task)`：单飞任务不被单请求取消波及 |
| `backend/app/services/retrieval_service.py` `search` | `asyncio.to_thread(self._retrieve_sync, ...)` |
| `backend/app/main.py` | `_rag_maintenance_task`/`_task_heartbeat_task` 全局持引用，shutdown 取消 + `gather(return_exceptions=True)` |

## 语法详解

### `create_task`：从串行到并发

顺序 `await` 是**串行**——上一个不结束下一个不开始（13 篇）；`create_task` 把协程**注册给事件循环**立刻返回 Task，两个任务交替前进：

```python
async def job():
    await asyncio.sleep(0.2)

async def main():
    t0 = time.monotonic()
    await job(); await job()
    seq = time.monotonic() - t0
    t0 = time.monotonic()
    t1 = asyncio.create_task(job()); t2 = asyncio.create_task(job())
    await asyncio.gather(t1, t2)
    par = time.monotonic() - t0
    print("seq ~2x par:", seq > 1.5 * par)
```

输出：`seq ~2x par: True`

**必须保留 Task 引用**：事件循环对 task 只持弱引用，没人持有时 task 可能被 GC 中途干掉——官方文档 "Coroutines and Tasks" 的 `create_task` 一节明确要求"save a reference to the result"。项目的写法是模块级变量：

```python
# 示意：main.py 的模式（行尾注释为讲解所加，非逐字摘录）
_rag_maintenance_task = asyncio.create_task(start_rag_maintenance())
# shutdown 时：
#   _rag_maintenance_task.cancel()
#   await asyncio.gather(..., return_exceptions=True)
```

`ManagedTask.run_task` 同理——注册表条目本身就是引用。

### `gather` / `wait` / `TaskGroup`

- `gather` 的取消语义见 `05_exceptions.md` 的实验（外层取消传播给子任务；子任务普通异常不取消兄弟）；
- `asyncio.wait`：拿到 `(done, pending)` 集合，等"任一完成/全部完成/超时"——比 gather 低层，项目未使用；
- `asyncio.TaskGroup`（3.11）：结构化并发的上下文管理器，任一子任务失败自动取消其余并聚合为 `ExceptionGroup`——**项目未使用**（并发工具轮是手写 `ensure_future` + `gather`，05 篇已精读）。

### 超时：`wait_for` 与 `asyncio.timeout`

3.11 里 `wait_for(coro, timeout)` 到期时**取消内部任务并等它落地**，然后抛 `TimeoutError`（本机 `Lib/asyncio/tasks.py` 源码：超时路径走 `await _cancel_and_wait(fut)` 再 `raise exceptions.TimeoutError`）。且 3.11 起 `asyncio.TimeoutError is TimeoutError` 就是内置 `TimeoutError`（实测 `True`）。实跑：

```python
async def hanging():
    await asyncio.sleep(10)

async def main():
    t = asyncio.create_task(hanging())
    try:
        await asyncio.wait_for(t, 0.05)
    except TimeoutError:
        print("wait_for TimeoutError; inner cancelled:", t.cancelled())
```

输出：`wait_for TimeoutError; inner cancelled: True`

`asyncio.timeout(delay)`（3.11）是等价的上下文管理器写法，超时同样抛 `TimeoutError`：

```python
async def w():
    try:
        async with asyncio.timeout(0.02):
            await asyncio.sleep(10)
    except TimeoutError:
        print("timeout() raised TimeoutError")
```

输出：`timeout() raised TimeoutError`（本项目代码用 `wait_for`，未用 `timeout()`）。

### 取消：`cancel()` 是请求

`t.cancel()` **不杀协程**——它在协程当前/下一个 `await` 点注入 `CancelledError`。协程可以 except 它做清理，**但必须重抛**，否则取消语义被破坏（3.11.15 实跑）：

```python
async def swallower():
    try:
        await asyncio.sleep(10)
    except asyncio.CancelledError:
        print("  swallower caught cancel, NOT re-raising")
        return "i survived"          # 吞掉：返回正常值

async def main():
    t = asyncio.create_task(swallower())
    await asyncio.sleep(0.01)
    t.cancel()
    r = await t
    print("  task result:", r, "cancelled():", t.cancelled())
```

输出：`swallower caught cancel, NOT re-raising` 与 `task result: i survived cancelled(): False`

吞掉的后果：任务"正常完成"返回了结果——上层（比如 `wait_for` 的超时取消、断线清理）以为它死了其实没死，后续逻辑全乱。**正确姿势**：`except CancelledError: ...清理...; raise`（05 篇全仓范式）；`finally` 兜底清理（08 篇）。3.11 还有 `task.uncancel()`：在确实要"消化"取消的高级场景（`timeout()`/`TaskGroup` 内部就靠它）撤销一次取消计数——普通业务代码不需要。

### `shield`：给内部任务穿防弹衣

`await asyncio.shield(t)` 保护 `t` 不被**外层**取消/超时波及——外层照样收到 `CancelledError`/`TimeoutError`，但 `t` 继续跑（两个实跑实验）：

```python
async def inner():
    try:
        await asyncio.sleep(0.05)
        print("  inner finished normally")
        return "inner-result"
    except asyncio.CancelledError:
        print("  inner was cancelled!")
        raise

async def demo_cancel():
    it = asyncio.create_task(inner())
    async def outer():
        return await asyncio.shield(it)
    ot = asyncio.create_task(outer())
    await asyncio.sleep(0.01)
    ot.cancel()
    try:
        await ot
    except asyncio.CancelledError:
        print("  outer await raised CancelledError")
    await asyncio.sleep(0.1)
    print("  inner done:", it.done(), "cancelled:", it.cancelled(), "result:", it.result())

async def demo_timeout():
    it = asyncio.create_task(inner())
    try:
        await asyncio.wait_for(asyncio.shield(it), 0.01)
    except TimeoutError:
        print("  wait_for TimeoutError; inner still running:", not it.done())
    print("  later await inner:", await it)
```

输出（3.11.15 实跑）：

```text
  outer await raised CancelledError
  inner finished normally
  inner done: True cancelled: False result: inner-result
  wait_for TimeoutError; inner still running: True
  inner finished normally
  later await inner: inner-result
```

对照实验（去掉 shield，外层 await 直接挂 it）：取消传播进内层——`inner was cancelled!` / `inner cancelled too: True`。**shield 只挡外层的取消/超时，内层自己的取消不受影响**；`wait_for` 超时时它取消的是 shield 包装，不是内层任务。

### `to_thread`：把同步工作移出事件循环

`asyncio.to_thread` 的源码 01 篇已摘（包 `run_in_executor` + contextvars 复制）。补三个运行时事实：

- **默认线程池**：`loop.run_in_executor(None, ...)` 用默认 `ThreadPoolExecutor`，`max_workers = min(32, (os.cpu_count() or 1) + 4)`（`Lib/concurrent/futures/thread.py` 核实）——共享池，不是每调用一个线程；
- **contextvars 复制**：`to_thread` 源码里 `ctx = contextvars.copy_context()` + `ctx.run`——请求上下文（追踪 id 等）随调用进线程；
- **GIL**：线程只对**阻塞 IO** 或**释放 GIL 的 C 调用**（sqlite3/httpx/hashlib 大件）有帮助——纯 Python CPU 循环在多线程下仍串行（`_retrieve_sync` 的打分是 Python 层计算，线程化赚的是"不挡事件循环"，不是真并行加速）；
- **取消不停线程**：`await to_thread(...)` 被取消时 await 立刻抛 `CancelledError`，但**线程继续跑完**（实跑）：

```python
import threading, time
done_marker = []
def blocking_io():
    time.sleep(0.05)
    done_marker.append("thread-finished")
    return "thread-result"

async def main():
    t = asyncio.create_task(asyncio.to_thread(blocking_io))
    await asyncio.sleep(0.01)
    t.cancel()
    try:
        await t
    except asyncio.CancelledError:
        print("  await raised CancelledError; thread alive at that moment:", not done_marker)
    await asyncio.sleep(0.1)
    print("  thread finished anyway:", done_marker)
```

输出：

```text
  await raised CancelledError; thread alive at that moment: True
  thread finished anyway: ['thread-finished']
```

推论：放进线程的工作要么天然幂等/可丢弃，要么自己处理中断——`task_service` 的落库操作（`mark_interrupted`/`finish`）都是"到线程里做完就做完"的短事务，取消 await 只影响"这边还等不等结果"，不影响"那边写不写库"。

## 常见陷阱

1. **`create_task` 丢引用**：`asyncio.create_task(f())` 后没接住返回值——GC 可能提前收掉任务。
2. **以为 `cancel()` 立即停**：它只是注入 `CancelledError`——协程在 CPU 循环里（无 await）永远不会响应取消。
3. **吞 `CancelledError` 不重抛**：任务"假死"返回正常值，上层清理逻辑全乱（见上面实验）。
4. **`wait_for` 内放不可中断的长事务**：超时取消会在任意 await 点炸开它——需要完整落库的操作要么缩短、要么 `shield`。
5. **`shield` 后忘了内层**：外层拿到 `TimeoutError` 时内层还在跑——不管不顾就是 leaked task，要按 `_run_candidate_once` 的模式显式接管或取消。
6. **`to_thread` 里放可中断性敏感的操作**：取消 await 不停线程（见实验），别指望取消能回滚线程内已做的事。
7. **`except Exception` 抓取消**：`CancelledError` 是 `BaseException`（05 篇），取消路径必须单独写。
8. `wait_for` 与 `timeout()` 的 TimeoutError 是**内置** `TimeoutError`（3.11 起统一），别再 `except asyncio.TimeoutError` 还自以为特别——两者同一个类。

## 项目代码精读

> 本节代码块均按仓库原文摘录（保留原缩进与注释），依赖项目内部上下文，**不可单独运行**。

### `_run_with_deadline`：总墙超时 + 取消 + 收口顺序

`backend/app/services/task_service.py`：

```python
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
```

- `wait_for` 是**任务总墙**：超时 → 内部执行协程被取消 → `managed.error` 记下 → `mark_interrupted('timeout')` 落库判死；
- `CancelledError` 单列：外部显式 `cancel` 走的通道，记账后**重抛**——`task_service.cancel` 里 `managed.run_task.cancel()` 后 `await asyncio.gather(managed.run_task, return_exceptions=True)` 等的就是这条路径收口；
- finally 顺序：先查库行终态（决定丢不丢 `rag_task_inputs`）→ release admission → **无 await** 地置 terminal/done/哨兵 → pending 则从 TASKS 摘除留给恢复扫描（13 篇讲过的"两次 await 之间即原子"）。

### `_run_candidate_once`：为什么需要 `shield`

`backend/app/services/rag_product_service.py`：

```python
    async def _run_candidate_once(self, emit):
        candidate = asyncio.create_task(self._candidate(emit))
        try:
            verdict, citations, release = await asyncio.wait_for(
                asyncio.shield(candidate), self.settings.rag_request_timeout_sec)
        except asyncio.TimeoutError:
            # 普通问答仍守 120s 墙；若评测作业已登记，继续由任务总墙托管，
            # 客户端等待可先返回 task_id，harness 完成后同一执行体落成绩。
            if await asyncio.to_thread(self.issues.has_grading_job, self.task_id):
                verdict, citations, release = await candidate
            else:
                candidate.cancel()
                await asyncio.gather(candidate, return_exceptions=True)
                verdict, citations, release = Verdict(UNAVAILABLE, "system_error", "generation_failed"), [], ""
        except asyncio.CancelledError:
            candidate.cancel()
            raise
        except RetryableTaskFailure:
            raise
        except Exception as exc:
            candidate.cancel()
            logger.warning("问答执行失败", extra={"event": "rag_request_failed", "error_type": type(exc).__name__})
            verdict, citations, release = Verdict(UNAVAILABLE, "system_error", "generation_failed"), [], ""
```

- `create_task(self._candidate(emit))`：执行体是独立 Task，`wait_for` 包装的是**外层等待**；
- `asyncio.shield(candidate)` 的关键作用：`wait_for` 超时时**只取消 shield 包装**——`candidate` 本体不被取消（对照上面实验 2）；
- 超时分支才有意义：已登记评测作业 → `await candidate` 继续等它跑完（任务总墙在 `_run_with_deadline` 兜底）；否则 `candidate.cancel()` + `gather(return_exceptions=True)` 显式收拾——**shield 把"超时"从"杀任务"变成"只松开等待"**，这就是为什么不直接 `wait_for(candidate)`（那样超时会把 candidate 一起取消，评测作业就断了）。

### `planner` 软截止：remaining + wall-clock 判因

`backend/app/agent/graph.py`：

```python
        try:
            if deadline is None:
                response = await _bounded_invoke()
            else:
                remaining = deadline - time.monotonic()
                if remaining <= 0:
                    raise asyncio.TimeoutError
                response = await asyncio.wait_for(_bounded_invoke(), remaining)
        except asyncio.TimeoutError:
            # wait_for 的软截止超时与上游自身 TimeoutError 用 wall-clock 区分：
            # 后者在 deadline 之前到达时原样上抛，不误判成 deadline_hit。
            if deadline is not None and time.monotonic() >= deadline:
                return updates(
                    deadline_hit=True,
                    planner_calls=state.get("planner_calls", 0) + 1,
                )
            raise
```

- `remaining = deadline - time.monotonic()`：软截止是绝对时刻，每轮 planner 前算剩余预算喂 `wait_for`；
- `_bounded_invoke` 里 `async with upstream_slot()` 排队也计入预算（注释：wait_for 截的是"排队+上游调用"的总和）；
- `except asyncio.TimeoutError` 里**再查一次 wall-clock**：`wait_for` 的软截止超时与上游自身抛的 `TimeoutError` 同一个类型——只有 `monotonic() >= deadline` 才置 `deadline_hit` 转 finalize，否则原样上抛不误判。这是"超时来源消歧"的工程范式。

### `async_cache.get_or_create`：singleflight + shield

`backend/app/core/async_cache.py`：

```python
                task = asyncio.create_task(populate())
                self._inflight[key] = task

        # ``shield`` prevents cancellation of one HTTP request from
        # propagating into this process-wide single-flight task.
        return await asyncio.shield(task)
```

- `populate` 任务被 `create_task` 包成 Task 存进 `_inflight[key]`——**所有并发请求共享这一个 Task**（singleflight）；
- 每个等待方 `await asyncio.shield(task)`：任一请求自己超时/断线被取消时，取消打到 shield 上不波及共享任务——别的等待方不受影响，`_inflight` 也不会留下"已取消的僵尸任务"（`populate` 内部 `except BaseException` 自清是第二层保险，11 篇闭包部分已见）。

### `wait_managed_result` 与 `cancel`

`backend/app/services/task_service.py`：

```python
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
```

- `managed.done` 是 `asyncio.Event`（15 篇）——`wait_for` 包它的 `wait()`：超时≠任务死，转成 409 让客户端去续传；
- `from None` 压掉 `CancelledError`→`TimeoutError` 的链（05 篇）。

`backend/app/services/task_service.py` `cancel`：

```python
    # 库内终态先于本地协程取消，成绩事务据此拒绝迟到的写入。
    await asyncio.to_thread(issues.cancel_task, task_id, origin, user_id)
    managed = TASKS.get(task_id)
    if managed is not None and managed.run_task is not None and not managed.run_task.done():
        managed.run_task.cancel()
        await asyncio.gather(managed.run_task, return_exceptions=True)
```

- 显式取消的顺序：先落库终态（cancel_task 条件更新，成绩事务据此拒写）→ 再 `run_task.cancel()` → `gather(return_exceptions=True)` 等 `_run_with_deadline` 的 `except CancelledError: raise` 收口——**取消是"库行终态 → 协程取消 → 等收口"三步**。

### `retrieval_service.search` 的 `to_thread` 调用

`backend/app/services/retrieval_service.py`：

```python
        selected = await asyncio.to_thread(
            self._retrieve_sync,
            query,
            rewritten,
            query_embedding,
            limit,
            candidate_limit,
            trace,
        )
```

`to_thread(fn, *args)` 的位置参数转发（01 篇）——同步召回+打分整块进线程池，协程挂起等结果；13 篇引用的注释解释"为什么必须移出"。

## 练习

1. **读项目代码回答**：`_run_candidate_once` 用 `wait_for(asyncio.shield(candidate), timeout)` 而非 `wait_for(candidate, timeout)`。如果去掉 shield，超时时 `candidate` 会怎样？`has_grading_job` 分支为什么就失去意义了？
2. **读项目代码回答**：`planner` 的 `except asyncio.TimeoutError` 里为什么要再用 `time.monotonic() >= deadline` 查一次？直接 `return updates(deadline_hit=True)` 会出什么问题？
3. **动手写代码**：写 `async def with_deadline(coro_factory, sec)`：`create_task` + `wait_for(shield(task), sec)`；超时时不取消 task，返回 `(False, task)`；正常完成返回 `(True, result)`。验证：超时后 `task` 仍可 `await` 拿结果。
4. **动手写代码**：写一个演示"取消是协作"的例子：`polite()` 协程在 except CancelledError 里打印 cleanup 再 raise；`rude()` 协程用 `while True: pass`（无 await）跑——`rude` 的 `cancel()` 生效吗？给出能确定结束的写法（提示：它根本不会被调度之外的机制打断）。
5. **读项目代码回答**：`wait_managed_result` 超时时抛 409 而不是让 `TimeoutError` 上传。为什么这是正确的接口语义？（提示：超时的是"等待"，不是"任务"。）

## 参考答案

<details><summary>参考答案 1</summary>

去掉 shield 后 `wait_for(candidate, timeout)` 超时时**直接取消 `candidate` 并等它落地**（`wait_for` 内部 `await _cancel_and_wait`），然后才抛 `TimeoutError` 进入 `except asyncio.TimeoutError` 分支。此时 `candidate` 已经是已取消的终态 task，分两条路：

- **`has_grading_job` 为真**：走到 `verdict, citations, release = await candidate`——对一个已取消的 task `await` 会**立即抛 `CancelledError`**（实测：`await` 已取消 task → `CancelledError`）。这个异常不在 `_run_candidate_once` 的 try 里（它在 except 块内），直接穿出 `_run_candidate_once` → 经 `service.run` → `_run_with_deadline` 的 `except asyncio.CancelledError: managed.error = exc; raise` → finally：库行仍是 pending → 保留 `rag_task_inputs`、释放 admission、置 terminal/done/哨兵、从 `TASKS` 摘除 → 之后 `recover_orphans` 扫到这条 pending 孤儿、`claim_recovery` 认领、用寄存的输入**重启整个执行体**。后果：已提交的 harness 评测作业服务端还在跑，但收成绩的 candidate 已死；重驱动会从头重跑 agent 全链路（planner/检索等上游调用再花一遍），但评测本身不会重复：`grade_plugin` 先经 `_resume_grading_job` 查 `rag_task_jobs` 账本，按同一 `job_id` 接续（harness 侧同 `job_id` 为"加入既有执行/回放已完成结果"），已登记的作业不二次扣配额——代价是延迟与上游用量，"同一执行体由任务总墙托管"的设计落空；
- **`has_grading_job` 为假**：走 `candidate.cancel()`——对已完成（含已取消）的 task `cancel()` 返回 False，`gather(return_exceptions=True)` 立即收口，`verdict = system_error` → `issues.finish` 落终态 → 正常结束。这条路径反而"碰巧能工作"——但超时把 candidate 杀掉这一点本身就是无 shield 才发生的。

所以 shield 的精确作用是：`wait_for` 超时时**只取消 shield 包装的外层等待**，`candidate` 本体存活——`has_grading_job` 分支才有机会 `await candidate` 把它交给 `_run_with_deadline` 的总墙继续托管。

</details>

<details><summary>参考答案 2</summary>

`except asyncio.TimeoutError` 抓到的超时有两个来源：`wait_for` 软截止到期（应该转 finalize）与**上游调用自己抛的 `TimeoutError`**（LLM/embedding HTTP 层超时——不是本图的软截止）。不查 wall-clock 直接置 `deadline_hit`，会把"上游故障"误判成"软截止"——明明是上游挂了，却当成"预算耗尽"安静地转 finalize，错误被掩盖。`time.monotonic() >= deadline` 只有真的到点才成立，上游超时发生在 deadline 之前则原样 `raise` 走正常异常路径。

</details>

<details><summary>参考答案 3</summary>

```python
import asyncio

async def with_deadline(coro_factory, sec):
    task = asyncio.create_task(coro_factory())
    try:
        return True, await asyncio.wait_for(asyncio.shield(task), sec)
    except asyncio.TimeoutError:
        return False, task

async def slow():
    await asyncio.sleep(0.05)
    return "finished"

async def main():
    ok, t = await with_deadline(slow, 0.01)
    print("ok:", ok, "task done:", t.done())
    print("later:", await t)

asyncio.run(main())
```

实跑输出：`ok: False task done: False` / `later: finished`——超时分支拿到的是**活着的 task**，调用方可以择时继续 `await`，这正是 `_run_candidate_once` 的 `has_grading_job` 分支模式。

</details>

<details><summary>参考答案 4</summary>

`rude` 的 `cancel()` **不会生效**（至少不会很快）——`task.cancel()` 只是把"下次恢复时抛 CancelledError"标记上（`_must_cancel`），`while True: pass` 的协程根本没有 await 点让事件循环接管，它一被调度进来就独占线程直到自己结束或外部干预。演示：

```python
import asyncio

async def rude():
    n = 0
    while n < 50_000_000:      # 无 await 的纯 CPU 循环
        n += 1
    return n

async def polite():
    try:
        await asyncio.sleep(10)
    except asyncio.CancelledError:
        print("cleanup then raise")
        raise

async def main():
    p = asyncio.create_task(polite())
    await asyncio.sleep(0.01)
    p.cancel()
    try:
        await p
    except asyncio.CancelledError:
        print("polite cancelled")
    r = asyncio.create_task(rude())
    await asyncio.sleep(0.01)
    r.cancel()
    print("rude cancelled?", r.cancelled())   # 还没到 await 点，循环冻死整个事件循环
    try:
        await asyncio.wait_for(asyncio.shield(r), 0.05)
    except Exception as e:
        print(type(e).__name__)
```

实跑可见：`polite` 正常收口；`rude` 的 `cancel()` 之后 `r.cancelled()` 仍是 `False`——它要等循环结束才有机会被打断（实际上它把事件循环冻死了，连 `wait_for` 都没法启动）。工程结论：**纯 CPU 长循环必须 `to_thread`（或显式 `await asyncio.sleep(0)` 让出），否则取消/超时都救不了**。

</details>

<details><summary>参考答案 5</summary>

`wait_managed_result` 等的是 `managed.done.wait()`——超时意味着"任务还在跑"，不是"任务失败"。把它抛成 `TimeoutError`/`500` 会让客户端误以为任务死了；转成 409 + `task_id` + `status: running` 是在接口语义上如实报告"你等的东西没好，拿这个 id 去续传/轮询"——执行协程继续跑、客户端拿到可操作的下一步。这也是"等待方超时"与"执行方超时"（`_run_with_deadline` 的 `wait_for` 总墙判死）的分层：前者是客户端的耐心问题，后者才是任务的生命周期问题。

</details>

## 延伸阅读

- Python 官方文档：`asyncio` "Coroutines and Tasks"（`create_task`/`gather`/`wait`/`shield`/`wait_for`/`timeout`/`to_thread`、`create_task` 持引用的提示）、"Timeouts"、"Task Groups"、"Scheduling"。
- `concurrent.futures` 文档的 `ThreadPoolExecutor`（`run_in_executor` 默认池）。
- PEP 654（`ExceptionGroup`，TaskGroup 的聚合）；`contextvars` 文档（`to_thread` 的上下文复制）。
- `asyncio` "Developing with asyncio"（调试模式、线程安全、阻塞检测）。
