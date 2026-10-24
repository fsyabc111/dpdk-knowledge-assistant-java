# 03 字符串、f-string、字节与 JSON

## 学习目标

- 熟练使用 `str` 常用方法：`split` / `strip` / `startswith` / `join` / `replace` / `lower` 等，知道 `str` 不可变；
- 会写 f-string：格式说明符（`:03d`、`:.2f`）、转换标志 `!r`、调试语法 `{x=}`；
- 分清 `str`（Unicode 文本）与 `bytes`（字节串），会 `encode` / `decode`、知道 `errors="replace"` 的语义；
- 会用 `hashlib.sha256(...).hexdigest()` 做内容指纹；
- 会用 `json.dumps(ensure_ascii=False)` / `json.loads`，理解 `separators`、`default` 参数；
- 会写原始字符串与 `re` 基础（`compile` / `finditer` / `split`）。

**本主题在项目里出现在哪**

| 位置 | 用到什么 |
| --- | --- |
| `backend/app/agent/tools.py` `grade_plugin` / `_record_submission` | `exec_source.encode("utf-8", errors="replace")`、`f"{protocol}:{hashlib.sha256(exec_bytes).hexdigest()}"` 去重键与 `code_hash` |
| `backend/app/services/task_service.py`、`backend/app/main.py`、`backend/app/agent/graph.py` | `logger.warning(json.dumps({"event": ...}, ensure_ascii=False))` 结构化日志 |
| `backend/app/services/rag_issue_service.py` `input_hash` | `json.dumps([...], ensure_ascii=False, separators=(",", ":"))` + `sha256` 生成请求指纹 |
| `backend/app/api/chat.py` `product_events` | f-string 拼 SSE 帧 `f"event: {...}\ndata: {...}\n\n"` |
| `backend/app/services/knowledge_sources.py` | `re.compile` 的 `FUNC_RE`/`STRUCT_RE` 切块、`re.split(r"\n\s*\n", ...)` 分段 |
| `backend/app/services/rag_issue_service.py` `begin_admitted` | `f"INSERT INTO ... VALUES ({','.join([ph] * 11)})"` 用 `join` 拼占位符 |

## 语法详解

### str：不可变文本

`str` 是 Unicode 字符序列，**不可变**——所有"修改"方法都返回新串：

```python
s = "  DPDK 报文检测  "
print(s.strip())                    # 去两端空白
print(s.lower().strip().startswith("dpdk"))
print("a,b,,c".split(","))          # ['a', 'b', '', 'c']：split 保留空段
print("a\n\nb\n  \nc".splitlines()) # 按行
print(", ".join(["x", "y"]))        # 'x, y'：join 是 str 的方法，参数是可迭代
print("abc".replace("b", "X"), "abc"[:2])
```

输出：

```text
DPDK 报文检测
True
['a', 'b', '', 'c']
['a', '', 'b', '  ', 'c']
x, y
aXc ab
```

`split` vs `splitlines`、`strip` vs `replace(" ", "")` 是常见混淆点。`join` 的方向也容易记反——是 `分隔符.join(序列)`，不是 `序列.join(分隔符)`。

### f-string

`f"..."` 在编译期转成"格式化 + 拼接"，花括号内是任意表达式：

```python
name = "DPDK"; count = 7
print(f"user={name!r} n={count:03d} {count=}")
```

输出：`user='DPDK' n=007 count=7`

- `{name!r}`：`!r` 调 `repr()`（`!s` 调 `str()`）；
- `{count:03d}`：冒号后是**格式说明符**——`03d` 补零到 3 位十进制；常用还有 `:.2f`（两位小数）、`:>10`（右对齐）、`:,`（千分位）；
- `{count=}`：`=` 调试语法，输出 `count=7`（连名字带值），等价于 `count={count!r}`。

f-string 里不能放语句，但可以放条件表达式、函数调用；3.11 中花括号内**不能含反斜杠**（`f"{x\n}"` 报错，3.12 才放开）——需要换行时写到花括号外。

### str ↔ bytes

`str` 是"字符"，`bytes` 是"字节"。两者互转必须指定编码：

```python
s = "abc测"
b = s.encode("utf-8")
print(b, len(b))                            # b'abc\xe6\xb5\x8b' 6
print(b"\xff\xfe".decode("utf-8", errors="replace"))  # '��'
```

- `"测"` UTF-8 编码为 3 字节，所以 `len(b)` 是 6 不是 4；
- `errors="replace"`：解码失败字节替换成 `U+FFFD`（`�`）而不是抛 `UnicodeDecodeError`——对"来源不可信的字节"（学生提交的代码、上游返回）是安全的兜底；`encode` 侧同理，无法编码的字符变成 `?`。

`bytes` 字面量写作 `b"..."`，支持 `bytes.hex()` / `bytes.fromhex()` 等。

### hashlib：内容指纹

```python
import hashlib
print(hashlib.sha256("abc".encode("utf-8")).hexdigest()[:16])
```

输出：`ba7816bf8f01cfea`

`hashlib.sha256` 吃的是 **bytes**——所以要先 `.encode()`。本项目用它做幂等键：同一份源码字节永远得到同一个 64 位十六进制串，天然去重。

### JSON

```python
import json
print(json.dumps({"事件": "x"}))                    # 默认 ensure_ascii=True → 转义
print(json.dumps({"事件": "x"}, ensure_ascii=False)) # 中文原样输出
print(json.dumps([1, "a", None], separators=(",", ":")))  # 最紧凑分隔符
print(json.loads('{"a": 1}'))
```

输出：

```text
{"\u4e8b\u4ef6": "x"}
{"事件": "x"}
[1,"a",null]
{'a': 1}
```

- `ensure_ascii=False` 让中文直接落盘/进日志——本项目所有结构化日志都这么写；
- `separators=(",", ":")` 去掉冒号/逗号后空格，产出最紧凑串（`input_hash` 用它保证同一输入永远编码成同一字节串）；
- `json.loads` 把 str 解析回 Python 对象（`null`→`None`、`true`→`True`）；解析失败抛 `json.JSONDecodeError`（`ValueError` 子类）；
- Python `dict`/`list`/`str`/`int`/`float`/`bool`/`None` 可直接序列化，其他对象（如 `datetime`、自定义类）要传 `default=` 回调或先转成基础类型。

### 原始字符串与 re

`r"..."` 中反斜杠不转义，写正则必备：

```python
import re
print(re.split(r"\n\s*\n", "a\n\nb\n  \nc"))   # 按空行分段
pat = re.compile(r"^\s*(?:static\s+)?(?:inline\s+)?[A-Za-z_][\w\s\*]{0,120}\s+([A-Za-z_]\w*)\s*\(", re.M)
m = pat.search("static int foo(int x) {")
print(m.group(0), m.group(1))
```

输出：

```text
['a', 'b', 'c']
static int foo( foo
```

- `\s` 空白、`\w` 单词字符、`(?:...)` 非捕获分组、`(...)` 捕获分组、`re.M` 多行模式（`^`/`$` 匹配每行）；
- `re.compile` 预编译后复用比每次传 pattern 快，且模块级常量如 `FUNC_RE` 只编译一次；
- `match.group(0)` 是整个匹配，`group(1)` 起是各捕获组。

## 运行机制与 CPython 实现

### 等价改写：f-string

```python
f"user={name!r} n={count:03d}"
```

大致等价于：

```python
"user=" + format(repr(name), "") + " n=" + format(count, "03d")
# 即 ''.join(["user=", repr(name).__format__(""), " n=", count.__format__("03d")])
```

注意 `!r` 不是把 `"r"` 当格式说明符——`format(name, "r")` 会抛 `ValueError: Unknown format code 'r' for object of type 'str'`（已实跑）。`!r` 是**先调 `repr(obj)`** 得到一个字符串，再让那个字符串按（空的）格式说明符走 `__format__`。

### 字节码：FORMAT_VALUE / BUILD_STRING

以下输出由 `backend/.venv/bin/python`（Python 3.11.15）实跑 `dis.dis` 得到：

```python
def render(name, count):
    return f"user={name!r} n={count:03d} {count=}"
```

```text
 24           0 RESUME                   0

 25           2 LOAD_CONST               1 ('user=')
              4 LOAD_FAST                0 (name)
              6 FORMAT_VALUE             2 (repr)
              8 LOAD_CONST               2 (' n=')
             10 LOAD_FAST                1 (count)
             12 LOAD_CONST               3 ('03d')
             14 FORMAT_VALUE             4 (with format)
             16 LOAD_CONST               4 (' count=')
             18 LOAD_FAST                1 (count)
             20 FORMAT_VALUE             2 (repr)
             22 BUILD_STRING             6
             24 RETURN_VALUE
```

逐条读：

- 字面量片段（`'user='` 等）直接 `LOAD_CONST`；
- 每个插值一次 `FORMAT_VALUE`：`2 (repr)` 对应 `!r`，`4 (with format)` 对应带格式说明符——说明符串 `'03d'` 也压栈；
- `{count=}` 被编译成字面量 `' count='` + `repr` 格式化——证明它确实等价于 `count={count!r}`；
- 最后 `BUILD_STRING 6` 一次性拼接 6 段——f-string 不是逐次 `+`，而是拼一次，这就是它比 `"...".format()` 与 `%` 都快的理由。

### CPython 源码

- `str` 的存储结构是 PEP 393 的弹性表示：CPython 3.11 分支 `Objects/unicodeobject.c` 中 `PyASCIIObject` / `PyCompactUnicodeObject` 让纯 ASCII 串每字符只占 1 字节，含宽字符才用 2/4 字节——所以 Python 3 的中文串没有单独的 "unicode 类型"。
- `json.dumps` 的默认路径是纯 Python：本机 3.11.15 用 `inspect.getsource` 取出 `Lib/json/__init__.py` 的真实源码（摘录）：

  ```python
  def dumps(obj, *, skipkeys=False, ensure_ascii=True, check_circular=True,
          allow_nan=True, cls=None, indent=None, separators=None,
          default=None, sort_keys=False, **kw):
      # cached encoder
      if (not skipkeys and ensure_ascii and
          check_circular and allow_nan and
          cls is None and indent is None and separators is None and
          default is None and not sort_keys and not kw):
          return _default_encoder.encode(obj)
      if cls is None:
          cls = JSONEncoder
      return cls(
          skipkeys=skipkeys, ensure_ascii=ensure_ascii,
          check_circular=check_circular, allow_nan=allow_nan, indent=indent,
          separators=separators, default=default, sort_keys=sort_keys,
          **kw).encode(obj)
  ```

  全部参数都是仅关键字（`*` 后）；默认参数组合走缓存的 `_default_encoder`，`ensure_ascii=False` 等非默认参数则现场构造 `JSONEncoder`——所以项目里每条日志的 `json.dumps(..., ensure_ascii=False)` 每次都会新建 encoder（量级很小，不是热点）。

## 常见陷阱

1. **`str` 不可变**：`s[0] = "x"` 抛 `TypeError`；循环里 `s += piece` 每次新建串，拼大量段用 `"".join(parts)` 或 `io.StringIO`。
2. **`bytes` 不是 `str`**：`b"abc" + "def"` 抛 `TypeError`；网络/文件读出的原始数据先 `decode`，落盘/上链前先 `encode`。`str(b)` 得到的是 `"b'...'"` 的 repr 文本，不是解码——要 `b.decode()`。
3. **`json.dumps` 默认转义非 ASCII**：日志里中文变 `\uXXXX` 不是 bug 是默认；本项目统一 `ensure_ascii=False`。
4. **`json.loads` 对格式敏感**：单引号、尾逗号都是非法 JSON（`json.loads("{'a':1}")` 报错）——Python 字面量 ≠ JSON。
5. **`split()` 无参 vs 有参**：`"a  b".split()` 按任意连续空白切且不产空段，`split(" ")` 按单个空格切会留 `''`。
6. **f-string 中花括号要转义写 `{{` / `}}`**；嵌套引号 3.11 中不能复用同种引号（`f"{"x"}"` 非法）。
7. `hashlib.sha256(s)` 直接传 str 报 `TypeError`，必须先 encode——去重键的"同文同键"依赖编码口径一致，项目里统一 `encode("utf-8", errors="replace")`。
8. `errors="replace"` 会**掩盖**编码错误：能修源头就别静默替换；它适合"宁可降级也不抛"的边界（不可信输入），不适合"必须无损"的场景。

## 项目代码精读

> 本节代码块均按仓库原文摘录（保留原缩进与注释），依赖项目内部上下文，**不可单独运行**。

### `tools.py`：sha256 去重键

`backend/app/agent/tools.py` `grade_plugin`：

```python
        exec_bytes = exec_source.encode("utf-8", errors="replace")
        # B7：同请求内同 (protocol, 执行源) 的重复评测直接复用首次结果——
        # 先于配额扣减与 MCP 调用，不重复记账、不重复落库。表里只有成功
        # 载荷，失败/超时不会命中，模型可照常重试。
        dedup_key = f"{protocol}:{hashlib.sha256(exec_bytes).hexdigest()}"
        cached = context.graded_sources.get(dedup_key)
        if cached is not None:
            return {"ok": True, "data": {**cached, "deduplicated": True}}
```

逐行解读：

- `exec_source.encode("utf-8", errors="replace")`：执行源是 `str`，哈希前先定编码口径；`errors="replace"` 保证即使源里有无法编码的字符也不抛错（对模型复述的代码这种不可信输入尤其重要）；
- `f"{protocol}:{...hexdigest()}"`：f-string 拼"协议 + 内容指纹"的去重键——同一协议同一源码字节必然同键；
- 命中后 `{**cached, "deduplicated": True}` 返回（见 `02` 篇）；
- 同文件 `_record_submission` 里 `hash_source = exec_source.encode("utf-8", errors="replace")` + `code_hash=hashlib.sha256(hash_source).hexdigest()` 用同一口径落库——**编码口径统一是"同文同键"成立的前提**，注释明确写了 hash 以执行源而非模型参数文本为准。

### `input_hash`：紧凑 JSON + sha256

`backend/app/services/rag_issue_service.py`：

```python
    encoded = json.dumps([normalize(question), filename, digest], ensure_ascii=False, separators=(",", ":"))
    return hashlib.sha256(encoded.encode("utf-8")).hexdigest()
```

- 三个字段先装进 list 序列化成 JSON——`separators=(",", ":")` 去空格保证**同一输入逐字节相同**（否则 dumps 的默认空格会让"同输入不同指纹"）；
- `ensure_ascii=False` 让中文按 UTF-8 进入哈希，与转义形式得到不同但确定的指纹——只要全系统统一口径即可；
- 最后 `.encode("utf-8")` 交给 `sha256`。

### 结构化日志：`json.dumps(..., ensure_ascii=False)`

`backend/app/services/task_service.py`：

```python
            logger.warning(json.dumps({"event": "task_heartbeat_failed"}, ensure_ascii=False))
```

`backend/app/main.py` `on_startup`：

```python
            logger.error(json.dumps({"event": "schema_version_mismatch", "reason": str(exc)}, ensure_ascii=False))
```

- 全仓日志约定：每条日志是一个 JSON 对象，`event` 字段是机器可过滤的事件名，其余键是上下文；
- `str(exc)` 把异常对象转成可读文本（异常的 `str()` 详见 `05` 篇）；
- `core/logging_config.py` 的 formatter 也走 `json.dumps(payload, ensure_ascii=False)` 同一口径。

### `chat.py product_events`：f-string 拼 SSE 帧

`backend/app/api/chat.py`：

```python
    async def product_events():
        try:
            async for item in stream:
                yield f"event: {item['event']}\ndata: {json.dumps(item['data'], ensure_ascii=False)}\n\n"
        except asyncio.CancelledError:
            raise
        except Exception:
            yield 'event: error\ndata: {"message":"服务暂时不可用，请稍后重试","retryable":true}\n\n'
    return StreamingResponse(product_events(), media_type="text/event-stream", headers={
        "Cache-Control": "no-cache", "X-Accel-Buffering": "no",
    })
```

- SSE 帧格式就是文本：`event: <名>\ndata: <JSON>\n\n`——f-string 把事件名插进去、`json.dumps` 保证 data 单行（JSON 内 `\n` 是转义的 `\n` 两字符，不会撕帧）；
- `item['event']` 用的是单引号，因为外层 f-string 已是双引号；
- `except` 分支兜底帧是**手写死**的 JSON 字面量，注意它内部全用双引号、外层用单引号包；
- `async for`/`yield`（异步生成器）见 `09`、`15` 篇；两个 `except` 的顺序语义见 `05` 篇。

### `knowledge_sources.py`：正则切块

`backend/app/services/knowledge_sources.py`：

```python
FUNC_RE = re.compile(r"^\s*(?:static\s+)?(?:inline\s+)?[A-Za-z_][\w\s\*\(\)]{1,120}\s+([A-Za-z_]\w*)\s*\([^;]*\)\s*\{", re.M)
STRUCT_RE = re.compile(r"^\s*typedef\s+struct\s+([A-Za-z_]\w*)?", re.M)


def chunk_code(text: str, rel_path: str) -> List[SourceChunk]:
    lines = text.splitlines()
    starts = []
    for match in FUNC_RE.finditer(text):
        starts.append((text[: match.start()].count("\n"), match.group(1)))
```

- `FUNC_RE` 匹配 C 函数定义行首：`(?:static\s+)?` 等可选修饰、`([A-Za-z_]\w*)` 捕获函数名到 `group(1)`、`\{` 要求行内出现函数体左括号；
- `finditer` 逐个产出 `Match` 对象；`match.start()` 给出命中位置的字符下标，`text[:match.start()].count("\n")` 数前面有多少换行换算出行号；
- `starts` 收集 `(行号, 符号名)` 元组列表——元组作容器元素，后面 `starts.sort()` 按元组字典序排（先行号）；
- `split_long_chunk` 里 `re.split(r"\n\s*\n", content)` 按"空行"切段（`\n\s*\n` 匹配两个换行之间的纯空白行）。

## 练习

1. **读项目代码回答**：`agent/tools.py` 中 `exec_source.encode("utf-8", errors="replace")` 与 `input_hash` 中的 `encoded.encode("utf-8")` 编码参数为什么不一样（一个带 `errors=` 一个不带）？分别防的是什么？
2. **读项目代码回答**：`api/chat.py` `product_events` 里 `json.dumps(item['data'], ensure_ascii=False)` 为什么不能换成 `str(item['data'])`？换成后在 SSE 帧格式与前端解析上各有什么问题？
3. **动手写代码**：写一个 `sse_frame(event: str, data: dict) -> str`，产出与项目同形的帧（`event: X\ndata: <json>\n\n`，JSON 用 `ensure_ascii=False`），并验证 `sse_frame("delta", {"文本": "你好"})` 的输出与项目写法逐字符一致。
4. **动手写代码**：写一个 `stable_key(protocol: str, source: str) -> str`，复刻 `dedup_key` 的口径（`f"{protocol}:{sha256(source.encode('utf-8', errors='replace')).hexdigest()}"`），并验证对同一输入两次调用结果一致、改一个字符就不同。
5. **动手写代码**：用 `re` 写 `extract_func_name(line: str) -> str | None`：若 `line` 是 `static int rte_hash_create(...) {` 这类函数定义开头，返回函数名，否则返回 `None`。参考 `FUNC_RE` 的子集即可。

## 参考答案

<details><summary>参考答案 1</summary>

`exec_source` 来自模型复述或用户粘贴，是**不可信文本**：里面可能混有孤立代理（lone surrogate）等异常字符，`errors="replace"` 保证哈希总能算出来（宁可以替换后的字节为准，也不让评测流程炸掉）。

`input_hash` 一侧的说法需要修正得更精确：`json.dumps` **不会**清洗孤立代理——`json.dumps(["\ud800"], ensure_ascii=False)` 实测得到 `'["\ud800"]'`（原样保留），随后的 `.encode("utf-8")` 会抛 `UnicodeEncodeError: 'utf-8' codec can't encode character '\ud800' ... surrogates not allowed`。也就是说"自己 dumps 出来的串一定能 encode"在 Python 层面并不成立。

但这条路径在当前代码下从 HTTP 入口到不了：`question` 来自 `ChatRequest` 的 `message` 字段，pydantic v2 的 str 校验在边界就拒绝孤立代理——实测（`.venv`，3.11.15）：

```text
ChatRequest.model_validate_json('{"message":"\\ud800"}')
  → ValidationError: Invalid JSON: unexpected end of hex escape
ChatRequest.model_validate({"message": "\ud800"})
  → ValidationError: unable to parse raw data as a unicode string
```

两条入口（原始 JSON 与已解码 dict）都被拦在 `input_hash` 之前，所以裸 `encode("utf-8")` 在受校验的输入下不会触发；这个结论只描述实测到的行为，不代表"任何调用方都到不了"——绕过校验层直接调 `input_hash` 的代码路径若喂入代理字符仍会抛错。

</details>

<details><summary>参考答案 2</summary>

`str(dict)` 产出的是 Python repr：键用单引号、`True`/`None` 大写——这不是合法 JSON，前端 `JSON.parse` 会失败（中文倒是原样保留：`str({"文本": "你好"})` → `{'文本': '你好'}`）。其次，repr 里可能含真实换行（字符串值里的 `\n`），SSE 协议以 `\n\n` 为帧边界、每行以 `\n` 分字段，裸 `str()` 会撕帧；`json.dumps` 输出的所有控制字符都被转义，永远单行。`json.dumps(..., ensure_ascii=False)` 是全仓约定的唯一序列化口径——前端按 JSON 解析、日志按 JSON 过滤，都依赖这个格式约定而不是 Python repr。

</details>

<details><summary>参考答案 3</summary>

```python
import json

def sse_frame(event: str, data: dict) -> str:
    return f"event: {event}\ndata: {json.dumps(data, ensure_ascii=False)}\n\n"

print(repr(sse_frame("delta", {"文本": "你好"})))
```

实跑输出：`'event: delta\ndata: {"文本": "你好"}\n\n'`——与 `product_events` 中 `yield` 的帧同形：`event:`/`data:` 前缀、JSON 单行、帧尾空行。

</details>

<details><summary>参考答案 4</summary>

```python
import hashlib

def stable_key(protocol: str, source: str) -> str:
    return f"{protocol}:{hashlib.sha256(source.encode('utf-8', errors='replace')).hexdigest()}"

k1 = stable_key("tcp", "int main() {}")
k2 = stable_key("tcp", "int main() {}")
k3 = stable_key("tcp", "int main() {/*x*/}")
print(k1 == k2, k1 == k3, k1[:12])
```

实跑输出：`True False tcp:00096d96`——同一源码字节永远同键（`k1 == k2`），任何字节差异（哪怕一个注释）都换键（`k1 != k3`），这正是幂等去重要求的性质。

</details>

<details><summary>参考答案 5</summary>

```python
import re

FUNC_HEAD = re.compile(r"^\s*(?:static\s+)?(?:inline\s+)?[A-Za-z_][\w\s\*]*\s+([A-Za-z_]\w*)\s*\(", re.M)

def extract_func_name(line: str):
    m = FUNC_HEAD.match(line)
    return m.group(1) if m else None

print(extract_func_name("static int rte_hash_create(int x) {"))   # rte_hash_create
print(extract_func_name("  void helper(char *p) {"))              # helper
print(extract_func_name("x = foo(1);"))                            # None
```

`FUNC_RE` 的关键件：`^\s*` 允许缩进、`(?:static\s+)?` 非捕获可选修饰、返回类型用 `[\w\s\*]*` 粗匹配、`([A-Za-z_]\w*)` 捕获函数名、`\s*\(` 必须紧跟左括号——赋值/调用语句因"名字前没有类型前缀"或"括号后直接 `)` 无 `{`"而不命中（项目版还要求 `\{`，这里放宽只演示捕获）。

</details>

## 延伸阅读

- Python 官方教程："Strings"（`String` 方法一览）、"Input and Output"（f-string 与 `str.format`）。
- 语言参考："Format Specification Mini-Language"（格式说明符全集）、"Binary Sequence Types"（bytes）。
- `json` 模块文档（`dump`/`dumps`/`load`/`loads` 参数）、`hashlib` 模块文档、`re` 模块文档与 "Regular Expression HOWTO"。
- PEP 393（Flexible String Representation）、PEP 498（f-string）、PEP 701（3.12 f-string 语法放宽，对照 3.11 限制）。
