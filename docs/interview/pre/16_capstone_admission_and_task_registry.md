# 16 综合实战：准入、任务注册表与恢复

本篇是综合练习：把 `task_service`/`rag_product_service` 里最核心的三个机制，用最小代码**自己写一遍**，再对照项目实现。每步给：需求 → 提示 → 可运行参考实现（已用 `.venv` 3.11.15 实跑）→ 与项目代码逐项对照。

前置知识：09（生成器）、13（事件循环与单线程并发）、14（任务/超时/取消/shield/to_thread）、15（同步原语与异步迭代）。

## 学习目标

- 能手写有界准入（Condition + 排队超时 + try_acquire）；
- 能手写进程级任务注册表（回放日志 + 扇出队列 + 异步生成器订阅 + 终态收口）；
- 理解"断线 ≠ 取消"、"两次 await 之间即原子"在注册表设计里的落点；
- 能手写超时总墙 + 孤儿恢复的内存版，并说清项目为什么用数据库条件更新保证跨进程原子性。

**对照的项目代码**

| 实战模块 | 项目对应 |
| --- | --- |
| `Admission` | `backend/app/services/rag_product_service.py` `Admission`（15 篇已精读） |
| 任务注册表 | `backend/app/services/task_service.py` `ManagedTask`/`_spawn`/`_run_with_deadline`/`subscribe`/`cancel`/`sweep_finished` |
| 超时与恢复 | `_run_with_deadline`（14 篇已精读）+ `recover_orphans`/`_recover_row`/`heartbeat_loop` + `rag_issue_service.claim_recovery` |

## 第一步：有界准入 Admission

### 需求

写一个 `Admission`：

- `acquire()`：活动数满 `max_active` 时排队等空位，最多 `max_waiting` 人排队、等 `queue_timeout` 秒；超限抛 `Busy`；
- `try_acquire()`：不排队，有槽占槽返回 `True`，否则 `False`；
- `release()`：归还槽位并唤醒排队者。

### 提示

- "排队等空位"是 `asyncio.Condition` + `wait_for(predicate)` 的教科书用法（15 篇）；
- 排队名额 `waiting` 必须在**所有出口**归还（成功/超时/取消）——`finally`；
- 所有计数更新都在同一事件循环里，无需真锁，但检查-修改要包在 `async with condition:` 里。

### 参考实现（可运行）

```python
import asyncio

class Busy(Exception):
    pass

class Admission:
    def __init__(self, max_active, max_waiting, queue_timeout):
        self.max_active = max_active
        self.max_waiting = max_waiting
        self.queue_timeout = queue_timeout
        self.active = 0
        self.waiting = 0
        self.condition = asyncio.Condition()

    async def acquire(self):
        async with self.condition:
            if self.active >= self.max_active:
                if self.waiting >= self.max_waiting:
                    raise Busy("queue full")
                self.waiting += 1
                try:
                    await asyncio.wait_for(
                        self.condition.wait_for(lambda: self.active < self.max_active),
                        self.queue_timeout)
                except asyncio.TimeoutError:
                    raise Busy("queue timeout") from None
                finally:
                    self.waiting -= 1
            self.active += 1

    def try_acquire(self):
        if self.active >= self.max_active:
            return False
        self.active += 1
        return True

    async def release(self):
        async with self.condition:
            self.active -= 1
            self.condition.notify()
```

测试驱动（`staggered` 里 `await asyncio.sleep(0)` 强制任务按序启动，输出确定）：

```python
async def demo_admission():
    ad = Admission(max_active=1, max_waiting=1, queue_timeout=0.03)
    log = []
    async def job(name, hold):
        try:
            await ad.acquire()
        except Busy as e:
            log.append(f"{name}:429({e})")
            return
        log.append(f"{name}:in")
        await asyncio.sleep(hold)
        await ad.release()
        log.append(f"{name}:out")
    async def staggered(*specs):
        tasks = []
        for name, hold in specs:
            tasks.append(asyncio.create_task(job(name, hold)))
            await asyncio.sleep(0)
        await asyncio.gather(*tasks)
    await staggered(("A", 0.02), ("B", 0.01), ("C", 0.01))   # A 占槽，B 排队成功，C 撞等待上限
    await staggered(("E", 0.06), ("D", 0.01))               # E 久占，D 排队超时
    print("events:", " | ".join(log))
    print("try_acquire:", ad.try_acquire())

asyncio.run(demo_admission())
```

输出（3.11.15 实跑）：

```text
events: A:in | C:429(queue full) | A:out | B:in | B:out | E:in | D:429(queue timeout) | E:out
try_acquire: True
```

### 与项目对照

`backend/app/services/rag_product_service.py` 的 `Admission`（15 篇已逐行精读）：

- **相同**：`active`/`waiting` 计数 + `asyncio.Condition` + `wait_for(condition.wait_for(lambda: ...), queue_timeout)` + `finally: waiting -= 1` + 满员直接拒绝/超时拒绝两级 429——骨架与这里一模一样；
- **项目多做的**：额度来自 `get_settings()`（`rag_max_active_requests`/`rag_max_waiting_requests`/`rag_queue_timeout_sec`）而不是构造参数；`Busy` 换成 `HTTPException(429, headers={"Retry-After": "10"})`；`release` 用 `notify_all()`（这里用 `notify()`——排队者谓词重判后只有一个能进，两者都正确，`notify_all` 更保守）；`try_acquire` 给恢复扫描专用，docstring 明确"满员留待下拍，绝不挤占实时受理队列"；
- **为什么**：把"容量护栏"做成服务级单例——正常受理与孤儿恢复共用一套账本，恢复路径才不会挤爆实时请求。

## 第二步：任务注册表

### 需求

写一个进程级注册表 `TASKS`：

- `start(task_id, coro_factory)`：登记 `ManagedTask` 并起执行协程；
- `emit(event)`：写回放日志（容量上限 `CAP`）并扇出到各订阅者队列；
- `subscribe(managed)`：异步生成器——先回放快照、再推实时事件、收哨兵收口；
- 订阅者断开（异常/取消）**不影响**执行；支持显式 `cancel(task_id)`；
- 执行协程 `finally`：置 terminal、`done.set()`、给订阅者发哨兵；
- `sweep_finished()`：清终态条目。

### 提示

- `ManagedTask` 用 dataclass：`events`/`subscribers` 可变字段要 `field(default_factory=)`（12 篇）——示例里用普通类省篇幅；
- 快照必须 `list(managed.events)` 复制——回放期间 `emit` 还在写（02/09 篇）；
- "断线 ≠ 取消"：订阅协程死了，执行协程照样跑——订阅队列的注销放在 `finally`；
- 哨兵用 `object()` 单例，`is` 判等（15 篇）。

### 参考实现（可运行）

```python
import asyncio

class ManagedTask:
    def __init__(self, task_id, run_task):
        self.task_id = task_id
        self.run_task = run_task
        self.events = []
        self.subscribers = []
        self.result = None
        self.error = None
        self.terminal = False
        self.done = asyncio.Event()

TASKS = {}
DONE = object()     # 哨兵：is 判等，不与业务帧冲突
CAP = 3

def start(task_id, coro):
    managed = ManagedTask(task_id, None)
    TASKS[task_id] = managed

    async def emit(event):
        managed.events.append(event)
        if len(managed.events) > CAP:
            del managed.events[: len(managed.events) - CAP]
        for q in managed.subscribers:
            q.put_nowait(event)

    async def runner():
        try:
            managed.result = await coro(emit)
        except asyncio.CancelledError as exc:
            managed.error = exc
            raise
        except Exception as exc:
            managed.error = exc
        finally:
            managed.terminal = True
            managed.done.set()
            for q in managed.subscribers:
                q.put_nowait(DONE)

    managed.run_task = asyncio.create_task(runner())
    return managed

async def subscribe(managed):
    q = asyncio.Queue()
    snapshot = list(managed.events)
    live = not managed.terminal
    if live:
        managed.subscribers.append(q)
    try:
        for e in snapshot:
            yield ("replay", e)
        if live:
            while True:
                e = await q.get()
                if e is DONE:
                    break
                yield ("live", e)
    finally:
        try:
            managed.subscribers.remove(q)
        except ValueError:
            pass

def sweep_finished():
    dead = [tid for tid, m in TASKS.items() if m.terminal]
    for tid in dead:
        del TASKS[tid]
    return len(dead)

async def cancel(task_id):
    m = TASKS.get(task_id)
    if m is None:
        return False
    m.run_task.cancel()
    return True
```

测试驱动（模拟执行体 + 断线订阅 + 显式取消 + 失败收口 + 清扫）：

```python
async def worker(emit, fail=None, hang=False):
    await emit({"stage": "s1"})
    if fail:
        raise fail
    if hang:
        await asyncio.sleep(60)
    await emit({"stage": "s2"})
    return "result-ok"

async def demo_registry():
    log = []
    m1 = start("t1", lambda emit: worker(emit))
    async def sub():
        async for tag, e in subscribe(m1):
            log.append(f"sub:{tag}:{e['stage']}")
            if e["stage"] == "s1":
                raise ConnectionError("client gone")
    st = asyncio.create_task(sub())
    await asyncio.sleep(0.01)
    log.append(f"m1 done:{m1.done.is_set()} result:{m1.result}")
    st.cancel()
    async for tag, e in subscribe(m1):
        log.append(f"replay:{tag}:{e['stage']}")
    m2 = start("t2", lambda emit: worker(emit, hang=True))
    await asyncio.sleep(0.01)
    await cancel("t2")
    try:
        await m2.run_task
    except asyncio.CancelledError:
        log.append("t2:cancelled")
    m3 = start("t3", lambda emit: worker(emit, fail=ValueError("boom")))
    await m3.done.wait()
    log.append(f"t3 error:{type(m3.error).__name__}")
    print("  " + " | ".join(log))
    print("  TASKS before sweep:", sorted(TASKS), "-> swept:", sweep_finished(), "-> after:", sorted(TASKS))

asyncio.run(demo_registry())
```

输出（3.11.15 实跑）：

```text
  sub:replay:s1 | m1 done:True result:result-ok | replay:replay:s1 | replay:replay:s2 | t2:cancelled | t3 error:ValueError
  TASKS before sweep: ['t1', 't2', 't3'] -> swept: 3 -> after: []
```

要点：**订阅者 `sub` 在收到 s1 后"断线"（抛 ConnectionError）被取消，`m1` 照样跑完**——这就是"断线 ≠ 取消"；终态任务仍能完整回放（`replay:s1`/`s2`）。

### 与项目对照

`backend/app/services/task_service.py`（`ManagedTask`/`_spawn`/`_run_with_deadline`/`subscribe`/`cancel`/`sweep_finished`）：

| 点 | 参考实现 | 项目 |
| --- | --- | --- |
| 事件存储 | `managed.events` list + CAP 截断 | 同构：`events` 有容量上限（`emit` 内 `del` 截断） |
| 扇出 | `subscribers` list + `put_nowait` | 同构：无界队列不反压；`emit` 遍历 `subscribers` |
| 快照 | `list(managed.events)` | 同构：`snapshot = list(managed.events)`（02/09 篇） |
| 收口 | `terminal`/`done`/`DONE` 哨兵在 finally | 同构，且显式注明"之间不做任何 await：订阅者观察到的状态变化是原子的"（13 篇精读） |
| 订阅注销 | `finally: subscribers.remove(q)` + `ValueError` 兜底 | 同构：`except ValueError: pass` 处理"已被 `subscribers.clear()`" |
| 终态回放 | 只对 live=False 放快照 | 项目另有 `_terminal_frames`：`result` 存在时按原 `events()` 尾部形态补终态帧（与库行重放口径对齐） |
| 心跳 | `await q.get()` 死等 | `wait_for(queue.get(), 1.0)` 每秒一拍发 `status` 帧并兜底检查 terminal（15 篇精读） |
| 启动约束 | 无 | `_spawn`/`start_reserved_task`/`ensure_started`：先 admission 占槽+登记再起步，`start_task` 检查重复 task_id |
| 清理 | `sweep_finished` 全清 | `sweep_finished` 按 `finished_at + TTL` 惰性清理（留回放窗口） |
| 持久化 | 无 | pending 行 + `rag_task_inputs` 寄存输入（重启恢复的前提） |

## 第三步：超时与恢复

### 需求

在第二步基础上加：

- 执行总墙：`wait_for(run_task, timeout)`；超时 → 任务行保持 pending（等待恢复扫描）；
- 用内存 dict `PENDING` 模拟 pending 行 + `updated` 时间戳；
- `claim_recovery(task_id, grace)`：只有 pending 且超过宽限期的行可认领——认领即拨新 `updated`（单进程内存版"原子认领"）；
- 演示：总墙超时 → 行仍 pending → 宽限期后 `claim_recovery` 返回 True、第二次返回 False。

### 提示

- `wait_for` 超时即取消执行协程（14 篇）——pending 行不变正是"孤儿"；
- 宽限期 `grace` 防"刚启动的行被误判孤儿"；
- 认领=读-改-写必须原子：内存版靠"同一事件循环无 await"（13 篇）；项目的多副本靠数据库条件更新（对照段讲）。

### 参考实现（可运行）

```python
import asyncio, time

PENDING = {}

async def run_with_deadline(task_id, coro, timeout):
    m = start(task_id, coro)                 # 复用第二步的 start
    PENDING[task_id] = {"status": "pending", "updated": time.monotonic()}
    try:
        await asyncio.wait_for(m.run_task, timeout)
    except asyncio.TimeoutError:
        pass                                 # 行仍 pending → 孤儿
    except asyncio.CancelledError:
        PENDING[task_id]["status"] = "cancelled"
    finally:
        if PENDING[task_id]["status"] != "pending":
            PENDING.pop(task_id)
        m.terminal = True
        m.done.set()

def claim_recovery(task_id, grace=0.03):
    row = PENDING.get(task_id)
    if row is None or row["status"] != "pending" or time.monotonic() - row["updated"] < grace:
        return False
    row["updated"] = time.monotonic()        # 内存版"原子认领"：无 await，单进程成立
    return True

async def demo_recovery():
    await run_with_deadline("orphan1", lambda emit: worker(emit, hang=True), 0.02)
    await asyncio.sleep(0.04)                # 超过宽限期
    claimed = claim_recovery("orphan1")
    print("  orphan claimed:", claimed, "| second claim:", claim_recovery("orphan1"))

asyncio.run(demo_recovery())
```

输出（3.11.15 实跑）：

```text
  orphan claimed: True | second claim: False
```

### 与项目对照

`backend/app/services/task_service.py` + `rag_issue_service.py`：

- **`_run_with_deadline`**（14 篇已逐行精读）：`wait_for(service.run(emit), rag_task_max_seconds)` 总墙；`TimeoutError` → `mark_interrupted('timeout')` 落库判死——项目对超时的处理比参考实现多一步"主动判死"（评测/普通请求不等孤儿）；
- **`heartbeat_loop`**：属主每 `_HEARTBEAT_SECONDS = 10.0` 秒 `to_thread(heartbeat_owned)` 拨新自己 pending 行的 `updated_at`——心跳 << `_ORPHAN_GRACE_SECONDS = 30`，连续漏两拍才当孤儿；
- **`recover_orphans`/`_recover_row`**：`issues.orphaned_pending` 扫描超宽限期的 pending 行 → `claim_recovery` 认领 → 重驱动（`load_task_inputs` 恢复输入 → `ensure_managed`/`_spawn`）；输入缺失判 `interrupted`；
- **`claim_recovery` 的原子性**（`rag_issue_service.py`）：

  ```python
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
  ```

- **关键差异**：参考实现的"认领"是单进程内存读写，靠"无 await"天然原子；项目是**多副本共享数据库**——两个后端进程可能同时相中同一孤儿行，所以认领必须是 `UPDATE ... WHERE status='pending' AND updated_at < cutoff` 的单条原子 UPDATE：`rowcount=1` 才认领成功，后到者看到"刚被拨新"放弃——**跨进程原子性只能交给数据库**，这是内存版与项目版最本质的差距。

## 综合练习

1. **幂等启动**：给第二步的 `start` 加"同一 task_id 已存在则复用"的幂等语义——已登记就返回现有 `ManagedTask` 不重复启动。对应项目里 `ensure_started` 的"同一 service 已注册则复用"。
2. **订阅者心跳**：给 `subscribe` 的 `while` 循环加 `wait_for(queue.get(), 1.0)`——超时 yield `{"event": "status", "terminal": managed.terminal}` 心跳帧；`terminal` 为真时收口。
3. **Admission 指标**：给 `Admission` 加 `stats = {"acquired": 0, "rejected_full": 0, "rejected_timeout": 0, "queued": 0}` 计数，三个 429 出口各记一笔。
4. **恢复重驱动**：把第三步的 `claim_recovery` 接上 `start`——认领成功后按 `PENDING` 里存的输入重启执行协程，验证孤儿任务能跑完。
5. **终态 TTL**：把 `sweep_finished` 改成按 `finished_at` 惰性清理（保留 `TTL` 秒内的终态供回放），给 `ManagedTask` 加 `finished_at` 字段。

## 参考答案

<details><summary>参考答案 1</summary>

```python
def start(task_id, coro):
    existing = TASKS.get(task_id)
    if existing is not None:
        return existing                      # 幂等：复用已登记的句柄
    managed = ManagedTask(task_id, None)
    TASKS[task_id] = managed
    # ... emit/runner 同前 ...
    managed.run_task = asyncio.create_task(runner())
    return managed
```

注意"查-写"之间不能有 `await`——事件循环单线程下这段是原子的；若 `start` 改成 `async def` 并在中间 await，两个并发启动方可能都看不到对方、重复建协程——这正是项目 `start_task` 注释"后者到 _spawn 之间没有 await，同一事件循环内不存在双起步窗口"的含义。

</details>

<details><summary>参考答案 2</summary>

```python
        if live:
            while True:
                try:
                    e = await asyncio.wait_for(q.get(), 1.0)
                except asyncio.TimeoutError:
                    if managed.terminal:
                        break
                    yield ("live", {"event": "status", "terminal": managed.terminal})
                    continue
                if e is DONE:
                    break
                yield ("live", e)
```

`wait_for` 每秒一拍：有帧推帧，无帧发心跳（SSE 保活），terminal 兜底检查防哨兵丢失死循环——项目 `subscribe` 的同款结构（15 篇）。

</details>

<details><summary>参考答案 3</summary>

```python
class Admission:
    def __init__(self, max_active, max_waiting, queue_timeout):
        ...
        self.stats = {"acquired": 0, "rejected_full": 0,
                      "rejected_timeout": 0, "queued": 0}

    async def acquire(self):
        async with self.condition:
            if self.active >= self.max_active:
                if self.waiting >= self.max_waiting:
                    self.stats["rejected_full"] += 1
                    raise Busy("queue full")
                self.waiting += 1
                self.stats["queued"] += 1
                try:
                    await asyncio.wait_for(...)
                except asyncio.TimeoutError:
                    self.stats["rejected_timeout"] += 1
                    raise Busy("queue timeout") from None
                finally:
                    self.waiting -= 1
            self.active += 1
            self.stats["acquired"] += 1
```

计数写在哪：拒绝在 `raise` 前、成功占槽在最后——`stats` 的读写在条件锁内，与 `active`/`waiting` 同一份原子性。项目里对应物是 `core/metrics.py` 的 prometheus 计数（admission 拒单走 `RAG_OUTCOMES`/chat_trace 口径）。

</details>

<details><summary>参考答案 4</summary>

```python
async def recover_orphans(grace=0.03):
    recovered = 0
    for task_id, row in list(PENDING.items()):
        if row["status"] != "pending" or time.monotonic() - row["updated"] < grace:
            continue
        row["updated"] = time.monotonic()    # 认领
        start(task_id, row["coro_factory"])  # 用寄存的输入重启
        recovered += 1
    return recovered
```

要点：`PENDING` 里必须存 `coro_factory`（重启输入）——对应项目的 `rag_task_inputs` 寄存表；认领后**先拨新 `updated` 再启动**（防扫描重入）；`list(PENDING.items())` 快照防遍历时修改。项目的差异：认领走 `claim_recovery` 的条件 UPDATE（跨进程原子）、输入落 `load_task_inputs`（DB 表而非内存）、`_RECOVERING` 集合防同一进程内并发恢复同一行。

</details>

<details><summary>参考答案 5</summary>

```python
class ManagedTask:
    def __init__(self, task_id, run_task):
        ...
        self.finished_at = 0.0

# runner finally 里：
        managed.finished_at = time.monotonic()
        managed.terminal = True
        managed.done.set()
        ...

TTL = 60.0
def sweep_finished(ttl=TTL):
    cutoff = time.monotonic() - ttl
    dead = [tid for tid, m in TASKS.items() if m.terminal and m.finished_at <= cutoff]
    for tid in dead:
        del TASKS[tid]
    return len(dead)
```

终态条目留 TTL 是给"续传/回放"留窗口——断线客户端几秒后回来还能从注册表回放终态帧，不必走库行回放。项目 `sweep_finished` 同构：`managed.terminal and managed.finished_at <= cutoff`（`finished_at` 就在 `_run_with_deadline` finally 的原子段里置）。

</details>

## 延伸阅读

- 本系列 09/13/14/15 篇——本篇所有机制都是它们的组合应用；
- `backend/app/services/task_service.py` 模块 docstring 的设计锚点（断线≠取消、按 task_id 身份、harness 作业接续、admission 复用）与 `docs/decisions.md §8`；
- `asyncio` 官方文档 "Coroutines and Tasks"/"Synchronization Primitives"/"Queues" 三节的组合用法；
- `docs/interview/learning_path.md` 阶段 5–7 对应本系列 11/14/15 篇的项目路径。
