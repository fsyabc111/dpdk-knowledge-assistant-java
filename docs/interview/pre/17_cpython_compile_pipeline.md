# 17 CPython 编译流水线：从源码到字节码到执行

## 学习目标

- 说得清 `python x.py` / `import x` 时 CPython 做了什么：源码 → tokens → AST → **code 对象** → 虚拟机逐条执行；
- 认识 code 对象的关键字段（`co_code` / `co_consts` / `co_varnames` / `co_argcount` / `co_stacksize` / `co_flags`），并能用 `compile()`、`ast.parse()`、`dis.dis()` 亲手走完整条流水线；
- 建立「栈机器」直觉：能对着 `dis` 输出推演操作数栈的变化；
- 分清三层产物的创建时机与分工：**code 对象（编译期）→ function 对象（`def` 执行时）→ frame 对象（每次调用时）**；
- 知道 `.pyc` 缓存的文件结构（16 字节头 + marshal 化的 code 对象）、失效机制、为什么不能跨版本；
- 知道 3.11 的 specializing adaptive interpreter（PEP 659）在做什么，`dis(adaptive=True)` 看到的是什么。

前置篇目：`01_functions_and_arguments.md`（函数是对象、`__defaults__`）、`11_scope_and_closures.md`（闭包/`co_cellvars`/`co_freevars`）。本篇偏「理解机制」，没有新语法。

**本主题在项目里出现在哪**

| 位置 | 用到什么 |
| --- | --- |
| `backend/app/services/retrieval_service.py` `_rewrite`（`inspect.signature(rewrite)`） | 运行时读签名——`inspect` 底层就是翻 `__code__` 的字段 |
| `backend/app/services/rag_product_service.py` `_assemble_orchestrator` | 函数体内 `from ..agent.service import ...` 惰性 import，字节码层面是 `IMPORT_NAME` 指令 |
| `backend/app/agent/graph.py` `build_agent_graph` 末尾 `graph.compile()` | LangGraph 的「compile」是装配语义，与字节码无关——术语辨析 |
| `uvicorn app.main:app` 启动后端 | 字符串路径 → importlib → 触发整包的编译与顶层语句执行 |
| `backend/app/agent/tools.py` `grade_plugin` | 学生代码**绝不**在本进程 `exec`——本篇解释为什么这是一条红线 |

## 全景：Python 也是「编译」的

先纠正一个常见误解。Python 和 C 一样要编译，区别只在产物：

```text
C:      .c ──编译器──> 机器码  ──> CPU 直接执行
Python: .py ──编译器──> 字节码 ──> CPython 虚拟机（一个 C 写的「软件 CPU」）逐条执行
```

所谓「解释型语言」，真实含义是**机器码这一层由一个 C 写的解释循环模拟**：字节码指令不进 CPU，而是被 `Python/ceval.c` 里的 dispatch 循环逐条取出、执行。编译是真的编译——有完整的词法/语法/代码生成阶段，只是目标指令集是「CPython 字节码」而不是 x86/ARM。

这套设计的两个直接后果：

- **跨平台**：字节码不绑定硬件，同一份 `.pyc` 任何系统的同版本解释器都能跑；
- **动态性有物理载体**：`def` 能在运行时造函数、`eval` 能跑动态代码、函数能挂属性——因为「程序」在运行时以 code 对象/function 对象的形式真实存在，你可以拿到手里操作。静态编译语言里函数在编译后只剩一个地址。

## 亲手走一遍流水线

流水线的每一级产物 Python 都暴露给了你，不需要读 C 源码就能验证：

```python
import ast, io, tokenize

src = "total = a + b * 2"

print("=== 第 1 步：tokenize（词法分析）===")
for tok in tokenize.generate_tokens(io.StringIO(src).readline):
    print(f"  {tokenize.tok_name[tok.type]:<10} {tok.string!r}")

print("\n=== 第 2 步：ast.parse（语法分析 → AST）===")
print(ast.dump(ast.parse(src), indent=2))

print("=== 第 3 步：compile（AST → code 对象）===")
code = compile(src, "<demo>", "exec")
print(" ", type(code), code)

print("=== 第 4 步：虚拟机执行 code 对象 ===")
ns = {"a": 1, "b": 2}
exec(code, ns)
print("  total =", ns["total"])
```

输出（3.11.15 实跑）：

```text
=== 第 1 步：tokenize（词法分析）===
  NAME       'total'
  OP         '='
  NAME       'a'
  OP         '+'
  NAME       'b'
  OP         '*'
  NUMBER     '2'
  NEWLINE    ''
  ENDMARKER  ''

=== 第 2 步：ast.parse（语法分析 → AST）===
Module(
  body=[
    Assign(
      targets=[
        Name(id='total', ctx=Store())],
      value=BinOp(
        left=Name(id='a', ctx=Load()),
        op=Add(),
        right=BinOp(
          left=Name(id='b', ctx=Load()),
          op=Mult(),
          right=Constant(value=2))))],
  type_ignores=[])

=== 第 3 步：compile（AST → code 对象）===
  <class 'code'> <code object <module> at 0x103cddf10, file "<demo>", line 1>

=== 第 4 步：虚拟机执行 code 对象 ===
  total = 5
```

读法：

- **tokenize** 把字符流切成 token（`Lib/tokenize.py`），此时还没有语法含义；
- **ast.parse** 把 token 组织成抽象语法树——注意 `a + b * 2` 里乘法在树的下层，优先级在 AST 结构里就已经定死；
- **compile** 把 AST 翻译成 code 对象——这是编译的最终产物，也是 `f.__code__` 里那个东西的同类；
- **exec/eval** 把 code 对象交给虚拟机执行。

`compile(source, filename, mode)` 的 `mode` 有三种：`"exec"`（语句序列，模块/函数体用这个）、`"eval"`（单个表达式，`eval()` 内部走这个）、`"single"`（交互式单条语句，REPL 用——表达式结果非 None 会打印）。

## code 对象逐字段

`co = f.__code__` 拿到的是「这段代码编译期确定的一切」的容器。用一个参数形状复杂的函数把字段看全：

```python
def classify(score, /, level="mid", *, tags=(), **extra):
    """按分数分级。"""
    base = 10
    if score > 80:
        return f"high-{level}"
    return base

co = classify.__code__
print("co_argcount       :", co.co_argcount)
print("co_posonlyargcount:", co.co_posonlyargcount)
print("co_kwonlyargcount :", co.co_kwonlyargcount)
print("co_varnames       :", co.co_varnames)
print("co_names          :", co.co_names)
print("co_consts         :", co.co_consts)
print("co_stacksize      :", co.co_stacksize)
print("co_firstlineno    :", co.co_firstlineno)
print("co_name/filename  :", co.co_name, "/", co.co_filename)
print("co_code           :", co.co_code[:24], "...")
print("co_flags          :", co.co_flags, bin(co.co_flags))
```

输出（3.11.15 实跑）：

```text
co_argcount       : 2
co_posonlyargcount: 1
co_kwonlyargcount : 1
co_varnames       : ('score', 'level', 'tags', 'extra', 'base')
co_names          : ()
co_consts         : ('按分数分级。', 10, 80, 'high-')
co_stacksize      : 2
co_firstlineno    : 2
co_name/filename  : classify / <stdin>
co_code           : b'\x97\x00d\x01}\x04|\x00d\x02k\x04\x00\x00\x00\x00r\x05d\x03|\x01\x9b\x00' ...
co_flags          : 11 0b1011
```

逐字段：

| 字段 | 含义 | 本例 |
| --- | --- | --- |
| `co_argcount` | 位置类参数个数（仅位置 + 位置或关键字） | 2（`score`, `level`） |
| `co_posonlyargcount` | `/` 之前的仅位置参数数 | 1（`score`） |
| `co_kwonlyargcount` | `*` 之后的仅关键字参数数 | 1（`tags`） |
| `co_varnames` | 参数名 + 局部变量名，顺序：位置类 → 仅关键字 → `*args` → `**kw` → 函数体局部变量 | 末位 `base` 是函数体内的局部变量 |
| `co_names` | 本函数引用的**全局名/属性名**（`LOAD_GLOBAL`/`LOAD_ATTR` 按下标查这里） | 空——本函数没碰任何全局名 |
| `co_consts` | 常量表：字面量、docstring（`co_consts[0]`）、嵌套函数的 code 对象 | docstring、`10`、`80`、f-string 片段 |
| `co_stacksize` | 操作数栈深度上限，**编译期算好** | 2 |
| `co_code` | 原始字节码 bytes | 开头的 `\x97\x00` 就是 `RESUME` |
| `co_flags` | 位标志：代码性质 | `0b1011` = `CO_OPTIMIZED(0x1) \| CO_NEWLOCALS(0x2) \| CO_VARKEYWORDS(0x8)`——有 `**extra`、无 `*args`（否则还有 `CO_VARARGS(0x4)`） |

签名/默认值信息是**分两处**存的：`co_*` 字段管「有几个参数、叫什么」，`__defaults__`/`__kwdefaults__` 管「默认值是什么」——前者在 code 对象（静态），后者在 function 对象（动态）。这就是 01 篇练习 4 里 `describe_signature` 要把两边拼起来的原因。

## 栈机器：把 dis 输出读成「栈的变化」

CPython 虚拟机是**栈机器**：指令不指定寄存器，而是对一根「操作数栈」压入/弹出。看懂这点，`dis` 输出就能逐条推演：

```python
import dis

def calc(price, tax=0.05):
    total = price * (1 + tax)
    if total > 100:
        label = "big"
    else:
        label = "small"
    return label

dis.dis(calc)
```

输出（3.11.15 实跑）：

```text
  3           0 RESUME                   0

  4           2 LOAD_FAST                0 (price)
              4 LOAD_CONST               1 (1)
              6 LOAD_FAST                1 (tax)
              8 BINARY_OP                0 (+)
             12 BINARY_OP                5 (*)
             16 STORE_FAST               2 (total)

  5          18 LOAD_FAST                2 (total)
             20 LOAD_CONST               2 (100)
             22 COMPARE_OP               4 (>)
             28 POP_JUMP_FORWARD_IF_FALSE     3 (to 36)

  6          30 LOAD_CONST               3 ('big')
             32 STORE_FAST               3 (label)
             34 JUMP_FORWARD             2 (to 40)

  8     >>   36 LOAD_CONST               4 ('small')
             38 STORE_FAST               3 (label)

  9     >>   40 LOAD_FAST                3 (label)
             42 RETURN_VALUE
```

输出的列：**源码行号 | 字节码偏移 | 指令名 | oparg | (oparg 的人类可读解释)**。`>>` 标记「这里是某条跳转指令的目标」；偏移是**字节偏移**（3.11 起每条指令占 2 字节，数字间隔 2 就是这个原因）。

拿第 4 行 `total = price * (1 + tax)` 逐条推演栈（`[]` 从空栈开始）：

```text
LOAD_FAST  0 (price)    [price]
LOAD_CONST 1 (1)        [price, 1]
LOAD_FAST  1 (tax)      [price, 1, tax]
BINARY_OP  0 (+)        [price, (1+tax)]        # 弹 2 个、加、压回
BINARY_OP  5 (*)        [price*(1+tax)]
STORE_FAST 2 (total)    []                       # 弹栈顶写进局部变量 total
```

栈最深到 2，所以 `co_stacksize` 是 2——编译器在生成字节码时同步算好了这个上界。

三类指令记住就够用：

- `LOAD_*` / `STORE_*`：在栈与变量槽之间搬数据。`FAST` 走局部变量**数组下标**（快），`GLOBAL`/`NAME` 走命名空间 **dict 查找**（慢），`CONST` 压 `co_consts` 里的字面量，`DEREF` 走闭包 cell；
- 运算与调用：`BINARY_OP`、`COMPARE_OP`、`CALL`、`RETURN_VALUE`——弹操作数、算、压结果；
- 控制流：`POP_JUMP_*`、`JUMP_*`、`FOR_ITER`——改写下一条指令的位置。`if/else` 编译成「条件跳 + 无条件跳」两条，`to 36`/`to 40` 就是字节偏移目标。

## 三层产物：code → function → frame

这是本篇最重要的一张表。同一个「函数」在三个时刻有三个不同的东西：

| 产物 | 类型 | 何时创建 | 装什么 | 共享性 |
| --- | --- | --- | --- | --- |
| **code 对象** | `code` | **编译期**（import 或 compile()） | 字节码、常量表、变量名表 | 只读，可被多个函数共享 |
| **function 对象** | `function` | **执行 `def`**（`MAKE_FUNCTION`） | `__code__` + `__globals__` + `__defaults__` + `__closure__` | 每次执行 `def` 新建一个 |
| **frame 对象** | `frame` | **每次调用函数** | `f_code` + 局部变量当前值 + 操作数栈 + `f_lasti` | 每次调用新建一个 |

### 嵌套函数的 code 对象躺在外层的常量表里

```python
import dis

def outer():
    k = 3
    def inner(x):
        return x * k          # k 是自由变量
    return inner

inner_code = [c for c in outer.__code__.co_consts
              if hasattr(c, "co_code")][0]
print("外层常量表中的 code 对象:", inner_code)
print("inner 的自由变量:", inner_code.co_freevars)
print("outer 被捕获的变量:", outer.__code__.co_cellvars)
print()
dis.dis(outer)
```

输出（3.11.15 实跑）：

```text
外层常量表中的 code 对象: <code object inner at 0x10678f500, file "<stdin>", line 6>
inner 的自由变量: ('k',)
outer 被捕获的变量: ('k',)

              0 MAKE_CELL                1 (k)

  4           2 RESUME                   0

  5           4 LOAD_CONST               1 (3)
              6 STORE_DEREF              1 (k)

  6           8 LOAD_CLOSURE             1 (k)
             10 BUILD_TUPLE              1
             12 LOAD_CONST               2 (<code object inner at 0x10678f500, file "<stdin>", line 6>)
             14 MAKE_FUNCTION            8 (closure)
             16 STORE_FAST               0 (inner)

  8          18 LOAD_FAST                1 (inner)
             20 RETURN_VALUE

Disassembly of <code object inner at 0x10678f500, file "<stdin>", line 6>:
              0 COPY_FREE_VARS           1

  6           2 RESUME                   0

  7           4 LOAD_FAST                0 (x)
              6 LOAD_DEREF               1 (k)
              8 BINARY_OP                5 (*)
             12 RETURN_VALUE
```

逐层对上号：

- `inner` 的 **code 对象编译期就存在**，作为字面量躺在 `outer.co_consts` 里——`LOAD_CONST` 那一条加载的就是它；
- `MAKE_CELL` 把 `k` 的存储升级成 cell（可被闭包共享，`co_cellvars` 记录哪些变量被捕获）；`inner` 侧用 `co_freevars` 声明自由变量，`LOAD_DEREF` 读 cell；
- `LOAD_CLOSURE + BUILD_TUPLE + MAKE_FUNCTION 8 (closure)`：执行到 `def inner` 这一行才**造函数对象**，把 code 对象和闭包 cell 组装起来。每次调 `outer()` 都产生一个不同的 `inner` 函数对象——共享同一个 code 对象，闭包各自不同；
- `COPY_FREE_VARS` 是 `inner` 帧启动时把闭包 cell 拷进自己帧的开头指令。

「`def` 是运行时语句」在字节码层面就是这一条 `MAKE_FUNCTION`——它是执行动作，不是编译动作。

### frame：code 对象的执行现场

```python
import sys

def inner():
    a = 10
    fr = sys._getframe()          # 当前正在执行的帧
    print("f_code is inner.__code__:", fr.f_code is inner.__code__)
    print("f_lasti (指令偏移):", fr.f_lasti)
    print("f_locals:", fr.f_locals)
    print("f_back 是外层帧:", fr.f_back.f_code.co_name)
    return a

def outer():
    return inner()

outer()
```

输出（3.11.15 实跑，地址会变）：

```text
f_code is inner.__code__: True
f_lasti (指令偏移): 126
f_locals: {'a': 10, 'fr': <frame at 0x101ab5d80, file '<stdin>', line 9, code inner>}
f_back 是外层帧: outer
```

frame 把「这次调用」的运行时状态打包：`f_code` 指回 code 对象、`f_lasti` 是执行到的字节偏移、`f_locals` 是当前局部变量、`f_back` 链向调用方的帧。异常的 traceback 能逐层打印「哪个文件哪一行」，就是因为每一层调用都留着一个 frame，沿 `f_back` 链一路向上。

生成器/coroutine 能「挂起再恢复」（09、13 篇）也是同一机制：`yield`/`await` 时帧不被销毁，`f_lasti` 记住断点，下次 `send`/`__anext__` 从断点继续。

## .pyc 缓存：编译结果落盘

编译不是免费的，所以 CPython 把结果缓存到 `__pycache__/`：

```python
import importlib.util, marshal, pathlib, py_compile, tempfile, dis

d = pathlib.Path(tempfile.mkdtemp())
p = d / "m.py"
p.write_text("NAME = 'dpdk'\nVALUE = 1 + 2\n")

pyc_path = py_compile.compile(str(p), doraise=True)
data = pathlib.Path(pyc_path).read_bytes()
print("缓存文件:", pathlib.Path(pyc_path).name)
print("magic (前4字节):", data[:4].hex(), "  flags:", int.from_bytes(data[4:8], "little"))

code_obj = marshal.loads(data[16:])     # 头 16 字节后是 marshal 的 code 对象
print("还原:", code_obj)
dis.dis(code_obj)

print("本机 magic:", importlib.util.MAGIC_NUMBER.hex())
```

输出（3.11.15 实跑）：

```text
缓存文件: m.cpython-311.pyc
magic (前4字节): a70d0d0a   flags: 0
还原: <code object <module> at 0x1061ae0b0, file "/var/.../m.py", line 1>
  0           0 RESUME                   0

  1           2 LOAD_CONST               0 ('dpdk')
              4 STORE_NAME               0 (NAME)

  2           6 LOAD_CONST               1 (3)
              8 STORE_NAME               1 (VALUE)
             10 LOAD_CONST               2 (None)
             12 RETURN_VALUE
本机 magic: a70d0d0a
```

结构拆开：

- **前 4 字节 magic number**：标记编译它的解释器版本（`importlib.util.MAGIC_NUMBER`）。版本一变缓存全废——pyc 从不跨版本复用；
- **接下来 4 字节 flags**：`0` 表示「时间戳失效」模式（再 8 字节是源文件 mtime + size，变了就重编）；PEP 552 定义的 hash-based 模式会置位；
- **第 17 字节起**：`marshal` 序列化的 code 对象。`marshal` 是 CPython 专用的对象二进制格式，比 pickle 快但只支持内建类型——选它就是因为 code 对象只需要这个表达力；
- 文件名 `m.cpython-311.pyc`：tag 里带解释器名与版本，`importlib.util.cache_from_source()` 负责这个命名。

顺带一个编译期优化的实证：`VALUE = 1 + 2` 编出来是 `LOAD_CONST 3`——**常量折叠**，编译器把能算的算掉了。CPython 编译器做的优化基本止步于此（不内联、不推导类型——动态语言编译期确定不了类型）；真正的性能工程在运行时的特化，见下节。

## 运行时编译与 import 的字节码

### exec/eval 的命名空间

`exec(code, globals, locals)` 显式接管「这段代码看到什么名字」——名字查找本身就是 dict 操作（`LOAD_NAME`/`STORE_NAME`），所以传什么命名空间它就活在什么世界里：

```python
ns = {}
exec(compile("y = x * 2 + offset", "<dyn>", "exec"),
     {"x": 21, "offset": 1},   # globals：内建与「全局」查找落这里
     ns)                        # locals：写入落这里
print(ns)
```

输出：`{'y': 43}`。REPL、`python -c`、调试器表达式求值全走这条路。

### import 在字节码层就是普通指令

`import` 不是编译期指令——它是**执行期**的字节码，每次都跑：

```python
import dis

def assemble():
    from app.agent.service import AgentOrchestrator
    return AgentOrchestrator

dis.dis(assemble)
```

输出（3.11.15 实跑）：

```text
  4           0 RESUME                   0

  5           2 LOAD_CONST               1 (0)
              4 LOAD_CONST               2 (('AgentOrchestrator',))
              6 IMPORT_NAME              0 (app.agent.service)
              8 IMPORT_FROM              1 (AgentOrchestrator)
             10 STORE_FAST               0 (AgentOrchestrator)
             12 POP_TOP

  6          14 LOAD_FAST                0 (AgentOrchestrator)
             16 RETURN_VALUE
```

- `LOAD_CONST 0` 压的是 level（`0` = 绝对导入；相对导入是包层数）；`('AgentOrchestrator',)` 是 fromlist——`from X import Y` 和 `import X` 的字节码差别就在这里；
- `IMPORT_NAME` 调 `__import__()`——背后就是 importlib 的整套查找/编译/执行/缓存流程；`IMPORT_FROM` 从模块对象上取属性；
- 函数体内写 `import`（惰性 import）就是**把这条指令的执行推迟到函数被调用时**。命中 `sys.modules` 缓存时成本≈一次 dict 查找，没命中才触发找文件→编译→执行模块顶层代码。

### `inspect.signature` 为什么能工作

`inspect.signature(f)` 对 Python 函数的实现落在 `Lib/inspect.py` 的 `_signature_from_function`，本机 3.11.15 用 `inspect.getsource` 取出摘录：

```python
def _signature_from_function(cls, func, skip_bound_arg=True,
                             globals=None, locals=None, eval_str=False):
    ...
    func_code = func.__code__
    pos_count = func_code.co_argcount
    arg_names = func_code.co_varnames
    posonly_count = func_code.co_posonlyargcount
    positional = arg_names[:pos_count]
    keyword_only_count = func_code.co_kwonlyargcount
    keyword_only = arg_names[pos_count:pos_count + keyword_only_count]
    annotations = get_annotations(func, globals=globals, locals=locals, eval_str=eval_str)
    defaults = func.__defaults__
    kwdefaults = func.__kwdefaults__
    ...
```

没有魔法：签名对象就是 `co_argcount`/`co_posonlyargcount`/`co_kwonlyargcount`/`co_varnames`（结构）加上 `__defaults__`/`__kwdefaults__`/`__annotations__`（值与类型标注）拼出来的。看懂了 code 对象的字段布局，这十几行就是「查表」。

## 3.11 的运行时特化（PEP 659）

编译期做不了的优化（类型未知），3.11 挪到运行时做：**specializing adaptive interpreter**。字节码执行若干次后，解释器把「通用指令」原地替换为「特化指令」并挂内联缓存——假设它下次还是这个形态，失配再退化回去。`dis.dis(f, adaptive=True)` 能看到特化后的真实指令：

```python
import dis

def loop_sum(n):
    s = 0
    for i in range(n):
        s += i
    return s

loop_sum(5000)   # 跑热，让解释器做特化

print("===== 普通 dis（编译期指令）=====")
dis.dis(loop_sum)
print("\n===== adaptive dis（运行时特化后）=====")
dis.dis(loop_sum, adaptive=True)
```

输出（3.11.15 实跑）：

```text
===== 普通 dis（编译期指令）=====
  3           0 RESUME                   0

  4           2 LOAD_CONST               1 (0)
              4 STORE_FAST               1 (s)

  5           6 LOAD_GLOBAL              1 (NULL + range)
             18 LOAD_FAST                0 (n)
             20 PRECALL                  1
             24 CALL                     1
             34 GET_ITER
        >>   36 FOR_ITER                 7 (to 52)
             38 STORE_FAST               2 (i)

  6          40 LOAD_FAST                1 (s)
             42 LOAD_FAST                2 (i)
             44 BINARY_OP               13 (+=)
             48 STORE_FAST               1 (s)
             50 JUMP_BACKWARD            8 (to 36)

  7     >>   52 LOAD_FAST                1 (s)
             54 RETURN_VALUE

===== adaptive dis（运行时特化后）=====
  3           0 RESUME_QUICK             0

  4           2 LOAD_CONST               1 (0)
              4 STORE_FAST               1 (s)

  5           6 LOAD_GLOBAL_ADAPTIVE     1 (NULL + range)
             18 LOAD_FAST                0 (n)
             20 PRECALL_ADAPTIVE         1
             24 CALL_ADAPTIVE            1
             34 GET_ITER
             36 FOR_ITER                 7 (to 52)
             38 STORE_FAST__LOAD_FAST     2 (i)

  6          40 LOAD_FAST__LOAD_FAST     1 (s)
             42 LOAD_FAST                2 (i)
             44 BINARY_OP_ADD_INT       13 (+=)
             48 STORE_FAST               1 (s)
             50 JUMP_BACKWARD_QUICK      8 (to 36)

  7     >>   52 LOAD_FAST                1 (s)
             54 RETURN_VALUE
```

对比读出的信息：

- `BINARY_OP → BINARY_OP_ADD_INT`：观察到两边一直是 int，换成专门的整数加法指令，跳过通用 `+` 的分派；
- `LOAD_GLOBAL → LOAD_GLOBAL_ADAPTIVE`：挂内联缓存记住「`range` 上次在模块 dict 的位置」，命中就跳过 dict 查找——**注意偏移从 6 直接跳到 18**，空隙就是给 adaptive 指令预留的 cache 槽；
- `STORE_FAST__LOAD_FAST`：两条指令融合成的「超指令」（superinstruction）；
- `RESUME_QUICK` / `JUMP_BACKWARD_QUICK`：跳过每次进入/回跳时的检查开销。

这就是为什么本文档所有 `dis` 输出都标注「3.11.15 实跑」：**字节码是 CPython 的内部实现细节，指令集、编码方式、优化机制每个版本都可能变**。3.11 与 3.9 的 `dis` 输出完全不同，面试时被问「这个字节码什么意思」先反问版本。

## CPython 源码地图

C 层只列已核实的文件与职责（CPython 3.11 分支）：

| 文件 | 职责 |
| --- | --- |
| `Grammar/python.gram` + `Parser/` | PEG 语法定义与解析器（PEP 617，3.10 起替换旧 LL(1) parser），产出 AST |
| `Python/compile.c` | AST → code 对象（符号表分析、基本块、指令生成、常量折叠、栈深度计算） |
| `Python/bytecodes.c` | 全部字节码指令的定义；`Tools/cases_generator` 据此生成 `Python/ceval.c` 的执行循环——3.11 起读虚拟机行为要看 bytecodes.c |
| `Objects/codeobject.c` | `PyCodeObject`：code 对象的 C 结构、`co_*` 属性的 getter、`code.replace()` |
| `Objects/funcobject.c` | `PyFunctionObject`、`PyFunction_NewWithQualName`（MAKE_FUNCTION 落点）、`__defaults__` 等属性 getter |
| `Python/marshal.c` | code 对象等的二进制序列化（pyc 落盘格式） |
| `Python/pythonrun.c` | `PyRun_*` 系列：`python x.py`、`-c`、交互式输入的编译+执行入口 |
| `Lib/importlib/_bootstrap.py` | import 系统的 Python 实现主体（冻结进二进制）：`sys.modules` 缓存、finders/loaders、`IMPORT_NAME` 最终落到这里 |

Lib 层摘录一段（本机 3.11.15 `inspect.getsource` 取 `Lib/py_compile.py` 的 `compile`，去 docstring 与异常包装）：

```python
def compile(file, cfile=None, dfile=None, doraise=False, optimize=-1,
    if cfile is None:
        if optimize >= 0:
            optimization = optimize if optimize >= 1 else ''
            cfile = importlib.util.cache_from_source(file,
                                                     optimization=optimization)
        else:
            cfile = importlib.util.cache_from_source(file)
    ...
    loader = importlib.machinery.SourceFileLoader('<py_compile>', file)
    source_bytes = loader.get_data(file)
    try:
        code = loader.source_to_code(source_bytes, dfile or file,
                                     _optimize=optimize)
```

`import` 命中 pyc 缓存失效时走的就是同一形态：读源码 → `source_to_code`（内部即编译器入口）→ 写 pyc → 执行。`.py` → code 对象的转化在标准库里是一个可以逐行读到的普通函数。

## 常见陷阱

1. **字节码与 dis 输出不跨版本**。指令集、编码宽度、特化机制每个版本都可能变；pyc 的 magic number 直接作废旧缓存。本文档及 `pre/` 各篇的 dis 输出一律标注 3.11.15 实跑就是这个原因。
2. **code 对象只读**。`f.__code__.co_argcount = 99` 抛 `AttributeError: readonly attribute`；要造变体用 `f.__code__.replace(co_name="...")`（返回新对象，原对象不动）。字节码层面没法「改写一个函数」——热更新换掉的是**函数对象**（重新 def 一个、让名字指向新的），不是改 code。
3. **`f_locals` 是物化快照，不是真存储**。函数帧的局部变量存在数组里（`LOAD_FAST` 按下标取），`f_locals` 是按需物化出来的 dict 副本：

   ```python
   import sys
   def f():
       x = 1
       sys._getframe().f_locals["x"] = 999
       return x          # 还是 1
   print(f())            # 1
   ```

   写 `f_locals` 不改真实局部变量（模块级帧才是活 dict）。同理：函数内 `exec("x = 1")` 写不进外层局部变量——这不是 bug，是局部变量根本不在 dict 里。
4. **`from __future__ import annotations` 改变编译行为**（本仓几乎每个 `.py` 首行）：注解不求值、编译为字符串存进 `__annotations__`（PEP 563 形态）。这让 `def f(x: SomeType)` 在 `SomeType` 尚未定义时也能编译过；代价是运行时要看真类型必须 `typing.get_type_hints`/`get_annotations(eval_str=True)` 再 eval 一次——`_signature_from_function` 摘录里的 `eval_str` 参数就是干这个的。
5. **`exec`/`eval` 绝不吃不可信输入**。`compile` 出来的 code 对象与手写代码权限完全等同：能 import、能 `open()`、能拿 `os` 做任何事情。本仓学生代码全部送去无外网、只读文件系统的 harness 容器评测（`agent/tools.py` `grade_plugin` → MCP），从不在本进程执行——这是安全红线，而不仅仅是「好习惯」。
6. **字符串形态的导入对静态工具不可见**。`uvicorn "app.main:app"`、`importlib.import_module(var)`、pytest 的用例路径都是字符串，ruff/IDE 静态分析追不到这条依赖；函数体内的 `import` 语句本身仍是静态可见的（语法上它就是 import 语句），只是执行时机推迟了。

## 项目代码精读

> 本节代码块均按仓库原文摘录（保留原缩进与注释），依赖项目内部上下文，**不可单独运行**。

### `RagProductService._assemble_orchestrator` 的函数内 import

`backend/app/services/rag_product_service.py`：

```python
    def _assemble_orchestrator(self):
        """编排层装配：唯一生产路径（AgentOrchestrator），失败直接抛给受理层。

        惰性 import 与既有 _patch_agent_path 测试约定一致（patch
        ``app.agent.service.AgentOrchestrator`` 模块属性即可替身）。
        """
        from ..agent.service import AgentOrchestrator
        if self._service is None:
            self._service = AgentOrchestrator()
        return self._service
```

- 这条 `from ... import` 编译成上面演示过的 `IMPORT_NAME + IMPORT_FROM` 字节码，**每次调用 `_assemble_orchestrator` 都执行**；命中 `sys.modules` 后成本≈dict 查找，所以可以放进「每次受理都会走」的路径；
- 为什么不在模块顶部 import：装配失败（缺依赖、配置错）发生在**调用时刻**而非 import 时刻，任务行保持 pending 等下一拍重驱动（docstring 上文注释）；同时测试只需 `patch("app.agent.service.AgentOrchestrator")` 这个模块属性即可替身——`IMPORT_FROM` 是**执行时**取属性，天然可 patch。若顶部 `from ... import` 则名字早已绑死在导入方模块，patch 点要换地方；
- 循环导入的规避也靠同一机制：模块顶部 import 在编译链上构成依赖环，函数内 import 把边推迟到运行时（详见 `07_modules_and_imports.md`）。

### `RetrievalService` 里 `inspect.signature` 的兼容探测

`backend/app/services/retrieval_service.py`：

```python
        rewrite = self.query_rewriter.rewrite
        params = inspect.signature(rewrite).parameters
        accepts_kwargs = any(
            item.kind == inspect.Parameter.VAR_KEYWORD for item in params.values()
        )
        kwargs: Dict[str, Any] = {}
        if "conversation_context" in params or accepts_kwargs:
            kwargs["conversation_context"] = conversation_context
        # usage 同样是增量扩展：不接受它的既有 rewriter 照常工作，只是账本上少了
        # 这一步——不因为记账而让检索失败。llm_rewrite 同口径（L5-A）。
        if "usage" in params or accepts_kwargs:
            kwargs["usage"] = usage
```

- `self.query_rewriter` 是可插拔组件（本地规则 / LLM 改写器）。「它吃不吃 `conversation_context`/`usage`/`llm_rewrite` 这几个参数」不是接口冻结的，靠 `inspect.signature` 在运行时探测——而 `inspect.signature` 底层就是上面摘录的 `_signature_from_function`：翻 `__code__` 的 `co_argcount`/`co_varnames` + `__defaults__`；
- `accepts_kwargs` 判 `VAR_KEYWORD` 形参（`**kw`）：签名信息齐全到能区分「显式声明了某参数」与「有个 `**kw` 兜着」，所以记账参数是渐进扩展——旧 rewriter 少记一笔账但不会调用失败；
- 这是「函数是对象」的延伸：**函数的签名也是可查询的数据**，框架级代码靠它做组件兼容，而不是靠约定文档。

### `graph.compile()`：另一种「编译」

`backend/app/agent/graph.py` `build_agent_graph` 末尾：

```python
    graph = StateGraph(AgentGraphState)
    graph.add_node("planner", planner)
    graph.add_node("tools", run_tools)
    graph.add_node("finalize", finalize)
    ...
    return graph.compile()
```

LangGraph 的 `compile` 与 CPython 编译**只是撞名**：它校验图结构（边是否闭合、节点是否可达），把节点函数对象装配成一个可 `invoke` 的 runnable，不产生任何字节码。面试或读文档时见到「compile」先区分语境：CPython 编译（源码→字节码）、框架的 compile（声明→可执行体）、`re.compile`（正则→匹配自动机）是三件不相干的事——共同点是「把一种描述翻译成另一种可高效执行的形式」。

### `uvicorn app.main:app`：启动就是一次编译风暴

`README`/`AGENTS.md` 里的启动命令 `uvicorn app.main:app --port 8090` 拆解后：

```text
"app.main"  ──importlib──> 找 backend/app/main.py
                         → 读 __pycache__（命中跳过编译，否则编译+写 pyc）
                         → 执行模块顶层 code 对象
                              → 顶部 import 递归触发整包同样的流程
                              → FastAPI() 实例化、@app.middleware 装饰器执行…
"app"      ──getattr──> 取模块对象的 app 属性作为 ASGI 入口
```

启动期报错的两个层次也对应编译的两个阶段：`SyntaxError` 是**编译期**失败（AST 都建不出来）；`ImportError`/`ModuleNotFoundError` 是**执行期**失败（顶层 `import` 语句跑挂了）。看堆栈时前者没有运行帧、后者有一串 IMPORT_NAME 帧。

## 练习

1. **读项目代码回答**：`rag_issue_service.py` 的 `probe(self, request_id, user_id, question, *, session_id=None, input_digest=None, purpose="chat")` 在 code 对象层面，哪些字段体现了 `*,`？`session_id`/`purpose` 的默认值存在哪个对象上？
2. **动手写代码**：对「3.11 的运行时特化」一节的 `loop_sum` 用 `dis.get_instructions(loop_sum)` 遍历，打印所有「会改变执行位置」的指令（指令名里含 `JUMP`、以及 `FOR_ITER`——它本质就是「取到则继续、取完则跳走」的分支）及各自目标偏移。
3. **动手写代码**：手工组装函数。`code = compile("(lambda x, y: x * 10 + y)", "<d>", "eval")`，从 `code.co_consts` 里掏出 lambda 的 code 对象，用 `types.FunctionType` 造出可调用的 `f`，调用 `f(3, 4)` 应得 `34`。
4. **动手写代码**：不用 `py_compile`，手写一个合法 pyc：`importlib.util.MAGIC_NUMBER` + flags(0) + mtime + size 头 + `marshal.dumps(compile(...))` 写入文件，再用 `marshal.loads` 读回并 `exec` 验证。
5. **读项目代码回答**：`main.py` 里 `if __name__ == "__main__"` 的 `__name__` 是什么时期、由谁绑定的？`python -m uvicorn app.main:app` 与在 REPL 里 `import app.main` 后再 `import app.main`，第二次 import 时哪些阶段被 `sys.modules` 跳过？

## 参考答案

<details><summary>参考答案 1</summary>

`*,` 右侧的 `session_id`/`input_digest`/`purpose` 是仅关键字参数：结构信息体现在 `co_kwonlyargcount = 3`（加上这三个名字出现在 `co_varnames` 的「仅关键字」区间），`co_argcount` 只数到 `question`（不含 `self` 绑定方法调用时的占用——`self` 本身是位置参数计入 `co_argcount`）。默认值**不在 code 对象上**：`purpose="chat"` 等在函数对象的 `__kwdefaults__` dict 里（执行 `def` 时由 MAKE_FUNCTION 装配），`session_id=None` 同理。这就是「结构静态、值动态」的分工。

</details>

<details><summary>参考答案 2</summary>

```python
import dis

def loop_sum(n):
    s = 0
    for i in range(n):
        s += i
    return s

for instr in dis.get_instructions(loop_sum):
    if "JUMP" in instr.opname or instr.opname == "FOR_ITER":
        print(f"{instr.offset:>4} {instr.opname:<28} -> {instr.argval}")
```

实跑输出：

```text
  36 FOR_ITER                     -> 52
  50 JUMP_BACKWARD                -> 36
```

要点：`FOR_ITER` 把迭代器压栈、取下一个值，取完时**向前跳**到循环后（偏移 52）；`JUMP_BACKWARD` 把执行拉回循环头（偏移 36）——这两条配对就实现了 `for` 循环。`argval`/`to xx` 是 dis 已算好的目标**字节偏移**；控制流指令在字节码层只是「改 `f_lasti`」。

</details>

<details><summary>参考答案 3</summary>

```python
import types

code = compile("(lambda x, y: x * 10 + y)", "<d>", "eval")
lam_code = next(c for c in code.co_consts if hasattr(c, "co_code"))
f = types.FunctionType(lam_code, globals(), "f")
print(f(3, 4))            # 34
print(f.__code__ is lam_code)   # True：函数对象只是 code + globals + 名字的组装
```

要点：`compile` 出的外层 code 只负责「返回一个 lambda」；真正的字节码在嵌套的 `lam_code` 里。`FunctionType` 手工组装验证了「函数对象 = code + globals + defaults + closure」这个等式——没有走 `def`。

</details>

<details><summary>参考答案 4</summary>

```python
import importlib.util, marshal, pathlib, struct, tempfile, time

src = "ANSWER = 40 + 2\n"
code = compile(src, "hand.py", "exec")

header = (importlib.util.MAGIC_NUMBER
          + struct.pack("<I", 0)                    # flags=0：时间戳模式
          + struct.pack("<I", int(time.time()))     # mtime
          + struct.pack("<I", len(src)))            # source size
p = pathlib.Path(tempfile.mkdtemp()) / "hand.cpython-311.pyc"
p.write_bytes(header + marshal.dumps(code))

back = marshal.loads(p.read_bytes()[16:])
ns = {}
exec(back, ns)
print(ns["ANSWER"])   # 42
```

要点：pyc 头 16 字节 = magic(4) + flags(4) + mtime(4) + size(4)，后面就是 `marshal` 的 code 对象。import 系统验证缓存时用 mtime/size 判断源文件变没变。

</details>

<details><summary>参考答案 5</summary>

`__name__` 是模块命名空间里的普通全局变量，由 import 系统在**执行模块顶层 code 对象之前**绑定：作为脚本/`python -m` 入口时绑 `"__main__"`，作为被导入模块时绑模块名（`"app.main"`）。所以 `if __name__ == "__main__"` 判断的是「我是入口还是被导入的」。

两次 `import app.main` 的差异：第一次走完整流程（找文件 → 看 pyc 缓存 → 编译或反序列化 → 执行顶层 code → 写入 `sys.modules["app.main"]`）；第二次 `IMPORT_NAME` 指令照样执行，但 `__import__` 在 `sys.modules` 里命中直接返回——**编译、顶层执行都跳过**，只剩一次 dict 查找。函数内惰性 import 敢放在热路径上靠的就是这个缓存。

</details>

## 延伸阅读

- Python 语言参考："Execution model"（code block / frame / name binding）与 "The import system"（`sys.modules`、finders/loaders、缓存）；
- PEP 617（New PEG parser）、PEP 659（Specializing Adaptive Interpreter）、PEP 552（Deterministic pycs / hash-based 缓存）、PEP 563（Postponed Evaluation of Annotations，对应 `from __future__ import annotations`）；
- CPython Developer Guide 的 compiler 章节；`Tools/cases_generator`（bytecodes.c → ceval.c 的生成器）；
- 模块文档：`dis`、`ast`、`tokenize`、`inspect`（含 `getsource`/`signature`/`getframe`）、`marshal`、`py_compile`、`code`（交互式解释器基建）。
