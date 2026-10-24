# 08 `with` 与上下文管理器

## 学习目标

- 掌握上下文管理器协议：`__enter__` / `__exit__(exc_type, exc, tb)`，`__exit__` 返回真值会**吞掉**异常；
- 会写 PEP 343 的等价展开（正常与异常两条路径）；
- 会用 `with a, b:` 多项写法，知道 `as` 绑定的是 `__enter__` 的**返回值**而不是管理器对象；
- 理解 `contextlib.contextmanager` 的实现：`next(gen)` / `gen.throw(...)`、为什么"只能 yield 一次"、为什么 yield 外面要包 `try/finally`；
- 认识 `contextlib.suppress` / `ExitStack`，知道 `threading.Lock` 也是上下文管理器；
- 认读 3.11 `with` 的字节码（`BEFORE_WITH` / `WITH_EXCEPT_START` / ExceptionTable）；
- `async with` 只点到为止，详见 `15_asyncio_3_sync_primitives_and_async_iter.md`。

**本主题在项目里出现在哪**

| 位置 | 用到什么 |
| --- | --- |
| `backend/app/core/tracing.py` `StageTimer` / `timed_stage` | 手写 `__enter__`/`__exit__` 的计时器；`__exit__` 返回 `None`（不吞异常）但耗时照记 |
| `backend/app/storage/postgres.py` `PostgresProvider.connect` | `@contextmanager` 包 `pool.connection()`，`yield` 之后才 `conn.commit()` |
| `backend/app/storage/sqlite.py` `SQLiteProvider.connect` | `@contextmanager` + `try/finally` 关连接 |
| `backend/app/db.py` `get_database_provider` / `close_database_provider` / `get_conn` | `with _provider_lock:`（`threading.Lock` 作上下文管理器）；`get_conn` 返回 provider 的连接管理器 |
| `backend/app/services/rag_index_service.py` `_publish` | 整个 `with get_conn() as conn:` 块是一笔事务，CAS 失败抛异常整笔回滚 |
| `backend/app/agent/mcp_client.py` `_call_once` | `async with streamablehttp_client(...) as (...)` + `async with ClientSession(...)` 嵌套 |

## 语法详解

### 协议：`__enter__` / `__exit__`

任何带这两个方法的对象都能进 `with`：

```python
class CM:
    def __enter__(self):
        print("enter")
        return "res"          # as 绑定的就是这个返回值
    def __exit__(self, exc_type, exc, tb):
        print("exit sees:", exc_type)
        return True           # 返回真值 → 吞掉异常

with CM() as x:
    print("body got:", x)
    raise ValueError("swallowed")
print("after")
```

输出（3.11.15 实跑）：

```text
enter
body got: res
exit sees: <class 'ValueError'>
after
```

三个参数是异常三元组（`sys.exc_info()` 那三个）：正常退出时全是 `None`。**`__exit__` 返回真值 = "异常已处理，不要传播"**；返回 `None`/`False` = 照常抛出。把 `return True` 换成 `return False`，`ValueError` 就传出去：

```python
class CM2:
    def __enter__(self):
        return self
    def __exit__(self, t, e, tb):
        return False
try:
    with CM2():
        raise KeyError("k")
except KeyError:
    print("propagated")
```

输出：`propagated`

注意 `with CM() as x:` 里 `x` 拿到的是 `__enter__` 的**返回值**（上例 `"res"`），不是 `CM` 实例——拿不到管理器本身时调试经常在这里看错。

### `with a, b:` 多项与退出顺序

```python
class Named:
    def __init__(self, n): self.n = n
    def __enter__(self): print(f"enter {self.n}"); return f"resource-{self.n}"
    def __exit__(self, *a): print(f"exit {self.n}"); return False

with Named("A") as ra, Named("B") as rb:
    print("body:", ra, rb)
```

输出：

```text
enter A
enter B
body: resource-A resource-B
exit B
exit A
```

等价于嵌套 `with`：**enter 按书写顺序，exit 反序**（栈式释放）。

### `threading.Lock` 也是上下文管理器

```python
import threading
lock = threading.Lock()
with lock:
    print("locked:", lock.locked())
print("unlocked:", lock.locked())
```

输出：`locked: True` / `unlocked: False`——`__enter__` 即 `acquire()`、`__exit__` 即 `release()`。`db.py` 的 `with _provider_lock:` 就是这个用法。`asyncio.Lock` 对应 `async with`（见 15 篇）。

## 运行机制与 CPython 实现

### 等价展开（PEP 343）

```python
with mgr as x:
    body(x)
```

等价于（可运行的改写，正常与异常两条路径都演示）：

```python
import sys

class CM2:
    def __enter__(self):
        print("enter")
        return self
    def __exit__(self, t, e, tb):
        print("exit sees:", t)
        return False

def with_equiv(body):
    mgr = CM2()                          # 1) 求值管理器表达式
    exit_ = type(mgr).__exit__           # 2) 经类型取 __exit__（不经实例 __dict__）
    value = type(mgr).__enter__(mgr)     # 3) 调 __enter__，as 绑定返回值
    exc = True
    try:
        try:
            body(value)
        except:                          # PEP 343 原文是裸 except（异常谱系全抓）
            exc = False
            if not exit_(mgr, *sys.exc_info()):
                raise                    # __exit__ 返回假 → 重抛
    finally:
        if exc:
            exit_(mgr, None, None, None) # 正常路径：__exit__(None, None, None)

with_equiv(lambda v: print("body ok"))
try:
    with_equiv(lambda v: 1 / 0)
except ZeroDivisionError:
    print("reraised")
```

输出：

```text
enter
body ok
exit sees: None
enter
exit sees: <class 'ZeroDivisionError'>
reraised
```

两个细节值得记：特殊方法经**类型**查找（实例上放 `__exit__` 属性无效，同 `__len__` 等）；`__exit__` 的判定结果决定是否 `raise`——"吞异常"的全部魔法就在 `if not exit_(...)` 这一行。

### 字节码：BEFORE_WITH / WITH_EXCEPT_START

以下输出由 `backend/.venv/bin/python`（Python 3.11.15）实跑 `dis.dis` 得到：

```python
def use_cm(cm):
    with cm as x:
        work(x)
    return x
```

```text
  5           0 RESUME                   0

  6           2 LOAD_FAST                0 (cm)
              4 BEFORE_WITH
              6 STORE_FAST               1 (x)

  7           8 LOAD_GLOBAL              1 (NULL + work)
             20 LOAD_FAST                1 (x)
             22 PRECALL                  1
             26 CALL                     1
             36 POP_TOP

  6          38 LOAD_CONST               0 (None)
             40 LOAD_CONST               0 (None)
             42 LOAD_CONST               0 (None)
             44 PRECALL                  2
             48 CALL                     2
             58 POP_TOP
             60 JUMP_FORWARD            11 (to 84)
        >>   62 PUSH_EXC_INFO
             64 WITH_EXCEPT_START
             66 POP_JUMP_FORWARD_IF_TRUE     4 (to 76)
             68 RERAISE                  2
        >>   70 COPY                     3
             72 POP_EXCEPT
             74 RERAISE                  1
        >>   76 POP_TOP
             78 POP_EXCEPT
             80 POP_TOP
             82 POP_TOP

  8     >>   84 LOAD_FAST               1 (x)
             86 RETURN_VALUE
ExceptionTable:
  6 to 36 -> 62 [1] lasti
  62 to 68 -> 70 [3] lasti
  76 to 76 -> 70 [3] lasti
```

逐条读（行号 5–8 来自验证脚本中的位置）：

- `BEFORE_WITH`：调 `type(cm).__enter__` 并把 `__exit__` 留在栈上备用；
- body 正常跑完后，三个 `LOAD_CONST None` + `CALL 2`：就是 `__exit__(None, None, None)`；
- body 抛异常 → ExceptionTable `6 to 36 -> 62` 跳到 `PUSH_EXC_INFO` + `WITH_EXCEPT_START`（调 `__exit__(*exc_info)`）；
- `POP_JUMP_FORWARD_IF_TRUE`：`__exit__` 返回真 → 跳到 76 清理后**正常继续**（异常被吞）；返回假 → `RERAISE` 重抛；
- 与 `try/finally` 相同的零开销结构：正常路径没有额外指令，全靠 ExceptionTable 路由。

### `contextlib.contextmanager` 的实现

`@contextmanager` 把一个"yield 一次"的生成器函数变成上下文管理器。本机 3.11.15 `inspect.getsource` 取出的 `Lib/contextlib.py` 真实源码：

```python
def contextmanager(func):
    @wraps(func)
    def helper(*args, **kwds):
        return _GeneratorContextManager(func, args, kwds)
    return helper
```

关键在 `_GeneratorContextManager.__enter__` / `__exit__`（同文件，有省略）：

```python
    def __enter__(self):
        del self.args, self.kwds, self.func
        try:
            return next(self.gen)
        except StopIteration:
            raise RuntimeError("generator didn't yield") from None

    def __exit__(self, typ, value, traceback):
        if typ is None:
            try:
                next(self.gen)
            except StopIteration:
                return False
            else:
                try:
                    raise RuntimeError("generator didn't stop")
                finally:
                    self.gen.close()
        else:
            ...
            try:
                self.gen.throw(typ, value, traceback)
            except StopIteration as exc:
                return exc is not value
            ...
```

读法：

- `__enter__` = `next(self.gen)`——生成器函数体跑到 `yield`，yield 出的值就是 `as` 绑定值；没 yield 就 `RuntimeError("generator didn't yield")`；
- `__exit__(None, ...)`（正常路径）= 再 `next(gen)`——期望生成器**自然结束**（`StopIteration`），返回 `False`（不吞异常，本来也没有）；如果它又 yield 了一次 → `RuntimeError("generator didn't stop")`，这就是"**只能 yield 一次**"的硬约束来源；
- `__exit__(typ, value, tb)`（异常路径）= `self.gen.throw(typ, value, traceback)`——把 body 里的异常**扔进生成器的 yield 点**：生成器里 `yield` 语句变成"抛出该异常"的位置。若生成器用 `try/except` 接住了它并正常结束，`gen.throw` 返回的 `StopIteration` 与传入异常不是同一对象 → `return True` **吞掉异常**；若生成器没接住（或接住后又抛出同一异常），异常继续传播；
- 推论：在 `@contextmanager` 函数里，`yield` 必须包在 `try/finally` 里——body 抛异常时异常会从 yield 点冒出来，没有 `finally` 你的清理代码就跳过了（这就是 `sqlite.connect` 写 `try: yield conn finally: conn.close()` 的原因）。

C 层：`gen.throw` 的实现是 CPython 3.11 分支 `Objects/genobject.c` 的 `gen_throw`（经 `_gen_throw`/`gen_send_ex2` 把异常注入生成器 frame 的 yield 点）；`next(gen)` 走 `gen_iternext` → `gen_send_ex`。

## 常见陷阱

1. **`__exit__` 误返回真值**：`return self`/`return result` 这类顺手写法会**吞掉所有异常**——`__exit__` 默认该写 `return None`/`False`，要吞必须有意识为之（`StageTimer.__exit__` 就是显式 `-> None`）。
2. **`as` 绑定的不是管理器**：`with open(...) as f:` 的 `f` 是 `__enter__` 返回值；`StageTimer` 的 `__enter__` 返回 `self` 才顺手，不是必须。
3. **`@contextmanager` 里 yield 不包 try/finally**：body 抛异常时 yield 点变抛出点，finally 外的清理全跳过。
4. **生成器 yield 超过一次**：`RuntimeError("generator didn't stop")`。
5. **with 里 `return`**：`__exit__` 照样跑（和 finally 一样），`return` 不会跳过清理。
6. **`with` 内 `as` 变量在块外仍可见**：没有块级作用域——`x` 出了 `with` 还能读（值是 `__enter__` 的返回值）。
7. 实例属性覆盖特殊方法无效：`obj.__exit__ = fake` 不影响 `with`——协议方法经类型查找（见等价展开第 2 步）。

## 项目代码精读

> 本节代码块均按仓库原文摘录（保留原缩进与注释），依赖项目内部上下文，**不可单独运行**。

### `StageTimer` / `timed_stage`：手写协议

`backend/app/core/tracing.py`：

```python
class StageTimer:
    def __init__(self, trace: Optional[RequestTrace], name: str) -> None:
        self.trace = trace
        self.name = name
        self.started_ms = 0.0

    def __enter__(self) -> "StageTimer":
        self.started_ms = now_ms()
        return self

    def __exit__(self, exc_type: object, exc: object, tb: object) -> None:
        if self.trace is not None:
            self.trace.add_stage(self.name, now_ms() - self.started_ms)


def timed_stage(trace: Optional[RequestTrace], name: str) -> StageTimer:
    return StageTimer(trace, name)
```

- `__enter__` 记起点并返回 `self`——所以 `with timed_stage(trace, "x"):` 不带 `as` 也行；
- `__exit__` 返回 `None`（注解 `-> None`）：**任何异常照常传播**，但耗时一定会被记进 `stages_ms`——这是"计时"语义的最佳实现：不管 body 成败，账都要记；
- `trace is None` 时整个对象变成 no-op（`rag_product_service` 里 trace 可为 None，调用处不用 if 判断）；
- 调用处形态：`with timed_stage(trace, "rrf_fusion_ms"):`（`retrieval_service._fetch_candidate_rows`）。

### `PostgresProvider.connect`：yield 之后才 commit

`backend/app/storage/postgres.py`：

```python
    @contextmanager
    def connect(self) -> Iterator[object]:
        pool = self._get_pool()
        # pool.connection() checks out a connection, yields it, and returns it
        # to the pool on exit (rolling back on exception). The explicit commit
        # before return persists writes for the request.
        with pool.connection() as conn:
            yield conn
            conn.commit()
```

- 两层上下文管理器：外层是 `@contextmanager` 生成器，内层 `with pool.connection() as conn:` 是 psycopg_pool 的连接管理器（注释说明：退出时归还连接、**异常时回滚**）；
- `yield conn` 把连接交给 with-body（即 `get_conn()` 的使用方）；body 正常跑完 → `conn.commit()` 提交；
- **body 抛异常时**：异常经 `gen.throw` 从 `yield conn` 处冒出 → `conn.commit()` 被跳过 → 内层 `pool.connection()` 的 `__exit__` 收到异常 → 按注释语义回滚并归还连接——三层语义严丝合缝："commit 只在无异常时执行"。

### `SQLiteProvider.connect`：try/finally 保证关连接

`backend/app/storage/sqlite.py`：

```python
    @contextmanager
    def connect(self) -> Iterator[sqlite3.Connection]:
        self.init()
        conn = sqlite3.connect(self.settings.db_path)
        conn.row_factory = sqlite3.Row
        try:
            self._configure(conn)
            yield conn
            conn.commit()
        finally:
            conn.close()
```

- `try/finally` 包住 `yield`：`conn.close()` 无论成败都执行——这正是上节说的"yield 必须包 try/finally"模式；
- `conn.commit()` 与 PG 版同位置：异常跳过提交，SQLite 未提交事务在 `close()` 时随连接丢弃；
- `conn.row_factory = sqlite3.Row` 让 `fetchone()`/`fetchall()` 返回可下标按列名取的行（全仓 `row["status"]` 读法靠它）。

### `db.py`：`with _provider_lock` 与 `get_conn`

`backend/app/db.py`：

```python
def get_database_provider() -> DatabaseProvider:
    """Return exactly one provider while concurrent requests cold-start."""
    global _provider
    with _provider_lock:
        if _provider is None:
            _provider = _build_database_provider()
        return _provider
```

- `with _provider_lock:` 是 `threading.Lock` 的 CM 用法——保证并发冷启动只建一个 provider（双重检查在锁内完成）；
- `close_database_provider` 同款 `with _provider_lock:`：取旧 provider 并置 `None`，再出锁释放资源；
- `get_conn()` 只是 `return get_database_provider().connect()`——把上面两个 `@contextmanager` 对象**原样返回**给调用方：管理器对象本身是值，可以传来传去，直到被 `with` 消费。

### `rag_index_service._publish`：整个 with 块即事务

`backend/app/services/rag_index_service.py`：

```python
def _publish(table, expected, release, configuration, desired, changed):
    with get_conn() as conn:
        # CAS 发布锁同时阻止两个不同来源快照互相覆盖；整笔事务失败即回滚。
        updated = conn.execute(
            "UPDATE rag_index_state SET release_id=%s WHERE name='active' AND release_id=%s",
            (release, expected),
        ).rowcount
        if updated != 1:
            raise RuntimeError("另一任务已发布新版本，请重新扫描后再试")
        for node in changed:
            vector = "[" + ",".join(str(float(x)) for x in node["embedding"]) + "]"
            conn.execute(
                f"INSERT INTO {table}(node_id,text,metadata_,embedding) VALUES (%s,%s,%s::jsonb,%s::vector)",
                (node["id"], node["content"], json.dumps(node["metadata"], ensure_ascii=False), vector),
            )
        conn.execute("INSERT INTO rag_releases(id,configuration,created_at,node_count) VALUES (%s,%s,%s,%s)",
                     (release, configuration, now(), len(desired)))
        for node in desired:
            conn.execute("INSERT INTO rag_release_nodes(release_id,node_id) VALUES (%s,%s)", (release, node["id"]))
```

- `with get_conn() as conn:` 包住全部写操作：CAS 更新 → 新节点插入 → 版本行 → 成员行；
- CAS `updated != 1` → `raise RuntimeError`：异常从 with 块冒出 → `connect` 的 `yield` 点抛出 → `conn.commit()` 跳过 → 整笔回滚——"CAS 失败不留半笔发布"；
- 这就是 `with` 相比手写 `try/commit/except/rollback` 的价值：事务边界 = 代码块边界，读缩进就知道原子性范围。

## 练习

1. **读项目代码回答**：`StageTimer.__exit__` 注解是 `-> None` 且没有 return。如果某个同事把它改成 `return True`（"计时器不该让异常打断"），`retrieval_service._fetch_candidate_rows` 里 `with timed_stage(trace, "rrf_fusion_ms"):` 的行为会发生什么变化？为什么这很糟？
2. **读项目代码回答**：`PostgresProvider.connect` 里如果调用方（with-body）抛异常，`conn.commit()` 为什么不会执行？按 `gen.throw` 的机制逐步说明连接最终走到哪一步。
3. **动手写代码**：写一个手写协议的 `LogSpan`：`__enter__` 打印 `[name] begin`、`__exit__` 打印 `[name] end (<耗时>ms)` 且**不吞异常**（返回 `None`）。用正常与异常两个 `with` 块验证。
4. **动手写代码**：用 `@contextmanager` 写一个 `temp_env(key, value)`：with 内临时设置 `os.environ[key]`，退出时恢复原值（原来没有则删除）。要求 yield 包在 `try/finally` 里，并验证异常路径下环境变量也恢复了。
5. **读项目代码回答**：`db.py` 的 `get_conn()` 只写了 `return get_database_provider().connect()` 而没有 `with`/`yield`。它返回的是什么对象？调用方 `with get_conn() as conn:` 时，`connect()` 里 `@contextmanager` 的生成器函数体何时才真正开始执行？

## 参考答案

<details><summary>参考答案 1</summary>

`__exit__` 返回真值会吞掉 `with` 块内抛出的一切异常——`rrf_fusion_ms` 计时块里任何一路召回失败（FTS 查询错、store 异常）都会被静默吞掉，函数继续往下跑拿到残缺候选集却表现得像成功：调用方看不到错误、日志没有 traceback、trace 的 `fallback_reason` 也不会被设置。检索管线的降级靠的是"异常上抛 → 各层 except 收敛"的明确语义，一个返回 True 的 `__exit__` 会把整条降级链破坏成"假装没失败"。`-> None` 的注解正是要表达"只记账、不改变异常语义"。

</details>

<details><summary>参考答案 2</summary>

with-body 抛异常 → 外层 `@contextmanager` 的 `__exit__`（`_GeneratorContextManager.__exit__`）拿到异常三元组，走 `typ is not None` 分支调 `self.gen.throw(typ, value, tb)`——异常从生成器的 `yield conn` 处注入生成器函数体；`yield conn` 下一行 `conn.commit()` 被跳过（异常没在这里被 except 接住）；异常继续向上到内层 `with pool.connection() as conn:` 的 `__exit__`——psycopg_pool 的连接管理器按注释语义回滚未提交事务并把连接归还池。最终效果：连接正常归还、写入全部回滚、异常原样传给调用方。

</details>

<details><summary>参考答案 3</summary>

```python
import time

class LogSpan:
    def __init__(self, name):
        self.name = name
    def __enter__(self):
        self.t0 = time.monotonic()
        print(f"[{self.name}] begin")
        return self
    def __exit__(self, exc_type, exc, tb):
        ms = (time.monotonic() - self.t0) * 1000
        print(f"[{self.name}] end ({ms:.1f}ms)")
        return None            # 不吞异常

with LogSpan("ok"):
    pass
try:
    with LogSpan("fail"):
        raise ValueError("x")
except ValueError:
    print("caught outside")
```

实跑输出：`[ok] begin` / `[ok] end (...ms)` / `[fail] begin` / `[fail] end (...ms)` / `caught outside`——异常路径下 `end` 仍打印（耗时照记），异常传到外层被捕获，语义与 `StageTimer` 一致。

</details>

<details><summary>参考答案 4</summary>

```python
import os
from contextlib import contextmanager

@contextmanager
def temp_env(key, value):
    sentinel = object()
    old = os.environ.get(key, sentinel)
    os.environ[key] = value
    try:
        yield
    finally:
        if old is sentinel:
            os.environ.pop(key, None)
        else:
            os.environ[key] = old

os.environ.pop("DEMO_VAR", None)
with temp_env("DEMO_VAR", "on"):
    print(os.environ.get("DEMO_VAR"))
print("restored:", os.environ.get("DEMO_VAR"))
try:
    with temp_env("DEMO_VAR", "on"):
        raise RuntimeError("boom")
except RuntimeError:
    pass
print("after exc:", os.environ.get("DEMO_VAR"))
```

实跑输出：`on` / `restored: None` / `after exc: None`。`yield` 包在 `try/finally` 内：body 抛 `RuntimeError` 时 `gen.throw` 把异常注入 yield 点，`finally` 照样执行恢复逻辑——这正是 `sqlite.connect` 的同款结构。

</details>

<details><summary>参考答案 5</summary>

`get_conn()` 返回的是 `_GeneratorContextManager` 对象——`@contextmanager` 的 `helper(*args, **kwds)` 只是**造**了这个管理器对象（保存 `func/args/kwds`），生成器函数体一行都没跑。真正的"setup 代码"（`self.init()`、`sqlite3.connect(...)`/`pool.connection()` 取连接）要到调用方写 `with get_conn() as conn:` 时、`__enter__` 里的 `next(self.gen)` 才把函数体推进到 `yield conn`。也就是说连接是按需在 `with` 入口处建立的，不是 `get_conn()` 返回时建立的——`get_conn` 可以当普通值传递而不提前消耗资源。

</details>

## 延伸阅读

- Python 官方教程：无独立章节，见 "Errors and Exceptions" 中 `with` 用法示例。
- 语言参考："The with statement"（协议与 `as` 语义）、"Special method names"（`__enter__`/`__exit__`）。
- PEP 343（with 语句原始提案，含等价展开全文）、PEP 380（`yield from`，`gen.throw` 的传递语义）、PEP 479（生成器内 StopIteration 转 RuntimeError——`_GeneratorContextManager.__exit__` 里那段 StopIteration 判断的由来）。
- `contextlib` 模块文档（`contextmanager`/`suppress`/`ExitStack`/`AbstractContextManager`）。
