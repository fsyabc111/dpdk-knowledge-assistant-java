# 06 类、对象与描述符

## 学习目标

- 会写 `class`、`__init__`、`self`，分清实例属性与类属性（含可变类属性陷阱）；
- 会用继承、`super()`、`__mro__` 读方法解析顺序；
- 会用常用特殊方法：`__repr__` / `__eq__` / `__hash__` / `__len__` / `__bool__`；
- 分清 `@property` / `@classmethod` / `@staticmethod` 三种"方法形态"的语义差异；
- 理解描述符协议 `__get__` / `__set__`、属性查找顺序（数据描述符 > 实例 `__dict__` > 非数据描述符 > 类属性）；
- 知道"方法绑定"就是函数描述符的 `__get__`，能用纯 Python 写出 `property` 的等价实现；
- 了解 `__slots__`。

**本主题在项目里出现在哪**

| 位置 | 用到什么 |
| --- | --- |
| `backend/app/services/rag_issue_service.py` `RagIssueService.ph` | `@property`：把"当前方言占位符"伪装成属性读 |
| `backend/app/core/llm_client.py` `OpenAICompatibleClient` | `ClassVar` 类属性 + `@classmethod`：进程级共享 `httpx.AsyncClient` 连接池（`_get_llm_client` / `_get_embedding_client` / `close_clients` / `get_llm_http_client`） |
| `backend/app/agent/mcp_client.py` `HarnessMcpClient._parse_result` | `@staticmethod`：不需要 `self`/`cls` 的纯函数挂类上 |
| `backend/app/storage/postgres.py` `PostgresProvider.schema_path` | `@property`：`Path(__file__).with_name("postgres_schema.sql")` |
| `backend/app/services/task_service.py` `ManagedTask`、`core/tracing.py` `RequestTrace` | `@dataclass`（详见 `12` 篇）：`field(default_factory=...)` 正是规避"可变类属性"的正解 |
| `backend/app/agent/mcp_client.py` `get_harness_mcp_client` | 模块级 `_clients` dict + 锁：另一种"进程级共享"形态（对照 llm_client 的类属性方案） |

## 语法详解

### class、`__init__`、`self`

```python
class Counter:
    def __init__(self, start=0):
        self.n = start            # 实例属性：每个对象一份

    def incr(self):
        self.n += 1
        return self.n

c = Counter()
print(c.incr(), c.n)
```

输出：`1 1`

- `class` 语句执行后产生一个**类对象**（本身也是对象，类型是 `type`）；
- `Counter()` 调用时：先造空实例，再以该实例为 `self` 调 `__init__`——`__init__` 不返回值；
- `self` 不是关键字，只是约定名：`c.incr()` 实际是 `Counter.incr(c)`（见下文"方法绑定"）。

### 实例属性 vs 类属性

```python
class Base:
    kind = "base"                    # 类属性：所有实例共享
    def __init__(self):
        self.tags = []               # 实例属性：每个实例独立

a, b = Base(), Base()
a.tags.append("x")
print(a.tags, b.tags, Base.kind, a.kind)
```

输出：`['x'] [] base base`

`a.kind` 读到的是**类**的 `kind`（实例 `__dict__` 里没有就往上找）。**可变类属性陷阱**：

```python
class Team:
    members = []                     # 类属性，所有实例共享同一个 list！

a, b = Team(), Team()
a.members.append("x")
print(b.members)                     # ['x'] —— b 也看到了
```

`a.members.append` 没有给 `a` 建实例属性——它原地改了那个共享 list。要"每实例一份"必须在 `__init__` 里赋值（`self.members = []`）。dataclass 的 `field(default_factory=list)` 就是为这个场景准备的。

### 继承、`super()`、MRO

```python
class Base:
    kind = "base"
    def who(self):
        return "base"
    def describe(self):
        return f"I am {self.who()}"

class Child(Base):
    kind = "child"
    def who(self):
        return "child"

c = Child()
print(c.describe())                  # I am child —— 动态分派
print(Child.__mro__)
```

输出：

```text
I am child
(<class '__main__.Child'>, <class '__main__.Base'>, <class 'object'>)
```

- `self.who()` 沿 `__mro__` 从实例的真实类开始找——`describe` 虽然定义在 `Base`，跑的却是 `Child.who`（虚方法语义，和 C++/Java 一致）；
- `super()` 返回"按 MRO 找下一个"的代理：`super().who()` 在 `Child` 实例上找到 `Base.who`；多继承时 MRO 用 C3 线性化，读 `__mro__` 即可。

### 常用特殊方法

```python
class Counter:
    def __init__(self):
        self.n = 0
    def __len__(self):
        return self.n
    def __bool__(self):
        return self.n > 0
    def __repr__(self):
        return f"Counter({self.n})"
    def __eq__(self, other):
        return isinstance(other, Counter) and self.n == other.n

ct = Counter()
print(len(ct), bool(ct), repr(ct), ct == Counter())
```

输出：`0 False Counter(0) True`

- `__repr__` 给开发者看（交互解释器、`!r`、日志），约定写成"能 eval 回去"的形态；
- `__eq__` 自定义后 `__hash__` 默认变 `None`（对象不可哈希）——要当 dict 键/set 成员需同时定义 `__hash__`，且"相等的对象哈希必须相同"；
- `__len__`/`__bool__` 接进真值体系（见 `04` 篇）。

### `@property` / `@classmethod` / `@staticmethod`

```python
class Demo:
    _pool = None                              # 类属性

    @property
    def ph(self):                             # 读属性一样读方法
        return "%s"

    @classmethod
    def get_pool(cls):                        # 拿 cls 不拿 self
        if cls._pool is None:
            cls._pool = []
        return cls._pool

    @staticmethod
    def parse(text):                          # 只是借类的命名空间
        return text.split(",")

d = Demo()
print(d.ph, Demo.get_pool() is Demo.get_pool(), Demo.parse("a,b"))
```

输出：`%s True ['a', 'b']`

- `@property`：`d.ph` 不加括号就执行方法——把"需要算一下"的取值伪装成字段；
- `@classmethod`：第一个参数是**类本身**——`cls._pool` 操作的是类属性，天然做"进程级单例"；子类调用时 `cls` 是子类；
- `@staticmethod`：不拿 `self`/`cls`，逻辑上属于这个类但不需要访问它——纯工具函数挂类上组织命名空间。

## 运行机制与 CPython 实现

### 描述符协议

任何实现了 `__get__`（可选 `__set__`/`__delete__`）的对象都是**描述符**。`obj.attr` 的查找顺序（`type(obj).__mro__` 上找 `attr` 后）：

1. 找到的类属性是**数据描述符**（同时有 `__get__` + `__set__`，如 `property`）→ 调它的 `__get__`；
2. 否则看实例 `__dict__` 有没有 `attr`；
3. 否则用那个**非数据描述符**（只有 `__get__`，如普通函数、`classmethod`、`staticmethod`、`functools.cached_property`）；
4. 否则就是那个普通类属性。

**函数就是描述符**——这就是方法绑定的全部秘密：

```python
class C:
    def m(self):
        return 42

obj = C()
bound = C.m.__get__(obj)          # 手动触发描述符协议
print(bound, bound())
print(C.m.__get__(None, C))       # 不传实例 → 原函数
```

输出（3.11.15 实跑，地址每次不同）：

```text
<bound method C.m of <__main__.C object at 0x1039e9690>> 42
<function C.m at 0x1039ff100>
```

`obj.m` 本质上是 `C.m.__get__(obj, C)`——`__get__` 返回一个把 `obj` 绑成第一参数的"绑定方法"。`self` 参数就是这样被塞进去的，不需要任何关键字。

### 等价改写：纯 Python 的 property

`property` 就是一个数据描述符类。用纯 Python 写一个能跑的等价物：

```python
class MyProperty:
    def __init__(self, fget):
        self.fget = fget
    def __get__(self, obj, objtype=None):
        if obj is None:               # 经类访问（Circle.area）返回描述符自身
            return self
        return self.fget(obj)
    def __set__(self, obj, value):
        raise AttributeError("can't set attribute")

class Circle:
    def __init__(self, r):
        self._r = r
    @MyProperty
    def area(self):
        return 3.14 * self._r ** 2

print(Circle(2).area)     # 12.56
print(Circle.area)        # <MyProperty object>：经类访问拿到描述符
```

`property` 比它多 `fset`/`fdel`/`doc` 与 `getter`/`setter`/`deleter` 方法，但骨架相同。因为 `MyProperty` 有 `__set__`，它是**数据描述符**——优先于实例 `__dict__`，所以 `circle.area = 9` 会走 `__set__` 抛错而不是塞进实例字典。

对照：**非数据描述符**可被实例属性覆盖——`functools.cached_property` 正是利用这点：首次 `__get__` 算出值后写入 `instance.__dict__`，之后实例字典命中、不再走描述符。本机 3.11.15 `inspect.getsource` 取出的 `Lib/functools.py` 真实源码（摘录）：

```python
class cached_property:
    def __init__(self, func):
        self.func = func
        self.attrname = None
        self.__doc__ = func.__doc__
        self.lock = RLock()

    def __get__(self, instance, owner=None):
        if instance is None:
            return self
        if self.attrname is None:
            raise TypeError(
                "Cannot use cached_property instance without calling __set_name__ on it.")
        try:
            cache = instance.__dict__
        except AttributeError:  # not all objects have __dict__ (e.g. class defines slots)
            ...
        val = cache.get(self.attrname, _NOT_FOUND)
        if val is _NOT_FOUND:
            with self.lock:
                val = cache.get(self.attrname, _NOT_FOUND)
                if val is _NOT_FOUND:
                    val = self.func(instance)
                    cache[self.attrname] = val
        return val
```

没有 `__set__`——所以它故意依赖"实例 `__dict__` 优先于非数据描述符"来缓存。

### CPython 源码

- **方法绑定**：CPython 3.11 分支 `Objects/funcobject.c` 的 `func_descr_get`——`PyFunction_Type` 的 `tp_descr_get`；`obj` 非 `NULL` 时 `return PyMethod_New(func, obj)`（造绑定方法），`NULL` 时返回函数自身。同一文件还有 `func_get_defaults`/`func_get_kwdefaults`（见 `01` 篇）。
- **property**：`Objects/descrobject.c` 的 `property_descr_get` / `property_descr_set` 挂进 `PyProperty_Type` 的 `tp_descr_get`/`tp_descr_set`——`property` 是货真价实的数据描述符；`property_init` 存 `fget/fset/fdel`。
- **属性查找**：`obj.attr` 的完整查找（数据描述符 → 实例 `__dict__` → 非数据描述符）实现在 `Objects/object.c` 的 `_PyObject_GenericGetAttrWithDict`（即 `object.__getattribute__`/`PyObject_GenericGetAttr` 的主体，其中用 `_PyType_Lookup` 沿 MRO 找类属性）；经类访问（`Cls.attr`）走 `Objects/typeobject.c` 的 `type_getattro`；同文件的 `lookup_maybe_method` 是解释器内部做**特殊方法查找**用的辅助函数（如 `len(x)` 找 `__len__`），不是 `obj.attr` 的普通路径。

### `__slots__`

```python
class S:
    __slots__ = ("x",)
    def __init__(self):
        self.x = 1

s = S()
try:
    s.y = 2
except AttributeError as e:
    print("slots err:", e)
```

输出：`slots err: 'S' object has no attribute 'y'`

`__slots__` 声明固定的实例属性集：实例没有 `__dict__`，省内存、快访问，但不能动态加属性（上例），也不能用需要 `__dict__` 的机制（如 `cached_property`，源码里专门处理这个 `AttributeError`）。

## 常见陷阱

1. **可变类属性被当实例属性改**：`self.items.append(x)` 改的是类属性（所有实例共享）——要独立就 `self.items = []` 在 `__init__` 里赋值。
2. **`__eq__` 与 `__hash__` 必须一致**：只定义 `__eq__` 会让对象不可哈希；`set`/`dict` 键行为立刻变。
3. **`@property` 里抛 `AttributeError`**：会被 `__getattr__`/鸭子类型探测误吞成"没有该属性"——property 内部别让它逃逸。
4. **`@classmethod` 的 `cls` 是调用者的类**：通过子类调用时，`cls._x = v` 会在**子类**上新建/覆盖属性（遮蔽父类同名类属性），而不是改父类那份——`Sub._get_llm_client()` 会让 `Sub` 惰性建出自己的池。本项目 `OpenAICompatibleClient` 没有子类，所以 `_llm_client` 实际上每进程一份；但若未来出现子类，"进程级共享"会静默退化为"每类一份"。
5. **`staticmethod` 不能访问 `self`/`cls`**：需要状态就写普通方法或 `classmethod`。
6. **`obj.method` 每次访问都新建绑定方法对象**：`a.m == a.m` 为 `True`（比较的是函数+self），但 `a.m is a.m` 为 `False`。
7. `super()` 在单继承里直觉正确，多继承走 MRO 不是"父类"——读 `__mro__` 别猜。

## 项目代码精读

> 本节代码块均按仓库原文摘录（保留原缩进与注释），依赖项目内部上下文，**不可单独运行**。

### `RagIssueService.ph`：property 做方言占位符

`backend/app/services/rag_issue_service.py`：

```python
class RagIssueService:
    @property
    def ph(self):
        return get_database_provider().placeholder
```

- `ph` 是 PG `%s` 或 SQLite `?` 的占位符——每次 `self.ph` 都重新查当前 provider（运行期决定，不是类加载时常量），所以做成 `@property` 而不是类属性；
- 调用处 `ph = self.ph` 先取到局部变量再用——一次属性访问（一次方法调用 + 一次 provider 查找）缓存到栈上，循环里不再重复查；
- 经类访问 `RagIssueService.ph` 得到的是 `property` 对象本身（描述符的 `__get__(None, cls)` 返回 self）——这就是为什么 `self.ph` 能用而 `RagIssueService.ph` 不是字符串。

`backend/app/services/rag_issue_service.py` SQL 拼接：

```python
                f"INSERT INTO rag_requests(id,request_id,input_hash,initial_request,user_id,session_id,question,"
                f"context_text,model_version,created_at,updated_at) VALUES ({','.join([ph] * 11)}) "
```

`[ph] * 11` 复制 11 份占位符再 `join`——占位符本身也是"查出来的方言值"不是写死的 `%s`。

### `OpenAICompatibleClient`：ClassVar + classmethod 进程级池

`backend/app/core/llm_client.py`（有省略）：

```python
    _llm_client: ClassVar[httpx.AsyncClient | None] = None
    _embedding_client: ClassVar[httpx.AsyncClient | None] = None

    def __init__(self, settings: Settings):
        self.settings = settings

    ...

    @classmethod
    def _build_client(cls, timeout_seconds: float) -> httpx.AsyncClient:
        return httpx.AsyncClient(
            timeout=httpx.Timeout(timeout_seconds, connect=10.0),
            limits=httpx.Limits(max_connections=200, max_keepalive_connections=50),
        )

    @classmethod
    def _get_llm_client(cls) -> httpx.AsyncClient:
        if cls._llm_client is None:
            cls._llm_client = cls._build_client(90.0)
        return cls._llm_client

    @classmethod
    def get_llm_http_client(cls) -> httpx.AsyncClient:
        """Expose the process-wide client for adapters such as LlamaIndex."""
        return cls._get_llm_client()

    ...

    @classmethod
    async def close_clients(cls) -> None:
        if cls._llm_client is not None:
            await cls._llm_client.aclose()
            cls._llm_client = None
        ...
```

- `_llm_client`/`_embedding_client` 是 `ClassVar` 类属性：**进程级唯一**的 httpx 连接池，所有实例共享；
- `_get_llm_client` 是 `@classmethod`：`cls._llm_client` 惰性初始化——第一次用时建池，之后全进程复用（连接池的价值就在复用 TCP/TLS 连接）；
- `get_llm_http_client` 是公开的 classmethod 门面：LlamaIndex 等外部适配器也拿同一个池；
- `close_clients` 关池并置 `None`：shutdown 钩子里调用（`main.py`），幂等（再调一次 `is not None` 为 False 直接跳过）；
- `cls._build_client(90.0)` 与 `cls._llm_client = ...` 都经 `cls` 读写类属性：池挂在**第一次调用时 `cls` 指向的那个类**上——本项目只有 `OpenAICompatibleClient` 一个类，池即进程级唯一（子类调用的后果见陷阱 4）。

### `HarnessMcpClient._parse_result`：staticmethod

`backend/app/agent/mcp_client.py`：

```python
    @staticmethod
    def _parse_result(tool_name: str, result: Any) -> Dict[str, Any]:
        # FastMCP 对 dict 返回值同时给出 structuredContent 与文本化 JSON。
        structured: Optional[Dict[str, Any]] = getattr(result, "structuredContent", None)
        if isinstance(structured, dict):
            return structured
        texts = [
            getattr(item, "text", "")
            for item in (getattr(result, "content", None) or [])
            if getattr(item, "text", "")
        ]
        payload = "\n".join(texts)
        if getattr(result, "isError", False):
            return {"ok": False, "error": "harness_tool_error", "detail": payload[:2000]}
        try:
            parsed = json.loads(payload)
        except (json.JSONDecodeError, ValueError):
            ...
            return {"ok": False, "error": "harness_bad_response", "detail": payload[:2000]}
        if isinstance(parsed, dict):
            return parsed
        return {"ok": True, "data": parsed}
```

- 解析逻辑不碰 `self`（`self.url`、连接状态一概不用）——纯函数，挂 `@staticmethod` 声明"不需要实例"；
- 调用处 `self._parse_result(tool_name, result)` 照样能写（实例调用时 `staticmethod.__get__` 不绑定 self，只传显式参数）；
- 全 `getattr` duck-typing（见 `04` 篇）——MCP 结果对象的字段有无都用默认值收敛。

### `PostgresProvider.schema_path`：只读派生路径

`backend/app/storage/postgres.py`：

```python
    @property
    def schema_path(self) -> Path:
        return Path(__file__).with_name("postgres_schema.sql")
```

- `__file__` 是本模块文件路径，`with_name` 换文件名——schema.sql 永远跟着源码走，不依赖启动时 cwd；
- 派生值每次现算（`Path` 构造极廉价），只读——没写 `@schema_path.setter`，外部赋值会走 `property` 的 `__set__` 抛 `AttributeError`。

## 练习

1. **读项目代码回答**：`llm_client.py` 中 `_llm_client` 是 `ClassVar[httpx.AsyncClient | None]`。如果把它改成实例属性（`self._llm_client` 在 `__init__` 里赋值），`get_llm_http_client` 还能工作吗？连接池语义会变成什么？
2. **读项目代码回答**：`rag_issue_service.py` `ph` 是 `@property` 而非普通属性 `self.ph = ...` 在 `__init__` 里赋值。除了"每次拿到当前 provider"，还有什么工程理由让它不能是 `__init__` 时算好的实例属性？
3. **动手写代码**：写一个 `Registry` 类：类属性 `_items`（dict）存注册表；`@classmethod register(cls, name, obj)` 登记、`@classmethod get(cls, name)` 查询（要求类与实例上都能调用）；再加一个 `@property count`，返回当前已注册的条目数。验证：`Registry.register("a", 1)`、`r = Registry(); r.get("a")`、`r.count` 都能跑，且再登记一条后 `r.count` 变为 2。
4. **动手写代码**：写一个非数据描述符 `Lazy`：`def __init__(self, func)`；`__get__` 首次调用 `func(instance)` 并把结果写进 `instance.__dict__[name]`（在 `__set_name__` 里记 `name`），之后直接读缓存。用它实现 `class Node: @Lazy def children(self): ...`，并验证两次访问只算一次、且 `node.__dict__` 里能看到缓存键。
5. **读项目代码回答**：`mcp_client._parse_result` 是 `@staticmethod`，而 `_call_once`/`call_tool` 是普通方法。如果把 `_parse_result` 改成普通方法（第一个参数 `self`），功能有区别吗？项目为什么仍选 staticmethod？

## 参考答案

<details><summary>参考答案 1</summary>

不能。`get_llm_http_client` 是 `@classmethod`，拿的是 `cls` 不是实例——类属性才能在无实例处（LlamaIndex 适配器、`close_clients` 关机钩子）访问到池。改成实例属性后：每次 `OpenAICompatibleClient(settings)` 新建一个 `AsyncClient`——每个客户端一条连接池，TLS 握手与 keep-alive 全废，全进程连接数随实例数翻倍（`Limits(max_connections=200)` 是每个池 200）；且没有统一入口关池（`close_clients` 遍历不到散落的实例）。`ClassVar` + `classmethod` 就是"进程级单例资源"的标准形态。

</details>

<details><summary>参考答案 2</summary>

provider 是**运行期可变**的：看 `backend/app/db.py` 的实现——`get_database_provider()` 首次调用时加锁惰性创建进程级单例 `_provider`，而 `close_database_provider()`（及其别名 `get_database_provider.cache_clear`，测试切后端时用）会把它重置为 `None`，下次调用重建。若在 `__init__` 里把 `ph` 算成实例属性，它会钉死**构造那一刻**那个 provider 的方言——provider 重置/更换后，旧实例仍拿旧占位符拼 SQL，静默出错。`@property` 把求值推迟到每次读取时，永远跟着 `get_database_provider()` 的当前值走；同时 `RagIssueService()` 保持无参轻量构造（`task_service._run_with_deadline` 等路径直接 `RagIssueService()` 即用），构造期不需要 provider 已就绪。

</details>

<details><summary>参考答案 3</summary>

```python
class Registry:
    _items = {}

    @classmethod
    def register(cls, name, obj):
        cls._items[name] = obj

    @classmethod
    def get(cls, name):
        return cls._items.get(name)

    @property
    def count(self):
        return len(type(self)._items)

Registry.register("a", 1)
r = Registry()
print(r.get("a"), Registry.get("a"), r.count)
Registry.register("b", 2)
print(r.count)
```

实跑输出：`1 1 1` 与 `2`。`count` 演示了"实例 property 读共享类属性"：`self` 上没有 `_items`，经 `type(self)._items`（或 `self._items`，实例查找回落到类属性）拿到类级 dict——这正是"实例方法操作类级共享状态"的形态；`register`/`get` 挂 `@classmethod` 使 `Registry.register(...)` 与 `r.register(...)` 都可用。

</details>

<details><summary>参考答案 4</summary>

```python
class Lazy:
    def __init__(self, func):
        self.func = func
    def __set_name__(self, owner, name):
        self.name = name
    def __get__(self, instance, owner=None):
        if instance is None:
            return self
        return instance.__dict__.setdefault(self.name, self.func(instance))

class Node:
    calls = 0
    @Lazy
    def children(self):
        type(self).calls += 1
        return ["c1", "c2"]

n = Node()
print(n.children, n.children)   # 两次相同，func 只跑一次
print(Node.calls, "children" in n.__dict__)
```

实跑输出：`['c1', 'c2'] ['c1', 'c2']` 与 `1 True`。`Lazy` 只有 `__get__` 没有 `__set__` → 非数据描述符 → 首次写入 `instance.__dict__["children"]` 后，后续访问被实例字典命中、描述符不再执行——这正是 `functools.cached_property` 的机制（它还用锁处理并发，演示版省略）。

</details>

<details><summary>参考答案 5</summary>

功能上没区别：改成普通方法后 `self._parse_result(...)` 一样能调，因为 `self` 只是多传一个没用的参数。选 `staticmethod` 是**语义声明**：向读者声明"这个函数不依赖实例状态"，意味着可安全地在类外/测试中独立调用、重构时不用担心隐藏依赖；同时它让"哪些是状态、哪些是纯函数"在阅读时一目了然——`call_tool` 走 `self.url` 有状态，`_parse_result` 是纯转换。同样的判断标准也适用于 `ph`（要 `self`→property）与 `_parse_result`（不要 `self`→staticmethod）。

</details>

## 延伸阅读

- Python 官方教程："Classes"（实例/类变量、继承、私有变量约定）。
- 语言参考："Special method names"（`__init__`/`__repr__`/`__eq__`/`__hash__`/`__len__`/`__bool__`/`__slots__`）、"Implementing Descriptors"（`__get__`/`__set__`/`__set_name__` 与调用优先级）、"Invoking Descriptors"。
- `types.MethodType`、`super()`、`property`/`classmethod`/`staticmethod` 内置文档；"Descriptor HowTo Guide"（Python howto 中关于 property/方法绑定的完整推导）。
- PEP 487（`__set_name__`）、PEP 252/253（new-style class 与描述符原始设计）。
