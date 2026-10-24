# 07 模块、包与导入

## 学习目标

- 知道"模块即对象"、`sys.modules` 缓存（重复 `import` 不重复执行）；
- 理解 `sys.path` 与查找过程（finder/loader 概念、`importlib`）；
- 会看包与 `__init__.py`、绝对导入 vs 相对导入（`from ..config import get_settings` 中点的含义、为何相对导入只能在包内用）；
- 知道本项目包根是 `backend/`、import 名为 `app`，`backend/scripts/` 如何找到 `app`；
- 会用 `if __name__ == "__main__":`；
- 理解循环导入的成因与"函数内延迟导入"解法；
- 认识 `from __future__ import annotations`（详见 `12` 篇）；
- 能认读 `IMPORT_NAME` / `IMPORT_FROM` 字节码。

**本主题在项目里出现在哪**

| 位置 | 用到什么 |
| --- | --- |
| `backend/app/main.py` | 顶部绝对导入 `from .api import ...` + `on_startup`/`start_rag_maintenance` 钩子里的**函数内导入** |
| `backend/app/services/rag_product_service.py` `_assemble_orchestrator` | `from ..agent.service import AgentOrchestrator` 惰性导入（注释写了测试约定原因）；同文件多处函数内导入 |
| `backend/app/rag/interfaces.py` | `if TYPE_CHECKING:` 下的导入：只在类型检查期可见 |
| `backend/scripts/*.py` | `sys.path.insert(0, str(Path(__file__).resolve().parents[1]))` 后 `from app.xxx import ...` |
| `backend/tests/conftest.py` | 同款 `sys.path.insert` 保证 `import app` 可用 |
| `backend/app/api/chat.py` `_citation_source` | 函数内 `from ..services.llama_index_service import get_llama_index_source` |
| `backend/app/agent/graph.py` `planner` | 函数内 `from ..core.upstream_gate import upstream_slot` |

## 语法详解

### 模块即对象与 sys.modules

`import x` 做的事：在 `sys.modules` 里查 `x`——有就直接绑名字，没有就**找文件、执行模块代码、把模块对象塞进 `sys.modules`** 再绑名字。模块对象的"全局变量"就是它的属性：

```python
import sys
import json as j1, json as j2

print(j1 is j2, "json" in sys.modules, j1 is sys.modules["json"])
```

输出：`True True True`

推论：模块级代码（顶层语句）**每个进程只跑一次**——模块级常量（`FUNC_RE`、`_clients` dict、`TASKS`）天然是"进程级单例"。这也是 `mcp_client.py` 里 `_clients: Dict[str, HarnessMcpClient] = {}` + `threading.Lock` 做进程级缓存的原理。

### sys.path 与查找

导入查找按 `sys.path` 列表逐个目录找 `x/__init__.py` 或 `x.py`。`sys.path` 的来源：脚本所在目录（或 `-c`/交互时的 cwd、`PYTHONPATH`、site-packages）。实验（在 `/tmp/pre_verify/pkgdemo/` 下）：

```text
sys.path[0] = /private/tmp/pre_verify/pkgdemo
mypkg package imported
mod loaded, __name__ = mypkg.mod
same module object: True
```

- 脚本 `main.py` 所在目录被自动放进 `sys.path[0]`——所以 `import mypkg.mod` 能找到同目录的 `mypkg/`；
- `import mypkg.mod` 会**先执行 `mypkg/__init__.py`**（打印 `mypkg package imported`）再执行 `mod.py`；
- 模块的 `__name__` 是它的"导入名"：`mypkg.mod`。

概念上查找分两步：**finder**（`sys.path` 上每个 `PathFinder` 决定哪个文件）→ **loader**（把文件读进来编译执行）。`importlib.util.find_spec("x")` 只查不装；`importlib.import_module("x")` 等价于 `import x` 的动态版。

### 包与 `__init__.py`

目录 + `__init__.py` = 包（`app/`、`app/services/`、`app/agent/` 全是包）。`import app.services.task_service` 逐层执行：`app/__init__.py` → `app/services/__init__.py` → `task_service.py`。`__init__.py` 可以是空文件（本项目各包基本如此），也可以放包级导出。

### 绝对导入 vs 相对导入

```python
# 示意：以 rag_product_service.py 顶部导入为例（行尾注释为讲解所加，非逐字摘录）
from ..config import get_settings        # 相对：.. 表示"上一级包"
from ..core.metrics import record_chat_trace
from .citation_service import citations_from_chunks   # . 表示"本包"
from fastapi import HTTPException         # 绝对：从 sys.path 顶向下找
```

- `.` = 本模块所在的包；`..` = 上一级包；`...` = 上两级；
- **相对导入依赖 `__package__`**：只有在"作为包的一部分被导入"的模块里才有意义。直接 `python app/services/rag_product_service.py` 跑一个文件，它的 `__package__` 是 `None`，`from ..config import ...` 立刻炸 `ImportError: attempted relative import with no known parent package`；
- 本项目约定：`app` 包内模块（包括 `main.py`）一律用相对导入（`from ..config import get_settings`、`from .api import ...`）；`backend/scripts/` 与 `tests/` 在包外用 `from app.xxx import ...` 绝对导入（配 `sys.path.insert`）。

### `if __name__ == "__main__":`

模块被**直接执行**（`python x.py`、`python -m x`）时 `__name__ == "__main__"`；被 `import` 时 `__name__` 是模块名。所以：

```python
# 片段，不可单独运行（摘自 backend/scripts/ingest_llama_index.py 末尾）
if __name__ == "__main__":
    asyncio.run(main())
```

让"既能 `import` 这个文件拿 `main` 函数、又能直接跑"成立——`import` 时不触发 `asyncio.run`。

### 循环导入与函数内延迟导入

`a` 导入 `b`、`b` 又导入 `a`：执行 `a` 到一半去执行 `b`，`b` 回头 `import a` 时 `sys.modules` 里的 `a` 是**半成品**（顶层语句还没跑完）——`from a import something` 拿不到还没定义的名字就 `ImportError`。

三种解法，本项目都在用：

1. **函数内延迟导入**：把 `import` 挪进函数体——调用时才执行，此时对方早已加载完；
2. `import x` 后 `x.name` 属性访问（而不是 `from x import name`）：名字在调用时才解析；
3. `if TYPE_CHECKING:`：只在类型检查期导入，运行期完全不加载（见下）。

### `from __future__ import annotations`

让本文件所有类型注解变成"字符串惰性求值"：注解里的名字在 `def`/`class` 执行时**不查找**，可以写尚未导入/前向引用的类型。它把注解从"运行期表达式"降级为"纯标注"——运行期开销为零、`TYPE_CHECKING` 导入可用。项目里 `rag_product_service.py`、`rag/interfaces.py`、`agent/mcp_client.py` 等大量文件第一行都是它。详见 `12` 篇。

## 运行机制与 CPython 实现

### 等价改写：`import json`

```python
import json
```

近似等价于：

```python
import sys, importlib
json = sys.modules.get("json") or importlib.import_module("json")
# import_module 内部：找到文件 → compile → exec 进新 module 对象 → sys.modules["json"] = module
```

`from os.path import join` 近似：

```python
_mod = importlib.import_module("os.path")
join = _mod.join          # IMPORT_FROM 做的就是 getattr(_mod, "join")
```

### 字节码：IMPORT_NAME / IMPORT_FROM

以下输出由 `backend/.venv/bin/python`（Python 3.11.15）实跑 `dis.dis` 得到：

```python
def importer():
    import json
    from os.path import join
    return json.dumps({"p": join("a", "b")})
```

```text
 55           0 RESUME                   0

 56           2 LOAD_CONST               1 (0)
              4 LOAD_CONST               0 (None)
              6 IMPORT_NAME              0 (json)
              8 STORE_FAST               0 (json)

 57          10 LOAD_CONST               1 (0)
             12 LOAD_CONST               2 (('join',))
             14 IMPORT_NAME              1 (os.path)
             16 IMPORT_FROM              2 (join)
             18 STORE_FAST               1 (join)
             20 POP_TOP

 58          22 LOAD_FAST                0 (json)
             24 LOAD_METHOD              3 (dumps)
             46 LOAD_CONST               3 ('p')
             48 PUSH_NULL
             50 LOAD_FAST                1 (join)
             52 LOAD_CONST               4 ('a')
             54 LOAD_CONST               5 ('b')
             56 PRECALL                  2
             60 CALL                     2
             70 BUILD_MAP                1
             72 PRECALL                  1
             76 CALL                     1
             86 RETURN_VALUE
```

- `import json`：`IMPORT_NAME` 调内置 `__import__`（两个常量参数是 level=0 与 fromlist=None），返回模块对象存到局部变量 `json`；
- `from os.path import join`：`LOAD_CONST ('join',)` 是 **fromlist**——`IMPORT_NAME` 拿到它就返回子模块 `os.path` 本身，`IMPORT_FROM` 再 `getattr(os.path, "join")` 存名字，最后 `POP_TOP` 丢掉模块对象；
- 函数内 `import` 每次调用都走 `IMPORT_NAME`——但 `sys.modules` 命中后是 dict 查找，代价可忽略，所以"函数内惰性导入"没有性能问题。

### CPython 源码

- 导入系统的 C 入口在 CPython 3.11 分支 `Python/import.c`：`PyImport_AddModuleObject`（建/取 `sys.modules` 里的模块对象）、`import_find_extension`（扩展模块查找）；同文件的 `remove_module` 在模块代码执行失败时把名字从 `tstate->interp->modules`（即 `sys.modules`）里弹出——文件注释原话是"avoid leaving damaged module objects in sys.modules"，这正是循环导入时看到 `partially initialized module` 的另一半：失败路径会清理半成品，成功路径才留下完整模块对象。
- `importlib.import_module` 是纯 Python 外壳，本机 3.11.15 `inspect.getsource` 取出 `Lib/importlib/__init__.py` 真实源码：

  ```python
  def import_module(name, package=None):
      """Import a module.

      The 'package' argument is required when performing a relative import. It
      specifies the package to use as the anchor point from which to resolve the
      relative import to an absolute import.

      """
      level = 0
      if name.startswith('.'):
          if not package:
              msg = ("the 'package' argument is required to perform a relative "
                     "import for {!r}")
              raise TypeError(msg.format(name))
          for character in name:
              if character != '.':
                  break
              level += 1
      return _bootstrap._gcd_import(name[level:], package, level)
  ```

  `name` 开头数点得到 `level`，真正的解析（`sys.modules` 查找、相对→绝对换算、finder/loader 调度）都在 `_bootstrap._gcd_import`——相对导入的核心就是"数点 + 锚定 package"。

## 常见陷阱

1. **直接跑包内文件**：`python app/services/x.py` 让 `__package__` 为空，相对导入全炸——要么 `python -m app.services.x`（在 `backend/` 下），要么写成脚本（`scripts/` 的做法）。
2. **`from x import name` 绑定的是"那一刻的对象"**：`from m import f` 之后 `m.f = new` 不影响已导入的名字——测试里 `monkeypatch` 要打在**使用方的模块命名空间**上（`rag_product_service` 注释里的 `_patch_agent_path` 约定正源于此）。
3. **模块级执行顺序副作用**：`import` 就会跑顶层代码——顶层写 `get_settings()`、建连接会在导入时产生副作用；本项目把这类动作放进 startup 钩子或函数内。
4. **`import a.b` 后 `a.b` 才可用**：只 `import a` 不自动有 `a.b`（除非 `a/__init__.py` 自己导入了 `b`）。
5. **`sys.path` 顺序决定同名冲突**：`insert(0, ...)` 抢占式前置——脚本这么写就是为了抢在 site-packages 前面拿到本仓库的 `app`。
6. **相对导入数错点**：`from ..x import` 在 `app.services` 里指 `app.x`，在 `app.services.y` 里指 `app.services.x`——点从"本模块所在包"开始数，不是从文件目录数。
7. 循环导入的报错信息常是 `ImportError: cannot import name 'X' from partially initialized module`——看到 "partially initialized" 就知道是循环导入，不是名字写错。

## 项目代码精读

> 本节代码块均按仓库原文摘录（保留原缩进与注释），依赖项目内部上下文，**不可单独运行**。

### 包根与 scripts 的 `sys.path`

本仓库 Python 包根是 `backend/` 目录本身，import 名是 `app`（AGENTS.md 明确：Python 包根即 `backend/`，import 名为 `app`）。但 `backend/scripts/*.py` 是直接执行的脚本——直接跑 `python scripts/ingest_llama_index.py` 时 `sys.path[0]` 是 `scripts/` 而不是 `backend/`，`import app` 找不到。所以每个脚本开头手动把包根塞进 `sys.path`：

`backend/scripts/ingest_llama_index.py`：

```python
"""Build the isolated LlamaIndex PostgreSQL index without touching legacy data."""
import asyncio
import json
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))
from app.services.llama_index_service import LlamaIndexIngestionService


async def main() -> None:
    print(json.dumps(await LlamaIndexIngestionService().ingest(), ensure_ascii=False))


if __name__ == "__main__":
    asyncio.run(main())
```

- `Path(__file__).resolve().parents[1]`：`__file__` 是 `scripts/ingest_llama_index.py`，`parents[0]`=`scripts/`、`parents[1]`=`backend/`——相对位置锚定，不依赖 cwd；
- `sys.path.insert(0, ...)` 必须在 `from app...` **之前**执行——导入语句位置即时机；
- `rag_maintenance.py`、`create_user.py` 同款写法；`tests/conftest.py` 用 `if str(ROOT) not in sys.path: sys.path.insert(0, str(ROOT))` 做幂等版本。

### `main.py`：顶部导入 + 钩子内导入

`backend/app/main.py` 顶部（相对导入，因为 `main.py` 在 `app` 包内）：

```python
from .api import admin, assignments, auth, chat, grading, rag, sources, tasks, templates, usage
from .config import get_settings
from .core.llm_client import OpenAICompatibleClient
from .core.logging_config import setup_logging
from .db import close_database_provider, init_db
from .rag.providers import clear_model_caches
```

而 startup 钩子里的导入是函数内的（`backend/app/main.py`）：

```python
@app.on_event("startup")
def on_startup() -> None:
    if get_settings().app_env == "prod":
        from .services.schema_version import require_schema_head
        try:
            require_schema_head()
        except RuntimeError as exc:
            logger.error(json.dumps({"event": "schema_version_mismatch", "reason": str(exc)}, ensure_ascii=False))
            raise
    init_db()
    # 唯一检索数据面：固定预热 li 运行时（内部对非 PG 后端跳过）。
    from .services.llama_index_service import warm_llama_index_runtime
    warm_llama_index_runtime()
```

同文件（`backend/app/main.py`）：

```python
@app.on_event("startup")
async def start_rag_maintenance():
    global _rag_maintenance_task, _task_heartbeat_task
    from .services import task_service
```

- `require_schema_head`/`warm_llama_index_runtime`/`task_service` 都是"重"模块（拉 LlamaIndex、服务层全链路）——函数内导入把它们的加载推迟到 startup，**模块导入期保持轻量**（测试里 `import app.main` 不会触发一堆重依赖）；
- `from .services import task_service` 是"导包内模块"形态：拿到 `task_service` 模块对象本身，后面 `task_service.recover_orphans()` 属性访问在调用时解析——天然的延迟绑定。

### `rag_product_service._assemble_orchestrator`：惰性导入

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

- `from ..agent.service import AgentOrchestrator`：`..` = `app` 包（本模块在 `app.services`），指向 `app.agent.service`；
- 函数内导入让 `agent.service`（拉 LangGraph、langchain-openai 的重组件）在**首次装配**时才加载——`import rag_product_service` 本身保持轻量；
- 注释点出测试约定：patch `app.agent.service.AgentOrchestrator` 模块属性即可替身——因为导入在调用时发生，`from X import Y` 在每次 `_assemble_orchestrator` 调用时重新 `getattr` 目标模块属性，patch 总能生效；若写成模块级 `from ..agent.service import AgentOrchestrator`，名字在导入 `rag_product_service` 那一刻就绑死了；
- 同文件还有 `from ..agent.tools import extract_fenced_source`（`_execution_input_source`）、`from .assignment_service import ...`（`_resolve_explain`）等多处函数内导入，全是"按需加载 + 可 patch"模式。

### `rag/interfaces.py`：TYPE_CHECKING 导入

`backend/app/rag/interfaces.py`：

```python
from __future__ import annotations

from pathlib import Path
from typing import TYPE_CHECKING, Any, AsyncIterator, Dict, Iterable, List, Optional, Protocol

from .types import AnswerDraft, RetrievedChunk, SourceChunk

if TYPE_CHECKING:  # 仅类型期依赖：interfaces 不能在运行期拖入 core.usage
    from ..core.usage import UsageRecorder
```

- `TYPE_CHECKING` 运行期恒 `False`——`from ..core.usage import UsageRecorder` 永远不会被执行；
- 配合 `from __future__ import annotations`：注解里的 `UsageRecorder` 是字符串惰求值，运行期不需要这个名字存在；只有 mypy/pyright 读文件时才看到它；
- 注释说明动机：`interfaces` 是被广泛依赖的抽象层，不能在运行期拖入 `core.usage`（防止底层模块反向依赖高层，也避免环形链）。

### `chat.py` 的分支内导入

`backend/app/api/chat.py` `_citation_source`：

```python
async def _citation_source(engine: str, chunk_id: str):
    if engine == "llama_index" or chunk_id.startswith("li_"):
        from ..services.llama_index_service import get_llama_index_source
        return await get_llama_index_source(chunk_id)
    # 非 li_ 前缀的历史引用（legacy 引擎时期写入的 knowledge_chunks 行）直查回读。
    return await asyncio.to_thread(get_historical_chunk, chunk_id)
```

`llama_index_service` 只有命中 `li_` 引用时才加载——历史引用回读路径根本不付它的导入成本。

## 练习

1. **读项目代码回答**：`scripts/rag_maintenance.py` 直接 `python backend/scripts/rag_maintenance.py` 能跑；如果把开头 `sys.path.insert(0, str(Path(__file__).resolve().parents[1]))` 删掉会怎样？改成 `sys.path.append` 呢？
2. **读项目代码回答**：`rag_product_service._assemble_orchestrator` 为什么必须函数内 `from ..agent.service import AgentOrchestrator` 而不是模块级导入？给出两个理由（加载成本 + 测试 patch）。
3. **动手写代码**：在 `/tmp/pre_verify/` 下建 `pkgdemo2/`：`pkgdemo2/mypkg/{__init__.py,a.py,b.py}`，`a.py` 里 `from .b import hello`；再写 `main.py` 演示 `import mypkg.a`、`importlib.import_module("mypkg.a")` 两种导入，打印 `__name__`/`__package__`，验证 `sys.modules` 命中（二次导入不重打印）。
4. **动手写代码**：写一个演示循环导入的最小例：`x.py` 顶部 `from y import B` 后定义 `A = 1`，`y.py` 顶部 `from x import A` 后定义 `B = 2`；先跑看报错形态，再把 `y.py` 的导入挪进函数体修复，打印 `A+B`。
5. **读项目代码回答**：`rag/interfaces.py` 里 `if TYPE_CHECKING:` 导入 `UsageRecorder`，而方法签名写的是 `usage: Optional["UsageRecorder"] = None`。如果把 `from __future__ import annotations` 删掉但保留 `TYPE_CHECKING` 导入，这个文件运行期会不会炸？为什么？（提示：注意注解里的引号。）

## 参考答案

<details><summary>参考答案 1</summary>

删掉后 `sys.path[0]` 是 `backend/scripts/`——`import app` 找不到 `backend/app/` 这个包，抛 `ModuleNotFoundError: No module named 'app'`。改成 `sys.path.append(str(ROOT))`（append 到末尾）：`backend/` 被排到最后——若 site-packages 里恰好有同名 `app` 包会导错对象；本机没有的话"碰巧能用"，但顺序语义错了（本仓库代码应优先）。`insert(0, ...)` 的抢占式前置是故意的：保证脚本用的永远是**这份仓库**的 `app`，与 `tests/conftest.py` 的 `insert(0, str(ROOT))` 同构。

</details>

<details><summary>参考答案 2</summary>

加载成本：`app.agent.service` 拉 LangGraph + langchain-openai + 工具层，是重组件；模块级导入会让"仅 import rag_product_service"（测试、维护脚本）都付这笔成本，函数内导入把它推迟到真正装配时。测试 patch：`from X import Y` 在**执行该行那一刻**做 `getattr(X, "Y")`——函数内导入意味着每次 `_assemble_orchestrator` 调用都重新取 `app.agent.service.AgentOrchestrator` 的当前值，patch 模块属性即可替身；模块级 `from` 导入在 `rag_product_service` 导入时就已把原函数绑死，后 patch 模块属性无效（要改 patch `rag_product_service.AgentOrchestrator`，而注释明确约定的是前者）。这正是"from 导入绑名字"陷阱的工程利用。

</details>

<details><summary>参考答案 3</summary>

目录结构（`/tmp/pre_verify/pkgdemo2/`）：

```text
main.py
mypkg/__init__.py     # print("mypkg init")
mypkg/a.py            # print("a loaded"); from .b import hello; X = hello()
mypkg/b.py            # print("b loaded"); def hello(): return "hi"
```

`main.py`：

```python
import sys, importlib
sys.path.insert(0, "/tmp/pre_verify/pkgdemo2")
import mypkg.a
print("name:", mypkg.a.__name__, "| package:", mypkg.a.__package__)
mod = importlib.import_module("mypkg.a")     # 二次导入：不重打印
print("cached:", mod is sys.modules["mypkg.a"], "| X =", mod.X)
```

实跑输出：

```text
mypkg init
a loaded
b loaded
name: mypkg.a | package: mypkg
cached: True | X = hi
```

`__package__` 是 `mypkg`——`a.py` 里的 `from .b import hello` 的 `.` 就锚在它上面；二次 `import_module` 命中 `sys.modules` 不重复执行（`a loaded` 只出现一次）。

</details>

<details><summary>参考答案 4</summary>

`x.py`：

```python
from y import B
A = 1
```

`y.py`（修复前）：

```python
from x import A
B = 2
```

`python -c "import x"` 报错形态：`ImportError: cannot import name 'A' from partially initialized module 'x'`——`x` 还在 `sys.modules` 里但 `A` 尚未定义（注意：直接 `python x.py` 时 x.py 以 `__main__` 运行，`from x import A` 会把 x.py **再执行一遍**作为模块 x，报的是 `cannot import name 'B' ... 'y'`——同一循环，另一个方向）。

修复后 `y.py`：

```python
def get_b():
    from x import A
    return A + 2
```

`x.py` 改为 `A = 1` 后 `from y import get_b; print(get_b())`——输出 `3`。要点：报错里的 "partially initialized" 是循环导入的标志；函数内导入把"取 `A`"推迟到调用时，那时 `x` 已加载完。

</details>

<details><summary>参考答案 5</summary>

对这个具体文件：**不会炸**。它里面对 `UsageRecorder` 的全部四处注解都写成了 `Optional["UsageRecorder"]`——类型名已经手动加引号成了字符串前向引用，`Optional[...]` 对字符串参数做惰性处理，运行期不查 `UsageRecorder` 这个名字，有没有 future import 都一样。

但这只是"这个文件恰好手动加了引号"，不是普遍安全。一般规律：没有 future import 时注解在 `def`/`class` 执行时就求值；写了未加引号的前向名字当场 `NameError`——可运行对照实验（3.11.15 实跑）：

```python
code = '''
def f(x: NotDefined) -> int: return 1
'''
try:
    exec(code)
except NameError as e:
    print("no future:", e)

code2 = "from __future__ import annotations\n" + code
ns = {}
exec(code2, ns)
print("with future:", ns["f"].__annotations__)
```

输出：

```text
no future: name 'NotDefined' is not defined
with future: {'x': 'NotDefined', 'return': 'int'}
```

所以 `from __future__ import annotations` + `TYPE_CHECKING` 的配套意义是：让**未加引号**的注解也能引用类型期才存在的名字（项目里大多数文件都靠这个机制，interface 文件额外加引号是双保险）。

</details>

## 延伸阅读

- Python 官方教程："Modules"（`sys.path`、`__name__`、包与 `__init__.py`）。
- 语言参考："The import system"（finder/loader、`sys.modules`、相对导入语义全集）。
- `importlib` 模块文档（`import_module`、`util.find_spec`）。
- PEP 302（finder/loader 原始设计）、PEP 328（相对导入）、PEP 563（`from __future__ import annotations`）、PEP 420（namespace package）。
