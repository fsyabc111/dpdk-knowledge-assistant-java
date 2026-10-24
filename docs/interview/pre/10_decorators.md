# 10 装饰器

## 学习目标

- 理解"函数是一等对象"：`@deco` 等价于 `f = deco(f)`，能在 dis 里看出应用顺序；
- 会写通用包装器 `(*args, **kwargs)`，知道 `functools.wraps` 做了什么（`__name__`/`__doc__`/`__wrapped__`）；
- 会写带参数的装饰器（三层函数）与叠加装饰器（离函数最近的先应用）；
- 认识"注册型"装饰器（只登记不改函数）；认识类装饰器（`@dataclass` 指向 12 篇）；
- 知道异步函数的装饰器 wrapper 必须是 `async def`；
- 掌握 `lru_cache`：用法、`cache_info`/`cache_clear`、参数必须可哈希、默认 `maxsize=128`；
- 知道函数可以挂属性（`db.py` 的 `cache_clear` 垫片）。

**本主题在项目里出现在哪**

| 位置 | 用到什么 |
| --- | --- |
| `backend/app/api/chat.py` | `@router.post("/chat/stream")` 注册型装饰器 |
| `backend/app/main.py` | `@app.middleware("http")`（`security_headers`）、`@app.on_event("startup"/"shutdown")` |
| `backend/app/config.py` `get_settings` | `@lru_cache`（裸写无括号）：进程级单例 Settings；测试用 `get_settings.cache_clear()` 重置 |
| `backend/app/agent/tools.py` `build_tools` | `@tool` 五个工具装饰器；末尾 `kb_search.description = (...)` 按配置改写描述 |
| `backend/app/db.py` | `get_database_provider.cache_clear = _clear_database_provider_cache`：给普通函数挂属性做兼容垫片 |
| `backend/app/storage/postgres.py`/`sqlite.py` `connect` | `@contextmanager`（见 08 篇） |
| `backend/app/services/task_service.py` `ManagedTask` 等 | `@dataclass`（见 12 篇）、`@property`（见 06 篇） |

## 语法详解

### 装饰器 = 语法糖

```python
@deco
def f():
    pass
```

**逐字**等价于：

```python
def f():
    pass
f = deco(f)
```

`deco` 是任意可调用对象：拿到函数对象、返回一个新（或同一个）对象再绑回 `f` 这个名字。没有魔法——能起作用只因为函数是一等对象，可以当参数传、当返回值拿、绑属性。

### 通用包装器

```python
def deco(f):
    def w(*a, **k):
        return f(*a, **k) * 2
    return w

@deco
def base():
    return 21

print(base())
print(base.__name__, base.__doc__)
```

输出：`42` 与 `w None`

问题立刻出现：`base.__name__` 变成了 `"w"`、`__doc__` 丢失——外层函数的元信息被 wrapper 顶掉了。

### `functools.wraps`

```python
import functools

def deco2(f):
    @functools.wraps(f)
    def w(*a, **k):
        return f(*a, **k) * 2
    return w

@deco2
def base2():
    """docstring here"""
    return 21

print(base2.__name__, base2.__doc__, base2.__wrapped__.__name__)
```

输出：`base2 docstring here base2`

`wraps` 把被包函数的元信息抄到 wrapper 上，并挂上 `__wrapped__` 指回原函数——`inspect.unwrap`、pytest、help() 都靠它。本项目 `@contextmanager` 内部用了 `wraps`（下文 Lib 摘录可证）；`@tool` 不一样——`inspect.getsource` 看 `langchain_core.tools.tool` 的实现，它是把函数包成 `StructuredTool` 对象而不是 wrapper 函数链，没有 `wraps`。

### 带参数的装饰器（三层）

```python
def tag(name):                      # 第 1 层：收装饰器参数
    def deco(f):                    # 第 2 层：收被装饰函数
        @functools.wraps(f)
        def w(*a, **k):             # 第 3 层：运行时包装
            print("tag", name)
            return f(*a, **k)
        return w
    return deco

@tag("outer")
@tag("inner")
def f3():
    print("body")

f3()
```

输出（3.11.15 实跑）：

```text
tag outer
tag inner
body
```

`@tag("outer")` 先求值 `tag("outer")` 得到 `deco`，再 `f3 = deco(f3)`——`@router.post("/chat/stream")`、`@app.on_event("startup")` 都是这种**装饰器工厂**形态：方法调用先返回一个装饰器，再应用到函数上（它们注册后原样返回函数，不包 wrapper，见下节"注册型"）。

### 叠加顺序：离函数最近的先应用

```python
calls = []
def rec(label):
    def deco(f):
        calls.append(label)
        return f
    return deco

@rec("A")
@rec("B")
def f4():
    pass
```

`calls` 实跑为 `['B', 'A']`——`@rec("B")`（离函数近）先应用，结果再交给 `@rec("A")`。等价于 `f4 = rec("A")(rec("B")(f4))`。

### 注册型装饰器

不改函数、只把它登记进一张表（可运行的迷你版，**仅为示意模式，不是 FastAPI 的实现**）：

```python
class Router:
    def __init__(self):
        self.routes = {}
    def post(self, path):
        def deco(f):
            self.routes[path] = f
            return f                      # 原样返回：登记而非包装
        return deco

r = Router()

@r.post("/chat/stream")
async def chat_stream():
    return "ok"

print(list(r.routes), r.routes["/chat/stream"])
```

输出：`['/chat/stream'] <function chat_stream at 0x...>`。`@router.post`、`@app.on_event` 都是这个模式——装饰器把函数注册进框架的路由表/事件表，函数本身保持原样。

### 异步函数的装饰器

`async def` 的函数被调用时返回 coroutine——wrapper 必须也是 `async def`（或至少返回 awaitable），否则包装后拿到的是"coroutine 对象"而不是"执行结果"：

```python
def traced(f):
    @functools.wraps(f)
    async def w(*a, **k):            # 必须是 async
        return await f(*a, **k)
    return w
```

本项目 agent 工具函数全是 `async def`（`@tool` 对协程函数生成 async 工具），`@app.middleware("http")` 包装的 `security_headers` 也是 `async def`。

### `lru_cache`

```python
import functools

@functools.lru_cache
def slow(n):
    return n * n

slow(3); slow(3); slow(4)
print(slow.cache_info())
slow.cache_clear()
print(slow.cache_info())
```

输出：`CacheInfo(hits=1, misses=2, maxsize=128, currsize=2)` 与 `CacheInfo(hits=0, misses=0, maxsize=128, currsize=0)`

- `maxsize` 默认 **128**（实测 `cache_parameters()` → `{'maxsize': 128, 'typed': False}`）；`maxsize=None` 为无上限；
- 缓存键 = 实参元组 → **参数必须可哈希**：`slow([1])` 抛 `TypeError: unhashable type: 'list'`；
- `@lru_cache` 裸写（无括号）与 `@lru_cache()` 等价；`cache_clear`/`cache_info` 是挂在 wrapper 上的方法；
- 并发更新安全：纯 Python 版（`Lib/functools.py` 的 `_lru_cache_wrapper`，见下文摘录）用 `RLock` 保护链表操作；运行时实际生效的是 C 实现（`Modules/_functoolsmodule.c`），接口相同、实现独立——别把 Python 版源码里的锁当成运行时事实来引用。

### 函数可以挂属性

函数是对象，可以直接绑属性：

```python
def myfunc():
    pass
myfunc.cache_clear = lambda: print("shim")
myfunc.cache_clear()      # shim
```

`db.py` 里 `get_database_provider.cache_clear = _clear_database_provider_cache` 就是这个手法：给非 lru_cache 的普通函数**手动补一个 cache_clear 接口**，让测试侧可以统一写 `xxx.cache_clear()` 重置单例。

## 运行机制与 CPython 实现

### 字节码：装饰器的应用顺序

以下输出由 `backend/.venv/bin/python`（Python 3.11.15）实跑 `dis.dis` 得到（对 `compile` 出的模块代码）：

```python
def deco(f):
    return f
@deco
@deco
def target():
    pass
```

```text
  1           2 LOAD_CONST               0 (<code object deco at 0x1053d21f0, file "<x>", line 1>)
              4 MAKE_FUNCTION            0
              6 STORE_NAME               0 (deco)

  3           8 LOAD_NAME                0 (deco)

  4          10 LOAD_NAME                0 (deco)

  5          12 LOAD_CONST               1 (<code object target at 0x1053d2370, file "<x>", line 3>)
             14 MAKE_FUNCTION            0

  4          16 PRECALL                  0
             20 CALL                     0

  3          30 PRECALL                  0
             34 CALL                     0

  5          44 STORE_NAME               1 (target)
             46 LOAD_CONST               2 (None)
             48 RETURN_VALUE
```

行号列直观展示了顺序：`LOAD_NAME deco`（行 3，外层）先求值、`LOAD_NAME deco`（行 4，内层）再求值——**装饰器表达式自上而下求值**；然后 `MAKE_FUNCTION` 造函数，行 4 的 `CALL` 先应用、行 3 的 `CALL` 后应用——**应用自下而上**，最后 `STORE_NAME target` 绑定。与等价式 `target = deco(deco(target))` 完全对应。

### Lib：`update_wrapper` 与 `_lru_cache_wrapper`

本机 3.11.15 `inspect.getsource` 取出的 `Lib/functools.py` 真实源码：

`update_wrapper`（`functools.wraps` 的底层，`wraps(f)` = `partial(update_wrapper, wrapped=f)`）：

```python
    for attr in assigned:
        try:
            value = getattr(wrapped, attr)
        except AttributeError:
            pass
        else:
            setattr(wrapper, attr, value)
    for attr in updated:
        getattr(wrapper, attr).update(getattr(wrapped, attr, {}))
    # Issue #17482: set __wrapped__ last so we don't inadvertently copy it
    # from the wrapped function when updating __dict__
    wrapper.__wrapped__ = wrapped
    return wrapper
```

`WRAPPER_ASSIGNMENTS = ('__module__', '__name__', '__qualname__', '__doc__', '__annotations__')`，`WRAPPER_UPDATES = ('__dict__',)`——抄五个元信息 + 合并 `__dict__` + 最后挂 `__wrapped__`。

`lru_cache` 的**纯 Python 版** `_lru_cache_wrapper`（同文件，运行时被 C 版遮蔽——`Lib/functools.py` 尾部 `from _functools import _lru_cache_wrapper` 用 C 实现覆盖同名对象；摘录 Python 版是为了看清算法）：

```python
def _lru_cache_wrapper(user_function, maxsize, typed, _CacheInfo):
    # Constants shared by all lru cache instances:
    sentinel = object()          # unique object used to signal cache misses
    make_key = _make_key         # build a key from the function arguments
    PREV, NEXT, KEY, RESULT = 0, 1, 2, 3   # names for the link fields

    cache = {}
    hits = misses = 0
    full = False
    cache_get = cache.get    # bound method to lookup a key or return None
    cache_len = cache.__len__  # get cache size without calling len()
    lock = RLock()           # because linkedlist updates aren't threadsafe
    root = []                # root of the circular doubly linked list
    root[:] = [root, root, None, None]     # initialize by pointing to self
    ...
    def wrapper(*args, **kwds):
        # Size limited caching that tracks accesses by recency
        nonlocal root, hits, misses, full
        key = make_key(args, kwds, typed)
        ...
```

- `make_key(args, kwds, typed)` 把实参拼成键——参数不可哈希在这里炸 `TypeError`；
- `cache` dict + 环形双向链表做 LRU 淘汰，`RLock` 保证并发更新安全；
- `wrapper` 是闭包：`cache`/`hits`/`misses`/`root` 全是外层作用域捕获（见 11 篇）。

### CPython 源码

- `lru_cache` 的 C 实现在 CPython 3.11 分支 `Modules/_functoolsmodule.c`：`lru_cache_object` 结构体、`lru_cache_make_key`（键构造，含不可哈希参数报错路径）、`uncached_lru_cache_wrapper`——与 Python 版同算法。

## 常见陷阱

1. **忘写 `functools.wraps`**：`__name__`/`__doc__` 全丢——日志、测试发现、工具描述生成都会错。
2. **带参装饰器少一层**：`@tag` 与 `@tag("x")` 是两种形态；把 `tag("x")` 写成 `tag` 会把函数对象当成 `name` 传进去。
3. **装饰器叠加顺序记反**：等价式 `f = A(B(f))`——**下先上后**；求值顺序才是自上而下。
4. **async 函数配 sync wrapper**：`def w` 包装的 `async def` 调出来是 coroutine 对象不是结果——全仓异步装饰器的 wrapper 都是 `async def`。
5. **`lru_cache` 参数不可哈希**：`list`/`dict` 实参当场 `TypeError`；含 `self` 的方法上用它会把 `self` 也键化（实例级缓存 = 每个实例一份 + 强引用泄漏）。
6. **注册型装饰器的副作用时机**：`@router.post` 在**导入时**执行登记——模块被 import 就会改路由表，重复导入不会重复登记（sys.modules 缓存，见 07 篇）。
7. `lru_cache` 缓存的是**返回值不是副本**：返回可变对象被调用方改了会污染缓存。

## 项目代码精读

> 本节代码块均按仓库原文摘录（保留原缩进与注释），依赖项目内部上下文，**不可单独运行**。

### `api/chat.py` 的 `@router.post`

`backend/app/api/chat.py`：

```python
@router.post("/chat/stream")
async def chat_stream(request: Request, principal: Principal = Depends(require_auth)) -> StreamingResponse:
```

- `@router.post("/chat/stream")` 是三层装饰器：`router.post("/chat/stream")` 先求值得到 deco，`chat_stream = deco(chat_stream)`——`deco` 把函数按 path 登记进 `router` 的路由表再原样返回；
- 模块导入时即完成注册：`main.py` 末尾 `app.include_router(chat.router)` 把表挂进 app——"装饰器在 import 时产生副作用"是框架装配的常规手法。

### `main.py`：`@app.middleware("http")` 与 `@app.on_event`

`backend/app/main.py`：

```python
@app.middleware("http")
async def security_headers(request: Request, call_next):
    response = await call_next(request)
    response.headers["X-Content-Type-Options"] = "nosniff"
    response.headers["X-Frame-Options"] = "DENY"
    response.headers["Referrer-Policy"] = "no-referrer"
    response.headers["Content-Security-Policy"] = "default-src 'self'"
    return response
```

同文件（`backend/app/main.py`，有省略）：

```python
@app.on_event("startup")
def on_startup() -> None:
    ...
```

- `@app.middleware("http")`：注册型 + 协议函数——框架接管后每个请求都会经 `security_headers(request, call_next)` 这个签名调用它；函数本体必须是 `async def`（`await call_next`）；
- `@app.on_event("startup")` / `@app.on_event("shutdown")`：把 `on_startup`/`start_rag_maintenance`/`stop_rag_maintenance` 登记进生命周期表——同步/异步函数都可以登记（FastAPI 按签名处理）；
- 三个都是"带参装饰器工厂"——参数（`"http"`/`"startup"`）进了 `deco` 的闭包，与被装饰函数一起进框架注册表。

### `config.py` 的 `@lru_cache` 单例

`backend/app/config.py`：

```python
@lru_cache
def get_settings() -> Settings:
    return Settings()
```

- 裸 `@lru_cache`（无括号，`maxsize=128` 默认）：`get_settings()` 第一次构造 `Settings()`，之后所有调用命中同一个对象——**进程级单例**；
- `pydantic-settings` 的构造要读环境变量/`.env`，缓存把"读配置"成本摊到一次；
- 测试怎么重置：`tests/conftest.py` 每个用例 fixture 里 `get_settings.cache_clear()` + `get_database_provider.cache_clear()`——`lru_cache` 自带的 `cache_clear` 配 `db.py` 手工挂的垫片，接口统一；
- 注意 `@lru_cache` 的键是"无参空元组"——只有一个缓存槽位，`cache_clear` 清掉的是整个单例。

### `agent/tools.py` 的 `@tool` 与描述改写

`backend/app/agent/tools.py` `build_tools`：

```python
    @tool
    async def kb_search(query: str) -> Dict[str, Any]:
```

同文件 `build_tools` 后段（`backend/app/agent/tools.py`）：

```python
    # L5-A：kb_search 的模型侧描述按 AGENT_KB_LLM_REWRITE 装配——true 保持
    # L5 前 docstring 原样（@tool 装饰器已取自函数 docstring，不动）；
    # false 换成引导英文代码关键词 + 单次合并调用的本地模式描述。
    if not settings.agent_kb_llm_rewrite:
        kb_search.description = (
            "检索项目知识库，回答协议原理、插件接口、项目结构等事实性问题。\n\n"
            "参数 query：直接写英文检索关键词——代码标识符（如 can_handle、"
            "session_entry_t）、文件名（如 engine_dns.cpp）、协议英文缩写"
            "（如 DNS、SMTP）。多个维度用 `;` 合并进**一次**调用，不要拆成多次"
            "（每请求检索次数有限）。返回编号片段列表，作答时用 [编号] 引用。"
        )
    return [kb_search, list_protocols, list_cases, plugin_template, grade_plugin]
```

- `@tool` 是 LangChain 的注册型+包装装饰器：从**函数签名**生成工具参数 schema、从 **docstring** 生成工具描述，返回 `StructuredTool` 对象赋回 `kb_search` 这个名字——名字还是 `kb_search`，但对象已不是原函数；
- `kb_search.description = (...)`：装饰完之后再**改写生成物的属性**——开关关时换掉模型侧看到的工具描述（代码里五个 `@tool` 都是这样装出来的）；
- 这正说明装饰器的返回值不必是函数：`@tool` 返回工具对象，工具协议由 LangChain 定义。

### `db.py` 的 `cache_clear` 垫片

`backend/app/db.py`：

```python
get_database_provider.cache_clear = _clear_database_provider_cache  # type: ignore[attr-defined]
```

- `get_database_provider` 是普通函数（`global` + `with _provider_lock` 惰性单例），没有 `cache_clear`——这行**给函数对象挂属性**，补上与 `lru_cache` 相同的重置接口；
- `conftest.py` 里 `get_settings.cache_clear()` 与 `get_database_provider.cache_clear()` 写法对称——读者不用关心一个是 lru_cache 自带、一个是手工垫片；
- `# type: ignore[attr-defined]`：类型检查器不认"给函数对象绑属性"，显式豁免（12 篇）。

## 练习

1. **读项目代码回答**：`tools.py` 里 `kb_search.description = (...)` 为什么不直接改 `kb_search` 函数的 docstring？`@tool` 装饰完成后 `kb_search` 这个名字绑的还是原函数吗？
2. **读项目代码回答**：`main.py` 里 `@app.middleware("http")` 与 `@app.on_event("startup")` 装饰的函数（`security_headers`、`on_startup`、`start_rag_maintenance`）被装饰后名字是否还需要？框架之后怎么调用它们？
3. **动手写代码**：写一个装饰器 `retry(times)`：包装函数失败时按 `times` 重试。要求支持 `@retry(3)` 用法、`functools.wraps` 保留元信息、能包装普通函数。
4. **动手写代码**：写一个注册型装饰器 `command(name)`：把函数登记进全局 `COMMANDS` dict，原样返回。验证 `@command("hello")` 后 `COMMANDS["hello"]()` 可调。
5. **读项目代码回答**：`db.py` 的 `get_database_provider.cache_clear = _clear_database_provider_cache` 与 `config.py` `get_settings` 自带的 `cache_clear` 有何异同？为什么 conftest 里两行 `cache_clear()` 调用看起来一样？

## 参考答案

<details><summary>参考答案 1</summary>

`@tool` 装饰完成后，`kb_search` 这个名字绑的是 **StructuredTool 对象**（LangChain 包装产物），不再是原函数——改函数 docstring 已经够不到工具对象上的 `description` 字段，而且 docstring 在装饰时已被读取并固化进 schema。代码注释"（@tool 装饰器已取自函数 docstring，不动）"明确了这个关系：描述来自 docstring 的**拷贝**，改 docstring 无意义，要改就改工具对象的 `description` 属性。这也是"装饰器返回值可以是任意对象"的活例——`kb_search` 仍能被调用（`tool.ainvoke`），但身份已换成工具对象。

</details>

<details><summary>参考答案 2</summary>

装饰后函数**名字本身不重要了**——框架在 `deco` 里拿到函数对象、登记进内部表（middleware 链、startup 回调列表），之后经表里的引用调用，跟 `security_headers`/`on_startup` 这个模块级名字无关。这也是注册型装饰器的特点：名字只是注册那一刻的入口，留着它主要是给人读代码与调试定位，运行时经表调用。

</details>

<details><summary>参考答案 3</summary>

```python
import functools

def retry(times):
    def deco(f):
        @functools.wraps(f)
        def w(*a, **k):
            last = None
            for _ in range(times):
                try:
                    return f(*a, **k)
                except Exception as e:
                    last = e
            raise last
        return w
    return deco

calls = {"n": 0}
@retry(3)
def flaky():
    calls["n"] += 1
    if calls["n"] < 3:
        raise ValueError("x")
    return "ok"

print(flaky(), flaky.__name__, calls["n"])
```

实跑输出：`ok flaky 3`。三层结构：`retry(times)` 收参数 → `deco(f)` 收函数 → `w` 是运行时包装；`wraps` 保住 `__name__ == "flaky"`；`last` 保留最后一次异常重抛。

</details>

<details><summary>参考答案 4</summary>

```python
COMMANDS = {}

def command(name):
    def deco(f):
        COMMANDS[name] = f
        return f
    return deco

@command("hello")
def hello():
    return "hi"

@command("bye")
def bye():
    return "see ya"

print(COMMANDS["hello"](), COMMANDS["bye"](), sorted(COMMANDS))
```

实跑输出：`hi see ya ['bye', 'hello']`。`deco` 把函数塞进 `COMMANDS` 后**原样返回**——`hello` 这个名字仍可直接调，与 `COMMANDS["hello"]` 指向同一函数对象；`@router.post`/`@app.on_event` 就是这个模式的框架化版本。

</details>

<details><summary>参考答案 5</summary>

同：两者都是"给函数对象挂一个 `cache_clear` 方法，清掉进程级缓存态"，调用方写法完全一致 `xxx.cache_clear()`。异：`get_settings` 的 `cache_clear` 是 `lru_cache` 装饰器**自动生成**在 wrapper 上的（标准接口）；`get_database_provider` 是普通函数，`cache_clear` 是**手工挂的属性**，指向同文件的 `_clear_database_provider_cache`（内部再调 `close_database_provider`）。对称的调用写法是刻意的——conftest 里两行连着出现，读者不必分辨"哪个是 lru_cache 自带、哪个是垫片"；`# type: ignore[attr-defined]` 只为让类型检查器接受"给函数绑属性"这一手。

</details>

## 延伸阅读

- Python 官方教程：无独立章节，见 "Defining Functions" 相关部分与 glossary 的 decorator 词条。
- PEP 318（装饰器原始提案）、PEP 614（装饰器语法放宽）。
- `functools` 模块文档：`wraps`/`update_wrapper`/`lru_cache`/`cached_property`/`partial`。
- `contextlib` 文档的 `contextmanager`（08 篇）；dataclasses 文档（12 篇）。
