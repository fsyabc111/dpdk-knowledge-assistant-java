# 11 作用域与闭包

## 学习目标

- 掌握 LEGB 查找顺序；理解"函数体内任何位置赋值，该名字就是局部变量"及 `UnboundLocalError`；
- 会用 `global` / `nonlocal`；
- 理解闭包与 cell：`__closure__`、`cell_contents`、`co_freevars` / `co_cellvars`；
- 认读 3.11 闭包字节码：`MAKE_CELL` / `COPY_FREE_VARS` / `LOAD_DEREF` / `STORE_DEREF` / `LOAD_CLOSURE`；
- 掌握循环 lambda 的延迟绑定陷阱与默认参数修复法；
- 理解闭包与类的等价性、用闭包捕获可变容器做缓存。

**本主题在项目里出现在哪**

| 位置 | 用到什么 |
| --- | --- |
| `backend/app/agent/graph.py` `build_agent_graph` | 整个图的节点函数（`planner`/`run_tools`/`finalize`/`_deadline_reached`/`_finalize_reason`）全是捕获外层参数的闭包；`planner` 内再嵌 `_bounded_invoke`/`updates`；docstring 明说 `usage` "闭包进节点，不进图 state" |
| `backend/app/agent/tools.py` `build_tools(context)` | `call_mcp` 与五个 `@tool` 工具函数闭包捕获 `context`（`AgentToolContext`） |
| `backend/app/agent/service.py` `_build_prompt_builder` | `escalated_cache`/`profile_cache` 两个 dict 被 `build_prompt` 闭包捕获当请求内缓存 |
| `backend/app/services/retrieval_service.py` `_rewrite_query` / `_embed_query` | `ran = False` + 内层 `nonlocal ran`：记录"loader 是否真跑了" |
| `backend/app/core/async_cache.py` `AsyncTTLCache.get_or_create` | `populate()` 闭包捕获 `key`/`factory`/`self`，作 singleflight 的共享任务 |
| `backend/app/api/chat.py` `product_events` | 闭包捕获 `stream`（03 篇已见） |

## 语法详解

### LEGB 与"赋值即局部"

名字查找顺序：**L**ocal → **E**nclosing（外层函数）→ **G**lobal（模块）→ **B**uiltins。

最关键的一条规则：**函数体内任何位置出现对某名字的赋值（`=`、`for x in`、`import x`、参数），整个函数里这个名字都是局部变量**——包括赋值点之前：

```python
def bad_scope():
    print(x)      # x 是局部变量（下面有赋值），但此刻还没绑值
    x = 1

try:
    bad_scope()
except UnboundLocalError as e:
    print("UBL:", e)
```

输出：`UBL: cannot access local variable 'x' where it is not associated with a value`

`x` 不是"先查全局再变局部"——它在整个函数内都是局部，`print(x)` 时未绑定直接 `UnboundLocalError`（不是 `NameError`，编译期就已知 x 是局部）。

要突破这条规则用声明：

```python
counter = 0
def bump():
    global counter          # 赋值目标是模块级名字
    counter += 1

def outer():
    n = 0
    def inner():
        nonlocal n          # 赋值目标是最近外层函数的 n
        n += 1
        return n
    return inner
```

`global` 指模块作用域；`nonlocal` 指**最近的含该名字的外层函数**作用域（找不到就 `SyntaxError`）。没有这两个声明，`count += 1` 会触发上面的"赋值即局部"——读外层变量只能读不能改。

### 闭包与 cell

内层函数引用外层函数的局部变量时，这个变量不再存在普通局部槽位，而被移进一个 **cell**（共享盒子）：外层和内层持有同一个 cell，内层的 `__closure__` 是 cell 元组：

```python
def outer():
    count = 0
    label = "c"
    def incr():
        nonlocal count
        count += 1
        return count
    return incr

f = outer()
print(f.__code__.co_freevars)          # ('count',)：内层捕获了哪些外层名
print(outer.__code__.co_cellvars)      # ('count',)：外层哪些变量被内层捕获
print(f.__closure__[0].cell_contents)  # 0
f(); f()
print(f.__closure__[0].cell_contents)  # 2
```

输出：

```text
('count',) ('count',)
0
2
```

- `co_cellvars`：外层函数中"被内层引用的局部变量"；
- `co_freevars`：内层函数"捕获了哪些外层名字"；
- `f.__closure__`：cell 元组，`cell_contents` 是共享的当前值——`label` 没被内层用，所以不进 cell。

### 循环 lambda 的延迟绑定陷阱

```python
fns = [lambda: i for i in range(3)]
print("late binding:", [f() for f in fns])
fns2 = [lambda i=i: i for i in range(3)]
print("fixed:", [f() for f in fns2])
```

输出：`late binding: [2, 2, 2]` / `fixed: [0, 1, 2]`

三个 lambda 捕获**同一个 cell**（循环变量 `i` 只有一个，被反复赋值）——调用时读到的都是最后的 `2`。修复：`lambda i=i: i` 用**默认参数**把当前值绑定进每个函数自己的 `__defaults__`（默认值在 def 执行时求值，见 01 篇）。

### 闭包 vs 类

```python
def make_counter():
    state = {"n": 0}
    def incr():
        state["n"] += 1
        return state["n"]
    return incr

c1, c2 = make_counter(), make_counter()
print(c1(), c1(), c2())
```

输出：`1 2 1`

`make_counter` 的闭包 ≈ 一个只有一个方法的类：`state` 是"实例字段"，`incr` 是"方法"。反过来，实现 `__call__` 的类也能当函数用。工程上怎么选：**一小撮状态 + 一个动作**用闭包；**多方法、要继承、要显式生命周期**用类——`graph.py` 的节点函数是前者（每请求新建、用完即弃），`RagProductService` 是后者。

## 运行机制与 CPython 实现

### 字节码：cell 的建与取

以下输出由 `backend/.venv/bin/python`（Python 3.11.15）实跑 `dis.dis` 得到：

```python
def outer():
    count = 0
    def incr():
        nonlocal count
        count += 1
        return count
    return incr
```

`outer` 的反汇编：

```text
              0 MAKE_CELL                1 (count)

 37           2 RESUME                   0

 38           4 LOAD_CONST               1 (0)
              6 STORE_DEREF              1 (count)

 39           8 LOAD_CLOSURE             1 (count)
             10 BUILD_TUPLE              1
             12 LOAD_CONST               2 (<code object incr at 0x109f55620, file "/tmp/pre_verify/gen_dis2.py", line 39>)
             14 MAKE_FUNCTION            8 (closure)
             16 STORE_FAST               0 (incr)

 43          18 LOAD_FAST                0 (incr)
             20 RETURN_VALUE
```

`incr` 的反汇编：

```text
              0 COPY_FREE_VARS           1

 39           2 RESUME                   0

 41           4 LOAD_DEREF               0 (count)
              6 LOAD_CONST               1 (1)
              8 BINARY_OP               13 (+=)
             12 STORE_DEREF              0 (count)

 42          14 LOAD_DEREF               0 (count)
             16 RETURN_VALUE
```

逐条读：

- `MAKE_CELL count`：外层函数入口处给 `count` 建 cell——因为 `incr` 要捕获它，`count` 不再是普通局部变量；
- `STORE_DEREF`/`LOAD_DEREF`：cell 里存取值（外层与内层共用一套指令）；
- `LOAD_CLOSURE` + `MAKE_FUNCTION 8 (closure)`：把 `count` 的 cell 打成元组挂进新函数对象——这就是 `__closure__` 的由来；
- 内层 `COPY_FREE_VARS`：把外层传入的 cell 元组复制进自己的 frame——之后 `LOAD_DEREF`/`STORE_DEREF` 与外层操作**同一个 cell 对象**，两边看到的永远是同一个 `count`。

### 等价改写

闭包 ≈ 显式共享的可变容器。`outer`/`incr` 可改写为：

```python
class _Cell:
    def __init__(self, v):
        self.value = v

def outer_equiv():
    count = _Cell(0)
    def incr():
        count.value += 1
        return count.value
    return incr
```

`incr` 读 `count.value` 就等价于 `LOAD_DEREF`——cell 就是一个只装一个值的盒子；`nonlocal` 声明只是告诉编译器"这个名字走 cell 不走局部槽"。

### CPython 源码

cell 对象与闭包机制在 CPython 3.11 分支 `Objects/cellobject.c`：`PyCellObject` 与存取原语 `PyCell_New`/`PyCell_Get`/`PyCell_Set`；`MAKE_FUNCTION` 携带 closure flag 时把 cell 元组拷进 `PyFunctionObject.func_closure`（`Objects/funcobject.c` 的字段，与 `func_defaults`/`func_kwdefaults` 同列）。

## 常见陷阱

1. **"先读后写"误判**：函数里先 `print(x)` 后 `x = 1` → `UnboundLocalError`，不是读到全局值——编译期就定死了 x 是局部。
2. **循环 lambda 共享 cell**：`[lambda: i for i in ...]` 全部拿到最后值——默认参数 `lambda i=i: i` 或 `functools.partial` 修复。
3. **`nonlocal` 找不到外层名字**：`SyntaxError`——外层必须先有绑定。
4. **闭包捕获可变容器改元素不需要 `nonlocal`**：`state["n"] += 1` 是对容器的方法调用（不重新绑定名字），`state = new` 才需要 `nonlocal`——分辨"改内容"与"换绑定"。
5. **闭包=隐式引用，GC 角度**：闭包持有的对象随函数存活——`build_tools(context)` 的工具集存活期 = context 存活期，属请求级，别顺手把大对象塞进长寿命闭包。
6. **`global` 滥用**：模块级 `TASKS`/`_clients` 的"改内容"（`TASKS[x] = v`）不需要 `global`；只有重新绑定模块名才需要（`_provider = None` 在 `close_database_provider` 里写 `global _provider`）。

## 项目代码精读

> 本节代码块均按仓库原文摘录（保留原缩进与注释），依赖项目内部上下文，**不可单独运行**。

### `build_agent_graph`：一张图全是闭包

`backend/app/agent/graph.py`（签名与 docstring，有省略）：

```python
def build_agent_graph(
    *,
    model: Any,
    tools: List[Any],
    prompt_builder: PromptBuilder,
    max_rounds: int,
    token_budget: int = 0,
    context_max_chars: int = 0,
    usage: Optional[UsageRecorder] = None,
    model_name: Optional[str] = None,
    parallel_tools: bool = False,
    deadline: Optional[float] = None,
) -> Any:
    """Compile the planner/tools/finalize graph. ``model`` is a LangChain chat model.

    ``usage`` 是请求级用量 sink：闭包进节点，**不进图 state**（state 要保持可序列化，
    将来接 checkpointer 才不用改）。默认 None 时行为与接线前逐字一致。

    ...
    """
    tools_by_name: Dict[str, Any] = {item.name: item for item in tools}
```

图里每个节点/路由函数都是 `build_agent_graph` 内的闭包，各捕获不同的外层名（同文件 `backend/app/agent/graph.py`）：

```python
    def _deadline_reached(state: AgentGraphState) -> bool:
        return bool(state.get("deadline_hit")) or (
            deadline is not None and time.monotonic() >= deadline
        )

    def _finalize_reason(state: AgentGraphState) -> str:
        if _deadline_reached(state):
            return "deadline"
        if token_budget and state.get("tokens_used", 0) >= token_budget:
            return "token_budget"
        if state.get("tool_rounds", 0) >= max_rounds:
            return "max_rounds"
        return "none"
```

- `_deadline_reached` 捕获 `deadline`；`_finalize_reason` 捕获 `token_budget`、`max_rounds` 并调用 `_deadline_reached`（捕获名也是闭包共享）；
- `planner` 捕获 `prompt_builder`、`usage`、`deadline`、`context_max_chars`、`model_name`、`planner_model`——内嵌的 `_bounded_invoke` 再捕获 `planner_model`/`prepared`/`usage`/`model_name`，`updates` 捕获 `started`（嵌套两层闭包，各自只拿需要的名）；
- `run_tools` 捕获 `tools_by_name`、`parallel_tools`——每次 `build_agent_graph` 调用重新建这张 dict，**节点函数拿到的工具表是本次构建的快照**，请求间互不干扰；
- 关键工程决策在 docstring 里：`usage` **闭包进节点不进 state**——state（TypedDict）要保持可序列化，sink 对象交给闭包携带；这正是"闭包作隐式参数通道"与"显式 state"的分工。

### `build_tools(context)`：请求级工具集闭包

`backend/app/agent/tools.py`：

```python
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
```

- `call_mcp` 捕获 `context`（经 `context.mcp_caller`/`_record_mcp_operation(context, ...)` 读写）与 `started`（每次调用新建，仅本调用闭包）；
- 五个 `@tool` 函数（`kb_search`/`list_protocols`/`list_cases`/`plugin_template`/`grade_plugin`）同样闭包捕获 `context`——**每请求 `build_tools` 重建一套绑定该请求 context 的工具**：配额、去重表、task_id 都经闭包注入，工具签名里不需要出现；
- `settings = context.settings` 提取到外层变量，工具内 `settings.xxx` 是普通自由变量捕获（不需要 nonlocal，只读）。

### `_build_prompt_builder`：闭包 dict 当请求内缓存

`backend/app/agent/service.py`：

```python
        escalated_cache: Dict[str, bool] = {}
        profile_cache: Dict[str, str] = {}

        async def build_prompt(state: Dict[str, Any]) -> str:
            """分级反馈升级（§4.3）：显式请求，或同协议近期 FAIL 达阈值。"""
            escalated = explicit_ask
            if not escalated:
                protocol = _last_graded_protocol(state.get("messages", []))
                if protocol:
                    if protocol not in escalated_cache:
                        try:
                            fails = await self.grading.recent_fail_count(
                                context.user_id, protocol
                            )
                            escalated_cache[protocol] = (
                                fails >= settings.feedback_escalation_threshold
                            )
                        except Exception:
                            # 统计失败不阻断主流程，保持默认档反馈。
                            escalated_cache[protocol] = False
                    escalated = escalated_cache[protocol]
```

- `escalated_cache`/`profile_cache` 是外层局部 dict，`build_prompt` 闭包捕获——**同一个 builder 多次被图调用时**（每轮 planner 都可能重建 prompt），同一 protocol 的 fail 统计只查一次库；
- 捕获的是 dict 对象、改的是元素——不需要 `nonlocal`（陷阱 4 的"改内容 vs 换绑定"）；
- `self`、`context`、`settings`、`explicit_ask` 也是自由变量：builder 拥有"按请求固化"的上下文。

### `retrieval_service` 的 `nonlocal ran`

`backend/app/services/retrieval_service.py` `_rewrite_query`：

```python
        # 缓存命中的判定必须靠「loader 有没有真的跑」而不是猜：AsyncTTLCache 命中
        # 时根本不调用工厂。单飞（另一个并发请求正在跑同一条 key）同样算本次零调用
        # ——账本记的是**本请求**的花费。
        ran = False

        async def _load() -> str:
            nonlocal ran
            ran = True
            return await self._rewrite_query_uncached(original, usage=usage)
```

- `ran` 是外层局部 bool；`_load` 闭包捕获并用 `nonlocal ran` **重新绑定**它——`nonlocal` 的典型用途：内层写外层变量；
- 语义：`get_or_create` 命中缓存时根本不调 `_load`，`ran` 保持 `False` → 本请求记 `cache_hit` 而不是记一笔假用量；同文件 `_embed_query` 是同款第二处。

### `async_cache.populate`：闭包作 singleflight 任务体

`backend/app/core/async_cache.py` `AsyncTTLCache.get_or_create`：

```python
                async def populate() -> V:
                    try:
                        value = await factory()
                    except BaseException:
                        async with self._lock:
                            if self._inflight.get(key) is asyncio.current_task():
                                self._inflight.pop(key, None)
                        raise
                    async with self._lock:
                        if self._inflight.get(key) is asyncio.current_task():
                            self._inflight.pop(key, None)
                            self._values[key] = (time.monotonic() + self.ttl_seconds, value)
```

- `populate` 捕获 `key`、`factory`、`self`——它被包成 `asyncio.Task` 存进 `_inflight[key]`，所有并发请求共享这一个任务（singleflight）；
- 闭包让"任务体"拿到上下文而不必造专用类；`except BaseException` 保证取消/失败都清 `_inflight`（05 篇语义）。

## 练习

1. **读项目代码回答**：`build_agent_graph` 为什么把 `usage` 闭包进节点而不放进 `AgentGraphState`？docstring 里的理由是什么，和 state 的类型（TypedDict，见 12 篇）有什么关系？
2. **读项目代码回答**：`retrieval_service._rewrite_query` 里 `nonlocal ran` 如果删掉，`ran` 会变成什么？`cache_hit` 账本语义会错在哪？
3. **动手写代码**：写一个 `make_bank()`：返回 `(deposit, balance)` 两个函数，闭包共享一个余额变量；`deposit(n)` 加钱返回新余额，`balance()` 只读。验证两个账户互不影响。
4. **动手写代码**：写一个 `memoize(f)`：用闭包捕获的 dict 做缓存（只允许一个位置参数）。验证 fib 递归版本套了它之后 `fib(30)` 秒回，且 `fib.__wrapped__` 可用（加 `functools.wraps`）。
5. **读项目代码回答**：`build_tools(context)` 每个请求都重建工具集。如果改成模块级共享一套工具（把 context 作为工具参数传入），哪些功能会坏掉？（提示：同请求去重表 `graded_sources`、配额、task_id 从哪来？）

## 参考答案

<details><summary>参考答案 1</summary>

docstring 原话："``usage`` 是请求级用量 sink：闭包进节点，**不进图 state**（state 要保持可序列化，将来接 checkpointer 才不用改）"。`AgentGraphState` 是 `MessagesState` 的 TypedDict 子类——图框架约定 state 是可序列化的数据（将来接 checkpointer 持久化）；`UsageRecorder` 是带业务逻辑与累加器的对象，放进 state 既不可序列化也把"请求级账"混进"对话数据"。闭包正是"不进签名/不进 state 的隐式通道"：节点函数签名仍是 `(state) -> dict`，usage 经外层变量直达。

</details>

<details><summary>参考答案 2</summary>

删掉 `nonlocal` 后 `_load` 里的 `ran = True` 变成**给 `_load` 新建一个局部变量**（赋值即局部），外层的 `ran` 永远是 `False`——后果：`get_or_create` 明明真跑了 loader（缓存未命中、真花了 LLM 调用），外层判断 `ran` 仍是 `False`，把真实花费记成 `cache_hit` 的零调用——用量账本系统性少记。`nonlocal` 在这里是"内层把事实回写给外层"的唯一通道。

</details>

<details><summary>参考答案 3</summary>

```python
def make_bank():
    balance_v = 0
    def deposit(n):
        nonlocal balance_v
        balance_v += n
        return balance_v
    def balance():
        return balance_v
    return deposit, balance

d1, b1 = make_bank()
d2, b2 = make_bank()
print(d1(100), d1(50), b1())
print(d2(7), b2())
```

实跑输出：`150`（100）、`150`（50 后再读）——`d1(100)`→100、`d1(50)`→150、`b1()`→150；`d2(7)`→7、`b2()`→7。两个函数捕获**同一个 cell**（`balance_v`），deposit 用 `nonlocal` 重绑，balance 只读；两次 `make_bank()` 各自建独立 cell——这就是 `ManagedTask` 级"每实例独立状态"的闭包版。

</details>

<details><summary>参考答案 4</summary>

```python
import functools

def memoize(f):
    cache = {}
    @functools.wraps(f)
    def w(n):
        if n not in cache:
            cache[n] = f(n)
        return cache[n]
    return w

@memoize
def fib(n):
    return n if n < 2 else fib(n - 1) + fib(n - 2)

print(fib(30), fib.__wrapped__.__name__, fib.__name__)
```

实跑输出：`832040 fib fib`。`cache` 是 `memoize` 的局部 dict 被 `w` 捕获——每个被装饰函数一份独立缓存；`wraps` 保留 `__name__` 并挂 `__wrapped__`（递归调用 `fib` 走的是包装后的名字所以递归也吃缓存）。注意这与 `lru_cache` 同构：外层 dict + 闭包查表。

</details>

<details><summary>参考答案 5</summary>

会坏三件事：其一，`context.graded_sources`（同请求 `(protocol, sha256)` 去重表）无处安放——共享工具若从参数拿 context，工具签名就要泄露 task_id/配额/去重表这些请求级字段，模型侧 schema 会多出它不该填的参数；其二，配额扣减与 `task_id` 绑定错请求——A 请求的工具可能扣到 B 请求的账上；其三，`_record_mcp_operation`/`_resume_grading_job` 都以 `context.task_id` 为键，共享工具的 `call_mcp` 拿不到请求身份，作业账本与用量记账全部断链。`build_tools` 每请求重建的本质就是"把请求身份烤进工具"——闭包是把 context 藏在工具签名之外、又不进共享状态的合法通道。

</details>

## 延伸阅读

- Python 官方教程：无独立章节；见 "Defining Functions" 作用域部分与 FAQ（"Why am I getting an UnboundLocalError"）。
- 语言参考："Naming and binding"（LEGB 精确定义）、"The global statement"、"The nonlocal statement"、"Special method names"（`__closure__` 相关在 function 属性表）。
- PEP 3104（`nonlocal`）。
- `inspect` 模块文档（`getclosurevars`）；dis 模块文档（`MAKE_CELL`/`COPY_FREE_VARS`/`LOAD_DEREF`）。
