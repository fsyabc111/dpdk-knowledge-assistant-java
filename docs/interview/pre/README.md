# Python 预备知识（pre）

> 面向读者：准备通读本仓库后端代码（`backend/app/`）、但 Python 语法还不熟的开发者。假定你有其他语言（C / Java / Go / JS 任一）基础，因此每篇都从"和你会的语言有什么不一样"讲起，不解释"什么是变量、什么是循环"。

## 这套文档是什么

本仓库后端约 1.78 万行 Python，大量用到这门语言里"别的语言没有对应物"的写法：仅关键字参数、推导式、装饰器、生成器、上下文管理器、协程与 `asyncio` 原语、描述符、惰性导入……直接带着其他语言的直觉去读代码，最容易卡在两类地方：

1. **语法不认识**：`f(a, b, *, c)`、`{**cached, "deduplicated": True}`、`del events[:n]`、`except*` 这类写法读不出含义；
2. **语义想当然**：`except Exception` 抓不到 `asyncio.CancelledError`、`and/or` 返回的是操作数而不是布尔值、默认值在 `def` 执行时就已求值——这些坑读错一行就会误解整条链路。

这 16 篇把这两类问题补齐。**先读完本目录再开始 `../learning_path.md` 的学习路线**；路线里每个阶段读代码遇到不熟的语法，随时回来查对应篇目。

## 运行环境（重要）

所有示例与练习都用项目自己的虚拟环境运行：

```bash
cd backend
.venv/bin/python            # Python 3.11.15
```

**不要用系统 `python3`**：本机系统解释器是 Python 3.9.6，而本仓库代码与本文档示例用到一批 3.10 / 3.11 才引入的特性，在 3.9 上会直接报错或行为不同：

- `zip(..., strict=True)`（3.10）
- `match` 语句（3.10）
- `ExceptionGroup` / `except*`（3.11）
- `asyncio.timeout()` / `asyncio.TaskGroup`（3.11）
- 3.11 新字节码（`KW_NAMES`、`CALL`、`PRECALL`、`JUMP_IF_TRUE_OR_POP`、ExceptionTable 等）——文档里的 `dis` 输出全部来自 3.11.15 实跑，换版本会对不上。

文档中所有 `dis.dis(...)` 字节码输出、`inspect.getsource` 摘录均为 3.11.15 实际运行原样粘贴。C 层引用只写"CPython 3.11 分支 `<路径>` 的 `<函数名/结构名>`"，均已逐一对照该分支源码核实存在；核实不到的一概不写。

## 目录

### 第一层：基础语法（本批）

| 文件 | 主题 | 优先级 | 预计对应的项目文件 |
| --- | --- | --- | --- |
| [01_functions_and_arguments.md](01_functions_and_arguments.md) | 函数与参数：`/`、`*`、默认值、`*args/**kwargs`、`__defaults__`、函数即对象 | 高 | `services/rag_issue_service.py` `begin_admitted`、`core/tracing.py` `RequestTrace.set_meta`、`services/rag_product_service.py` `probe_replay` |
| [02_data_structures_and_comprehensions.md](02_data_structures_and_comprehensions.md) | 容器、推导式、解包、`enumerate/zip/sorted/any/all` | 高 | `services/rag_index_service.py` `plan_nodes`/`ingest_release`、`agent/tools.py` `grade_plugin`、`services/retrieval_service.py` `_fetch_candidate_rows`、`services/task_service.py` `_run_with_deadline` |
| [03_strings_bytes_json.md](03_strings_bytes_json.md) | 字符串、f-string、bytes、hashlib、JSON、re | 高 | `agent/tools.py` `grade_plugin`（sha256 去重键）、`services/task_service.py` 结构化日志、`api/chat.py` `product_events`（SSE 帧）、`services/knowledge_sources.py`（正则切块） |
| [04_truthiness_and_idioms.md](04_truthiness_and_idioms.md) | 真值、`is None`、`and/or` 短路、`getattr`、链式比较、`:=` | 高 | `services/rag_product_service.py` `_candidate`、`services/task_service.py` `_run_with_deadline`、`agent/graph.py` `run_tools` |
| [05_exceptions.md](05_exceptions.md) | `try/except/else/finally`、`raise ... from`、`CancelledError` 层次、`ExceptionGroup`/`except*` | 高 | `api/chat.py` `start_product`/`prepare_product`、`agent/graph.py` `run_tools`、`main.py` 维护循环 |
| [06_classes_and_descriptors.md](06_classes_and_descriptors.md) | 类、`super()`/MRO、特殊方法、`@property/@classmethod/@staticmethod`、描述符协议 | 高 | `services/rag_issue_service.py` `RagIssueService.ph`、`core/llm_client.py` 进程级连接池、`agent/mcp_client.py` `_parse_result`、`storage/postgres.py` `schema_path` |
| [07_modules_and_imports.md](07_modules_and_imports.md) | 模块对象、`sys.modules`、`sys.path`、包与相对导入、循环导入、惰性导入 | 高 | `main.py` 启动钩子、`services/rag_product_service.py` `_assemble_orchestrator`、`rag/interfaces.py`、`backend/scripts/*` |

### 第二层：项目重点（08–16 全部完成）

| 文件 | 主题 | 优先级 | 预计对应的项目文件 |
| --- | --- | --- | --- |
| [08_context_managers.md](08_context_managers.md) | `with` 与上下文管理器、`contextlib` | 高 | `db.py` `get_conn`/`_provider_lock`、`storage/postgres.py`/`sqlite.py` `connect`、`services/rag_index_service.py` `_publish`、`core/tracing.py` `StageTimer`/`timed_stage` |
| [09_iterators_and_generators.md](09_iterators_and_generators.md) | 迭代协议、`yield`、生成器 | 中 | `services/knowledge_sources.py` `iter_files`、`services/task_service.py` `subscribe`、`services/rag_product_service.py` `events`（异步生成器） |
| [10_decorators.md](10_decorators.md) | 装饰器、`functools.wraps`、带参装饰器 | 高 | `api/*.py` 的 `@router.*` 路由与 `@app.on_event`/`@app.middleware`（`main.py`）、`config.py` `@lru_cache`、`agent/tools.py` `@tool`、`db.py` `cache_clear` 垫片、`storage/postgres.py`/`sqlite.py` `@contextmanager` |
| [11_scope_and_closures.md](11_scope_and_closures.md) | LEGB、闭包、`nonlocal/global` | 中 | `agent/graph.py` `build_agent_graph`（节点闭包）、`agent/tools.py` `build_tools`、`agent/service.py` `_build_prompt_builder`、`services/retrieval_service.py` `nonlocal ran`、`core/async_cache.py` `populate` |
| [12_type_hints_and_dataclasses.md](12_type_hints_and_dataclasses.md) | 类型注解、`Protocol`、`TypedDict`、`dataclass` | 高 | `storage/interfaces.py` `DatabaseProvider`、`rag/interfaces.py`、`agent/graph.py` `AgentGraphState`、`core/tracing.py` `RequestTrace`、`services/task_service.py` `ManagedTask`、`core/async_cache.py`、`core/usage.py` |
| [13_asyncio_1_coroutines_and_event_loop.md](13_asyncio_1_coroutines_and_event_loop.md) | 协程、`await`、事件循环原理 | 高 | `task_service.py` docstring/`_run_with_deadline`、`retrieval_service.py` `to_thread` 注释 |
| [14_asyncio_2_tasks_timeout_cancel.md](14_asyncio_2_tasks_timeout_cancel.md) | `create_task`、`wait_for`、超时、取消、`shield`、`to_thread` | 高 | `services/task_service.py`、`services/rag_product_service.py`、`agent/graph.py`、`core/async_cache.py` |
| [15_asyncio_3_sync_primitives_and_async_iter.md](15_asyncio_3_sync_primitives_and_async_iter.md) | `Lock/Condition/Semaphore/Event`、`async for`、异步生成器 | 高 | `services/rag_product_service.py` `Admission`、`services/task_service.py` `ManagedTask`、`core/upstream_gate.py`、`api/chat.py` SSE 订阅 |
| [16_capstone_admission_and_task_registry.md](16_capstone_admission_and_task_registry.md) | 综合实战：仿写 `Admission` 与任务注册表 | 高 | `services/rag_product_service.py` `Admission`、`services/task_service.py` 全文、`rag_issue_service.claim_recovery` |

### 专题附录（按需查阅，不计入 16 篇主线）

| 文件 | 主题 | 建议时机 |
| --- | --- | --- |
| [17_cpython_compile_pipeline.md](17_cpython_compile_pipeline.md) | CPython 编译流水线：源码 → tokenize → AST → code 对象 → 栈机器执行；`.pyc` 缓存；3.11 运行时特化（PEP 659） | 读完 01、11 之后；看不懂「code 对象 / `MAKE_FUNCTION` / dis 输出」时回来查 |

### 与学习路线的对应关系

`../learning_path.md` 的每个阶段，开读代码前建议先过掉对应篇目：

| learning_path 阶段 | 先读 |
| --- | --- |
| 开始前（阶段 0 鸟瞰） | 01–07 全部 + 12 的类型注解基础部分 |
| 阶段 1–2（基础设施、知识入库） | 08、10 |
| 阶段 3–4（检索管线、LLM 基础设施） | 13、14 |
| 阶段 5（Agent 编排） | 11（`build_agent_graph` 大量闭包） |
| 阶段 6–7（产品化编排、任务托管） | 14、15、16 |

## 每篇的统一结构

1. **学习目标 + 在项目里出现在哪**：列出本篇语法落在哪些文件与函数上；
2. **语法详解**：最小可运行示例逐个讲透；
3. **运行机制与 CPython 实现**：等价改写、`dis` 字节码（3.11.15 实跑粘贴）、CPython 源码引用（Lib/ 层用 `inspect.getsource` 摘录，C 层只写已核实的文件名与函数/结构名）；
4. **常见陷阱**；
5. **项目代码精读**：从本仓库原样摘录真实片段逐行解读；
6. **练习**（3–5 道，含"读项目代码回答"与动手写代码）；
7. **参考答案**（文末 `<details>` 折叠，代码题答案均实际跑通过）；
8. **延伸阅读**（官方文档章节名、PEP 编号）。

约定：标为可运行的代码块都可以整段贴进 `.venv/bin/python` 或存成文件直接跑；只作展示、依赖项目内部状态的片段会在代码块首行标注 `# 片段，不可单独运行`。

## 第三层：框架库速查（不单独成篇）

以下库贯穿全代码，但不属于"Python 语言"本身，不单独成篇。建议按下表按需看各自官方文档的入门章节（只列章节名，去官方站点找对应版本）：

| 库 | 何时看 | 最少要懂什么 | 建议读的官方文档章节 |
| --- | --- | --- | --- |
| pydantic v2 | 读 `schemas/`、`config.py` 时 | `BaseModel` 字段校验、`Field`、校验错误 `ValidationError`；`pydantic-settings` 的 `BaseSettings` 从环境变量装配 | "Models"、"Fields"、"Validation"（pydantic docs）；pydantic-settings 的 "Settings management" |
| FastAPI | 读 `api/*.py` 时 | `APIRouter` 路由装饰器、`Depends` 依赖注入、`Query/Path/Body` 参数声明、`HTTPException`、`StreamingResponse` | "First Steps"、"Path Parameters"、"Query Parameters"、"Dependencies"、"Streaming Responses" |
| httpx | 读 `core/llm_client.py` 时 | `AsyncClient` 连接池、`Timeout`/`Limits`、响应模型 | "Quickstart"、"Async Support"、"Timeouts"、"Connection Pooling" |
| LangChain messages | 读 `agent/` 时 | `SystemMessage/HumanMessage/AIMessage/ToolMessage` 四种消息角色、`tool_calls`、`usage_metadata` | LangChain "Messages" 概念页 |
| LangGraph | 读 `agent/graph.py` 时 | `StateGraph`、`add_node`/`add_conditional_edges`、状态合并语义（本仓无 checkpointer） | LangGraph "Low Level Concepts" / "Quickstart" |
| psycopg 3 & sqlite3 | 读 `storage/` 时 | `conn.execute().fetchall()`、占位符方言（`%s` vs `?`）、事务边界 | psycopg "Basic module usage"；sqlite3 "DB-API 2.0" |
| pytest | 读 `tests/` 时 | `fixture`/`conftest.py`、`parametrize`、`mark`（本仓 `live`/`postgres`）、`monkeypatch` | pytest "How to use fixtures"、"Parametrize"、"Marks" |
