# 13 协程与事件循环原理

## 学习目标

- 知道 `async def` 调用只返回协程对象、函数体**不执行**；认识 "coroutine was never awaited" 警告；
- 理解 await 协议：`__await__` 返回迭代器；awaitable 有三类：协程、Task、Future；
- 从生成器过渡到协程（承接 09）：协程同样有 `send`/`throw`/`close`，能手工 `send` 驱动；
- 能手写一个迷你事件循环（就绪队列 + 定时器堆），并与真实 asyncio 逐项对照；
- 用 `inspect.getsource` 读真实 asyncio 的驱动链路（Future `yield self` → Task `__step` → loop `_run_once`）；
- 理解单线程并发模型：同一时刻只有一个协程在跑，只在 `await` 处切换——以及阻塞调用冻结整个循环的后果；
- 认读 3.11 `async def` 字节码。

**本主题在项目里出现在哪**

| 位置 | 用到什么 |
| --- | --- |
| `backend/app/services/task_service.py` 模块 docstring | "注册表 ``TASKS`` 只在事件循环线程读写（asyncio 单线程语义，无需锁）" |
| `backend/app/services/task_service.py` `_run_with_deadline` | finally 注释"terminal/done/哨兵之间不做任何 await：订阅者观察到的状态变化是原子的" |
| `backend/app/services/retrieval_service.py` `search` | 整段注释解释为什么同步召回+打分必须 `asyncio.to_thread` 移出事件循环 |
| `backend/app/` 全仓 | 243 处 `async def`（grep 实测）——全后端运行在单事件循环上 |

## 语法详解

### `async def` 调用不执行函数体

```python
async def noop():
    pass

coro = noop()          # 只造出协程对象，函数体一行没跑
```

`coro` 是 `coroutine` 对象——和生成器一样，是"挂起的执行现场"（09 篇）。如果这个对象被丢掉，解释器在垃圾回收时警告（`-X dev` 下实跑输出）：

```text
RuntimeWarning: coroutine 'noop' was never awaited
```

修这个警告只有两条路：`await coro` 或 `coro.close()`——**创建后必须有人来驱动它**。

### await 协议与三类 awaitable

`await x` 要求 `x` 有 `__await__` 方法（返回迭代器）。能 await 的东西就三类：

1. **协程**：`async def` 的调用结果；
2. **Task**：`asyncio.create_task(coro)` 的产物（Future 的子类）；
3. **Future**：事件循环上的"将来结果"占位符。

可以手写一个 awaitable：

```python
class Sleep:
    def __init__(self, delay):
        self.delay = delay
    def __await__(self):
        yield self.delay        # 把"我要睡到什么时候"交给驱动者
        return "slept"

async def worker():
    r = await Sleep(0.5)
    return f"done:{r}"
```

`__await__` 里的 `yield` 是协程与事件循环之间的**交接点**：yield 出的值被上交给驱动方（Task/事件循环），驱动方决定何时恢复。

### 手工驱动协程：从生成器到协程

协程对象与生成器有同一套接口——`send`/`throw`/`close`（09 篇）。不用事件循环也能手动驱动它到第一个挂起点：

```python
c = worker()
try:
    sent = c.send(None)          # 启动协程
    print("yielded:", sent)      # 0.5：Sleep.__await__ yield 出来的值
except StopIteration as e:
    print("finished:", e.value)
```

输出：`yielded: 0.5`

- `coro.send(None)` 启动协程，跑到 `await Sleep(0.5)` 内部的 `yield self.delay` 处挂起，yield 值 `0.5` 返回给 `send` 的调用方；
- 再 `send` 会继续执行；协程 `return` 时 `StopIteration.value` 携带返回值——和生成器完全相同；
- 协程与生成器的差别只在"谁 yield、yield 给谁"：生成器把**数据**yield 给消费者，协程把**挂起请求**（"等这个 Future"/"睡这么久"）yield 给事件循环；
- 历史：PEP 492（3.5）引入 `async/await` 之前，asyncio 的协程就是拿生成器 + `@asyncio.coroutine` + `yield from` 写的——`yield from` 的委托机制正是今天 `await` 的前身。

## 运行机制与 CPython 实现

### 迷你事件循环（可运行）

事件循环的核心就两件事：**谁在就绪队列里**、**下一个定时唤醒是什么时候**。一个 30 行可运行版本——协程通过 `yield` 一个时长来表达"我想睡到何时"：

```python
import heapq

READY = []          # heap: (wake_at, seq, coro)
_seq = 0

def schedule(coro, at):
    global _seq
    _seq += 1
    heapq.heappush(READY, (at, _seq, coro))

async def task(name, n, d):
    for i in range(n):
        await Sleep(d)          # __await__ yield 出 d
        print(f"  {name} step{i}")
    return name

schedule(task("A", 2, 3), 0)
schedule(task("B", 3, 2), 0)
t = 0.0
while READY:
    at, _, c = heapq.heappop(READY)
    t = max(t, at)              # 虚拟时钟：直接跳到下个唤醒点
    try:
        req = c.send(None)      # 驱动协程到下一个 yield
        schedule(c, t + req)    # yield 出的时长 → 重新排入定时堆
    except StopIteration as e:
        print(f"t={t}: {e.value} done")
```

输出（3.11.15 实跑，确定性）：

```text
  B step0
  A step0
  B step1
  A step1
t=6.0: A done
  B step2
t=6.0: B done
```

对照真实 asyncio：迷你循环的 `READY` 堆 ≈ `loop._scheduled`（定时唤醒）；`send(None)` ≈ `Task.__step`；协程 yield 的时长 ≈ `asyncio.sleep` 内部排进 `_scheduled` 的回调；"StopIteration 携带返回值" ≈ `Task` 完成置结果。真实循环多一层**就绪队列**（`_ready` deque：本轮要跑的回调快照）和 **I/O 多路复用**（`selector.select`：有就绪回调时 timeout=0 立即返回，否则睡到下个定时器或 I/O 事件）。

### 真实 asyncio 的驱动链路（inspect 核实）

以下均用 `inspect.getsource` 从本机 3.11.15 的 `Lib/asyncio/` 取出。先说明一个重要事实：**`asyncio.Task`/`asyncio.Future` 在运行时是 C 加速版**（`Modules/_asynciomodule.c`），`Lib/asyncio/tasks.py` 里的纯 Python 实现以 `_PyTask`/`_PyFuture` 的名字保留（本机实测 `asyncio.Task is asyncio.tasks._PyTask` 为 `False`）——两者接口一致，读 Python 版是为了看懂算法。

链路四步：

1. **`Future.__await__`（`asyncio/futures.py` `_PyFuture`）**：

   ```python
       def __await__(self):
           if not self.done():
               self._asyncio_future_blocking = True
               yield self  # This tells Task to wait for completion.
           if not self.done():
               raise RuntimeError("await wasn't used with future")
           return self.result()  # May raise too.
   ```

   `await fut` 未完成时 **yield Future 自身**——这就是"挂起请求"交给 Task 的那个值。

2. **`Task.__step`（`asyncio/tasks.py` `_PyTask`）**：事件循环每轮回调它一次，核心是 `coro.send(None)`/`coro.throw(exc)`（有省略）：

   ```python
       def __step(self, exc=None):
           ...
           coro = self._coro
           self._fut_waiter = None
           _enter_task(self._loop, self)
           # Call either coro.throw(exc) or coro.send(None).
           try:
               if exc is None:
                   # We use the `send` method directly, because coroutines
                   # don't have `__iter__` and `__next__` methods.
                   result = coro.send(None)
               else:
                   result = coro.throw(exc)
           except StopIteration as exc:
               ...
                   super().set_result(exc.value)
   ```

   协程 yield 出的对象回到 `__step`：是 Future → 存进 `self._fut_waiter` 并 `add_done_callback(self.__wakeup)`——**Future 完成 → `__wakeup` → 再次调度 `__step`**。这正是 09 篇"生成器被反复 send"的工业化版本：Task 就是被事件循环反复 `send` 的协程。

3. **`BaseEventLoop._run_once`（`asyncio/base_events.py`，节选）**：单轮顺序——先算 `select` 的 timeout（`_ready` 非空或 `_stopping` → `timeout=0`；否则取 `_scheduled` 堆顶的最近时刻）→ `selector.select(timeout)` → `_process_events` 把就绪 I/O 回调排进 `_ready` → 把到期的 `_scheduled` 定时器挪进 `_ready` → 按本轮快照（`ntodo`）逐个执行 `_ready` 回调。

4. **`asyncio.run`（`asyncio/runners.py`）**：3.11 起是 `Runner` 类——建循环、`run_until_complete`、收尾时 `shutdown_asyncgens`（异步生成器清理，15 篇相关）+ `shutdown_default_executor` + 关循环。

C 层对应物（镜像核实，CPython 3.11 分支 `Modules/_asynciomodule.c`）：`TaskObj`/`FutureObj` 结构体、`task_step`/`task_wakeup`/`task_call_step_soon`（与 `_PyTask.__step`/`__wakeup` 同职责）、`future_schedule_callbacks`、`register_task`。运行时用的就是这套 C 实现。

### 字节码：async def

`async def` 编译成与生成器同族的代码对象（3.11.15 实跑 `dis.dis`）：

```python
async def coro_demo(x):
    y = await asyncio.sleep(x)
    return y
```

```text
 31           0 RETURN_GENERATOR
              2 POP_TOP
              4 RESUME                   0

 32           6 LOAD_GLOBAL              1 (NULL + asyncio)
             18 LOAD_ATTR                1 (sleep)
             28 LOAD_FAST                0 (x)
             30 PRECALL                  1
             34 CALL                     1
             44 GET_AWAITABLE            0
             46 LOAD_CONST               0 (None)
        >>   48 SEND                     3 (to 56)
             50 YIELD_VALUE
             52 RESUME                   3
             54 JUMP_BACKWARD_NO_INTERRUPT     4 (to 48)
        >>   56 STORE_FAST               1 (y)

 33          58 LOAD_FAST                1 (y)
             60 RETURN_VALUE
```

- `RETURN_GENERATOR`：调用 `coro_demo(1)` 立即返回协程对象，函数体不跑——和生成器同一条指令；
- `GET_AWAITABLE`：拿到 `asyncio.sleep(x)` 的 `__await__` 迭代器；
- `SEND`/`YIELD_VALUE`/`JUMP_BACKWARD_NO_INTERRUPT` 循环：把 awaitable 的 yield 值逐层透传给驱动者——与 `yield from` 的字节码形态相同（09 篇）；
- `RESUME 3`：await 恢复点。

### 单线程并发：阻塞冻结整个循环

同一事件循环里**同一时刻只有一个协程在跑**——切换只发生在 `await`。推论：协程里写了阻塞调用，冻结的是**所有**协程（3.11.15 实跑）：

```python
async def slow_sync():
    time.sleep(0.2); return "sync"
async def slow_async():
    await asyncio.sleep(0.2); return "async"

async def main():
    t0 = time.monotonic()
    await asyncio.gather(slow_sync(), slow_sync())
    t_sync = time.monotonic() - t0
    t0 = time.monotonic()
    await asyncio.gather(slow_async(), slow_async())
    t_async = time.monotonic() - t0
    print("two blocking tasks overlapped:", t_sync < 0.3)
    print("two async sleeps overlapped:", t_async < 0.3)
```

输出：

```text
two blocking tasks overlapped: False
two async sleeps overlapped: True
```

两个 `time.sleep(0.2)` 串行（≈0.4s），两个 `asyncio.sleep(0.2)` 并行（≈0.2s）——阻塞调用之间没有切换点。所以项目里所有同步 IO/CPU 工作都要经 `asyncio.to_thread` 移出事件循环（14 篇展开）。

## 常见陷阱

1. **`async def` 忘了 await**：`coro = f()` 不执行任何东西；看到 "coroutine was never awaited" 先找谁没 await。
2. **在协程里写 `time.sleep`/同步 requests**：冻结整个事件循环——全仓同步工作一律 `to_thread`。
3. **以为 `await` 是并行**：`await` 是**让出**不是并行——顺序 `await a(); await b()` 是串行的（并行要 `create_task`/`gather`，见 14）。
4. **跨线程碰 asyncio 对象**：`Task`/`Future`/队列都不是线程安全的——`loop.call_soon_threadsafe` 是唯一的跨线程入口（本项目维护循环不走跨线程路径）。
5. **在 `__init__`/同步代码里 await**：构造器不能 async——项目里都是"先同步构造、`prepare`/`ensure_started` 里再 await"。
6. **yield 出来的值不是给调用方的**：`await` 表达式拿到的是 awaitable 的**完成结果**，yield 的中间值只给驱动者看——别把 `send` 看到的 `0.5` 当成 `await` 的返回值（返回值走 `StopIteration`/Future result）。

## 项目代码精读

> 本节代码块均按仓库原文摘录（保留原缩进与注释），依赖项目内部上下文，**不可单独运行**。

### `task_service` 模块 docstring：单线程语义即并发模型

`backend/app/services/task_service.py`：

```python
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
```

最后一句是本节的落点：`TASKS` 是普通 dict，没有锁——因为**所有读写都在同一事件循环线程**、且读写点之间没有 await 让出。一旦某天有 `to_thread` 的回调路径要碰 `TASKS`，这个"无需锁"前提就要重审。

### `_run_with_deadline` 的 finally：两次 await 之间即原子

`backend/app/services/task_service.py`：

```python
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

- 这一串赋值+队列写入之间**没有任何 `await`**——别的协程不可能插进来观察到"terminal 置了但 done 还没 set"的中间态；
- `put_nowait` 是同步调用：帧入队立即返回，不会让出——这是"无界队列不反压"设计能成立的前提；
- 订阅者要么看到"未终态"，要么看到"终态+全部终态帧已入队"——**两次 await 之间的代码就是临界区**，这是 asyncio 单线程模型送你的原子性（15 篇讲什么时候还需要锁）。

### `retrieval_service.search`：为什么必须 to_thread

`backend/app/services/retrieval_service.py`：

```python
        # The candidate fetch + rerank + MMR is synchronous, blocking work: it
        # issues several DB queries and runs CPU-bound scoring. Running it in
        # the event loop would serialize all concurrent requests. Offload the
        # whole block to a worker thread so other requests' LLM/DB work can
        # overlap. The per-stage timed_stage markers inside still record.
        candidate_limit = max(limit * 28, 160)
        selected = await asyncio.to_thread(
            self._retrieve_sync,
            query,
            rewritten,
            query_embedding,
            limit,
            candidate_limit,
            trace,
        )
        return selected
```

注释把 13 篇的机制讲全了：同步 DB 查询 + CPU 打分若在事件循环里跑会**把所有并发请求串行化**；移进 worker 线程后，本协程 await 挂起、其他请求的 LLM/DB 工作继续走——`to_thread` 的细节（线程池、取消语义）见 14 篇。

### 规模感：`async def` 243 处

`grep -rn "async def" backend/app/` 实测 243 处——整个后端的请求处理、检索管线、agent 图、任务执行、SSE 流全部跑在**同一个事件循环**上：每行 `await` 都是潜在切换点，每段无 await 的代码都是隐式临界区。

## 练习

1. **读项目代码回答**：`task_service` 的 docstring 说 `TASKS` "只在事件循环线程读写……无需锁"。如果哪天需要从一个 `to_thread` 的回调里删 `TASKS` 条目，会引入什么风险？为什么现在不需要？
2. **动手写代码**：写一个 awaitable `Nop`，`__await__` 里 `yield` 一个标记字符串 `"pause"`；再写协程 `demo()` await 它两次后 `return "ok"`；手工 `send` 驱动它到结束，打印每个 yield 值与 `StopIteration.value`。
3. **动手写代码**：扩展本文的迷你事件循环：支持"立即就绪"队列（`call_soon` 等价物）——一个 `READY_NOW` deque 先于定时堆处理；让任务 A `await Soon()` 在下个 tick 恢复而不是睡时长。验证 A 的 step 在 B 的定时 step 之前全部跑完。
4. **读项目代码回答**：`_run_with_deadline` 的 finally 里 `admission.release()` 在 `terminal/done/哨兵` 段**之前**。为什么顺序不能反过来（先置 terminal 再 release）？提示：release 里有 `await`。
5. **读项目代码回答**：`retrieval_service.search` 注释说同步工作放进事件循环会"serialize all concurrent requests"。用本篇的模型解释这句话：为什么 `time.sleep` 会串行化而 `asyncio.sleep` 不会？

## 参考答案

<details><summary>参考答案 1</summary>

现在的安全前提是：**`TASKS` 的全部读写都发生在事件循环线程上**，且任何一次"读-改-写"之间没有 `await`——asyncio 单线程模型保证这段代码执行期间没有其他协程插入。若从 `to_thread` 的回调里碰 `TASKS`：线程池里的代码与事件循环**真并发**运行，`TASKS.pop()`/`TASKS[id] = m` 可能与事件循环线程上的遍历、写入交错——轻则读到撕裂状态（pop 到一半），重则 dict 内部结构损坏。正确做法是仍把变更交给事件循环线程执行（`loop.call_soon_threadsafe`），而不是给 dict 加锁（docstring 的"无需锁"是架构决策，不是侥幸）。

</details>

<details><summary>参考答案 2</summary>

```python
class Nop:
    def __await__(self):
        yield "pause"

async def demo():
    await Nop()
    await Nop()
    return "ok"

c = demo()
for _ in range(3):
    try:
        print("yielded:", c.send(None))
    except StopIteration as e:
        print("finished:", e.value)
        break
```

实跑输出：`yielded: pause`（两次）→ `finished: ok`。`send` 拿到的是 `__await__` yield 出的标记；`return "ok"` 进入 `StopIteration.value`——与生成器的 `send`/`StopIteration` 完全同构。

</details>

<details><summary>参考答案 3</summary>

```python
import heapq
from collections import deque

READY_NOW = deque()
READY_LATER = []      # heap (wake_at, seq, coro)
_seq = 0
def schedule(c, at):
    global _seq
    _seq += 1
    heapq.heappush(READY_LATER, (at, _seq, c))

class Soon:
    def __await__(self):
        yield "now"

class Sleep:
    def __init__(self, d): self.d = d
    def __await__(self):
        yield self.d

async def task(name, waits):
    for i, w in enumerate(waits):
        await w
        print(f"  {name} step{i}")
    return name

schedule(task("A", [Soon(), Soon()]), 0)
schedule(task("B", [Sleep(1)]), 0)
t = 0.0
while READY_NOW or READY_LATER:
    if READY_NOW:
        c = READY_NOW.popleft()
    else:
        at, _, c = heapq.heappop(READY_LATER)
        t = max(t, at)
    try:
        req = c.send(None)
    except StopIteration as e:
        print(f"t={t}: {e.value} done")
        continue
    if req == "now":
        READY_NOW.append(c)
    else:
        schedule(c, t + req)
```

实跑输出：`A step0` / `A step1` / `t=0.0: A done` / `B step0` / `t=1: B done`——`Soon` 的任务在下个 tick 立即恢复，排在所有定时任务之前。真实 `loop.call_soon` 的回调就是进 `_ready` deque：`_run_once` 单轮先按 `_ready`/`_scheduled` 算 `select` 的 timeout（有就绪回调时 timeout=0），`select` 返回后把到期定时器挪进 `_ready`，最后按快照执行本轮回调——迷你版的 `READY_NOW` 对应 `_ready`，`READY_LATER` 对应 `_scheduled`。

</details>

<details><summary>参考答案 4</summary>

`admission.release()` 内部是 `async with self.condition:` + `notify()`——含 `await`（获取条件锁与 wait_for 的 future 等待）。如果把"置 terminal/done/哨兵"放在 release **之前**：release 的 `await` 让出时，订阅者已经看到 terminal=True 且收到 `_TASK_DONE`，开始按"终态"回放——但此时 admission 槽还没还，`active` 计数仍占着；若 release 这一步再失败/排队，外界观察到的是"任务终态但容量没释放"的中间态，高峰期可能看到短暂的假满员。项目顺序（先 release 落计数，再无 await 地置终态帧）保证：订阅者看到 terminal 时，容量账本也已一致——**有 await 的副作用先做完，无 await 的对外可观察状态切换最后做**。

</details>

<details><summary>参考答案 5</summary>

事件循环同一时刻只跑一个协程，切换只发生在 `await`。`time.sleep(0.2)` 是同步阻塞调用——没有 `await`，事件循环就被钉在这个协程里 0.2s，期间**所有**其他协程（其他请求的 LLM 调用、SSE 推送、任务执行）都不能前进，N 个并发请求被强行串行成一条线；`asyncio.sleep(0.2)` 在 `await` 处挂起，把"0.2 秒后唤醒"的请求交给事件循环（yield 挂起点），循环立刻去跑别的协程，0.2 秒后再回来——时间片在 N 个请求间轮转，宏观上并行。`_retrieve_sync` 的 DB 查询和打分与 `time.sleep` 同性质，所以要 `to_thread` 移走。

</details>

## 延伸阅读

- Python 官方文档：`asyncio` "Coroutines and Tasks"（含 "Awaitables" 与 "coroutine was never awaited" 说明）、"Event Loop"（`call_soon`/`call_later`/I/O 回调概念）、"Task Groups"、"Synchronization Primitives"。`_run_once` 是私有实现，官方文档不覆盖，见 `Lib/asyncio/base_events.py` 源码。
- PEP 492（`async`/`await` 与 `__await__` 协议）、PEP 3156（asyncio 原始设计）、PEP 380（`yield from`，协程前身）。
- `asyncio` 开发者文档（源码导读）：`asyncio.tasks.Task`、`asyncio.futures.Future`、`asyncio.base_events` 章节对应 Lib 文件。
