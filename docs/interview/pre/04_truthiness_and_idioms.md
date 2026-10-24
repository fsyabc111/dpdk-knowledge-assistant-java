# 04 真值、None 与表达式惯用法

## 学习目标

- 掌握 Python 的真值（truthiness）规则：`__bool__` → `__len__` → 默认 True；
- 分清 `is None` 与 `== None`、身份（`is`）与相等（`==`）；
- 知道 `and` / `or` 返回的是**操作数**而不是布尔值、会短路，能读 `a or default`、`x and x.f()` 这类惯用法；
- 会用条件表达式 `x if cond else y`；
- 会用 `getattr(obj, name, default)` / `hasattr` / `setattr`；
- 会读链式比较 `1 < x <= 5` 与海象运算符 `:=`。

**本主题在项目里出现在哪**

| 位置 | 用到什么 |
| --- | --- |
| `backend/app/services/rag_product_service.py` `RagProductService._candidate` | `getattr(getattr(service.retrieval, "_retriever", None), "release_id", "") or ""`（嵌套 getattr + or 兜底） |
| 同文件 `_resolve_explain` | `self.session_id or getattr(self.request, "session_id", None) or ""`（多级 or 回退） |
| `backend/app/services/task_service.py` `_run_with_deadline` | `row is None or row["status"] == "pending"`（短路保护下标访问） |
| `backend/app/agent/graph.py` `_deadline_reached` / `run_tools` | `bool(state.get("deadline_hit")) or (...)`、`getattr(last, "tool_calls", None) or []` |
| `backend/app/agent/mcp_client.py` `_parse_result` | `getattr(result, "structuredContent", None)`、`getattr(result, "content", None) or []` |
| `backend/app/agent/graph.py` `run_tools._invoke` | `call.get("name", "")`、`call.get("args") or {}`（dict.get 默认值 + or） |

## 语法详解

### 真值规则

任何对象都能放进布尔语境（`if`、`while`、`and/or/not`）。判定顺序：

1. 类定义了 `__bool__` → 用它；
2. 否则定义了 `__len__` → `len() != 0`；
3. 否则默认 `True`。

```python
class Empty:
    def __len__(self):
        return 0

class NoBool:
    pass

print(bool(Empty()), bool(NoBool()))
print(bool([]), bool("0"), bool(0.0), bool(None))
```

输出：`False True` 与 `False True False False`——注意 `"0"`（非空串）是 True，`0.0` 是 False。

内置假值全家桶：`None`、`False`、`0` / `0.0`、`""`、`[]`、`{}`、`set()`、`()`、`range(0)`。

### `is None` vs `== None`

- `==` 调 `__eq__`，**可被类重载**——`x == None` 可能跑自定义逻辑；
- `is` 比较的是对象身份（同一对象），`None` 是全局唯一单例，所以判空**永远**写 `x is None` / `x is not None`。

```python
print(None == False, None is None, None == None)
```

输出：`False True True`——`None` 不等于 `False`（虽然两个都是假值）。项目里 `row is None or row["status"] == "pending"` 这种写法里，`is None` 是**前置存在性检查**，语义与"值为空"完全不同。

### `and` / `or`：返回操作数 + 短路

这是与 C/Java 最不同的地方：`and`/`or` **不返回 `bool`**，而是返回决定结果的那个操作数：

- `a or b`：`a` 为真返回 `a`，否则返回 `b`；
- `a and b`：`a` 为假返回 `a`，否则返回 `b`。

```python
print(0 or "x", 3 and "x", "" or 0, None or [])
```

输出：`x x 0 []`

由此产生两个核心惯用法：

```python
user_input = ""
name = user_input or "anonymous"     # 空串/None → 默认值
class Msg: pass
msg = Msg()
items = getattr(msg, "tool_calls", None) or []   # 缺属性或为 None/空 → 空列表
print(name, items)
```

输出：`anonymous []`

**短路**：`or` 左侧为真就不再求右侧；`and` 左侧为假就不再求右侧。所以 `row is None or row["status"] == "pending"` 是安全的——`row` 为 `None` 时右侧 `row["status"]` 根本不会求值，不会抛 `TypeError`。顺序写反（先取下标再判空）就是 bug。

注意 `and` 版本的保护习惯写法 `x and x.attr`：`None and None.attr` → `None`（安全）；但若 `x` 是 `0`/`""` 等假值也会短路——当 `x` 的合法值里含假值时要改显式 `if x is not None`。

### 条件表达式

`a if cond else b` 是**表达式**，可以嵌在任意值的位置：

```python
session_id = None
label = "首发" if session_id is None else "续会话"
print(label)
```

输出：`首发`

对应 C 系语言的 `cond ? a : b`，但语序不同。`begin_admitted` 的调用处 `attachment.filename if self.attachment else None` 就是典型用法。

### `getattr` / `hasattr` / `setattr`

```python
class M:
    pass

m = M()
print(getattr(m, "tool_calls", None))      # None：属性不存在给默认值
print(getattr(m, "tool_calls", None) or [])
setattr(m, "tool_calls", [{"name": "kb"}])
print(getattr(m, "tool_calls"))
```

输出：

```text
None
[]
[{'name': 'kb'}]
```

`getattr(obj, name)`（不带默认值）在属性不存在时抛 `AttributeError`；带默认值版本配合 `or` 是全仓读 LangChain 消息对象的固定套路：`getattr(message, "tool_calls", None) or []`——缺属性、`None`、空列表三种情况统一得到 `[]`。

`hasattr(m, "x")` ≈ `getattr` 不抛异常版（它内部就是 `getattr` + 捕获 `AttributeError`）。

### 链式比较

```python
print(1 < 2 <= 2 < 5)      # True：等价于 (1 < 2) and (2 <= 2) and (2 < 5)
```

中间每个值只求值一次。本项目未使用链式比较，但读第三方代码（`if 0 <= score <= 100` 这类写法）会遇到——它比 `score >= 0 and score <= 100` 更接近数学记号。

### 海象运算符 `:=`（3.8+，PEP 572）

在表达式内部赋值并返回该值，消灭"先赋值再判断"的两行写法：

```python
data = "abcd"
if (n := len(data)) > 3:
    print("n =", n)
```

输出：`n = 4`

典型场景：`while (line := f.readline())`、`if (m := pattern.match(s))`。注意在 `if`/`while` 条件里用必须加括号（`if n := len(d) > 3` 会把比较结果赋给 `n`）。

## 运行机制与 CPython 实现

### 等价改写

`x = a or b` 等价于：

```python
# 片段，不可单独运行（示意 a/b 为已定义变量）
x = a if a else b          # a 只求值一次
```

`x = a or b or c` 等价于嵌套条件表达式；`row is None or row["status"] == "pending"` 等价于：

```python
# 片段，不可单独运行（示意 row 为查询结果行或 None）
if row is None:
    pending = True
else:
    pending = row["status"] == "pending"   # row 非 None 才执行，无下标风险
```

`getattr(m, "tool_calls", None) or []` 等价于：

```python
# 片段，不可单独运行（示意 m 为消息对象）
_tmp = getattr(m, "tool_calls", None)
calls = _tmp if _tmp else []
```

### 字节码：短路跳转

以下输出由 `backend/.venv/bin/python`（Python 3.11.15）实跑 `dis.dis` 得到：

```python
def pick(a, b):
    return a or b
def both(a, b):
    return a and b
def expr(a):
    return a or "default"
```

```text
 30           0 RESUME                   0

 31           2 LOAD_FAST                0 (a)
              4 JUMP_IF_TRUE_OR_POP      1 (to 8)
              6 LOAD_FAST                1 (b)
        >>    8 RETURN_VALUE
----------------------------------------
 32           0 RESUME                   0

 33           2 LOAD_FAST                0 (a)
              4 JUMP_IF_FALSE_OR_POP     1 (to 8)
              6 LOAD_FAST                1 (b)
        >>    8 RETURN_VALUE
----------------------------------------
 34           0 RESUME                   0

 35           2 LOAD_FAST                0 (a)
              4 JUMP_IF_TRUE_OR_POP      1 (to 8)
              6 LOAD_CONST               1 ('default')
        >>    8 RETURN_VALUE
```

- `JUMP_IF_TRUE_OR_POP`：`a` 为真就**保留在栈上**直接跳去 `RETURN_VALUE`（返回 `a`）；为假则弹出 `a`、继续算 `b`——这正对应"返回操作数"的语义；
- `and` 对称地用 `JUMP_IF_FALSE_OR_POP`；
- `or "default"` 的右侧是常量：为假时 `LOAD_CONST 'default'` 返回——`x or default` 的全部成本就是一次跳转 + 一次常量加载，没有函数调用。

### CPython 源码

真值判定入口是 CPython 3.11 分支 `Objects/object.c` 的 `PyObject_IsTrue`，其实现顺序与上文规则一一对应（节选，有省略）：

```c
if (v == Py_True)  return 1;
if (v == Py_False) return 0;
if (v == Py_None)  return 0;
else if (Py_TYPE(v)->tp_as_number != NULL &&
         Py_TYPE(v)->tp_as_number->nb_bool != NULL)
    res = (*Py_TYPE(v)->tp_as_number->nb_bool)(v);       /* __bool__ */
else if (... tp_as_mapping->mp_length ...)               /* __len__（mapping） */
else if (... tp_as_sequence->sq_length ...)              /* __len__（sequence） */
else
    return 1;                                          /* 默认真 */
```

三个特例（`True`/`False`/`None`）先走指针比较直接返回——单例身份的硬件级快路径；然后依次试 `nb_bool`（`__bool__` 的 C 槽位）、`mp_length`/`sq_length`（`__len__`），都没有就默认真。

## 常见陷阱

1. **`or` 默认值陷阱**：`x or default` 在 `x` 为 `0`、`""`、`[]` 时也取默认值——如果这些假值是**合法值**，必须写 `x if x is not None else default`。例：配额 `quota or 10` 会把合法的 `0` 改成 `10`。
2. **`== None` 陷阱**：自定义类可能重载 `__eq__` 让 `x == None` 返回 True（SQLAlchemy 的列对象就会）——判空一律 `is None`。
3. **`and` 链的返回值不是 bool**：`ok = a and b` 得到的是 `b` 的值；需要真布尔值时用 `bool(a and b)` 或 `is not None` 显式判。
4. **短路方向记反**：`row["status"] == "pending" or row is None` 会先炸下标；判空**永远写左边**。
5. **`getattr` 默认值只挡"属性不存在"**：属性存在但取值为 `None` 时照样返回 `None`——所以要 `or []` 兜底，两层各管一件事。
6. **`if x:` 与 `if x is not None:` 不同**：前者过滤所有假值，后者只过滤 `None`。`ManagedTask.result` 是 `Optional[ChatResponse]`——判"有没有结果"用 `is not None`；判"列表非空"用 `if rows:`。
7. 海象 `:=` 在条件里必须加括号；列表推导式里 `(n := f(x))` 的 `n` 会泄漏到外层作用域（推导式里赋值表达式绑定在外层）。

## 项目代码精读

> 本节代码块均按仓库原文摘录（保留原缩进与注释），依赖项目内部上下文，**不可单独运行**。

### `task_service._run_with_deadline`：短路判空

`backend/app/services/task_service.py`：

```python
        pending = True
        try:
            row = await asyncio.to_thread(
                issues.get_task, managed.task_id, service.principal.subject)
            pending = row is None or row["status"] == "pending"
            if not pending:
                await asyncio.to_thread(issues.drop_task_inputs, managed.task_id)
```

- `row is None or row["status"] == "pending"`：行不存在（已被清理）视为 pending——保守保留执行输入；行存在才读 `status`。短路保证 `row` 为 `None` 时不取下标；
- 整段在 `try/except` 里（查库失败默认 `pending=True`）——"宁可留输入等下次恢复扫描，不误删可恢复任务的输入"，与注释语义一致；
- `pending` 先置 `True` 再被覆盖：`try` 内任何一步抛异常，`pending` 保持 `True` 的保守值。

### `graph.py`：getattr + or 的固定套路

`backend/app/agent/graph.py` `run_tools`：

```python
        last = state["messages"][-1]
        calls = getattr(last, "tool_calls", None) or []
```

- `last` 是 LangChain `BaseMessage`——不同消息类型对 `tool_calls` 的支持不一，`getattr(..., None)` 先取属性、`or []` 把 `None`/空列表统一成 `[]`；
- 同一函数里 `call.get("name", "")`、`call.get("args") or {}` 是 dict 版本：`get` 默认值挡"键不存在"，`or {}` 挡"值为 `None`"。

`backend/app/agent/graph.py` `build_agent_graph` 的 `_deadline_reached`：

```python
    def _deadline_reached(state: AgentGraphState) -> bool:
        return bool(state.get("deadline_hit")) or (
            deadline is not None and time.monotonic() >= deadline
        )
```

- `state.get("deadline_hit")` 取不到给 `None`，`bool(...)` 显式归一成布尔——函数注解要求 `-> bool`，不能返回 `None`；
- `deadline is not None and time.monotonic() >= deadline`：`and` 短路先判"配置了软截止"再读时钟——`deadline` 为 `None` 时不做无谓比较；
- 整体 `bool(...) or (...)`：两个来源任一成立即达限。

### `rag_product_service._candidate`：嵌套 getattr 链

`backend/app/services/rag_product_service.py` `RagProductService._candidate`：

```python
        release = getattr(getattr(service.retrieval, "_retriever", None), "release_id", "") or ""
```

- 内层 `getattr(service.retrieval, "_retriever", None)`：`retrieval` 可能没有 `_retriever` 属性（私有属性名），取不到给 `None`；
- 外层 `getattr(<上一步>, "release_id", "")`：上一步若是 `None`，`None` 上取 `release_id` 给默认值 `""`——`getattr` 的默认值在**对象上没有该属性**时返回，`None` 上没有 `release_id`，安全落到 `""`；
- 末尾 `or ""`：属性存在但值为 `None`/`""` 时再兜一层；
- 整条链把"retrieval 无 retriever / retriever 无 release_id / release_id 为空"三种情况收敛成 `""`，日志里不会写 `None`。

`backend/app/services/rag_product_service.py` `_resolve_explain`：

```python
        session_id = self.session_id or getattr(self.request, "session_id", None) or ""
```

- `self.session_id` 为 `None` 或 `""` 时回退到 `request.session_id`，仍没有则 `""`——`""` 与 `None` 在这里语义等价（都是"无会话"），`or` 链把它们收敛成统一的空串；
- 下一行 `if not session_id.startswith("explain-")` 就能安全调方法——空串也有 `startswith`。

### `mcp_client._parse_result`：duck-typing 读响应

`backend/app/agent/mcp_client.py`：

```python
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
```

- MCP 返回的是 SDK 对象不是 dict，全程 `getattr` duck-typing 取值；
- `(getattr(result, "content", None) or [])`：无 `content` 或为 `None` → `[]`，推导式安全空转；
- `if getattr(item, "text", "")`：空串假值过滤——推导式的条件直接用真值；
- `getattr(result, "isError", False)`：缺属性按 `False` 处理（正常路径）。

## 练习

1. **读项目代码回答**：`task_service.py` 中 `pending = row is None or row["status"] == "pending"`，如果改成 `pending = row["status"] == "pending" or row is None` 会发生什么？改成 `pending = row is None or row["status"] is "pending"` 呢？
2. **读项目代码回答**：`graph.py` `_deadline_reached` 里 `bool(state.get("deadline_hit")) or (...)` 如果去掉 `bool(...)`，在 `deadline_hit` 缺键时会返回什么？为什么注解 `-> bool` 会"说谎"？
3. **动手写代码**：实现 `first_non_empty(*vals)`：返回第一个"非 None 且非空串"的值，全不满足返回 `""`；分别用 `or` 链和显式循环写两版，并讨论它们对 `0`、`[]` 的处理差异。
4. **动手写代码**：写一个 `safe_get(obj, *names, default="")`：连续 `getattr` 链（`safe_get(a, "b", "c")` ≈ `a.b.c`），任一环节无属性或为 `None` 即返回 `default`。用它模拟 `release = getattr(getattr(service.retrieval, "_retriever", None), "release_id", "") or ""`。
5. **读项目代码回答**：`mcp_client._parse_result` 里 `for item in (getattr(result, "content", None) or [])`，如果 `result.content` 是空列表 `[]` 和是 `None`，`or []` 各起什么作用？

## 参考答案

<details><summary>参考答案 1</summary>

改成 `row["status"] == "pending" or row is None`：`row` 为 `None` 时**先求** `row["status"]`，立即抛 `TypeError: 'NoneType' object is not subscriptable`——判空必须写左边靠短路保护。改成 `row["status"] is "pending"`：语法上能跑（3.8+ 会给 `SyntaxWarning: "is" with a literal`），但语义错误——`is` 比的是对象身份，字符串"pending"是否同一对象取决于驻留（interning）实现细节，长字符串/动态拼出的串可能 `is` 失败。值相等永远用 `==`，身份判定（单例）才用 `is`。

</details>

<details><summary>参考答案 2</summary>

去掉 `bool(...)` 后，若 `deadline_hit` 键不存在，`state.get` 返回 `None`，`or` 继续求右半部分——逻辑结果不变（右半会给出 `False` 或 `True`）；但若右半也不成立，`or` 的返回值是**右操作数** `False`——这里恰好还是 bool。真正的隐患在反向：若 `deadline_hit` 存在且值是某个非 bool 真值（未来状态字段写入约定变化），`or` 会**短路返回该值本身**，函数声明 `-> bool` 却返回非 bool。`bool(...)` 显式归一让返回值类型与注解一致，是"声明了 bool 就真的返回 bool"的防御写法。

</details>

<details><summary>参考答案 3</summary>

```python
def first_non_empty(*vals):
    """真值版（语义等价于 or 链）：0、[] 也会被跳过。"""
    for v in vals:
        if v:
            return v
    return ""

# 等价的单行写法：next((v for v in vals if v), "")

def first_non_empty_strict(*vals):
    """显式版：只跳过 None 与 ""。"""
    for v in vals:
        if v is not None and v != "":
            return v
    return ""

print(first_non_empty(None, "", "x", "y"))        # x
print(first_non_empty(0, "a"))                    # a（0 被当真值跳过）
print(first_non_empty_strict(0, "a"))             # 0（0 是合法值）
print(first_non_empty([], "b"))                   # b
print(first_non_empty_strict([], "b"))            # []
```

项目里 `x or default` 相当于"宽松版"——读代码时要先确认该位置的合法值域里有没有 `0`/`[]`/`""` 这类假值；`begin_admitted` 的参数、配额这类**数值**字段都不能用 `or` 给默认值（`0` 会被误伤），字符串/列表字段用 `or` 才是安全的。

</details>

<details><summary>参考答案 4</summary>

```python
def safe_get(obj, *names, default=""):
    cur = obj
    for name in names:
        cur = getattr(cur, name, None)
        if cur is None:
            return default
    return cur

class Retriever:
    release_id = "rel_123"
class Retrieval:
    _retriever = Retriever()
class Service:
    retrieval = Retrieval()

print(safe_get(Service(), "retrieval", "_retriever", "release_id"))  # rel_123
print(safe_get(object(), "retrieval", "_retriever", "release_id"))   # ''
```

项目里的两行嵌套 `getattr` 就是这个函数固定两次调用的展开；末尾 `or ""` 对应"属性值本身是 `None`/`''` 也归一成 `default`"——本实现里 `cur is None` 已覆盖 `None`，要完全等价可把返回改成 `return cur or default`。

</details>

<details><summary>参考答案 5</summary>

`result.content` 为 `None`：`or []` 把 `None` 变成空列表，推导式空转——`texts` 得 `[]`。`result.content` 为 `[]`：`or` 左侧已是假值，返回的还是 `[]`——结果相同但走了"假值替换"路径。两层语义：`getattr(..., None)` 挡"属性不存在"，`or []` 同时挡"为 None"与"为空容器"，三态归一到"可迭代为空"。这也是全仓 `or []`/`or {}`/`or ""` 的通用读法：**先 `getattr`/`get` 给默认值，再 `or` 兜底假值**。

</details>

## 延伸阅读

- Python 官方教程："More on Conditions"（`in`/`not in`/`is`、and/or 短路）。
- 语言参考："Boolean operations"（and/or/not 精确语义）、"Comparisons"（链式比较与 `is`）、"Special method names"（`__bool__`/`__len__`/`__eq__`）。
- PEP 572（赋值表达式 `:=`）。
- `getattr`/`hasattr`/`setattr`/`bool` 内置函数文档。
