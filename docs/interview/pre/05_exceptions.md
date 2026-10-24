# 05 异常处理

## 学习目标

- 掌握 `try / except / else / finally` 的执行顺序与 `finally` 和 `return` 的交互；
- 看懂异常层次：`BaseException` / `Exception` / `KeyboardInterrupt`，特别是 **`asyncio.CancelledError` 自 3.8 起直接继承 `BaseException`**——`except Exception` 抓不到取消；
- 会用 `raise`、裸 `raise` 重抛、`raise X from Y` 与 `from None`，理解 `__cause__` / `__context__` / `__suppress_context__`；
- 会定义自定义异常类并用它做"流程信号"；
- 了解 3.11 的 `add_note` 与 `ExceptionGroup` / `except*`；
- 知道 3.11 零开销异常：字节码末尾的 ExceptionTable。

**本主题在项目里出现在哪**

| 位置 | 用到什么 |
| --- | --- |
| `backend/app/api/chat.py` `start_product` | `except BaseException: await admission.release(); raise`（兜底释放后原样重抛） |
| `backend/app/api/chat.py` `prepare_product` / `chat` | `except HTTPException: raise` 在 `except Exception` 之前；`raise HTTPException(503, ...) from None` |
| `backend/app/agent/graph.py` `run_tools._invoke` 与并发分支 | `except RetryableTaskFailure: raise` 在 `except Exception` 之前；`except BaseException: 取消其余任务; raise` |
| `backend/app/agent/graph.py` `_invoke_with_usage` | `except BaseException: usage.record_llm(..., None); raise`（失败也记账后重抛） |
| `backend/app/agent/tools.py` `grade_plugin` | `raise RetryableTaskFailure("task_job_ledger_unavailable") from exc`（异常链） |
| `backend/app/main.py` `start_rag_maintenance` / `maintain` | 每段独立 `try/except`，互不影响的长循环 |
| `backend/app/services/task_errors.py` | 自定义异常 `RetryableTaskFailure(Exception)` |
| `backend/app/agent/mcp_client.py` `_call_once` | `except Exception as exc: return None, exc, submitted`（异常收敛为返回值） |

AGENTS.md 说的 "fallback 哲学"——内部异常不让接口 500、各组件独立降级——全靠本篇的语法实现。

## 语法详解

### try / except / else / finally

```python
def t3(x):
    try:
        v = 10 // x          # 可能抛
    except ZeroDivisionError:
        return "zero"        # 命中 except
    else:
        return f"ok {v}"     # 没抛异常才走 else
    finally:
        pass                 # 走哪条路都执行

print(t3(2), t3(0))
```

输出：`ok 5 zero`

- `except` 按**书写顺序**逐个匹配，命中即停——所以更具体的异常必须写在更宽的前面；
- `else` 只在 try 块**无异常**时执行，用于"把不该进 try 的成功路径挪出去"；
- `finally` 无论是否异常、是否 `return`/`break`/`continue` 都执行——放清理代码。

### `finally` 与 `return` 的交互

```python
def t1():
    try:
        return "try"
    finally:
        print("cleanup")

def t2():
    try:
        return "try"
    finally:
        return "finally"     # finally 的 return 覆盖 try 的 return

print(t1(), t2())
```

输出：

```text
cleanup
try finally
```

`t1`：先算好返回值，执行 `finally`（打印），再返回 `"try"`。`t2`：`finally` 里的 `return` **覆盖** try 中的返回值——甚至能吞掉正在传播的异常。这是陷阱：`finally` 里写 `return`/`break` 会静默吃掉异常，项目代码里 `finally` 只做清理不做 return。

### 异常层次与 CancelledError

```python
import asyncio
print(asyncio.CancelledError.__mro__)
```

输出（3.11.15 实跑）：

```text
(<class 'asyncio.exceptions.CancelledError'>, <class 'BaseException'>, <class 'object'>)
```

**`CancelledError` 不是 `Exception` 的子类**。层次简图：

```text
BaseException
 ├── KeyboardInterrupt        # Ctrl+C
 ├── SystemExit               # sys.exit()
 ├── GeneratorExit
 ├── asyncio.CancelledError   # task.cancel()
 └── Exception                # 正常"错误"都挂这里
      ├── ValueError / TypeError / KeyError ...
      ├── OSError → TimeoutError / ConnectionError ...
      ├── RuntimeError
      └── 自定义异常（如 RetryableTaskFailure）
```

验证 `except Exception` 抓不到取消：

```python
try:
    raise asyncio.CancelledError()
except Exception:
    print("caught by Exception")
except BaseException:
    print("caught by BaseException only")
```

输出：`caught by BaseException only`

这正是全仓"兜底清理后重抛"必须写 `except BaseException` 的原因：协程被取消时也要释放 admission 槽、记账、取消兄弟任务——用 `except Exception` 会在取消路径上漏清理。

### `raise` 与裸 `raise`

```python
def outer():
    try:
        raise KeyError("orig")
    except KeyError:
        raise   # 裸 raise：重抛当前正在处理的异常（不是新造一个）

try:
    outer()
except KeyError as e:
    print("reraised:", e)
```

输出：`reraised: 'orig'`

裸 `raise` 只能在 `except` 块（或它调用的函数）里用，重抛的是**同一个异常对象**，traceback 上保留原始抛出点——`except BaseException: 清理; raise` 的收尾语义全靠它。

### `raise X from Y` 与 `from None`

```python
try:
    raise ValueError("inner") from KeyError("k")
except ValueError as e:
    print("cause:", repr(e.__cause__), "ctx:", repr(e.__context__), "suppr:", e.__suppress_context__)

try:
    try:
        raise KeyError("k")
    except KeyError:
        raise ValueError("inner") from None
except ValueError as e:
    print("from None:", repr(e.__cause__), repr(e.__context__), e.__suppress_context__)
```

输出（3.11.15 实跑）：

```text
cause: KeyError('k') ctx: None suppr: True
from None: None KeyError('k') True
```

- `raise X from Y`：`__cause__ = Y`，`__suppress_context__ = True`——traceback 打印 "The above exception was the direct cause of..."；
- 在 `except` 块里 `raise` 新异常（不带 `from`）：`__context__` 自动链上正在处理的异常，打印 "During handling of the above exception, another exception occurred"；
- `from None`：`__context__` 仍记录着（如上 `KeyError('k')`），但 `__suppress_context__ = True` 让 traceback **不显示**它——`raise HTTPException(503, ...) from None` 就是把内部异常藏成"服务不可用"，对外只暴露干净原因。

### 自定义异常

```python
class RetryableTaskFailure(Exception):
    """数据库暂不可用导致步骤未提交；不得把请求收成正常终态。"""
```

项目里就这两行（`services/task_errors.py`）。自定义异常的价值不在字段，在于**类型本身就是信号**：`except RetryableTaskFailure: raise` 让"可重驱动失败"穿过 `except Exception` 的兜底网直达上层。

### `add_note` 与 `ExceptionGroup` / `except*`（3.11）

```python
e = ValueError("v")
e.add_note("extra info")
print(e.__notes__)                    # ['extra info']

try:
    try:
        raise ExceptionGroup("eg", [ValueError("a"), TypeError("b")])
    except* ValueError as eg:
        print("caught ValueErrors:", eg.exceptions)
        # 组内未被匹配的 TypeError 会被重新包装继续向上抛
except* TypeError as tg:
    print("rest:", tg.exceptions)
```

输出（3.11.15 实跑）：

```text
['extra info']
caught ValueErrors: (ValueError('a'),)
rest: (TypeError('b'),)
```

`add_note` 给已捕获的异常追加一行注释（traceback 里可见），用于"异常往上走时沿途补充上下文"。`ExceptionGroup` 把多个异常打包，`except* T` 只匹配组内类型为 `T` 的成员、其余成员继续传播——并发任务聚合错误（`TaskGroup`）依赖它。本项目当前未用 `TaskGroup`，但读 LangGraph/asyncio 内部代码会遇到。

## 运行机制与 CPython 实现

### 等价改写：except 链

```python
# 片段，不可单独运行（work/log 为示意名）
try:
    work()
except RetryableTaskFailure:
    raise
except Exception as exc:
    log(exc)
```

等价于（概念上）：

```python
# 片段，不可单独运行（work/log 为示意名）
try:
    work()
except BaseException as _e:          # 解释器先接住一切
    if isinstance(_e, RetryableTaskFailure):
        raise
    elif isinstance(_e, Exception):
        exc = _e
        log(exc)
    else:
        raise                        # 都不匹配：原样传播
```

每个 `except` 子句就是一次 `isinstance` 判定——**顺序即优先级**。

### 字节码：零开销异常（3.11）

以下输出由 `backend/.venv/bin/python`（Python 3.11.15）实跑 `dis.dis` 得到：

```python
def f(x):
    try:
        return 1 / x
    except ZeroDivisionError:
        return -1
    finally:
        print("done")
```

```text
 44           0 RESUME                   0

 45           2 NOP

 46           4 LOAD_CONST               1 (1)
              6 LOAD_FAST                0 (x)
              8 BINARY_OP               11 (/)

 50          12 LOAD_GLOBAL              1 (NULL + print)
             24 LOAD_CONST               2 ('done')
             26 PRECALL                  1
             30 CALL                     1
             40 POP_TOP
             42 RETURN_VALUE
        >>   44 PUSH_EXC_INFO

 47          46 LOAD_GLOBAL              2 (ZeroDivisionError)
             58 CHECK_EXC_MATCH
             60 POP_JUMP_FORWARD_IF_FALSE    19 (to 100)
             62 POP_TOP

 48          64 POP_EXCEPT

 50          66 LOAD_GLOBAL              1 (NULL + print)
             78 LOAD_CONST               2 ('done')
             80 PRECALL                  1
             84 CALL                     1
             94 POP_TOP
             96 LOAD_CONST               3 (-1)
             98 RETURN_VALUE

 47     >>  100 RERAISE                  0
        >>  102 COPY                     3
            104 POP_EXCEPT
            106 RERAISE                  1
        >>  108 PUSH_EXC_INFO

 50         110 LOAD_GLOBAL              1 (NULL + print)
            122 LOAD_CONST               2 ('done')
            124 PRECALL                  1
            128 CALL                     1
            138 POP_TOP
            140 RERAISE                  0
        >>  142 COPY                     3
            144 POP_EXCEPT
            146 RERAISE                  1
ExceptionTable:
  4 to 10 -> 44 [0]
  44 to 62 -> 102 [1] lasti
  64 to 64 -> 108 [0]
  100 to 100 -> 102 [1] lasti
  102 to 106 -> 108 [0]
  108 to 140 -> 142 [1] lasti
```

读法（dis 输出的行号 44–50 来自上面这段代码在验证脚本中的位置，下同）：

- **没有 `SETUP_FINALLY` 了**——3.11 起 try 块的正常路径零开销：偏移 4–10 只是普通的除法；
- 末尾 `ExceptionTable` 是异常路由表：`4 to 10 -> 44` 表示"偏移 4–10 抛异常就跳到 44"（`PUSH_EXC_INFO` 开始匹配 `except`）；
- finally 被编译成**多份副本内联到各出口**：源码第 50 行的 `print("done")` 在 dis 里出现三次——正常返回路径（偏移 12–40）、`except` 命中路径（66–94）、异常穿透路径（110–138 的 `PUSH_EXC_INFO` 段）；
- `CHECK_EXC_MATCH` 就是 `except ZeroDivisionError` 的 `isinstance` 判定；不匹配则 `RERAISE` 重抛；
- 推论：try 块便宜，**抛异常贵**——异常路径要做表查找 + traceback 构造，所以项目里用异常做"出错路径"而不是"正常流程"（与 `or` 默认值、哨兵值的分工）。

### CPython 源码

- `__context__` / `__cause__` / `__suppress_context__` 是 `BaseException` 上的 getset 属性：CPython 3.11 分支 `Objects/exceptions.c` 中 `BaseException_set_context` 对 `__context__` 赋非异常值会报 `"exception context must be None or derive from BaseException"`，`__suppress_context__` 是结构体上的 `T_BOOL` 成员；`raise ... from ...` 由解释器在 `RAISE_VARARGS` 执行时设置这两个字段（同文件可见 `suppress_context = 1` 的赋值路径）。
- `add_note` 是同文件的 `BaseException_add_note`（3.11 新增，挂在 `BaseException_methods` 表上），把字符串追加进 `__notes__` 列表。
- `ExceptionGroup` 的层次（`BaseExceptionGroup extends BaseException`、`ExceptionGroup extends BaseExceptionGroup and Exception`）写在该文件的注释与类型定义处。

## 常见陷阱

1. **`except:` 裸写**：等价于 `except BaseException`，会吞 `KeyboardInterrupt`/`CancelledError`——全仓禁止；要兜底用 `except BaseException` 且必须 `raise` 或明确记录。
2. **except 顺序颠倒**：`except Exception` 写在 `except ValueError` 前，后者永远不命中（`Exception` 已拦截）。
3. **`finally` 里 `return`/吞异常**：覆盖返回值、静默吃掉传播中的异常——`finally` 只做幂等清理。
4. **`except Exception` 当取消用**：`CancelledError` 不在其内——async 代码里"无论如何都要清理"用 `except BaseException` 或 `try/finally`。
5. **`raise X` 与 `raise X()`**：`raise ValueError` 合法（解释器自动实例化），但带消息更常见。
6. **在 `except` 块里再抛新异常默认带上 `__context__`**：日志会打两条 traceback；不想暴露内部细节时显式 `from None`（对外 API）或 `from exc`（保留因果链给运维看）。
7. **`except` 中访问 `e` 出块即删**：`except X as e:` 结束后 `e` 被解除绑定（防引用环）——要留用得先存别的变量。

## 项目代码精读

> 本节代码块均按仓库原文摘录（保留原缩进与注释），依赖项目内部上下文，**不可单独运行**。

### `chat.py start_product`：BaseException 兜底释放

`backend/app/api/chat.py`：

```python
async def start_product(product):
    """先占槽再受理；冲突、配额或寄存失败均释放槽且不启动第二执行体。"""
    await admission.acquire()
    try:
        await prepare_product(product, quota=product.principal.daily_quota)
        if product.replayed is not None or product.attached_row is not None:
            await admission.release()
            return None
        return task_service.start_reserved_task(product)
    except BaseException:
        await admission.release()
        raise
```

- 占槽后任何一步失败——包括 `CancelledError`（客户端断线）——都必须 `release()`，否则 admission 槽泄漏、后续请求永久 429；
- `except BaseException` 兜住 `Exception` + `CancelledError` + `KeyboardInterrupt` 全谱系，清理后裸 `raise` 重抛同一异常——不吞、不转换；
- 早退分支（`replayed`/`attached_row`）显式 `release()` 后 `return None`：正常路径不放异常通道。

### `chat.py prepare_product`：except 顺序 + from None

`backend/app/api/chat.py`：

```python
async def prepare_product(product, quota: int = 0):
    """原子受理的超时兜底；配额只在新任务事务中记一次。"""
    try:
        await asyncio.wait_for(product.prepare(quota=quota), get_settings().rag_prepare_timeout_sec)
    except HTTPException:
        raise
    except Exception:
        logger.exception("问答受理失败")
        raise HTTPException(503, "服务暂时不可用，请保留问题后重试") from None
```

- `except HTTPException: raise` 必须在 `except Exception` **之前**——`HTTPException` 本身是 `Exception` 子类，写反了会被兜底分支转成 503，业务码（409/403）全丢；
- 非 HTTPException 的一切内部错误：记全 traceback（`logger.exception`）后 `raise HTTPException(503, ...) from None`——对外干净 503，`from None` 隐藏内部异常链不泄露实现细节；
- `wait_for` 超时抛 `asyncio.TimeoutError`（`Exception` 子类）→ 同样被收敛成 503。

### `graph.py run_tools`：语义分层的三段 except

`backend/app/agent/graph.py`：

```python
                try:
                    raw = await tool_impl.ainvoke(call.get("args") or {})
                    result = raw if isinstance(raw, dict) else {"ok": True, "data": raw}
                except RetryableTaskFailure:
                    raise
                except Exception as exc:
                    logger.warning(json.dumps({
                        "event": "agent_tool_exception", "tool": name,
                        "error_type": type(exc).__name__, "detail": str(exc)[:300],
                    }, ensure_ascii=False))
                    result = {
                        "ok": False, "error": "tool_exception",
                        "detail": f"工具执行异常：{type(exc).__name__}",
                    }
```

- `except RetryableTaskFailure: raise`：**可重驱动失败不当普通工具错误**——`RetryableTaskFailure` 意味着 DB 暂不可用、任务应等孤儿恢复，把它写成 `{"ok": False}` 工具结果会让模型看到"工具失败"并把任务收终态，不可恢复；
- 其后的 `except Exception` 才是普通工具异常：收敛成结构化错误结果喂给模型（fallback 哲学：工具失败 ≠ 问答失败）；
- 顺序即语义：先放行"信号型异常"，再兜底"数据型错误"。

并发分支的 `except BaseException`（`backend/app/agent/graph.py` `run_tools`）：

```python
            tasks = [asyncio.ensure_future(_invoke(call)) for call in calls]
            try:
                outputs = list(await asyncio.gather(*tasks))
            except BaseException:
                for task in tasks:
                    task.cancel()
                await asyncio.gather(*tasks, return_exceptions=True)
                raise
```

- 任一调用抛错（含 `RetryableTaskFailure` 与外部 `CancelledError`）：先 `cancel()` 其余兄弟任务、`gather(..., return_exceptions=True)` 等它们全部落地（避免"任务还在跑却被遗弃"的 leaked task 警告），再原样上抛；
- 为什么要手动取消？`gather` 的行为分两种：**外层 `await gather` 被取消时**，gather 会把取消传播给所有子任务；但**某个子任务抛普通异常**（未设 `return_exceptions=True`）时，gather 立即把该异常抛给 await 方，**其余子任务不被取消、继续在后台跑**——`run_tools` 的并发轮要的语义是"一个失败全轮作废"，所以必须显式 `task.cancel()`。可运行实验（3.11.15 实跑）：

  ```python
  import asyncio

  async def child(n, fail=None, delay=10):
      try:
          await asyncio.sleep(0.01 if fail else delay)
          if fail:
              raise fail
          return n
      except asyncio.CancelledError:
          print(f"child {n} cancelled")
          raise

  async def demo_exception():
      t1 = asyncio.ensure_future(child(1, fail=ValueError("boom")))
      t2 = asyncio.ensure_future(child(2))
      try:
          await asyncio.gather(t1, t2)
      except ValueError as e:
          print("gather raised:", e)
      await asyncio.sleep(0.02)
      print("t2 done after sibling failure:", t2.done(), "cancelled:", t2.cancelled())

  async def demo_cancel():
      t1 = asyncio.ensure_future(child(1))
      t2 = asyncio.ensure_future(child(2))
      g = asyncio.ensure_future(asyncio.gather(t1, t2))
      await asyncio.sleep(0.005)
      g.cancel()
      try:
          await g
      except asyncio.CancelledError:
          print("outer gather cancelled")
      print("t1 cancelled:", t1.cancelled(), "t2 cancelled:", t2.cancelled())

  asyncio.run(demo_exception())
  asyncio.run(demo_cancel())
  ```

  输出：

  ```text
  gather raised: boom
  t2 done after sibling failure: False cancelled: False
  child 2 cancelled
  child 1 cancelled
  child 2 cancelled
  outer gather cancelled
  t1 cancelled: True t2 cancelled: True
  ```

  第一段：t1 抛 `ValueError` 后 gather 立即上抛，但 `t2.done()` 仍是 `False`——t2 没被取消；随后那行 `child 2 cancelled` 是 `asyncio.run` 关闭时统一取消 pending 任务打出来的，不是 gather 干的。第二段：`g.cancel()` 取消外层 gather，取消**传播**进两个子任务（先打出 `child 1 cancelled`/`child 2 cancelled`，`t1.cancelled()`/`t2.cancelled()` 都为 `True`）——**取消会传播，普通异常不会**，这正是项目里手动 `task.cancel()` 要补的缺口。

### `main.py` 维护循环：每段独立 try

`backend/app/main.py` `start_rag_maintenance`：

```python
    try:
        await task_service.recover_orphans()
    except Exception:
        logger.exception("启动时孤儿任务恢复失败")
    try:
        from .services.assignment_grading_attempts import reconcile
        await reconcile(get_settings())
    except Exception:
        logger.exception("启动时正式提交尝试核对失败")

    async def maintain():
        from .services.rag_issue_service import RagIssueService
        while True:
            try:
                await asyncio.to_thread(RagIssueService().maintain, get_settings().rag_retention_days)
            except Exception:
                logging.getLogger("assistant").exception("RAG 留存维护失败")
            # 恢复扫描与留存清理独立成败：一边挂了另一边照常跑。
            try:
                await task_service.recover_orphans()
            except Exception:
                logging.getLogger("assistant").exception("孤儿任务恢复扫描失败")
            try:
                from .services.assignment_grading_attempts import reconcile
                await reconcile(get_settings())
            except Exception:
                logging.getLogger("assistant").exception("正式提交尝试核对失败")
            await asyncio.sleep(300)
```

- 三个维护动作各自 `try/except Exception` + `logger.exception`（带 traceback 的 error 级日志）：一个挂掉不影响另外两个，也不杀死 `while True` 循环；
- `except Exception` 在这里够用——`CancelledError`（关闭时 `task.cancel()`）会穿透这三个 except 直接终止 `maintain` 协程，**这正是想要的**：维护任务该死就死；
- 与 `except BaseException` 的分工：要清理资源→`BaseException`（清理后重抛）；要隔离故障→`Exception`（让取消穿透）。

### `tools.py`：`raise ... from exc` 保留因果链

`backend/app/agent/tools.py` `grade_plugin`：

```python
        try:
            ledger = await _resume_grading_job(
                context, protocol, exec_bytes, call_mcp) if context.task_id else None
        except Exception as exc:
            raise RetryableTaskFailure("task_job_ledger_unavailable") from exc
```

- 账本接续失败 = DB 暂不可用 = 任务等孤儿恢复，所以转成 `RetryableTaskFailure`；
- `from exc` 把原始异常挂进 `__cause__`——日志里能看到 "direct cause" 链，运维能追到真正的 DB 错误；
- 对照 `prepare_product` 的 `from None`：对内（日志/运维）保留链，对外（HTTP 响应）隐藏链。

## 练习

1. **读项目代码回答**：`graph.py` `_invoke` 中 `except RetryableTaskFailure: raise` 若被删（只留 `except Exception`），`task_job_ledger_unavailable` 失败会发生什么？对任务恢复有什么影响？
2. **读项目代码回答**：`chat.py start_product` 为什么用 `except BaseException` 而不是 `except Exception` 来保护 `admission.release()`？写一个会泄漏槽位的具体场景。
3. **动手写代码**：写一个 `divide_all(pairs)`：对 `(a, b)` 列表逐项除法，`b == 0` 时该位记 `"err"` 不中断；最后返回结果列表与错误计数。要求用 `try/except/else` 结构。
4. **动手写代码**：写 `load_config(path)`：读文件并 `json.loads`；文件不存在或 JSON 非法时 `raise ConfigError("...") from exc`（自定义 `ConfigError`），并验证 `__cause__` 链接正确、`from None` 版本 traceback 不显示内部异常。
5. **读项目代码回答**：`mcp_client._call_once` 用 `except Exception as exc: return None, exc, submitted` 把异常变成返回值。为什么这里**不**用 `except BaseException`？`submitted` 标记与"已提交调用绝不重试"的关系是什么？

## 参考答案

<details><summary>参考答案 1</summary>

删掉后 `RetryableTaskFailure` 会被 `except Exception` 拦截，收敛成 `{"ok": False, "error": "tool_exception"}` 工具结果喂给模型——图继续跑：planner 拿这个"工具失败"消息继续编，最终走到 `finalize` 产出答案，`_run_candidate_once` 正常到达 `issues.finish(...)` 把任务行写成终态。这笔请求就以"已答复"收官，**不会被重驱动**。

对比被 `except RetryableTaskFailure: raise` 放行后的实际路径（已对照代码核对）：异常穿透 `_invoke` → `run_tools` → `rag_product_service._run_candidate_once` 中同名 `except RetryableTaskFailure: raise` 再上抛——这一步**跳过了 `issues.finish`**（finish 调用在 except 链之后，只有拿到 verdict 的路径才执行），任务行保持 `pending`；`run()` 的 `finally` 只记 `_log_trace()`；异常到 `task_service._run_with_deadline` 被 `except Exception` 收成 `managed.error`，`finally` 里查库行 `row is None or row["status"] == "pending"` → 仍是 pending → **保留 `rag_task_inputs`**（不 `drop_task_inputs`）、释放槽位、置 `terminal`（`is_running` 变 False，`sweep_finished` 惰性清理条目）；之后 `recover_orphans` 扫到这条过宽限期的 pending 行，用寄存的输入重驱动。一层 `except` 之差 = "库行终态判死"与"留输入等恢复"之别。

</details>

<details><summary>参考答案 2</summary>

`CancelledError` 继承 `BaseException` 而非 `Exception`。具体场景：请求处理协程被取消时（例如服务关闭或上层显式 `task.cancel()`），`CancelledError` 在 `await prepare_product(...)` 处抛出 → 若只 `except Exception`，取消穿透而不释放槽位 → 该槽永久被占；槽位是有限的（活动/等待各有界），反复发生会把容量耗光，新请求全部 429 且进程内无自愈路径。`except BaseException` 兜住取消路径完成 `release()` 再重抛，是"资源归还必须覆盖取消语义"的标准写法；等价替代是 `try/finally`，但这里需要区分"正常 return"与"异常/取消"两条出口，except 写法更贴合。

</details>

<details><summary>参考答案 3</summary>

```python
def divide_all(pairs):
    results, errors = [], 0
    for a, b in pairs:
        try:
            v = a / b
        except ZeroDivisionError:
            results.append("err")
            errors += 1
        else:
            results.append(v)
    return results, errors

print(divide_all([(10, 2), (1, 0), (7, 2)]))
```

实跑输出：`([5.0, 'err', 3.5], 1)`。`else` 分支只放"成功才做"的事，与项目 `prepare` 里"成功才登记"同构；单个元素失败不中断整体，正是 main.py 维护循环"每段独立 try"的缩小版。

</details>

<details><summary>参考答案 4</summary>

```python
import json

class ConfigError(Exception):
    pass

def load_config(path):
    try:
        with open(path, encoding="utf-8") as f:
            return json.loads(f.read())
    except (OSError, json.JSONDecodeError) as exc:
        raise ConfigError(f"配置不可用: {path}") from exc

try:
    load_config("/tmp/pre_verify/nonexistent.json")
except ConfigError as e:
    print("cause:", type(e.__cause__).__name__, "| ctx suppressed:", e.__suppress_context__)
```

实跑输出：`cause: FileNotFoundError | ctx suppressed: True`。`from exc` 显式设置 `__cause__` 并抑制 `__context__` 显示——traceback 会打印 "direct cause" 链。若写 `from None`，`__cause__` 为 `None` 但 `__suppress_context__` 仍为 True，traceback 只显示 `ConfigError`——这就是 `prepare_product` 对外的形态。

</details>

<details><summary>参考答案 5</summary>

`_call_once` 的职责是"把一次尝试的成败变成 `(result, exc, submitted)` 三元组"供重试循环决策。若用 `except BaseException`，`CancelledError` 也会被吞成返回值——外层的"不重试、直接上抛取消"语义被破坏（取消变成普通失败数据）。`except Exception` 恰好让 `CancelledError` 穿透，与全仓取消语义一致。`submitted` 标记区分"建连/initialize 阶段失败"（未提交，可安全重试）与"call_tool 已发出后失败"（已提交，评测可能已在 harness 侧计费/执行，重试会重复消耗配额与工时）——`call_tool` 的重试循环看到 `submitted=True` 直接放弃，这就是注释"已提交的调用绝不重试"的实现。

</details>

## 延伸阅读

- Python 官方教程："Errors and Exceptions"（含 `raise ... from` 链、`finally`）。
- 语言参考："The try statement"、"The raise statement"、"Exceptions"（内置层次全图）。
- PEP 3134（异常链 `__cause__`/`__context__`）、PEP 678（`add_note`）、PEP 654（`ExceptionGroup`/`except*`）、PEP 479/380 相关生成器异常。
- `traceback` 模块文档；`contextlib` 的 `suppress`/`ExitStack`。
