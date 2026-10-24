# 02 容器、推导式、解包与常用内置函数

## 学习目标

- 熟练使用 `list` / `dict` / `set` / `tuple` 的常用操作，知道各自的均摊复杂度；
- 会用切片读、改、删序列（含 `del xs[i:j]`）；
- 知道 `dict` 自 3.7 起**语言层面**保证插入序及其实现（compact dict）；
- 会写并读懂列表/字典/集合推导式与生成器表达式；知道 3.11 里推导式是独立函数作用域；
- 会用元组解包、`*rest` 收集、`{**a, "k": v}` 与 `a | b` 合并字典；
- 熟练使用 `enumerate` / `zip(strict=True)` / `sorted(key=)` / `lambda` / `any` / `all` / `min` / `max` / `sum`。

**本主题在项目里出现在哪**

| 位置 | 用到什么 |
| --- | --- |
| `backend/app/services/rag_index_service.py` `plan_nodes` / `ingest_release` / `_existing` | 集合推导式 `{node["id"] for node in desired}`、`sum(生成器)`、`zip(batch, vectors, strict=True)`、tuple 作 dict 键、列表推导式 |
| `backend/app/agent/tools.py` `grade_plugin` | `{**cached, "deduplicated": True}`（dict 解包合并） |
| `backend/app/services/retrieval_service.py` `_fetch_candidate_rows` | `sorted(ranks.items(), key=lambda item: ..., reverse=True)`、`seen` 集合去重、列表推导式 |
| `backend/app/services/task_service.py` `_run_with_deadline` 内层 `emit` | `del managed.events[: n]`（切片删除做容量截断） |
| `backend/app/services/knowledge_sources.py` `chunk_code` | `lines[:starts[0][0]]`、`starts[index + 1][0]` 切片与索引 |

## 语法详解

### 四种内置容器

| 类型 | 字面量 | 可变 | 有序 | 典型操作与均摊复杂度 |
| --- | --- | --- | --- | --- |
| `list` | `[1, 2]` | 是 | 插入序 | `append/pop` O(1) 均摊、`insert/del` O(n)、`in` O(n)、`len` O(1) |
| `dict` | `{"k": 1}` | 是 | 插入序（3.7+ 保证） | `[]/get/in/del` O(1) 均摊 |
| `set` | `{1, 2}` | 是 | 无（实现序） | `add/in/discard` O(1) 均摊 |
| `tuple` | `(1, 2)` | 否 | 插入序 | 索引 O(1)、可作 dict 键（元素均可哈希时） |

```python
xs = [3, 1, 2]
xs.append(4); xs.insert(0, 0)      # [0, 3, 1, 2, 4]
xs.sort()                          # 原地排序，返回 None
d = {"a": 1}
d["b"] = 2; d.setdefault("a", 9)   # 已有键不覆盖
print(d.get("zzz", "默认"))         # get 带默认值
s = {1, 2, 3}; s.add(2); s.discard(9)
print(d, s, "a" in d, 5 in s)
```

输出：

```text
{'a': 1, 'b': 2} {1, 2, 3} True False
```

`sort()` 与 `sorted()`、`d["k"]` 与 `d.get("k")` 的区别是高频考点：前者原地改返回 `None`，后者返回新对象/不存在的键不抛错。

### 切片：`xs[i:j:k]` 与 `del` 切片

切片**复制**出新 list，不越界报错；赋值切片原地改写长度；`del` 切片原地删除：

```python
xs = list(range(8))
print(xs[2:5], xs[-2:], xs[::2])
del xs[2:5]
print(xs)
ys = [0, 1, 2]
ys[1:1] = [9, 9]        # 插入
print(ys)
```

输出：

```text
[2, 3, 4] [6, 7] [0, 2, 4, 6]
[0, 1, 5, 6, 7]
[0, 9, 9, 1, 2]
```

`del xs[: n]` 是"丢掉最旧 n 条"的最简写法——`task_service` 用它做事件回放日志的容量截断。

### dict 的插入序

```python
d = {}
d["z"] = 1; d["a"] = 2; d["m"] = 3
print(list(d))                    # ['z', 'a', 'm'] —— 插入序，不是排序序
print(list(d.items()))
```

3.7 起这是**语言保证**（3.6 是 CPython 实现细节）。依赖：遍历 `d.items()` 的顺序 = 键首次插入的顺序；对已存在键重新赋值不改变位置；删除再插入会移到最后。

### list 的过量分配（为什么不关心 `append` 的代价）

```python
import sys
xs = []
for i in range(12):
    xs.append(i)
    print(len(xs), sys.getsizeof(xs))
```

输出（3.11.15 实跑）：

```text
1 88
2 88
3 88
4 88
5 120
6 120
7 120
8 120
9 184
10 184
11 184
12 184
```

`getsizeof` 阶梯式增长：底层数组按过量分配策略扩容（见 C 层 `list_resize`），`append` 均摊 O(1)。结论：Python 里"循环里 append 造列表"是正常写法，不需要像某些语言那样预分配。

### 推导式与生成器表达式

三种推导式 + 一种生成器表达式：

```python
nums = range(6)
print([x * x for x in nums if x % 2])          # list
print({x: x * x for x in nums})                # dict
print({x % 3 for x in range(10)})              # set（自动去重）
g = (x * x for x in range(4))                  # generator：惰性
print(sum(g))                                  # 14
print(sum(x for x in range(5) if x % 2))       # 4：生成器直接喂 sum，不建中间列表
```

输出：

```text
[1, 9, 25]
{0: 0, 1: 1, 2: 4, 3: 9, 4: 16, 5: 25}
{0, 1, 2}
14
4
```

`sum(node["id"] not in ids for node in existing)` 这种"sum 布尔生成器"等价于"数有多少个 `existing` 的 id 不在 `ids` 里"——`True` 是 1。

**作用域**：3.11 中推导式会编译成独立函数（`co_name = '<listcomp>'`），循环变量不泄漏到外层；生成器表达式的循环变量同样封闭。这与 `for` 循环不同——`for` 循环变量会留在作用域里。

### 解包

```python
a, b = 1, 2
a, b = b, a              # 交换：右侧先整体求值为元组
first, *rest, last = [1, 2, 3, 4, 5]
print(first, rest, last)  # 1 [2, 3, 4] 5
x, y = "ab"               # 任意可迭代对象都可解包
k, v = ("key", 9)
```

解包在"函数返回多个值"（实际是返回 tuple）处最常用：`state, row, message = await asyncio.to_thread(...)`。`*rest` 只能出现一次；目标个数不匹配抛 `ValueError`。

### 合并字典：`{**a, **b}` 与 `a | b`

```python
d1 = {"a": 1, "b": 2}
d2 = {"b": 3, "c": 4}
print({**d1, **d2})        # {'a': 1, 'b': 3, 'c': 4}：后者覆盖前者
print(d1 | d2)             # 同上（3.9+ 的 | 运算）
print({**d1, "b": 9})      # 键可按需再覆盖
```

`{**cached, "deduplicated": True}` 是"复制一份 dict 并追加/覆盖一个键"的惯用法——不改原 `cached`（它可能还被别处持有），返回新 dict。

### 常用内置函数

```python
words = ["bbb", "a", "cc"]
print(sorted(words, key=len))                 # ['a', 'cc', 'bbb']
print(sorted(words, key=len, reverse=True))   # ['bbb', 'cc', 'a']
print(min(words, key=len), max(words, key=len))
print(list(enumerate("ab", start=1)))         # [(1, 'a'), (2, 'b')]
print(list(zip([1, 2, 3], "ab")))             # [(1, 'a'), (2, 'b')]：默认短截
print(any(x > 1 for x in [0, 2]), all(x > 1 for x in [2, 3]))
```

`zip(strict=True)`（3.10+）要求两侧等长，否则 `ValueError`——比"静默短截"更适合"两条序列本应一一对应"的场景：

```python
try:
    list(zip([1, 2, 3], [1, 2], strict=True))
except ValueError as e:
    print("zip strict:", e)
```

输出：`zip strict: zip() argument 2 is shorter than argument 1`。

`lambda` 只是"单行匿名函数"：`lambda item: item[1].rrf_score(k)` 等价于 `def f(item): return item[1].rrf_score(k)`。`sorted` 的 `key` 对每个元素算一次键再按键排序（decorate-sort-undecorate），不是两两比较。

## 运行机制与 CPython 实现

### 等价改写

```python
out = [x * x for x in range(5) if x % 2]
```

等价于（3.11 中实际编译成一个独立函数再调用）：

```python
def _listcomp(iterable):
    result = []
    for x in iterable:
        if x % 2:
            result.append(x * x)
    return result

out = _listcomp(iter(range(5)))
```

而生成器表达式 `(x * x for x in range(5))` 等价于一个生成器函数（惰性，详见 `09_iterators_and_generators.md`）：

```python
def _gen(iterable):
    for x in iterable:
        yield x * x
```

### 字节码：推导式是独立函数

以下输出由 `backend/.venv/bin/python`（Python 3.11.15）实跑 `dis.dis` 得到：

```python
def make_squares():
    return [x * x for x in range(5)]
```

```text
 18           0 RESUME                   0

 19           2 LOAD_CONST               1 (<code object <listcomp> at 0x109e1d6f0, file "/tmp/pre_verify/gen_dis.py", line 19>)
              4 MAKE_FUNCTION            0
              6 LOAD_GLOBAL              1 (NULL + range)
             18 LOAD_CONST               2 (5)
             20 PRECALL                  1
             24 CALL                     1
             34 GET_ITER
             36 PRECALL                  0
             40 CALL                     0
             50 RETURN_VALUE

Disassembly of <code object <listcomp> at 0x109e1d6f0, file "/tmp/pre_verify/gen_dis.py", line 19>:
 19           0 RESUME                   0
              2 BUILD_LIST               0
              4 LOAD_FAST                0 (.0)
        >>    6 FOR_ITER                 7 (to 22)
              8 STORE_FAST               1 (x)
             10 LOAD_FAST                1 (x)
             12 LOAD_FAST                1 (x)
             14 BINARY_OP                5 (*)
             18 LIST_APPEND              2
             20 JUMP_BACKWARD            8 (to 6)
        >>   22 RETURN_VALUE
```

要点：

- `LOAD_CONST` 载入一个独立 code 对象 `<listcomp>`，`MAKE_FUNCTION` 造函数，`range(5)` 经 `GET_ITER` 变成迭代器作为**唯一参数**（在 listcomp 内部叫 `.0`）传入；
- 内部是普通的 `FOR_ITER` 循环 + `LIST_APPEND`——所以循环变量 `x` 是 `<listcomp>` 函数的局部变量，不会泄漏到 `make_squares` 的作用域；
- **版本注意**：3.12 起 PEP 709 把推导式改为内联编译，不再有 `<listcomp>` 函数对象——本文档与仓库统一按 3.11 行为讲解。

### CPython 源码

- **list 过量分配**：CPython 3.11 分支 `Objects/listobject.c` 的 `list_resize` 计算新容量 `new_allocated = ((size_t)newsize + (newsize >> 3) + 6) & ~(size_t)3`——约 1/8 的超额，上文 `getsizeof` 的阶梯正来源于此。
- **dict 插入序**：同分支 `Objects/dictobject.c` 文件头注释完整描述了 compact dict 设计：哈希表 `dk_indices` 只存条目下标（空位标记 `DKIX_EMPTY`），条目本体 `dk_entries` 是**按插入顺序追加**的紧凑数组，遍历时按 entries 顺序走，天然保序；注释 "Preserving insertion order" 一节专门说明。
- tuple 可哈希是因为元素不可变且都实现了 `__hash__`——dict 键的要求是"可哈希"，不是"不可变"（自定义类的可哈希性见 `06` 篇）。

## 常见陷阱

1. **`list.sort()` 返回 `None`**：`xs = xs.sort()` 会把 `xs` 变成 `None`。要新列表用 `sorted(xs)`。
2. **切片是拷贝**：`ys = xs[:]` 是浅拷贝——元素仍是同一批对象。`ys.append` 不影响 `xs`，但 `ys[0].x = 1` 会影响。
3. **dict/set 的 `in` 查的是键**：`"a" in {"a": 1}` 为 True；查值要 `in d.values()`（O(n)）。
4. **`zip` 默认短截**：长度不齐静默丢尾部——两条"本应等长"的序列用 `strict=True`，把数据错误变成异常而不是错位数据。
5. **`tuple` 只有一个元素时要写逗号**：`(1)` 是 int，`(1,)` 才是 tuple。`f(x),` 这种尾随逗号在多行调用里最常见。
6. **可变对象作 dict 键**：`list`/`dict`/`set` 不可哈希不能当键；`tuple` 含可变元素同样不行。
7. **迭代中改容器**：`for x in xs: xs.remove(x)` 会跳元素；要过滤请用推导式造新列表，或遍历 `xs[:]` 副本。
8. `a | b` 与 `a.update(b)`：`a | b` 返回**新** dict；`update` 原地改。`{**a}` 同理是新对象。

## 项目代码精读

> 本节代码块均按仓库原文摘录（保留原缩进与注释），依赖项目内部上下文，**不可单独运行**。

### `rag_index_service.plan_nodes`：增量计划

`backend/app/services/rag_index_service.py`：

```python
def plan_nodes(desired: list[dict], existing: list[dict], same_config: bool) -> tuple[list[dict], list[dict]]:
    by_position = {}
    for node in existing:
        meta = node["metadata"]
        by_position[(meta.get("source_key"), meta.get("ordinal"))] = node
    changed = []
    for node in desired:
        meta = node["metadata"]
        old = by_position.get((meta["source_key"], meta["ordinal"]))
        if same_config and old and old["content"] == node["content"] and old["metadata"] == meta:
            node["id"] = old["id"]
        else:
            node["id"] = "li_" + uuid.uuid4().hex
            changed.append(node)
    return desired, changed
```

逐行解读：

- `by_position` 用 **tuple `(source_key, ordinal)` 作 dict 键**——位置定位的旧节点 O(1) 查找；`meta.get("source_key")` 容忍缺键返回 `None`；
- 返回注解 `tuple[list[dict], list[dict]]`（3.9+ 内置泛型）声明返回两个列表；
- `by_position.get((...))` 取不到给 `None`，`same_config and old and ...` 用短路链先验配置再验存在性再逐项比较（真值惯用法见 `04` 篇）；
- 复用旧 id 的节点不进 `changed`——只有真正变了的内容才会拿去重算 embedding。

### `ingest_release`：集合推导式 + sum 生成器 + zip(strict=)

`backend/app/services/rag_index_service.py`：

```python
    ids = {node["id"] for node in desired}
    removed = sum(node["id"] not in ids for node in existing)
    if not changed and not removed:
        return {"release_id": previous, "nodes": len(desired), "unchanged": len(desired),
                "inserted_or_updated": 0, "deleted": 0, "deduplicated": 0, "table": settings.llama_index_table}
    embedding = build_embedding_model(settings)
    batch_size = max(1, min(settings.embedding_batch_size, 10))
    # 离线摄取没有请求 trace：本轮自建累加器，不管成功失败都在 finally 落库 ——
    # 中途失败时前面几批的 embedding 已经花掉了，账不能跟着异常一起丢。
    usage = UsageRecorder()
    try:
        for start in range(0, len(changed), batch_size):
            batch = changed[start:start + batch_size]
            vectors = await embedding.embed(
                [node["content"] for node in batch], usage=usage, stage="ingest_embed",
            )
            if len(vectors) != len(batch):
                raise ValueError("Embedding 数量与片段不匹配")
            for node, vector in zip(batch, vectors, strict=True):
```

- `{node["id"] for node in desired}` 集合推导式：建 `desired` 的 id 集合供 O(1) 成员查询；
- `sum(node["id"] not in ids for node in existing)`：布尔生成器喂 `sum`，数出"被移除的节点数"——比先建列表再 `len` 省一次中间容器；
- `changed[start:start + batch_size]` 切片分页；`[node["content"] for node in batch]` 抽出文本列表；
- `zip(batch, vectors, strict=True)`：embedding 返回数必须与 batch 等长——虽然前一行已 `len` 校验，`strict=True` 仍是双保险，把"错位配对"从数据 bug 变成异常。

同文件 `_existing`（`backend/app/services/rag_index_service.py`）还演示了"列表推导式拼 dict"：

```python
    return (config["configuration"] if config else ""), [
        {"id": row["node_id"], "content": row["text"], "metadata": row["metadata_"]} for row in rows
    ]
```

### `retrieval_service._fetch_candidate_rows`：sorted + lambda

`backend/app/services/retrieval_service.py`：

```python
        with timed_stage(trace, "rrf_fusion_ms"):
            candidate_ids = [
                chunk_id
                for chunk_id, _ in sorted(
                    ranks.items(),
                    key=lambda item: item[1].rrf_score(self.settings.retrieval_rrf_k),
                    reverse=True,
                )
            ]
```

- `ranks.items()` 产出 `(chunk_id, CandidateRanks)` 键值对；
- `key=lambda item: item[1].rrf_score(...)`：按值对象的 RRF 分数排序，`item[1]` 取元组第二项；
- `reverse=True` 降序；外层列表推导式 `for chunk_id, _ in ...` 用 `_` 丢弃分数只留 id。

### `tools.py`：`{**cached, "deduplicated": True}`

`backend/app/agent/tools.py` `grade_plugin`：

```python
        dedup_key = f"{protocol}:{hashlib.sha256(exec_bytes).hexdigest()}"
        cached = context.graded_sources.get(dedup_key)
        if cached is not None:
            return {"ok": True, "data": {**cached, "deduplicated": True}}
```

- `context.graded_sources.get(dedup_key)` 查同请求去重表，命中时 `{**cached, "deduplicated": True}` 复制缓存结果并打标——原 `cached` 不被污染（后面别的调用命中时拿到的还是干净副本）。

### `task_service._run_with_deadline`：`del` 切片截断

`backend/app/services/task_service.py`：

```python
    async def emit(event: Dict[str, Any]) -> None:
        managed.events.append(event)
        if len(managed.events) > _EVENT_LOG_CAP:
            del managed.events[: len(managed.events) - _EVENT_LOG_CAP]
        for queue in managed.subscribers:
            queue.put_nowait(event)
```

- `managed.events` 是事件回放日志（cap 500）；每 `append` 一条就检查一次，超长时 `del events[: len - cap]` 从头部切掉溢出的最旧若干条——"环形缓冲"的最简写法，不用 `deque`；
- `queue.put_nowait` 无界入队不阻塞——订阅者慢不反压执行体（这正是该函数 docstring 说的扇出语义）。

## 练习

1. **读项目代码回答**：`rag_index_service.py` `plan_nodes` 用 `(source_key, ordinal)` 的 tuple 作 dict 键。为什么这里可以用 tuple 而 `by_position` 不能直接用 `meta`（整个 dict）作键？
2. **读项目代码回答**：`task_service.py` `emit` 里 `del managed.events[: len(managed.events) - _EVENT_LOG_CAP]` 与 `managed.events = managed.events[-_EVENT_LOG_CAP:]` 效果有何不同？为什么前者对 `ManagedTask` 更合适？
3. **动手写代码**：不用 `set`，只用 dict 写一个 `dedupe(seq)`，保持首次出现顺序去重，返回 list。验证 `dedupe([3,1,3,2,1]) == [3,1,2]`。
4. **动手写代码**：实现 `top_k_scores(ranks, k)`：`ranks` 是 `{id: score}` 的 dict，按分数降序取前 k 个 id（分数相同的顺序不限），用 `sorted` + `key=lambda` 完成，并写成列表推导式返回。
5. **动手写代码**：`plan_nodes` 的 `removed` 用 `sum(... for node in existing)` 统计。写一个等价的 `count_missing(existing_ids, desired_ids)`，要求一行生成器表达式完成（不用 `len(set(...) - set(...))`）。

## 参考答案

<details><summary>参考答案 1</summary>

dict 键必须可哈希；`meta` 是 dict，本身不可哈希（`hash({})` 抛 `TypeError`），不能直接作键。tuple `(meta.get("source_key"), meta.get("ordinal"))` 的两个元素都是可哈希的（字符串/整数/None），所以 tuple 可哈希。更本质的原因：这里要的是"按位置身份索引"——同一 `(source_key, ordinal)` 代表"同一来源的同一块"，是值语义；整个 `meta` dict 既不可哈希也不是要找的身份。

</details>

<details><summary>参考答案 2</summary>

两种写法在本处都可行，区别只在对象身份：`del managed.events[:n]` 是**原地**删除——同一个 list 对象被截短，所有持有该列表引用的代码（`ManagedTask.events` 字段、`task_service.subscribe` 里 `snapshot = list(managed.events)` 取快照的逻辑）看到的还是那同一个对象；`managed.events = managed.events[-cap:]` 是**重新绑定**——`managed.events` 指向一个新列表，旧对象原封不动留在原处（已被 `snapshot` 复制的快照不受影响，因为快照本身就是一份新列表）。`emit` 内部没有任何 `await`，asyncio 单线程下它从 `append` 到 `put_nowait` 是原子执行的，不存在"改到一半被别的协程看到"的窗口——两种写法选哪种纯粹是"就地维护同一容器"与"换一个新容器"的语义选择，项目选了语义更直接的原地 `del`。

</details>

<details><summary>参考答案 3</summary>

```python
def dedupe(seq):
    seen = {}
    for x in seq:
        if x not in seen:
            seen[x] = True
    return list(seen)

print(dedupe([3, 1, 3, 2, 1]))   # [3, 1, 2]
```

利用 dict 保插入序：键就是"见过的元素"，遍历顺序即首次出现顺序。更短的等价写法：`list(dict.fromkeys(seq))`——`dict.fromkeys` 同样按插入序建键。

</details>

<details><summary>参考答案 4</summary>

```python
def top_k_scores(ranks, k):
    return [
        rid
        for rid, _ in sorted(ranks.items(), key=lambda item: item[1], reverse=True)[:k]
    ]

print(top_k_scores({"a": 0.1, "b": 0.9, "c": 0.5}, 2))   # ['b', 'c']
```

与项目里 `retrieval_service` 的写法同构：`ranks.items()` → `sorted(key=lambda item: item[1]...)` → 推导式取 id。切片 `[:k]` 放在 `sorted` 之后。

</details>

<details><summary>参考答案 5</summary>

```python
def count_missing(existing_ids, desired_ids):
    wanted = set(desired_ids)
    return sum(eid not in wanted for eid in existing_ids)

print(count_missing(["a", "b", "c"], ["b", "c", "d"]))   # 1：'a' 不在 desired
```

`sum` 把布尔值当 0/1 累加——这正是 `ingest_release` 里 `removed = sum(node["id"] not in ids for node in existing)` 的计数手法；`wanted` 先建成 set 保证 `not in` 是 O(1)。

</details>

## 延伸阅读

- Python 官方教程："Data Structures"（list/dict/set/tuple、推导式、循环技巧 `enumerate`/`zip`/`sorted`）。
- 语言参考："Expressions"（解包、推导式语义）；"Special method names"（`__hash__`/`__eq__` 与 dict 键）。
- PEP 709（3.12 推导式内联，对照理解 3.11 行为）、PEP 584（dict `|` 运算）、PEP 618（`zip(strict=)`）。
- `dis` 模块文档；"Sorting HOW TO"（`key` 函数与 `reverse`）。
