# 01 函数与参数

## 学习目标

- 读得懂 Python 函数定义的完整参数语法：位置参数、默认值、`*args`、`**kwargs`、仅位置参数 `/`、仅关键字参数 `*`；
- 读得懂调用处的 `*iterable`、`**mapping` 展开；
- 知道默认值**在 `def` 语句执行时只求值一次**、存在 `__defaults__` / `__kwdefaults__` 上，理解可变默认值陷阱；
- 知道"函数是对象"：能看 `__code__` 的 `co_argcount` / `co_kwonlyargcount` / `co_varnames`；
- 能认读 3.11 函数调用的字节码（`KW_NAMES` / `PRECALL` / `CALL`）。

**本主题在项目里出现在哪**

| 位置 | 用到什么 |
| --- | --- |
| `backend/app/services/rag_issue_service.py` `RagIssueService.begin_admitted` / `probe` | 签名中间的 `*,`：其后所有参数只能按关键字传 |
| `backend/app/core/tracing.py` `RequestTrace.set_meta` | `**items` 收集任意关键字参数；调用处 `trace.set_meta(fts_rewritten_candidates=..., ...)` 见 `services/retrieval_service.py` `_fetch_candidate_rows` |
| `backend/app/services/rag_product_service.py` `RagProductService.probe_replay` / `prepare` | `asyncio.to_thread(fn, 位置参数..., 关键字参数...)` 把同步函数连参数一起转发到线程池 |
| `backend/app/agent/mcp_client.py` `HarnessMcpClient.call_tool` | `tool_name, arguments` 两个普通位置参数 |

作用域与闭包（`def` 里再 `def`、内层函数捕获外层变量）本篇只点到为止，详见 `11_scope_and_closures.md`。

## 语法详解

### 位置参数与关键字参数

Python 里"调用时按名字传参"是语言级特性，不只是约定：

```python
def connect(host, port, timeout=10.0, retries=3):
    return f"{host}:{port} timeout={timeout} retries={retries}"

print(connect("db", 5432))                        # 位置
print(connect("db", 5432, timeout=5))            # 混用：关键字必须排在位置之后
print(connect(port=5432, host="db", retries=0))  # 全关键字，顺序随意
```

输出：

```text
db:5432 timeout=10.0 retries=3
db:5432 timeout=5.0 retries=3
db:5432 timeout=10.0 retries=0
```

一旦某个实参写成 `name=value` 形式，它**后面**的所有实参都必须是关键字形式（`f(1, x=2, 3)` 是 `SyntaxError`）。同一个参数不能既按位置又按关键字传：

```python
def f(a, b, c=3, *args, d, e=5, **kw):
    return a, b, c, args, d, e, kw

try:
    f(1, 2, d=1, b=9)
except TypeError as exc:
    print("dup:", exc)
```

输出：`dup: f() got multiple values for argument 'b'`。

### `*args` 与 `**kwargs`

- `*args` 收集**多余的**位置实参为一个 `tuple`；
- `**kw` 收集**多余的**关键字实参为一个新 `dict`。

接上例：

```python
def f(a, b, c=3, *args, d, e=5, **kw):
    return a, b, c, args, d, e, kw

print(f(1, 2, d=4))                  # d 无默认值，必须按关键字给
print(f(1, 2, 30, 40, 50, d=4, extra=9))
```

输出：

```text
(1, 2, 3, (), 4, 5, {})
(1, 2, 30, (40, 50), 4, 5, {'extra': 9})
```

注意：`args` 是元组不是列表；`kw` 是每次调用新建的 dict，在函数内改它不会影响调用方。`d` 是"仅关键字且无默认值"的参数——`f(1, 2)` 漏传会直接 `TypeError: f() missing 1 required keyword-only argument: 'd'`。

### 仅关键字参数：孤立的 `*`

`*args` 后面出现的参数（上例的 `d`、`e`）**只能按关键字传**。如果不想要 `*args`、只想强制"从这里开始全部按关键字传"，写一个孤立的 `*`：

```python
def probe(request_id, user_id, question, *, session_id=None, purpose="chat"):
    return request_id, session_id, purpose

try:
    probe("r1", "u1", "q", "s1")      # session_id 按位置传 → 报错
except TypeError as exc:
    print(exc)
print(probe("r1", "u1", "q", session_id="s1"))
```

输出：

```text
probe() takes 3 positional arguments but 4 were given
('r1', 's1', 'chat')
```

本项目服务层不少函数都这么写：前面三五个"是什么"按位置，后面一串"怎么算"的标志位/可选项强制按名字传——读代码时看到 `*,` 就知道右侧全是可选开关，看到调用处一长串 `x=y` 时也能对上号。

### 仅位置参数：`/`

对称地，`/` 之前的参数**只能按位置传**（3.8 起，PEP 570）：

```python
def pos_only(a, b, /, c):
    return a, b, c

try:
    pos_only(a=1, b=2, c=3)
except TypeError as exc:
    print("posonly:", exc)
print(pos_only(1, 2, c=3))
```

输出：

```text
posonly: pos_only() got some positional-only arguments passed as keyword arguments: 'a, b'
(1, 2, 3)
```

内置函数大量用它（`len(x)`、`dict.get(k, default)`）。标准库里一个典型例子是 `asyncio.to_thread(func, /, *args, **kwargs)`——`func` 强制按位置传，这样它自己的参数名永远不会和要转发的 `**kwargs` 撞名。

### 调用处的 `*` 与 `**` 展开

定义处 `*`/`**` 是"收集"，调用处是"展开"：

```python
def f(a, b, c=3, *args, d, e=5, **kw):
    return a, b, c, args, d, e, kw

args = [1, 2]
kw = {"d": 9}
print(f(*args, **kw))   # 等价于 f(1, 2, d=9)
```

输出：`(1, 2, 3, (), 9, 5, {})`。

`*x` 在调用处接受任意可迭代对象；`**x` 接受任意 mapping。这一对在"转发参数"的封装函数里极其常见：

```python
def retry(times, func, *args, **kwargs):
    for _ in range(times):
        try:
            return func(*args, **kwargs)
        except Exception:
            continue
    raise RuntimeError("all attempts failed")
```

### 默认值的求值时机

这是 Python 和其他语言差异最大的一点。**默认值在 `def` 语句执行的那一刻求值一次，存在函数对象的 `__defaults__` / `__kwdefaults__` 上**，之后所有调用共享同一个对象：

```python
def f(a, b=1, *args, c, d=2, **kw):
    pass

print(f.__defaults__)     # (1,)            —— b 的默认值
print(f.__kwdefaults__)   # {'d': 2}        —— 仅关键字参数的默认值
```

由此引出著名的"可变默认值陷阱"，见下文【常见陷阱】。

### 函数是对象

`def` 语句的执行效果就是"造一个函数对象并绑定到名字"。函数对象身上挂着一切：

```python
def f(a, b=1, *args, c, d=2, **kw):
    pass

co = f.__code__
print(co.co_argcount)        # 2：a, b（位置/位置或关键字参数个数）
print(co.co_kwonlyargcount)  # 2：c, d
print(co.co_varnames)        # ('a', 'b', 'c', 'd', 'args', 'kw')
```

`co_varnames` 先是所有参数名（位置/关键字参数 → 仅关键字参数 → `*args` → `**kw`），后面跟函数体内的局部变量名。函数还能随意绑属性、被塞进容器、当参数传——`agent/graph.py` 里节点函数 `planner`/`run_tools`/`finalize` 就是被当成普通对象传给 `add_node` 的。

## 运行机制与 CPython 实现

### 等价改写：`def` 语句干了什么

```python
def greet(name, greeting="你好"):
    return f"{greeting}, {name}"
```

用 `types.FunctionType` 可以直接演示"函数对象 = code 对象 + globals + 默认值"的组合关系——下面造一个**共享 code 对象但默认值不同**的新函数（可运行）：

```python
import types

def greet(name, greeting="你好"):
    return f"{greeting}, {name}"

greet2 = types.FunctionType(greet.__code__, globals(), "greet2", ("您好",))
print(greet("DPDK"), "|", greet2("DPDK"))
print("same code:", greet.__code__ is greet2.__code__)
print("defaults:", greet.__defaults__, greet2.__defaults__)
```

输出（3.11.15 实跑）：

```text
你好, DPDK | 您好, DPDK
same code: True
defaults: ('你好',) ('您好',)
```

也就是说 `greet.__defaults__` 里的那个默认值对象，是在"执行 `def` 这一行"时创建并塞进函数对象的——**挂在函数对象上而不在 code 对象上**（两个函数共享同一份 `__code__` 却各有各的默认值），改它会影响后续所有调用。

### 字节码：一次带关键字的调用

以下输出由 `backend/.venv/bin/python`（Python 3.11.15）实跑 `dis.dis` 得到：

```python
def greet(name, greeting="你好", *, punct="!", **extra):
    return f"{greeting}, {name}{punct}"

def caller():
    return greet("DPDK", punct="?", lang="zh")
```

```text
  9           0 RESUME                   0

 10           2 LOAD_GLOBAL              1 (NULL + greet)
             14 LOAD_CONST               1 ('DPDK')
             16 LOAD_CONST               2 ('?')
             18 LOAD_CONST               3 ('zh')
             20 KW_NAMES                 4
             22 PRECALL                  3
             26 CALL                     3
             36 RETURN_VALUE
```

逐条读：

- `LOAD_GLOBAL` 把 `greet` 压栈（`NULL +` 前缀是 3.11 vectorcall 的调用准备）；
- 三个 `LOAD_CONST` 压入 `'DPDK'`、`'?'`、`'zh'`——注意 `punct`/`lang` 的**值**直接压栈；
- `KW_NAMES 4` 加载常量表第 4 项，即名字元组 `('punct', 'lang')`，告诉解释器栈顶几个值对应哪些关键字名；
- `PRECALL`/`CALL 3`：3.11 把旧的 `CALL_FUNCTION` / `CALL_FUNCTION_KW` 统一成了 `CALL`，参数个数 3（1 个位置 + 2 个关键字）。

再看 `greet` 本体，能看到 `*args/**kw` 之外的参数只是普通局部变量：

```text
  6           0 RESUME                   0

  7           2 LOAD_FAST                1 (greeting)
              4 FORMAT_VALUE             0
              6 LOAD_CONST               1 (', ')
              8 LOAD_FAST                0 (name)
             10 FORMAT_VALUE             0
             12 LOAD_FAST                2 (punct)
             14 FORMAT_VALUE             0
             16 BUILD_STRING             4
             18 RETURN_VALUE
```

`LOAD_FAST` 按 `co_varnames` 下标直接取局部变量——这就是 Python 局部变量访问比全局变量快的原因（数组下标 vs dict 查找）。

### CPython 源码

- 函数对象的 C 结构是 `PyFunctionObject`：`__defaults__` / `__kwdefaults__` 由 CPython 3.11 分支 `Objects/funcobject.c` 的 `func_get_defaults` / `func_get_kwdefaults`（挂在 `func_getsetlist` 里）暴露出来，构造入口是 `PyFunction_NewWithQualName`。
- 函数之所以能"绑定成方法"（`obj.m()` 自动传入 `self`），是因为函数类型挂了 `tp_descr_get = func_descr_get`（同文件），它调用 `PyMethod_New`——细节见 `06_classes_and_descriptors.md`。

### Lib/ 层：`asyncio.to_thread` 怎么转发参数

`probe_replay` 里那行 `asyncio.to_thread(self.issues.probe, ..., session_id=...)` 的实现很短，以下是本机 3.11.15 用 `inspect.getsource` 取出的 `Lib/asyncio/threads.py` 真实源码（去 docstring 摘录）：

```python
async def to_thread(func, /, *args, **kwargs):
    loop = events.get_running_loop()
    ctx = contextvars.copy_context()
    func_call = functools.partial(ctx.run, func, *args, **kwargs)
    return await loop.run_in_executor(None, func_call)
```

- `func` 是仅位置参数（`/`），保证它的名字永不与 `**kwargs` 冲突；
- `functools.partial` 把 `func` 和参数打包成一个无参可调用对象，交给线程池执行；
- 于是调用处写成 `to_thread(self.issues.probe, self.request_id, ..., session_id=...)`，等价于在线程里执行 `self.issues.probe(self.request_id, ..., session_id=...)`。

## 常见陷阱

1. **可变默认值共享**。`def bad_log(msg, errors=[])` 的 `errors` 列表只创建一次、被所有未传参的调用共享：

   ```python
   def bad_log(msg, errors=[]):
       errors.append(msg)
       return errors

   print(bad_log("a"), bad_log("b"))   # ['a', 'b'] ['a', 'b'] —— 第二次带着上一次的值！
   print(bad_log.__defaults__)         # (['a', 'b'],)
   ```

   正确写法是哨兵 `None` + 函数体内新建：

   ```python
   def log(msg, errors=None):
       if errors is None:
           errors = []
       errors.append(msg)
       return errors
   ```

   项目里的同类形态是 `dataclass` 的 `field(default_factory=list)`（见 `services/task_service.py` `ManagedTask.events`）——`default_factory` 每次实例化都新调一次，天然规避共享。

2. **`f(a, b=1)` 里 `b` 是"位置或关键字"参数**，不是仅位置参数——`f(1, b=9)` 合法。只有 `/` 前才禁止关键字、`*` 后才禁止位置。

3. **关键字实参在位置实参之后**，但 `f(*args, k=1)` 里 `*` 展开的位置实参与后面的关键字实参可以混写；`f(**kw)` 必须与形参名对得上，多余名字进 `**kwargs`（没有 `**kw` 形参则 `TypeError`）。

4. **`probe(..., session_id=None)` 与"不传"不同**：`begin_admitted` 里 `purpose="chat"` 是显式默认值，传 `purpose=None` 会真的写进 `None`。读代码时注意区分"缺省"与"传了空值"。

5. 参数求值顺序：默认值在 `def` 时求值，**实参表达式在调用时**从左到右求值。

## 项目代码精读

> 本节代码块均按仓库原文摘录（保留原缩进与注释），依赖项目内部上下文，**不可单独运行**。

### `RagIssueService.begin_admitted` 的 `*,`

`backend/app/services/rag_issue_service.py`：

```python
    def begin_admitted(self, request_id, user_id, session_id, question, context, model, *,
                       input_digest, initial_request, quota, purpose="chat", attachment_name=None,
                       attachment_suffix=None, source_text=None, source_origin=None):
        """原子受理：唯一任务、恢复输入、用户消息与日配额同提交。

        唯一键冲突返回 None，调用方按既有 task 接续；配额/寄存失败回滚全笔，
        不留下无输入的 pending 行或重复扣费。会话归属已由调用方检查。
        """
```

逐行解读：

- `self` 之后的 6 个参数是"这次请求是什么"：请求 id、用户、会话、问题、上下文、模型——按位置传即可；
- `*,` 之后 8 个参数全是"这笔受理怎么记账"的可选细节：输入摘要、是否首发、配额、用途、附件与执行源。**强制按名字传**让这些布尔/可空标志在调用处必须写明含义——读 `prepare` 的调用时一眼能看出 `quota=quota` 扣的是哪份额度，而不会把第 7 个 `None` 数错位置；
- `input_digest`、`initial_request`、`quota` 没有默认值：它们可选位置但**必选值**，漏传会在调用时立即 `TypeError`，而不是静默绑错；
- 同类签名同文件还有 `probe(self, request_id, user_id, question, *, session_id=None, input_digest=None, purpose="chat")`。

调用方（`backend/app/services/rag_product_service.py` `RagProductService.prepare`）：

```python
        self.task_id = await asyncio.to_thread(
            self.issues.begin_admitted, self.request_id, self.principal.subject,
            self.session_id, self.request.message, self.memory.as_retrieval_context(),
            self.settings.model_name, input_digest=self.input_digest,
            initial_request=self.request.session_id is None, quota=quota, purpose=self.purpose,
            attachment_name=self.attachment.filename if self.attachment else None,
            attachment_suffix=self.attachment.suffix if self.attachment else None,
            source_text=input_source, source_origin=input_origin,
        )
```

- `to_thread` 的第一个实参是**函数对象本身** `self.issues.begin_admitted`（绑定方法，不含括号——写了括号就变成先执行再传返回值了）；
- 其后的位置实参对应 `begin_admitted` 的前 6 个形参，关键字实参逐个命中 `*,` 右侧；
- `attachment.filename if self.attachment else None` 是条件表达式（见 `04_truthiness_and_idioms.md`），保证无附件时传 `None` 而不是抛 `AttributeError`。

### `RequestTrace.set_meta` 的 `**items`

`backend/app/core/tracing.py`：

```python
    def set_meta(self, **items: Any) -> None:
        for key, value in items.items():
            if value is not None:
                self.metadata[key] = value
```

- `**items` 把任意多个 `name=value` 收成 dict 逐条写入 `self.metadata`；
- 调用处（`services/retrieval_service.py` `_fetch_candidate_rows`）：

  ```python
            trace.set_meta(
                fts_rewritten_candidates=len(rewritten_ids),
                fts_original_candidates=len(original_ids),
                path_candidates=len(path_ids),
                semantic_candidates=len(semantic_ids),
            )
  ```

  四个关键字实参全进了 `items`；`value is not None` 的过滤让"某路召回没跑"时不会写入 `None` 污染 trace。

### `probe_replay` 里 `asyncio.to_thread` 的转发

`backend/app/services/rag_product_service.py`：

```python
        state, row, message = await asyncio.to_thread(
            self.issues.probe, self.request_id, self.principal.subject,
            self.request.message, session_id=self.request.session_id,
            input_digest=self.input_digest, purpose=self.purpose)
```

- 左边 `state, row, message = ...` 是元组解包（`02` 篇）；
- `self.issues.probe` 是绑定方法对象——它已经"自带" `self`；
- 位置实参对应 `probe` 的 `request_id, user_id, question`，`*,` 右侧三个按名字传。

## 练习

1. **读项目代码回答**：`backend/app/services/rag_issue_service.py` 的 `probe` 签名是 `def probe(self, request_id, user_id, question, *, session_id=None, input_digest=None, purpose="chat")`。如果某个调用方写成 `self.issues.probe(rid, uid, q, sid)`（把 `session_id` 按位置传），会发生什么？报什么错？
2. **读项目代码回答**：`core/tracing.py` `RequestTrace.set_meta(**items)` 里为什么要判断 `if value is not None` 才写入？结合 `retrieval_service.py` 中 `trace.set_meta(fused_candidates=..., candidate_limit=...)` 的调用语境说明。
3. **动手写代码**：写一个函数 `merge_config(base, /, *keys, strict=False, **overrides)`：`base` 是 dict（仅位置）；把 `overrides` 逐项并入 `base` 的副本返回；`keys` 给出时要按序打印"只保留这些键"的结果。要求 `merge_config(d, k=1)`、`merge_config(d, "a", "b", strict=True, x=2)` 都能跑。
4. **动手写代码**：用 `__defaults__` 与 `__kwdefaults__` 写一个 `describe_signature(f)`，打印函数每个参数的默认值（没有默认值的打印 `<required>`），并对 `rag_issue_service` 的写法（`*,` 前后参数分别来自哪个属性）验证你的理解。
5. **读项目代码回答**：`rag_product_service.py` `probe_replay` 中 `asyncio.to_thread(self.issues.probe, ...)` 为什么不写成 `asyncio.to_thread(self.issues.probe(...))`？两种写法分别在哪个线程执行 `probe`？

## 参考答案

<details><summary>参考答案 1</summary>

`session_id` 在 `*,` 之后，是仅关键字参数，按位置传会立刻抛 `TypeError`：`probe() takes 3 positional arguments but 4 were given`（`self` 已由绑定方法提供）。这正是签名里写 `*,` 的目的：让"第 4 个位置参数"这种含糊写法在调用时就报错，而不是静默绑错。

</details>

<details><summary>参考答案 2</summary>

`set_meta` 是"有值才记"的累加器：`metadata` 最终进 `chat_trace` 结构化日志，写 `None` 会让日志里出现没有信息量的 `null` 字段，还会把之前已写入的真实值覆盖成 `None`。检索管线中每路召回都可能因上游失败而跳过（`rewritten_ids` 等可能为空但调用方依然传 `len(...)`），而另一些场景（如 trace 为 `None` 时根本不调用）调用方不保证每个键都有值；过滤 `None` 保证"没发生"与"发生了但值为 0"在日志里可区分——这也是全仓 `usage` 记账区分 unknown 与 0 的同一思想。

</details>

<details><summary>参考答案 3</summary>

```python
def merge_config(base, /, *keys, strict=False, **overrides):
    merged = dict(base)
    merged.update(overrides)
    if keys:
        if strict:
            merged = {k: merged[k] for k in keys}
        else:
            merged = {k: merged[k] for k in keys if k in merged}
    return merged

d = {"a": 1, "b": 2}
print(merge_config(d, x=9))                    # {'a': 1, 'b': 2, 'x': 9}
print(merge_config(d, "a", strict=True))       # {'a': 1}
print(merge_config(d, "a", "zzz"))             # {'a': 1}（非 strict 忽略缺失键）
try:
    merge_config(d, "zzz", strict=True)
except KeyError as e:
    print("KeyError:", e)
```

要点：`base` 在 `/` 前只能按位置传；`keys` 收进 tuple；`overrides` 是调用处所有 `name=value` 的 dict。实跑输出：`{'a': 1, 'b': 2, 'x': 9}`、`{'a': 1}`、`{'a': 1}`、`KeyError: 'zzz'`。

</details>

<details><summary>参考答案 4</summary>

```python
def describe_signature(f):
    code = f.__code__
    names = code.co_varnames
    pos_names = names[:code.co_argcount]                    # 位置/位置或关键字
    kw_names = names[code.co_argcount:
                     code.co_argcount + code.co_kwonlyargcount]
    defaults = f.__defaults__ or ()
    kwdefaults = f.__kwdefaults__ or {}
    # __defaults__ 只覆盖位置参数的后 N 个
    offset = len(pos_names) - len(defaults)
    for i, name in enumerate(pos_names):
        d = defaults[i - offset] if i >= offset else "<required>"
        print(f"  {name} = {d}")
    for name in kw_names:
        print(f"  {name} = {kwdefaults.get(name, '<required>')}")

def demo(a, b=1, *args, c, d=2, **kw): pass
describe_signature(demo)
```

实跑输出：

```text
  a = <required>
  b = 1
  c = <required>
  d = 2
```

关键理解：`__defaults__` 只装"位置参数尾部"的默认值（所以要用长度差对齐）；`*,` 右侧的默认值全部在 `__kwdefaults__` 这个 dict 里——`begin_admitted` 的 `purpose="chat"` 等就落在 `__kwdefaults__`，而 `input_digest` 这类"仅关键字但无默认值"的参数两边都不出现。

</details>

<details><summary>参考答案 5</summary>

`asyncio.to_thread(self.issues.probe, args...)` 传的是**函数对象**：`to_thread` 把它包进 `functools.partial` 交给线程池，`probe` 在线程里执行（同步 DB 调用不阻塞事件循环，这正是全仓把 DB 访问挪出事件循环的手法）。如果写成 `asyncio.to_thread(self.issues.probe(...))`，`probe` 会**立即在当前协程里同步执行**——`to_thread` 收到的只是它的返回值 `(state, row, message)`，一个元组不是可调用对象，随后 `partial`/`run_in_executor` 调用它会抛 `TypeError`；更糟的是这个阻塞调用本身发生在事件循环线程上，违背了挪出事件循环的初衷。传"函数 + 参数"与传"调用结果"的区别，就是"延迟执行"与"立即执行"的区别。

</details>

## 延伸阅读

- Python 官方教程："More on Defining Functions"（默认参数、关键字参数、任意参数列表、解包参数列表）。
- 语言参考："Function definitions"（`/`, `*` 语法规则）与 "Calls"。
- PEP 570（Positional-Only Parameters）、PEP 3102（Keyword-Only Arguments）。
- `inspect` 模块文档（`signature`、`getsource`）；`dis` 模块文档。
