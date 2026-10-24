# 09 迭代器与生成器

## 学习目标

- 分清 iterable 与 iterator；会用 `iter()` / `next()` / `StopIteration`，能写出 for 循环的等价展开；
- 会手写迭代器类（`__iter__` + `__next__`）；
- 掌握生成器函数与惰性求值、生成器的四种状态（`inspect.getgeneratorstate`）；
- 会用 `send` / `throw` / `close`，认识 `GeneratorExit`；知道 `return` 值放在 `StopIteration.value`；
- 会用 `yield from` 委托；理解生成器表达式（02 篇已见，这里补机制）；
- 认读 3.11 生成器字节码：`RETURN_GENERATOR` / `YIELD_VALUE` / `RESUME` / `SEND`；
- 理解"生成器是协程的前身"，为 `13_asyncio_1_coroutines_and_event_loop.md` 铺垫。

**本主题在项目里出现在哪**

| 位置 | 用到什么 |
| --- | --- |
| `backend/app/services/knowledge_sources.py` `YamlKnowledgeSource.iter_files` | 生成器函数惰性产出知识源文件路径（`yield path` / `yield resolved`） |
| `backend/app/services/task_service.py` `subscribe` | **异步生成器**：`yield` 回放快照帧 + 实时帧（`snapshot = list(managed.events)`） |
| `backend/app/services/rag_product_service.py` `RagProductService.events` | 异步生成器薄壳：`yield frame` 转发 task_service 的事件流 |
| `backend/app/storage/postgres.py` `connect` | `@contextmanager` 装饰的生成器函数（与 08 篇呼应，不重复） |
| `backend/app/api/chat.py` `product_events` | 异步生成器产出 SSE 帧（03 篇已精读 f-string 部分） |

## 语法详解

### iterable 与 iterator

- **iterable**：能被 `iter()` 的东西——`list`/`str`/`dict`/`range`/生成器……它**本身没有 `__next__`**（`list` 就没有）；
- **iterator**：`iter()` 的返回值，有 `__next__`，记住遍历位置，耗尽后抛 `StopIteration`。

```python
it = iter([10, 20])
print(next(it), next(it))
try:
    next(it)
except StopIteration:
    print("stop")
```

输出：`10 20` 与 `stop`

### for 循环的等价展开

```python
xs = []
for x in [1, 2]:
    xs.append(x)
```

等价于（可运行）：

```python
xs = []
it2 = iter([1, 2])
while True:
    try:
        xs.append(next(it2))
    except StopIteration:
        break
print(xs)
```

输出：`[1, 2]`。`StopIteration` 是**正常**的循环结束信号，不是错误。

### 手写迭代器

```python
class CountDown:
    def __init__(self, n): self.n = n
    def __iter__(self): return self       # 迭代器返回自身
    def __next__(self):
        if self.n <= 0:
            raise StopIteration
        self.n -= 1
        return self.n + 1

print([x for x in CountDown(3)])
```

输出：`[3, 2, 1]`。一个类若 `__iter__` 返回**别的**迭代器（如 `return iter(self.items)`），它就只是 iterable。

### 生成器函数与惰性

函数体里出现 `yield`，这个 `def` 就变成了生成器函数：**调用它不执行函数体，只返回一个生成器对象**；每次 `next()` 才推进到下一个 `yield`：

```python
import inspect

def g():
    yield 1
    x = yield 2
    yield x

gen = g()
print(inspect.getgeneratorstate(gen))   # GEN_CREATED：函数体还没跑过
next(gen)
print(inspect.getgeneratorstate(gen))   # GEN_SUSPENDED：停在 yield
gen.close()
print(inspect.getgeneratorstate(gen))   # GEN_CLOSED
```

输出：`GEN_CREATED` / `GEN_SUSPENDED` / `GEN_CLOSED`。四种状态是 `GEN_CREATED`（未启动）→ `GEN_RUNNING`（正在执行）→ `GEN_SUSPENDED`（停在 yield）→ `GEN_CLOSED`（耗尽/关闭）。

### `send` / `throw` / `close`

`yield` 是**双向**的：`next(gen)` 让生成器产出值，`gen.send(v)` 在产出同时**把一个值送回 yield 表达式**：

```python
def g3():
    received = yield "q1"
    yield f"got {received}"

gg = g3()
print(gg.send(None))        # 启动必须用 send(None)（等价 next）
print(gg.send("hello"))     # yield "q1" 求值成 "hello"
try:
    gg.send("x")
except StopIteration:
    print("stopped")
```

输出：`q1` / `got hello` / `stopped`

- `gen.throw(E)`：把异常注入 yield 点（08 篇 `_GeneratorContextManager.__exit__` 就靠它）；
- `gen.close()`：在 yield 点注入 `GeneratorExit` 让生成器收尾——生成器可以用 `try/except GeneratorExit` 做清理（但不能再 yield）：

```python
def g2():
    try:
        yield 1
    except GeneratorExit:
        print("GeneratorExit caught")
        raise
gg = g2(); next(gg); gg.close()
```

输出：`GeneratorExit caught`

### `return` 值与 `yield from`

生成器 `return v` 的值放在终止它的 `StopIteration.value` 上：

```python
def g4():
    yield 1
    return "final"
gg = g4()
next(gg)
try:
    next(gg)
except StopIteration as e:
    print("return value:", e.value)
```

输出：`return value: final`

`yield from sub` 把产出权委托给子生成器，并把它的 `return` 值作为 `yield from` 表达式的值返回（双向通道也透传 send/throw）：

```python
def inner():
    yield 1
    yield 2
    return "inner-done"

def outer():
    r = yield from inner()
    yield r

print(list(outer()))
```

输出：`[1, 2, 'inner-done']`

## 运行机制与 CPython 实现

### 等价改写

生成器函数 `def g(): yield 1; return "x"` 在概念上等于"把函数体编译成一个可暂停的状态机"——`next()` 恢复执行直到下个 `yield`/`return`。手写迭代器类可以逼近它（见上面 `CountDown`），但状态机要手工维护局部变量与位置；生成器把这件事交给解释器：局部变量、指令位置全部保存在**生成器对象持有的 frame** 里——`gen` 不是"结果序列"，是"挂起的执行现场"。

### 字节码：RETURN_GENERATOR / YIELD_VALUE / RESUME / SEND

以下输出由 `backend/.venv/bin/python`（Python 3.11.15）实跑 `dis.dis` 得到：

```python
def gen():
    x = yield 1
    yield x + 1
    return "done"
```

```text
 12           0 RETURN_GENERATOR
              2 POP_TOP
              4 RESUME                   0

 13           6 LOAD_CONST               1 (1)
              8 YIELD_VALUE
             10 RESUME                   1
             12 STORE_FAST               0 (x)

 14          14 LOAD_FAST                0 (x)
             16 LOAD_CONST               1 (1)
             18 BINARY_OP                0 (+)
             22 YIELD_VALUE
             24 RESUME                   1
             26 POP_TOP

 15          28 LOAD_CONST               2 ('done')
             30 RETURN_VALUE
```

逐条读：

- `RETURN_GENERATOR`：调用 `gen()` 时**立即返回生成器对象**，函数体一行不跑——这就是"调用不执行"；
- `RESUME`：每次 `next()`/`send()` 恢复执行的入口点；
- `YIELD_VALUE`：把栈顶值产出给调用方，然后挂起；`send(v)` 送入的值在恢复后成为 yield 表达式的值（`x = yield 1` 的 `x`）；
- `RETURN_VALUE`：函数结束 → 外层看到 `StopIteration(value='done')`。

`yield from` 的字节码用了专门的 `SEND` 指令（委托子迭代器的 send 通道）：

```text
 20           6 LOAD_GLOBAL              1 (NULL + range)
             18 LOAD_CONST               1 (3)
             20 PRECALL                  1
             24 CALL                     1
             34 GET_YIELD_FROM_ITER
             36 LOAD_CONST               0 (None)
        >>   38 SEND                     3 (to 46)
             40 YIELD_VALUE
             42 RESUME                   2
             44 JUMP_BACKWARD_NO_INTERRUPT     4 (to 38)
        >>   46 POP_TOP
             48 LOAD_CONST               0 (None)
             50 RETURN_VALUE
```

`GET_YIELD_FROM_ITER` 把 `range(3)` 变成可委托的迭代器，`SEND`/`YIELD_VALUE`/`JUMP_BACKWARD_NO_INTERRUPT` 循环逐值透传。

### CPython 源码

- 生成器对象是 `PyGenObject`：CPython 3.11 分支 `Objects/genobject.c` 中 `PyGen_Type` 定义了它的方法表（`send`/`throw`/`close` 挂在 `PyGen_methods`）；
- `next`/`send` 的核心是 `gen_iternext` → `gen_send_ex` → `gen_send_ex2`——后者恢复 frame 执行并产出值；`gen_throw` 走 `_gen_throw` 把异常注入 yield 点（08 篇 `gen.throw` 的落点）；
- `close()` 注入 `GeneratorExit` 后要求生成器退出——若它在 except 里又 yield，`close` 会报 `RuntimeError: generator ignored GeneratorExit`。

## 常见陷阱

1. **`gen()` 调用不执行函数体**：`def g(): print(1); yield` 的 `g()` 不打印——`next` 第一次才打印。诊断"为什么代码没跑"时先想到这条。
2. **迭代器是一次性的**：`for x in it` 后再 `for x in it` 得空——生成器/迭代器耗尽不可重播，要重播先 `list(it)`（`task_service.subscribe` 的 `snapshot = list(managed.events)` 就是固化快照）。
3. **`send` 首次必须是 `send(None)`**：`gg.send("x")` 对未启动的生成器报 `TypeError: can't send non-None value to a just-started generator`。
4. **`close()` 后在 `except GeneratorExit` 里 yield** → `RuntimeError`。
5. **迭代中修改容器**：对 list 迭代时改它会跳元素（02 篇）；对 dict 迭代时改大小直接 `RuntimeError: dictionary changed size during iteration`。
6. **生成器表达式是迭代器**：`sum(g)` 后 `g` 已耗尽——同一生成器不能喂两个消费者。
7. `yield` 只能在函数体里（推导式里不行）；`return x` 只能在生成器/函数里（普通迭代器类没有 `StopIteration.value` 这种回传，要用属性携带）。

## 项目代码精读

> 本节代码块均按仓库原文摘录（保留原缩进与注释），依赖项目内部上下文，**不可单独运行**。

### `iter_files`：惰性产出文件路径

`backend/app/services/knowledge_sources.py` `YamlKnowledgeSource`：

```python
    def iter_files(self) -> Iterable[Path]:
        config = self.load_config()
        for source in config.get("sources", []):
            root = Path(source["root"]).expanduser().resolve()
            for item in source.get("include", []):
                path = (root / item).resolve()
                if not path.is_relative_to(root):
                    raise ValueError(f"知识源路径越界: {item}")
                if path.is_file() and path.suffix.lower() in TEXT_EXTENSIONS:
                    yield path
                elif path.is_dir():
                    for child in path.rglob("*"):
                        if child.is_file() and child.suffix.lower() in TEXT_EXTENSIONS:
                            resolved = child.resolve()
                            if not resolved.is_relative_to(root):
                                raise ValueError(f"知识源符号链接越界: {child.name}")
                            yield resolved
                else:
                    # macOS commonly masks case mistakes; Linux does not. Fail
                    # the index job instead of silently dropping a source tree.
                    raise FileNotFoundError(f"Knowledge source path not found: {path}")
```

- 返回注解写 `Iterable[Path]`，实现是**生成器函数**（`yield path`/`yield resolved`）——调用方 `for path in sorted(set(source.iter_files()))`（`rag_index_service.snapshot_sources`）拿到的恰好是迭代协议；
- 惰性在这里不只是性能：`path.rglob("*")` 的目录遍历与 `yield` 交错进行，大知识源目录不必一次性收齐再校验；
- `raise` 直接写在生成器里——消费到一半抛 `ValueError`/`FileNotFoundError`，消费方（摄取任务）收到异常即失败：这就是"遍历期校验"的自然写法。

### `task_service.subscribe`：异步生成器 + 快照

`backend/app/services/task_service.py`：

```python
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
```

- `snapshot = list(managed.events)`：把回放日志**物化**成 list——`managed.events` 之后还会被 `emit` 原地追加/截断（02 篇的 `del events[:n]`），订阅者回放必须看自己进来那一刻的固定快照；
- `for event in snapshot: yield event`：先回放历史帧，再 `await queue.get()` 追实时帧——同一生成器串起"历史 + 实时"两段；
- `async for`/`await` 与 `yield` 混用让它成为**异步生成器**（`async for item in stream` 消费，见 15 篇）；`_TASK_DONE` 哨兵用 `is` 比较对象身份（04 篇）。

### `RagProductService.events`：委托式转发

`backend/app/services/rag_product_service.py`：

```python
    async def events(self):
        """SSE 事件流薄壳（S3-c）：执行装配收进 task_service，订阅方退出绝不取消 run。

        替代旧的「本地 run 协程 + finally cancel」写法（B13 根因：断线即取消）。
        - 重放：不占执行槽，直接回放终态帧；
        - 接续（probe 命中 in_progress/terminal）：委托 task_service.task_events；
        - 正常：ensure_started（幂等起步）→ subscribe。
        """
        from . import task_service
        if self.replayed is not None:
            for frame in task_service.response_frames(self.replayed):
                yield frame
            return
        if self.attached_row is not None or self._recovered:
            task_id = self.attached_row["id"] if self.attached_row is not None else self.task_id
            async for frame in task_service.task_events(task_id, self.principal.subject):
                yield frame
            return
        managed = await task_service.ensure_started(self)
        async for frame in task_service.subscribe(managed):
            yield frame
```

- 三个分支都是"从别的可迭代源取帧再 `yield`"——同步生成器 `response_frames` 用 `for`，异步源用 `async for`；
- `return`（提前结束）在异步生成器里合法但不能带值；
- 没有用 `yield from`：Python 没有 `async yield from`，逐帧 `async for ... yield` 就是异步委托的写法。

## 为什么生成器是协程的前身（承接到 13）

回头看生成器的本质：**一个可以挂起、恢复、双向传值的执行现场**。`next()`/`send()` 恢复它，`yield` 挂起它。把"谁恢复它"从"调用方手动 `next`"换成"事件循环在 I/O 就绪时恢复"，`yield` 换成 `await`，你就得到了协程——`async def` 的函数在 CPython 里就是基于同一套 frame 挂起机制实现的（`asyncio` 的 task 本质上是一个被事件循环反复 `send` 的生成器，历史上 `asyncio` 早期确实直接用 `@asyncio.coroutine` + `yield from` 写协程）。`run_tools` 里的 `async def _invoke`、`task_service` 的 `subscribe`，都是把"函数的执行"变成了可挂起可恢复的对象——读 13 篇时请记住：你已经在 09 里见过这个机制的同步版。

## 练习

1. **读项目代码回答**：`iter_files` 的返回注解是 `Iterable[Path]` 而不是 `Iterator[Path]` 或 `List[Path]`。为什么写成 `Iterable` 最准确？如果改成 `List` 并要求函数体 `return [...]`，会失去什么？
2. **读项目代码回答**：`task_service.subscribe` 里 `snapshot = list(managed.events)` 为什么要先物化再 yield？如果直接 `for event in managed.events: yield event` 会发生什么？（提示：`emit` 会原地改这个列表。）
3. **动手写代码**：手写一个迭代器类 `Take(iterable, n)`：`__iter__`/`__next__` 实现"取前 n 个就 StopIteration"。再写一个生成器函数 `take(iterable, n)` 做同样的事，对比两种写法。验证 `list(take(range(10), 3)) == [0, 1, 2]`。
4. **动手写代码**：写一个生成器 `running_total()`：`total = 0`，`send(x)` 累加并 `yield` 当前累计值；`close()` 时打印最终 total（`except GeneratorExit`）。验证：启动后 `send(10)`→10、`send(5)`→15、close 打印。
5. **读项目代码回答**：`RagProductService.events` 里 `for frame in task_service.response_frames(self.replayed): yield frame` 为什么不写成 `yield from task_service.response_frames(self.replayed)`？两种写法在这个函数里有没有区别？

## 参考答案

<details><summary>参考答案 1</summary>

`Iterable` 只承诺"能被 `iter()`"，对调用方约束最小：生成器对象本身就是 iterable（`iter(gen) is gen`），所以生成器函数天然满足注解；写 `Iterator` 也对但把"可迭代"说窄了（还要求有 `__next__`，语义上是"已经是个迭代器"）。改成 `List` 并要求收集返回会失去两点：惰性——目录遍历/越界校验要全部跑完才有第一个结果（大目录时白白付全量成本）；流式错误时机——现在消费到第 N 个文件才触发越界 `raise`，列表版则必须整棵树扫完才能发现错误。`snapshot_sources` 随后 `sorted(set(...))` 本来就要物化，没必要在生产侧再建一次中间列表。

</details>

<details><summary>参考答案 2</summary>

`managed.events` 是 `emit` 原地维护的回放日志（`append` + `del` 截断）。`for event in managed.events` 迭代的是**活对象**：回放过程中 `emit` 追加新事件，迭代器会**把新事件也回放出来**——订阅者先看到历史、紧接着实时队列又推来同一条新事件，帧重复；截断 `del` 甚至会让迭代中途跳元素或提前耗尽。`list(managed.events)` 在订阅那一刻固化边界：快照覆盖"订阅前所有事件"，实时帧只从队列取，两段无重叠无遗漏——配合 docstring 说的"快照与订阅注册之间无 await，同一事件循环内不存在漏帧窗口"。

</details>

<details><summary>参考答案 3</summary>

```python
class Take:
    def __init__(self, iterable, n):
        self.it = iter(iterable)
        self.n = n
    def __iter__(self):
        return self
    def __next__(self):
        if self.n <= 0:
            raise StopIteration
        self.n -= 1
        return next(self.it)

def take(iterable, n):
    for x in iterable:
        if n <= 0:
            return
        n -= 1
        yield x

print(list(Take(range(10), 3)), list(take(range(10), 3)))
```

实跑输出：`[0, 1, 2] [0, 1, 2]`。迭代器类要手工管三件状态：`self.it`（内层迭代器）、`self.n`（剩余额度）、`__iter__` 返回自身；生成器函数里这些状态就是普通局部变量，`for`+`yield` 自然展开——生成器把"状态机"从"你写"变成"解释器维护"。

</details>

<details><summary>参考答案 4</summary>

```python
def running_total():
    total = 0
    try:
        while True:
            x = yield total
            total += x
    except GeneratorExit:
        print("final total:", total)
        raise

g = running_total()
print(g.send(None))     # 0（首次必须 None 启动）
print(g.send(10))       # 10
print(g.send(5))        # 15
g.close()               # final total: 15
```

实跑输出：`0` / `10` / `15` / `final total: 15`。`yield total` 在 `send(x)` 时恢复并求值为 `x`，累加后下个循环产出新值——send/yield 的"一来一回"就是累加器的完整一拍；`close()` 注入 `GeneratorExit`，`except` 块打印后必须 `raise`（吞掉 GeneratorExit 再 yield 会 RuntimeError）。

</details>

<details><summary>参考答案 5</summary>

`response_frames` 是**同步**生成器，`yield from` 在语法上可用、语义上完全等价（逐个产出并透传 `return` 值）——单看这一个分支，`yield from` 更简洁。但 `events` 的另外两个分支源是**异步**生成器（`task_events`/`subscribe`），Python 没有 `async yield from`，必须写成 `async for frame in ...: yield frame`；三个分支统一用"逐帧 yield"的写法反而更整齐。功能无区别，风格一致性是项目这么写的原因。

</details>

## 延伸阅读

- Python 官方教程："Iterators" 与 "Generators"。
- 语言参考："The yield statement"、"Generator-iterator methods"（send/throw/close 的精确语义）、"Asynchronous generator-iterator methods"。
- PEP 255（生成器）、PEP 342（`send`/`throw`/`close` 与 yield 表达式——"生成器即协程"的起点）、PEP 380（`yield from`）、PEP 479（生成器内 StopIteration 转换）。
- `inspect.getgeneratorstate` 文档；`itertools` 文档（惰性迭代工具族）。
