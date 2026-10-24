# 15 同步原语与异步迭代

## 学习目标

- 理解单线程为什么还需要锁：`await` 点即切换点，read-modify-write 中间夹 `await` 会丢更新；
- 会用 `asyncio.Lock`/`Semaphore`/`Event`/`Condition`/`Queue`；
- 分清 `threading.Lock` 与 `asyncio.Lock`：await 期间绝不持 threading 锁；
- 掌握 `async with`（`__aenter__`/`__aexit__`、`asynccontextmanager`）与 `async for`（`__aiter__`/`__anext__`/`StopAsyncIteration`、异步生成器）；
- 知道异步生成器提前停止时 `finally` 的四条执行路径（取消注入/aclose/引用消亡+finalizer/循环收尾）——与 SSE 断线直接相关。

**本主题在项目里出现在哪**

| 位置 | 用到什么 |
| --- | --- |
| `backend/app/services/rag_product_service.py` `Admission` | `asyncio.Condition` + `wait_for` 排队超时 → 429；`try_acquire` 不排队 |
| `backend/app/core/upstream_gate.py` | `@asynccontextmanager` + 每事件循环 `asyncio.Semaphore` 上游并发限额 |
| `backend/app/services/task_service.py` `ManagedTask`/`subscribe` | `done`（`asyncio.Event`）、`subscribers`（无界 `asyncio.Queue` + `_TASK_DONE` 哨兵）、`wait_for(queue.get(), 1.0)` 心跳 |
| `backend/app/api/chat.py` `product_events` | 异步生成器产 SSE 帧（03/09 篇已见） |
| `backend/app/services/rag_product_service.py` `_timed_generation`/`_candidate` | 异步生成器包装 `graph.astream`，`finally` 记耗时 |
| `backend/app/agent/mcp_client.py` `_call_once` | 嵌套 `async with`（transport + session） |
| `backend/app/core/async_cache.py` | `asyncio.Lock` 保护 `_values`/`_inflight` |
| `backend/app/db.py`、`storage/postgres.py`、`agent/mcp_client.py` | `threading.Lock` 只在同步代码段使用（正确用法对照） |

## 语法详解

### 单线程为什么还需要锁

asyncio 同一时刻只跑一个协程——但 `await` 就是切换点。**read-modify-write 中间夹一个 `await`，就是丢失更新**（3.11.15 实跑）：

```python
counter = 0
async def bump_no_lock():
    global counter
    for _ in range(50):
        v = counter
        await asyncio.sleep(0)      # 切换点：另一个协程可能插入
        counter = v + 1

async def main():
    await asyncio.gather(*[bump_no_lock() for _ in range(4)])
```

4 个协程各加 50 次，期望 200——输出：`counter: 50`（丢 150 次）。每个协程读到 `v` 后挂起，醒来时别的协程已把 `counter` 改到别处，`v+1` 把旧值写回。

用 `asyncio.Lock` 把临界区变成"持锁期间不允许别的协程进"：

```python
async def bump_locked(lock):
    global counter
    for _ in range(50):
        async with lock:
            v = counter
            await asyncio.sleep(0)    # 持锁也会让出，但别人进不来
            counter = v + 1
```

输出：`counter: 200`。锁的语义不是"不切换"（持锁期间本协程照样会 await 挂起），而是**别的协程拿不到锁**。

### 五个原语速览

- **`asyncio.Lock`**：`async with lock:` 互斥临界区；
- **`Semaphore(n)`**：上限 n 的通行证——`async with sem:` 限并发（`upstream_gate`）；
- **`Event`**：`set()` 置位/`wait()` 等置位——一次性"好了"信号（`ManagedTask.done`）；
- **`Condition`**：`async with cond:` 内 `await cond.wait_for(predicate)` 等条件、`notify_all()` 唤醒——"状态变化要排队等"（`Admission`）；
- **`Queue`**：`put`/`put_nowait`/`get`——生产者-消费者通道；项目用**无界**队列 + `put_nowait`（订阅者不反压执行）+ **哨兵对象**（`_TASK_DONE`，用 `is` 判等）。

### `threading.Lock` vs `asyncio.Lock`

- `threading.Lock.acquire()` 是**阻塞线程**——在 `await` 期间持它，事件循环线程被冻结在锁里，所有协程停摆；
- `asyncio.Lock.acquire()` 是 **await 挂起**——排队等锁时事件循环照跑别的协程；
- 规则：**`await` 期间绝不持 threading.Lock**。本项目 `db.py _provider_lock`、`mcp_client._clients_lock`、`postgres.py` 的锁都只在**同步代码段**（`get_database_provider`/`get_harness_mcp_client`/`_get_pool`）里用——持锁区间无 `await`，是正确用法。

### `async with` 与 `asynccontextmanager`

`async with` 是上下文管理器的异步版（08 篇的同步版对照）：`__aenter__`/`__aexit__` 可以是 `async def`——**进入/退出本身允许挂起**（取锁、建连接、握手都是 IO）。`contextlib.asynccontextmanager` 与 `contextmanager` 同构，装饰**异步生成器函数**：

```python
from contextlib import asynccontextmanager

@asynccontextmanager
async def slot(sem):
    await sem.acquire()
    try:
        yield
    finally:
        sem.release()
```

`async with slot(sem):` 进入时 `await sem.acquire()`——这就是 `upstream_slot` 的形态。

### `async for` 与异步生成器

`async for x in it` 要求 `it.__aiter__()` 返回异步迭代器，逐项 `await it.__anext__()`，耗尽抛 `StopAsyncIteration`。`async def` + `yield` 就是异步生成器——`yield` 挂起（产出值），`await` 也合法（09 篇对照）。

**提前停止消费时，`finally` 什么时候跑**——逐个实跑（3.11.15）：

```python
import gc

async def gen_with_await():
    try:
        for i in range(100):
            await asyncio.sleep(0.5)      # 生成器内部的挂起点（如 subscribe 的 queue.get）
            yield i
    finally:
        print("  finally ran")

async def consumer():
    async for x in gen_with_await():
        pass

async def own_and_break():
    g = gen_with_await()
    async for x in g:
        if x == 2:
            break                          # 只 break：什么都不发生
    print("  broke, g still held")

async def main():
    t = asyncio.create_task(consumer())
    await asyncio.sleep(0.01)
    t.cancel()
    try:
        await t
    except asyncio.CancelledError:
        print("  consumer cancelled")
    await own_and_break()
    gc.collect()
    await asyncio.sleep(0)
    print("  after gc+tick")
```

输出：

```text
  finally ran              ← 取消立即注入生成器内部的 await 点，finally 同步执行
  consumer cancelled       ← 它甚至先于 await t 返回
  broke, g still held
  after gc+tick
  finally ran              ← 引用消亡 → finalizer 调度 aclose，随后的拍执行
```

`finally` 有四条执行路径：

1. **消费方协程被取消**：若生成器此刻正挂在自己内部的 `await`（如 `subscribe` 的 `await queue.get()`），`CancelledError` 从**生成器帧内部**抛出——`finally` 随协程帧回退**同步执行**（输出里先于 `consumer cancelled`）。这是 SSE 场景的主路径：`chat.py` 的 `product_events` 用 `async for` 驱动 `subscribe`——**若**处理协程被取消（例如框架在客户端断开或超时取消处理协程、服务关闭），`CancelledError` 穿过 `subscribe` 的挂起点，`finally` 里的 `subscribers.remove(queue)` 立即收口；
2. **显式 `await g.aclose()`**：立即执行（前面 tick/aclose 实验）；
3. **引用消亡**（持有者 frame 结束/del+gc）：asyncio 的 asyncgen finalizer 钩子把 `aclose` 排成一个任务——随后的拍执行（上面 `after gc+tick` 之后才打出来，不是同步）；
4. **循环关闭**：`asyncio.run` 收尾调 `shutdown_asyncgens` 兜底。

**`break` 本身不触发任何一条**——只 break 又一直拿着引用，生成器就一直停在那里；能救它的是引用消亡/显式 aclose/循环收尾。

## 运行机制与 CPython 实现

### Lib 源码：`Condition.wait` / `wait_for` / `Lock.acquire`

`inspect.getsource`（3.11.15，`Lib/asyncio/locks.py`）：

`Lock.acquire`（节选）：锁空闲立即 `_locked = True` 返回；否则造一个 future 进 `self._waiters` deque 再 `await fut`——**等锁 = 排队一个 future 等被唤醒**：

```python
        if self._waiters is None:
            self._waiters = collections.deque()
        fut = self._get_loop().create_future()
        self._waiters.append(fut)

        try:
            try:
                await fut
            finally:
                self._waiters.remove(fut)
```

`Condition.wait_for`：谓词循环——每次 `wait()` 醒来重新判 `predicate()`，成立才返回：

```python
    async def wait_for(self, predicate):
        result = predicate()
        while not result:
            await self.wait()
            result = predicate()
        return result
```

`Condition.wait` 内部是"放锁 → `await` 一个 future → 拿锁"的三步，`notify`/`notify_all` 把 waiters 里的 future 置结果——`Admission` 的 `wait_for(lambda: self.active < settings...)` 正是这套谓词等待。

### 等价改写：`async with` ≈ `async` 版 PEP 343

`async with cm as x:` 等价于 `type(cm).__aenter__`/`__aexit__` 的 `await` 调用（08 篇的同步版展开把 `exit_`/`enter` 换成 `await` 即可，不再重复）；`async for` 等价于手写 `it = aiter(obj); while True: try: x = await anext(it) except StopAsyncIteration: break`。

### CPython 源码

异步迭代协议方法在 `PyAsyncMethods` 槽位上（`am_aiter`/`am_anext`）：CPython 3.11 分支 `Objects/abstract.c` 的 `PyObject_GetAIter`（经 `tp_as_async->am_aiter` 取迭代器）与 `PyObject_GetIter`；`Objects/genobject.c` 的 `PyAsyncGen_Type`（异步生成器类型，`async_gen_asend`/`async_gen_athrow`/`async_gen_aclose` 挂在它的方法表上）——已按镜像核实函数名存在。

## 常见陷阱

1. **持 `threading.Lock` 过 `await`**：锁里是 `await` → 整个事件循环冻在锁上等自己——死锁形态不是报错是"全部卡住"。
2. **以为 asyncio 原语是线程间通信**：`asyncio.Queue`/`Event` 只对**同一事件循环**的协程有意义；跨线程用 `queue.Queue`/`loop.call_soon_threadsafe`。
3. **`Condition` 忘拿锁**：`wait`/`notify` 必须在 `async with cond:` 内——否则 `RuntimeError`。
4. **`async for` break 后以为生成器清理了**：`break` 不触发 `finally`（见实验）——要靠显式 `aclose`、取消注入、引用消亡后的 finalizer 或循环收尾；资源型生成器（SSE 订阅）应保证清理路径可达。
5. **`put_nowait` 打满有界队列**：无界队列才安全（`subscribers` 用无界正是"订阅者不得反压执行"）；有界队列 `put_nowait` 满时 `QueueFull`。
6. **`Event.wait()` 忘了 set 时机**：`done` 是一次性信号——重复 set 无害，但 set 之前就 wait 的协程只能等到 set 或超时。
7. `asyncio` 原语**没有** `__enter__` 同步协议：`with lock:` 对 `asyncio.Lock` 是 `TypeError`——必须 `async with`。

## 项目代码精读

> 本节代码块均按仓库原文摘录（保留原缩进与注释），依赖项目内部上下文，**不可单独运行**。

### `Admission`：Condition + 排队超时 + try_acquire

`backend/app/services/rag_product_service.py`：

```python
class Admission:
    """单 worker 有界队列；所有计数在同一事件循环中更新。"""
    def __init__(self):
        self.active = 0
        self.waiting = 0
        self.condition = asyncio.Condition()

    async def acquire(self):
        settings = get_settings()
        async with self.condition:
            if self.active >= settings.rag_max_active_requests:
                if self.waiting >= settings.rag_max_waiting_requests:
                    raise HTTPException(429, "服务繁忙，请稍后重试", headers={"Retry-After": "10"})
                self.waiting += 1
                try:
                    await asyncio.wait_for(self.condition.wait_for(lambda: self.active < settings.rag_max_active_requests),
                                           settings.rag_queue_timeout_sec)
                except asyncio.TimeoutError:
                    raise HTTPException(429, "等待超时，请稍后重试", headers={"Retry-After": "10"}) from None
                finally:
                    self.waiting -= 1
            self.active += 1

    async def release(self):
        async with self.condition:
            self.active -= 1
            self.condition.notify_all()
```

- `async with self.condition:`：整个判定+等待在条件锁内——`active`/`waiting` 的检查与修改是原子的（docstring："所有计数在同一事件循环中更新"）；
- 三道闸：`active >= max` → `waiting >= max` 直接 429（不排队）→ `wait_for(condition.wait_for(...), queue_timeout)` 排队等空位，超时 429 `from None`；
- `finally: self.waiting -= 1`：排队**结果**无论成败都归还等待名额（拿到槽/超时/取消三种出口统一收口）；
- `release` 的 `notify_all`：空位释放叫醒所有排队者，谓词重判（`wait_for` 的谓词循环决定谁能进）；
- `try_acquire`（`task_service` 恢复扫描用）：只看 `active` 不排队——"满员留待下拍"不挤占实时受理队列。

### `upstream_slot`：asynccontextmanager + 每循环 Semaphore

`backend/app/core/upstream_gate.py`：

```python
_gates = weakref.WeakKeyDictionary()


@asynccontextmanager
async def upstream_slot():
    from ..config import get_settings
    settings = get_settings()
    loop = asyncio.get_running_loop()
    gate = _gates.get(loop)
    if gate is None:
        gate = asyncio.Semaphore(settings.rag_upstream_concurrency)
        _gates[loop] = gate
    async with gate:
        yield
```

- `@asynccontextmanager`：`async with upstream_slot():` 包一段调用——进入时 `async with gate:` 拿信号量槽位（`graph.py` 的 `_bounded_invoke` 就是它）；
- `_gates` 是 `WeakKeyDictionary`：**每个事件循环一个 Semaphore**——测试里每个 `asyncio.run` 一个新 loop 自动拿到独立限额；
- `async with gate: yield`：槽位获取本身可挂起（排队等上游额度），释放由 asynccontextmanager 的 `finally` 保证。

### `ManagedTask.done` 与 `subscribers` + `subscribe` 的收口

`backend/app/services/task_service.py` `subscribe` 尾部：

```python
                yield event
        async for frame in _terminal_frames(managed):
            yield frame
    finally:
        try:
            managed.subscribers.remove(queue)
        except ValueError:
            pass
```

- `done`（`asyncio.Event`）：`managed.done.set()` 在 `_run_with_deadline` finally 置位——`wait_managed_result` 的 `wait_for(done.wait(), timeout)` 与它是"一次性完成信号"的标准用法；
- `subscribers` 是 `List[asyncio.Queue]`（无界）：`emit` 的 `put_nowait` 永不阻塞执行体；`_TASK_DONE` 哨兵对象 `queue.put_nowait(_TASK_DONE)`，`is` 判等收口；
- `wait_for(queue.get(), 1.0)`：每秒一拍，超时发 `{"event": "status"}` 心跳帧或检查 terminal 兜底——订阅循环不空转也不死等；
- `finally: managed.subscribers.remove(queue)`：订阅协程结束（正常/断线/取消）时把队列摘出——不然 `emit` 会继续往没人读的队列塞帧；`ValueError` 兜底"已被 clear"的竞态。

### `_timed_generation` 与 `_candidate` 的 `async for`

`backend/app/services/rag_product_service.py`：

```python
async def _timed_generation(updates, trace):
    """把图的流式消费包成一个可计时的异步生成器：计一段 ``answer_generation_ms``。

    用生成器的 ``finally`` 而不是在循环后补一行：生成抛错、请求超时、客户端断流
    （取消）时也要留下这一段耗时——「失败的那一次花了多久」正是要看的数。
    包装流本身而不是重排 ``_candidate`` 的缩进，改动面最小。
    """
    started = time.monotonic()
    try:
        async for update in updates:
            yield update
    finally:
        trace.add_stage("answer_generation_ms", (time.monotonic() - started) * 1000)
```

- 包装器模式：`async for` 消费上游 `graph.astream`，逐帧 `yield` 透传，`finally` 记整段耗时——docstring 说明为什么用 finally：错误/超时/断流都要留账；
- `_candidate` 里 `async for update in _timed_generation(graph.astream(...), self.trace)`：消费方逐帧遍历节点 update 取 `planner_calls`/`planner_ms`/`finalize_reason`/`messages` 的用量——异步迭代把"图的内部进度"变成可流式观察的帧。

### `mcp_client._call_once`：嵌套 async with

`backend/app/agent/mcp_client.py`：

```python
            async with streamablehttp_client(self.url) as (read_stream, write_stream, _):
                async with ClientSession(read_stream, write_stream) as session:
                    await session.initialize()
                    submitted = True
                    result = await session.call_tool(tool_name, arguments)
```

- 两层 `async with`：transport（`streamablehttp_client`）→ session（`ClientSession`）——退出反序：先关 session 再断 transport（08 篇栈式释放）；
- `__aenter__`/`__aexit__` 都是 IO：建连/握手/关闭都需要挂起，同步 `with` 表达不了；
- `submitted = True` 写在 `initialize` 之后、`call_tool` 之前：区分"没提交成功的失败"（可重试）与"已提交的失败"（绝不重试）——08 篇"资源归还"语义之外的业务层信号位。

### `async_cache` 的 `asyncio.Lock`

`backend/app/core/async_cache.py` `get_or_create`：

```python
        async with self._lock:
            cached = self._values.get(key)
            if cached and cached[0] > now:
                self._values.move_to_end(key)
                return cached[1]
            if cached:
                self._values.pop(key, None)

            task = self._inflight.get(key)
            if task is None:
                # Keep cache bookkeeping inside the shared task.  A caller is
                # allowed to cancel its own request, but that must neither
                # cancel work other callers are waiting on nor leave a
                # cancelled task stuck in ``_inflight`` forever.
                async def populate() -> V:
```

- `async with self._lock:` 包住"查缓存 → 查在飞任务 → 登记"三步——中间没有 await 时锁是多余的，但 `populate` 创建与 `_inflight` 登记之间不能被另一并发请求插进来（否则两个并发请求各建一个任务，singleflight 失效）；
- 注意临界区里没有 await——锁防的是"协程间交错"，不是真并行（13/15 篇同一个模型的两面）。

## 练习

1. **读项目代码回答**：`Admission.acquire` 里 `waiting -= 1` 为什么放在 `finally` 而不是 `wait_for` 成功之后？漏掉会怎样？
2. **读项目代码回答**：`subscribe` 的 `finally` 里 `managed.subscribers.remove(queue)` 如果删掉，断线的订阅者会造成什么后果？（提示：`emit` 对每个 `queue` 调 `put_nowait`）
3. **动手写代码**：写一个 `AsyncBarrier(n)`：`async def wait(self)` 用 `asyncio.Condition` 实现"集齐 n 个协程一起放行"。验证 3 个协程先后到达、第 3 个到达后全部打印 continue。
4. **动手写代码**：写一个异步生成器 `tick(n, interval)`：每秒 yield 一次计数，用 `asyncio.sleep`；消费者 `async for` 收到第 3 个就 `aclose` 并打印生成器 finally 是否执行（对比不加 aclose 时循环收尾才执行）。
5. **读项目代码回答**：`upstream_slot` 用 `WeakKeyDictionary` 按 loop 存 Semaphore。为什么"每事件循环一份"比模块级一个 Semaphore 更合适？（提示：测试里每个 `asyncio.run` 是一个新 loop。）

## 参考答案

<details><summary>参考答案 1</summary>

`wait_for` 的排队路径有三条出口：拿到槽（`active` 变空）、超时（`asyncio.TimeoutError`）、被取消（外层取消协程）。`waiting -= 1` 必须在**所有**出口都执行——放成功分支就漏掉超时/取消两条，等待名额永远不归还，`waiting` 只增不减：几轮 429/断线后 `waiting` 顶住上限，后续所有请求连排队的资格都没有，永远"服务繁忙"。`finally` 是"名额记账"的唯一正确位置（05 篇"清理必须覆盖取消语义"同款）。

</details>

<details><summary>参考答案 2</summary>

订阅者队列留在 `managed.subscribers` 里后，`emit` 每次仍对它 `put_nowait`——无界队列意味着内存只涨不回收：断线的客户端越积越多，每个 `emit` 帧都被复制进一个永远不会被消费的队列，长跑任务 + 高频帧会把内存吃光（事件帧虽小，积压无界）。`remove(queue)` 是订阅生命周期管理的一环：生成器退出路径（正常/异常/取消/aclose）都经 `finally` 收口；`ValueError` 兜底的是 `_run_with_deadline` 收口时 `subscribers.clear()` 已清的情况。

</details>

<details><summary>参考答案 3</summary>

```python
import asyncio

class AsyncBarrier:
    def __init__(self, n):
        self.n = n
        self.count = 0
        self.cond = asyncio.Condition()
    async def wait(self):
        async with self.cond:
            self.count += 1
            if self.count >= self.n:
                self.cond.notify_all()
            else:
                await self.cond.wait_for(lambda: self.count >= self.n)

async def main():
    bar = AsyncBarrier(3)
    async def worker(name):
        await asyncio.sleep({"A": 0.03, "B": 0.02, "C": 0.01}[name])
        print(name, "arrived")
        await bar.wait()
        print(name, "continue")
    await asyncio.gather(worker("A"), worker("B"), worker("C"))

asyncio.run(main())
```

实跑输出：`C arrived` / `B arrived` / `A arrived` / `A continue` / `C continue` / `B continue`——先到的 C、B 在 `wait_for` 谓词里排队，A 第三个到达 `notify_all` 后自己直接放行，C、B 按 waiter 队列序被唤醒。`asyncio.Condition` 没有 `Barrier` 的现成实现——`wait_for(predicate)` 就是"等到条件成立"的通用砖。

</details>

<details><summary>参考答案 4</summary>

```python
import asyncio

async def tick(n, interval):
    try:
        for i in range(n):
            await asyncio.sleep(interval)
            yield i
    finally:
        print("tick finally")

async def main():
    g = tick(10, 0.01)
    count = 0
    async for x in g:
        count = x
        if x == 2:
            break
    print("after break, before aclose")
    await g.aclose()
    print("after aclose")

asyncio.run(main())
```

实跑输出：`after break, before aclose` / `tick finally` / `after aclose`——`finally` 只在 `aclose`（或取消注入/引用消亡的 finalizer/循环收尾）时跑。SSE 语义：`subscribe` 的 `finally` 段（`subscribers.remove`）的可达性来自消费方协程的生命周期——**若**框架取消处理协程，`CancelledError` 在 `subscribe` 内部的 `await queue.get()` 挂起点抛出、`finally` 同步收口；即使没有显式 aclose，引用消亡后的 finalizer 与循环收尾也是兜底——task_service 靠这条 finally 路径摘掉孤儿队列。

</details>

<details><summary>参考答案 5</summary>

`asyncio.Semaphore` 绑事件循环（内部 future/waiter 都属当前 loop）——模块级一个 Semaphore 在测试里会被第一个 `asyncio.run` 创建的 loop 绑定，第二个 `asyncio.run`（新 loop）再用它，waiter future 跨 loop 直接错。`_gates` 用 `WeakKeyDictionary` 按 `loop` 存：每个事件循环拿到自己的 Semaphore，测试每跑一次 `asyncio.run` 都干净独立；loop 关闭后条目随 WeakKey 回收，不泄漏。生产只有一条 loop，等价于"每进程一份"——两种环境同一套代码都成立。

</details>

## 延伸阅读

- Python 官方文档：`asyncio` "Synchronization Primitives"（Lock/Event/Condition/Semaphore/Queue/Barrier）、"Queues"（Queue/PriorityQueue/LifoQueue 与 shutdown）。
- 语言参考："Asynchronous Iterators"（`__aiter__`/`__anext__`/`StopAsyncIteration`）、"Asynchronous Context Managers"（`__aenter__`/`__aexit__`）、"Asynchronous generator-iterator methods"（`asend`/`athrow`/`aclose`）。
- PEP 525（异步生成器）、PEP 492（`async with`/`async for`）。
- `contextlib.asynccontextmanager` 文档；`loop.shutdown_asyncgens` 文档。
