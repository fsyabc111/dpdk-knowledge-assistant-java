# 12 类型注解、Protocol 与 dataclass

## 学习目标

- 知道注解只是元数据：`__annotations__`，运行期**不校验**；
- 会用 `Optional` / `Union` / `X | None`、`List`/`Dict`/`Tuple`/`Callable`/`Awaitable`/`Iterator`/`AsyncIterator`/`Any`/`ClassVar`、内置泛型（PEP 585）；
- 会用 `TypeVar`/`Generic`、`Protocol` 结构化子类型（含 `runtime_checkable`）、`TypedDict`；
- 理解 `TYPE_CHECKING` + `from __future__ import annotations`（PEP 563）与字符串前向引用——衔接 07 篇；
- 掌握 `dataclass`：自动 `__init__`/`__repr__`/`__eq__`、`field(default_factory)`、可变默认值报错、`frozen`/`order`/`slots`/`kw_only`/`__post_init__`；
- 知道 dataclass 用 `exec` 生成 `__init__` 源码；
- 一段对比：pydantic 同样读注解但运行期校验。

**本主题在项目里出现在哪**

| 位置 | 用到什么 |
| --- | --- |
| `backend/app/rag/interfaces.py` | `ChatModel`/`EmbeddingModel`/`QueryRewriter` 等 `Protocol`；`TYPE_CHECKING` + 引号前向引用 |
| `backend/app/storage/interfaces.py` | `DatabaseProvider(Protocol)`；`PostgresProvider`/`SQLiteProvider` **不继承**它，靠结构化匹配 |
| `backend/app/core/async_cache.py` | `AsyncTTLCache` 是 `Generic[K, V]`（`OrderedDict[K, tuple[float, V]]`、`Dict[K, asyncio.Task[V]]`） |
| `backend/app/services/task_service.py` `ManagedTask` | `@dataclass`，`done: asyncio.Event = field(default_factory=asyncio.Event)` |
| `backend/app/core/tracing.py` `RequestTrace` | `@dataclass`，`field(default_factory=lambda: f"trace_{...}")` 等 |
| `backend/app/agent/graph.py` `AgentGraphState(MessagesState)` | `MessagesState` 是 **TypedDict**（非 dataclass），字段全是注解声明 |
| `backend/app/rag/types.py` | `SourceChunk`/`RetrievedChunk`/`AnswerDraft` 三个朴素 `@dataclass` |
| `backend/app/core/usage.py` | `ModelPrice`/`UsageEntry` 是 `@dataclass(frozen=True)`；`UsageRecorder` 是普通类（对照） |

## 语法详解

### 注解只是元数据

```python
def g(x: int) -> int:
    return x

print(repr(g("not an int")))       # 运行期不校验
print(g.__annotations__)
```

输出（3.11.15 实跑，无 future import 的模块）：

```text
'not an int'
{'x': <class 'int'>, 'return': <class 'int'>}
```

同一个函数放进带 `from __future__ import annotations` 的模块（用 exec 模拟）：

```python
ns = {}
exec("from __future__ import annotations\ndef g(x: int) -> int: return x", ns)
print(ns["g"].__annotations__)
```

输出：`{'x': 'int', 'return': 'int'}`——注解被存成字符串（PEP 563 细节见下文"TYPE_CHECKING"小节与 07 篇）。

**类型注解不改变任何运行期行为**——`int` 标注的函数收 `str` 照样跑。校验是静态工具（mypy/pyright）或显式库（pydantic）的事。这个认识贯穿全仓：`-> Dict[str, Any]` 是给人和 IDE 看的契约文档，不是运行时护栏。

### 常用写法速查

```python
from typing import Optional, Dict, List, Tuple, Callable, Awaitable, Iterator, AsyncIterator, Any, ClassVar

x: Optional[int] = None          # = Union[int, None] = int | None（3.10+ 写法）
y: int | None = 3
def f(cb: Callable[[int], str]) -> str: ...
async def g() -> int: ...        # async def 的返回注解写 await 出来的值类型
def h() -> Iterator[str]: ...
async def s() -> AsyncIterator[str]: ...  # 只对"函数体含 yield 的异步生成器"成立
d: Dict[str, Any] = {}           # Any = 关掉检查
class C:
    pool: ClassVar[list] = []    # 类属性（见 06 篇 llm_client 的用法）
```

注意 `Awaitable`/`AsyncIterator` 的用法位置：`async def g() -> int` 注解的是**await 完成后拿到的值**；`Awaitable[V]` 用在"参数/返回值是可等待对象"的位置，例如 `AsyncTTLCache.get_or_create` 的 `factory: Callable[[], Awaitable[V]]`——它接收一个"调用后返回 coroutine"的工厂。`async def s() -> AsyncIterator[str]` 只对异步生成器函数（函数体含 `yield`）成立——普通 async def 返回 coroutine，不是 async iterator。

PEP 585（3.9+）：`list[int]`/`dict[str, int]`/`tuple[int, ...]` 内置类型直接做泛型——项目里 `tuple[list[dict], list[dict]]`（`plan_nodes`）就是这种；`typing.List`/`Dict` 大写写法是旧式兼容，新代码用小写。

### `TypeVar` / `Generic`

```python
from typing import TypeVar, Generic, Dict

K = TypeVar("K"); V = TypeVar("V")

class Box(Generic[K, V]):
    def __init__(self):
        self.d: Dict[K, V] = {}

b = Box()
```

`Box[K, V]` 是参数化容器：静态检查器把 `Box[str, int]` 与 `Box[int, str]` 区分开。`AsyncTTLCache` 就是这个形态（`Generic[K, V]`）。

### `Protocol` 结构化子类型

`Protocol` 定义"长得像就行"的接口（duck typing 的类型版）——实现方**不需要继承**：

```python
from typing import Protocol, runtime_checkable

@runtime_checkable
class Sized(Protocol):
    def __len__(self) -> int: ...

print(isinstance([1, 2], Sized), isinstance(5, Sized))
```

输出：`True False`

- 不加 `@runtime_checkable` 的 Protocol 只能静态检查用，`isinstance` 会 `TypeError`；加了也只检查**方法存在**（不查签名细节）；
- 与 ABC 的区别：ABC 要显式继承、可带实现；Protocol 零侵入——`PostgresProvider` 不认识 `DatabaseProvider` 也能满足它。

### `TypedDict`

```python
from typing import TypedDict

class TD(TypedDict):
    name: str

td: TD = {"name": "x"}           # 本质还是 dict，只是键的类型被声明
```

`TypedDict` 的实例就是 `dict`——注解声明"该 dict 有哪些键、各键什么类型"。`AgentGraphState` 就是这种（见下文）。

### `TYPE_CHECKING` / `__future__.annotations` / 前向引用

与 07 篇衔接，只补机制不重复：

```python
from __future__ import annotations   # PEP 563：注解存成字符串不求值
import typing

def f(x: NotDefined) -> int:
    pass

print(f.__annotations__)              # {'x': 'NotDefined', 'return': 'int'}：原样字符串
try:
    typing.get_type_hints(f)          # 需要时才解析
except NameError as e:
    print("get_type_hints:", e)
```

输出：`{'x': 'NotDefined', 'return': 'int'}` 与 `get_type_hints: name 'NotDefined' is not defined`

- future import 把 `__annotations__` 全变字符串，`def` 执行时不查名字——`TYPE_CHECKING` 导入的名字可以放心出现在注解里；
- `get_type_hints` 是"认真解析"的入口：它真的去查名字，查不到才炸——**工具用它时才需要名字存在**；
- 字符串前向引用 `Optional["UsageRecorder"]` 是手动版同一机制（`interfaces.py` 的双保险，见 07 篇答案 5）。

### dataclass：自动方法

```python
import dataclasses

@dataclasses.dataclass
class P:
    x: int
    y: int = 2
    tags: list = dataclasses.field(default_factory=list)

p = P(1)
print(p, p == P(1, 2, []))
```

输出：`P(x=1, y=2, tags=[]) True`

`@dataclass` 按字段注解自动生成 `__init__`（按声明顺序收参）、`__repr__`（`P(x=1, ...)` 形态）、`__eq__`（逐字段比较）。

### `field(default_factory)` 与可变默认值

可变默认值直接写会被**拒绝**（不是共享陷阱，是明确报错，3.11.15 实测）：

```text
ValueError: mutable default <class 'list'> for field items is not allowed: use default_factory
```

`field(default_factory=list)` 每次实例化新调 `list()`——`ManagedTask.events`/`subscribers`、`RequestTrace.stages_ms` 全靠它（01 篇函数默认值、06 篇可变类属性的同款陷阱，在 dataclass 里变成**类定义时**就拦下来的明确报错）。

### 常用参数

```python
@dataclasses.dataclass(frozen=True)
class F:
    v: int

try:
    F(1).v = 2
except dataclasses.FrozenInstanceError as e:
    print("frozen:", type(e).__name__)

@dataclasses.dataclass(order=True)
class O:
    v: int

print(O(1) < O(2))

@dataclasses.dataclass(kw_only=True)
class KW:
    v: int

print(KW(v=5))

@dataclasses.dataclass(slots=True)
class S:
    x: int

s = S(1)
print(hasattr(s, "__dict__"), s.x)

@dataclasses.dataclass
class PP:
    x: int
    doubled: int = dataclasses.field(init=False)
    def __post_init__(self):
        self.doubled = self.x * 2

print(PP(3).doubled)
```

输出：

```text
frozen: FrozenInstanceError
True
KW(v=5)
False 1
6
```

- `frozen=True`：实例不可变（`FrozenInstanceError`），可哈希——`ModelPrice`/`UsageEntry` 用它做纯值对象；
- `order=True`：生成 `<`/`<=`/`>`/`>=`（按字段序）；
- `kw_only=True`（3.10+）：所有字段仅关键字——`begin_admitted` 的 `*,` 在 dataclass 上的对应物；
- `slots=True`：类带 `__slots__`（无 `__dict__`，见 06 篇）；
- `__post_init__`：`__init__` 末尾自动调用，做派生字段——`init=False` 的字段常配它。

### pydantic 对比（一段）

`pydantic.BaseModel` 也读字段注解，但**运行期校验**：`ChatRequest.model_validate({"message": 1})` 会抛 `ValidationError`（03 篇答案 1 还演示了孤立代理被拒）——注解在 pydantic 里是"模式"不是"文档"。读 `schemas/` 与 `config.py` 时记住这条分界线：dataclass/TypedDict 是声明，pydantic 是闸门。

## 运行机制与 CPython 实现

### 注解的存储：`__annotations__` 与零字节码成本

注解写在签名上，但**函数体字节码与无注解版完全一样**——注解只是挂在函数对象的 `__annotations__` dict 上的元数据。对下面两个函数实跑 `dis.dis`（3.11.15）：

```python
def plain(x: int) -> int: return x
def plain2(x) -> int: return x
```

`plain` 的输出：

```text
 11           0 RESUME                   0
              2 LOAD_FAST                0 (x)
              4 RETURN_VALUE
```

`plain2` 的输出与之一字不差——`x: int` 与 `-> int` 不产生任何运行时指令（有 `from __future__ import annotations` 时连注解求值都省了，存字符串，见上文 PEP 563 与 07 篇）。

### dataclass：exec 生成 `__init__`

dataclass 不是反射填值——它**拼出 `__init__` 的源码文本再 exec**。`Lib/dataclasses.py` 的 `_init_fn`（摘录）：

```python
def _init_fn(fields, std_fields, kw_only_fields, frozen, has_post_init,
             self_name, globals, slots):
    ...
    seen_default = False
    for f in std_fields:
        # Only consider the non-kw-only fields in the __init__ call.
        if f.init:
            if not (f.default is MISSING and f.default_factory is MISSING):
                seen_default = True
            elif seen_default:
                raise TypeError(f'non-default argument {f.name!r} '
                                'follows default argument')

    locals = {f'_type_{f.name}': f.type for f in fields}
    ...
    body_lines = []
    for f in fields:
        line = _field_init(f, frozen, locals, self_name, slots)
        if line:
            body_lines.append(line)

    # Does this class have a post-init function?
    if has_post_init:
        params_str = ','.join(f.name for f in fields
                              if f._field_type is _FIELD_INITVAR)
        body_lines.append(f'{self_name}.{_POST_INIT_NAME}({params_str})')
```

每个字段一行赋值语句，拼成 `def __init__(self, x, y=2, tags=_HAS_DEFAULT_FACTORY):` 这样的源码；`_create_fn`（同文件）把文本 `exec(txt, globals, ns)` 成真函数——所以 dataclass 的 `__init__` 和手写的一样快，错误信息（"non-default argument follows default argument"）也是生成时显式检查的。

exec 产物是一个**普通函数**，dis 一下就能看到（3.11.15 实跑，对上面的 `P`）：

```text
              0 COPY_FREE_VARS           2

  2           2 RESUME                   0

  3           4 LOAD_FAST                1 (x)
              6 LOAD_FAST                0 (self)
              8 STORE_ATTR               0 (x)

  4          18 LOAD_FAST                2 (y)
             20 LOAD_FAST                0 (self)
             22 STORE_ATTR               1 (y)

  5          32 LOAD_FAST                3 (tags)
             34 LOAD_DEREF               4 (_HAS_DEFAULT_FACTORY)
             36 IS_OP                    0
             38 POP_JUMP_FORWARD_IF_FALSE    10 (to 60)
             40 PUSH_NULL
             42 LOAD_DEREF               5 (_dflt_tags)
             44 PRECALL                  0
             48 CALL                     0
             58 JUMP_FORWARD             1 (to 62)
        >>   60 LOAD_FAST                3 (tags)
        >>   62 LOAD_FAST                0 (self)
             64 STORE_ATTR               2 (tags)
             74 LOAD_CONST               0 (None)
             76 RETURN_VALUE
```

- `STORE_ATTR x/y`：普通 `self.x = x`；
- `tags` 字段走 `default_factory`：实参是哨兵 `_HAS_DEFAULT_FACTORY` 时才 `CALL` `_dflt_tags`（捕获的 `list`），否则用传入值——`field(default_factory=)` 的惰性就在这一个 `IS_OP` 分支里；
- `COPY_FREE_VARS`/`LOAD_DEREF`：哨兵与 factory 都是闭包 cell（11 篇的机制）。

## 常见陷阱

1. **以为注解会运行期校验**：`def f(x: int)` 传 `str` 不报错——要校验用 pydantic 或手写检查，注解只是文档。
2. **dataclass 可变默认值**：`items: list = []` 直接 `ValueError`（定义时炸，好事）；但 `items: list = field(default=list)` 写错成 `default=` 又把同一个 list 共享给所有实例——`default` 与 `default_factory` 一字之差。
3. **`runtime_checkable` Protocol 只查方法存在**：`isinstance(obj, P)` 不校验签名与返回类型——别把结构检查当类型安全保证。
4. **TypedDict 与 dataclass 混用**：`AgentGraphState` 的实例是 `dict`——`state.x` 属性访问会 `AttributeError`，必须 `state["x"]`；反过来 dataclass 实例没有 `.get()`。读图节点代码时永远记住 state 是 dict。
5. **`get_type_hints` 会真的查名字**：future import 下注解是字符串，调用方解析时才炸 `NameError`——`Optional["UsageRecorder"]` 在 `get_type_hints` 路径下同样需要 `TYPE_CHECKING` 导入对运行期可见。grep 本仓 `backend/` 没有业务代码调用 `get_type_hints`（截至本文写作）；第三方库是否会解析这些注解取决于库的实现，不能据此断言"永远安全"。
6. **以为 `Generic`/`TypeVar` 有运行期约束**：`Box()` 不传类型参数、`Box[int]()` 传错类型的值，运行期都照常——泛型参数只服务静态检查器，不是运行期泛型（这点与 Java/C# 的直觉不同）。

## 项目代码精读

> 本节代码块均按仓库原文摘录（保留原缩进与注释），依赖项目内部上下文，**不可单独运行**。

### `rag/interfaces.py`：Protocol 抽象层

`backend/app/rag/interfaces.py`（docstring 有省略）：

```python
class ChatModel(Protocol):
    async def generate(
        self,
        messages: List[Dict[str, str]],
        *,
        usage: Optional["UsageRecorder"] = None,
        stage: str = "",
    ) -> str:
        """Generate a chat completion from provider-native message dictionaries.

        ...
        """
```

- `ChatModel`/`EmbeddingModel`/`QueryRewriter` 是 Protocol——`DirectOpenAIChatModel` 等实现**不继承**它们，只要签名对得上就算实现（结构化匹配）；
- `Optional["UsageRecorder"]`：引号前向引用（运行期名字不存在，见 07 篇）；
- 这层 Protocol 让 `retrieval_service`/`providers` 只面向接口编程——替换实现不改调用方。

### `storage/interfaces.py` `DatabaseProvider`

```python
class DatabaseProvider(Protocol):
    name: str
    # SQL placeholder token. SQLite uses "?" and PostgreSQL uses "%s".
    placeholder: str
    # True when the backend has a separately maintained FTS virtual table.
    # PostgreSQL uses a generated tsvector column instead.
    supports_manual_fts: bool
    # Column that stores the chunk embedding. SQLite stores JSON text in
    # `embedding_json`; PostgreSQL stores a vector in `embedding`.
    embedding_column: str
    # SQL cast appended to the metadata placeholder on insert. SQLite keeps
    # the column as TEXT; PostgreSQL needs "::jsonb" to bind into JSONB.
    metadata_cast: str
    # SQL cast appended to the embedding placeholder on insert. SQLite keeps
    # the text; PostgreSQL needs "::vector" to bind into VECTOR.
    embedding_cast: str

    def init(self) -> None:
        """Initialize schema and backend-specific connection settings."""

    def connect(self) -> AbstractContextManager[Any]:
        """Return a context manager yielding a DB-API style connection."""

    def close(self) -> None:
        """Release process-lifetime resources held by the provider."""
```

- 方言差异全部参数化为**字段注解**：`placeholder`（`%s` vs `?`）、`metadata_cast`（`::jsonb` vs 空）、`embedding_cast`（`::vector` vs 空）——业务代码读 `self.ph`/`provider.metadata_cast` 而不 `if backend == ...`；
- `connect() -> AbstractContextManager[Any]`：返回值类型本身就是"上下文管理器"的注解（08 篇呼应）；
- `PostgresProvider`（`class PostgresProvider:`）与 `SQLiteProvider` 都**不继承** `DatabaseProvider`——结构化 Protocol 的意义：接口在消费方定义，实现方零依赖。`db.py` 的 `get_database_provider() -> DatabaseProvider` 返回注解指它。

### `core/async_cache.py`：`Generic[K, V]`

```python
    def __init__(self, ttl_seconds: float, maxsize: int = 256) -> None:
        self.ttl_seconds = ttl_seconds
        self.maxsize = maxsize
        self._values: "OrderedDict[K, tuple[float, V]]" = OrderedDict()
        self._inflight: Dict[K, asyncio.Task[V]] = {}
        self._lock = asyncio.Lock()

    async def get_or_create(self, key: K, factory: Callable[[], Awaitable[V]]) -> V:
```

- 类声明 `class AsyncTTLCache(Generic[K, V])`——`K`/`V` 是 TypeVar，`get_or_create` 签名把键/工厂/返回值类型串起来；
- `OrderedDict[K, tuple[float, V]]` 引号注解（值是 `(到期时刻, 缓存值)` 元组）；`Callable[[], Awaitable[V]]` 是"无参返回 awaitable"的工厂类型。

### `task_service.ManagedTask` 与 `tracing.RequestTrace`

`backend/app/services/task_service.py` `ManagedTask`：

```python
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
```

- 字段全部带注解；`events`/`subscribers`/`done` 用 `default_factory`——每个 `ManagedTask` 自己的 list 与 `Event`；
- `Optional[asyncio.Task]`、`Optional[ChatResponse]`、`Optional[BaseException]`：`Optional` 在 dataclass 字段里就是"可为 None 的普通字段"；
- `service: RagProductService`：注解指向业务类（前向依赖靠 `from __future__ import annotations` 或引号解决）。

`core/tracing.py`：

```python
@dataclass
class RequestTrace:
    request_id: str = field(default_factory=lambda: f"trace_{uuid.uuid4().hex[:12]}")
    started_ms: float = field(default_factory=now_ms)
    stages_ms: Dict[str, float] = field(default_factory=dict)
    metadata: Dict[str, Any] = field(default_factory=dict)
    fallback_reason: str = ""
    # 用量累加器：与 stages_ms 同构（请求级、只累加），不进 metadata，
    # 避免把逐条明细灌进 chat_trace 日志——日志里只落 totals() 汇总。
    usage: UsageRecorder = field(default_factory=UsageRecorder)
```

- `default_factory` 的三种形态：lambda（拼随机 id）、无参函数（`now_ms`）、类（`UsageRecorder`/`dict`/`list`）——都是"每次实例化新调"；
- `fallback_reason: str = ""` 普通默认值；`usage` 注解为 `UsageRecorder`——同模块导入的具体类。

### `AgentGraphState`：TypedDict 不是 dataclass

`backend/app/agent/graph.py`：

```python
class AgentGraphState(MessagesState):
    # 已执行的工具调用轮数（tools 节点每次 +1），用于循环硬上限。
    tool_rounds: int
    tokens_used: int
    # L5-G/D：planner 上游调用次数、累计耗时（ms）、软截止命中标记、
    # finalize 触发原因（none/max_rounds/token_budget/deadline）。
    planner_calls: int
    planner_ms: float
    deadline_hit: bool
    finalize_reason: str
```

- `MessagesState` 实测（.venv）是 `typing_extensions._TypedDictMeta`——**`AgentGraphState` 是 TypedDict 子类不是 dataclass**：实例就是 `dict`，`state["messages"]`、`state.get("deadline_hit")` 的读写都是 dict 操作；
- 类体里只有注解没有赋值——TypedDict 的字段只声明键与类型，不像 dataclass 那样生成 `__init__`；
- 基类 `MessagesState` 带 `messages: Annotated[list[AnyMessage], add_messages]`——`Annotated` 把"合并函数"挂在类型上，这就是 LangGraph 节点返回 `{"messages": [...]}` 会被追加而不是覆盖的机制（节点返回的是**部分更新**，框架按注解里的 reducer 合并进 state）。

### `rag/types.py` 与 `usage.py` 的 dataclass

`backend/app/rag/types.py` `SourceChunk`：

```python
@dataclass
class SourceChunk:
    section: str
    content: str
    metadata: Dict[str, str]
```

`backend/app/core/usage.py` `UsageEntry`：

```python
@dataclass(frozen=True)
class UsageEntry:
    """一次上游花费（或一次显式的「零调用」）。token 为 None 表示未知。"""

    kind: str
    stage: str
    usage_source: str
    model: Optional[str] = None
    prompt_tokens: Optional[int] = None
    completion_tokens: Optional[int] = None
    total_tokens: Optional[int] = None
    call_count: int = 1
    duration_ms: Optional[int] = None
```

- `SourceChunk`/`RetrievedChunk`/`AnswerDraft` 是最朴素的 dataclass——纯数据搬运；
- `UsageEntry` 是 `frozen=True` + 大量 `Optional[...] = None` 字段 + 默认值：`token 为 None 表示未知` 的语义直接写在类型里（`Optional[int]`）；
- 同文件的 `UsageRecorder` 是**普通类**——它有方法、有累加状态、要可变，dataclass 的"值对象"语义不合适：选不选 dataclass 的分水岭是"这个类是不是一堆字段 + 几乎无行为"。

## 练习

1. **读项目代码回答**：`storage/interfaces.py` 的 `PostgresProvider` 没有 `class PostgresProvider(DatabaseProvider)` 继承关系，`db.py` 却写 `-> DatabaseProvider`。这种"不继承也能当返回值"的合法依据是什么？如果 `PostgresProvider` 少了 `placeholder` 字段会怎样？
2. **读项目代码回答**：`ManagedTask` 里 `events: List[dict] = field(default_factory=list)` 如果改成 `events: List[dict] = []` 会发生什么（报错还是共享）？给出实测报错原文。
3. **动手写代码**：写一个 `TypedDict` `TaskRow`：`id: str`、`status: str`、`pending: bool`；再用 `dataclass` 写一个等价的 `TaskRec`，分别造一个实例，说明"dict 键访问 vs 属性访问"的差异。
4. **动手写代码**：写一个 `@runtime_checkable` 的 `Closable` Protocol（要求 `close() -> None`），验证 `PostgresProvider` 风格的类（有 `close` 方法不继承）`isinstance` 判定为 True；再写一个无 `close` 的类验证 False。
5. **读项目代码回答**：`RequestTrace.usage` 字段注解是 `UsageRecorder` 且 `default_factory=UsageRecorder`。`UsageRecorder` 不是 dataclass 也不影响做 factory——`default_factory` 只要求什么？

## 参考答案

<details><summary>参考答案 1</summary>

`DatabaseProvider` 是 `Protocol`——结构化子类型，判定标准是"有没有接口要求的成员"而非继承关系：`PostgresProvider` 有 `placeholder`、`connect`、`init`、`close` 等成员，静态检查器就认它是 `DatabaseProvider`；`-> DatabaseProvider` 注解在运行期不校验，靠检查器/测试保证。若 `PostgresProvider` 少了 `placeholder`，静态检查会报"不满足 Protocol"，运行期则在首次读 `self.ph`/拼 SQL 时 `AttributeError`——接口不匹配在运行期的代价与静态检查的发现时机之差，正是 Protocol 静态检查的价值。

</details>

<details><summary>参考答案 2</summary>

会**报错**而不是共享——3.11.15 实测原文：`ValueError: mutable default <class 'list'> for field events is not allowed: use default_factory`。dataclass 在生成 `__init__` 时检查字段默认值类型，`list`/`dict`/`set` 直接拒绝，逼你用 `default_factory`——这比 01 篇"可变默认值悄悄共享"友好得多：错误在类定义时炸，不在运行时潜伏。

</details>

<details><summary>参考答案 3</summary>

```python
from typing import TypedDict
import dataclasses

class TaskRow(TypedDict):
    id: str
    status: str
    pending: bool

@dataclasses.dataclass
class TaskRec:
    id: str
    status: str
    pending: bool

row: TaskRow = {"id": "t1", "status": "pending", "pending": True}
rec = TaskRec(id="t1", status="pending", pending=True)
print(row["status"], rec.status)
print(type(row), type(rec))
```

实跑输出：`pending pending` 与 `<class 'dict'> <class '__main__.TaskRec'>`。`TaskRow` 实例就是 dict——`row["status"]` 下标访问、可以和任意 dict 混用；`TaskRec` 是真对象——`rec.status` 属性访问、有 `__eq__`/`__repr__`。选哪个看"要 dict 的灵活性还是对象的封装"：`AgentGraphState` 选 TypedDict 因为 LangGraph 节点之间传的就是 dict。

</details>

<details><summary>参考答案 4</summary>

```python
from typing import Protocol, runtime_checkable

@runtime_checkable
class Closable(Protocol):
    def close(self) -> None: ...

class PostgresLike:
    def close(self) -> None:
        pass

class NoClose:
    pass

print(isinstance(PostgresLike(), Closable), isinstance(NoClose(), Closable))
```

实跑输出：`True False`。`runtime_checkable` 让 `isinstance` 检查"方法存在"（不查签名/返回类型）——`PostgresLike` 不继承 `Closable` 也判 True，正是 `PostgresProvider`/`SQLiteProvider` 与 `DatabaseProvider` 的关系。

</details>

<details><summary>参考答案 5</summary>

`default_factory` 只要求**无参可调用**——类、`lambda`、普通函数、任何 callable 都行；`UsageRecorder()` 每次实例化调一次构造器。是否 dataclass 与它无关：factory 的职责是"每个实例各造一个新默认值"，`UsageRecorder` 是带累加器的对象，每个 `RequestTrace` 要独立的一份——与 `events`/`metadata` 用 `list`/`dict` 同一道理。

</details>

## 延伸阅读

- `typing` 模块文档：`Optional`/`Union`/`Callable`/`TypeVar`/`Generic`/`Protocol`/`TypedDict`/`Annotated`/`get_type_hints`。
- PEP 484（类型注解总纲）、PEP 526（变量注解）、PEP 544（Protocol）、PEP 589（TypedDict）、PEP 563（postponed evaluation）、PEP 585（内置泛型）、PEP 604（`X | Y`）、PEP 612/646 相关。
- `dataclasses` 模块文档（`field`/`frozen`/`order`/`slots`/`kw_only`/`__post_init__`/`InitVar`）。
- pydantic v2 文档 "Models" 章节（与 dataclass 的语义对照）。
