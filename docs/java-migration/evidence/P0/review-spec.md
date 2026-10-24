# P0 独立 Spec 审查

- 审查者：`/root/p0_review_spec`；日期：2026-10-24。
- 固定点：`80b29135faddd16d5e39fdbf0633291ea6a20489`；审查提交：`08158c6`。
- 差分：`git diff 80b29135faddd16d5e39fdbf0633291ea6a20489...08158c6`；提交列表只有 `08158c6 冻结 P0 初版契约与合成差分 oracle，提交独立审查`。
- 范围：只核对 P0 规格；未读取其他审查者结论。仅写本文，未修改实现/契约/fixture/状态，未提交或推送。
- 验证边界：只读源码、已提交合成证据和纯内存 Python 断言；没有连接 PG、导入应用、启动服务或运行动态 oracle/负载。

## 审查结论

发现 4 项需要修正的问题：1 项 P1、3 项 P2。它们影响未来候选的差分判定或 P1 可消费契约，不是要求提前实现 Java。

正式三轮长测、阈值确认/最终报告和有效 token 转停用补验，已由主 Agent 明确列为待补，本审查不把这些待补本身另报缺陷。COMP-003 保留 Python 重投实测与 Java 安全目标的区分正确，不要求更改 Python 黄金，也没有将 Java 安全策略视作已实现。

## 发现

### S1 [P1] 差分 trace 缺少数据库业务值，重复扣次等错误可以得到相同 trace

- 规格：`docs/java-migration/acceptance.md:93` 要求“每个样例保存输入、前置状态、上游脚本、预期响应、状态迁移及副作用计数”；`:98` 要求比较“权限、顺序与副作用”；`:125` 明确“SQL结果必须比较业务意义与约束，不能只比较行数”；`:184` 的重复业务扣费硬阈值为零。
- 新代码：`migration/oracle/route_cases.py:18-41` 的前置状态统一为 `seeded-synthetic-tree`，逐请求副作用只记录 LLM/MCP 调用次数；`migration/oracle/run.py:43-46` 只取部分表行数和任务/消息关联，`:60-65` 把数据库事务、向量结果及上游账本放进独立 report，未放入被 `verify` 比较的 trace。`migration/oracle/verify.py:19-24` 只读取黄金和 candidate trace。
- 已提交证据：`migration/fixtures/p0-v1/python-golden.json:11601-11700` 的 state 只有 associations/counts/source_marker_hits；既无 `usage_counters` 值，也无 upstream usage 的 null/0、job.submission_done、成绩行的业务字段及引用行内容。有效 usage HTTP 读取发生在 observation 17–19，正式提交和 chat 在之后，没有成功的业务写后 usage 回读。
- 可复现断言：同样的 HTTP 响应、外部调用数、表行数与任务身份关联下，将 chat 写入的 `chat_count=N+1` 错写为 `N+2`，或者仅在 replay 再扣一次，当前 trace 结构无法表达该差异，`verify` 无可比较字段。纯内存检查已确认两个 usage 表不在 counts，全部 184 个 side_effect_counts 仅含 `llm/mcp`；物化黄金中的生成 ID/耗时后自对照通过。没有动态注入 PG 故障，故这里是代码和投影结构已证实的盲区，不声称实测了 Java 错误实现。
- 修正：为关键写/重放样例导出可比较的最小语义前后状态/增量，至少包括主体/day/kind 扣次、成绩与 job 提交标志、答案/引用/终态关联及 usage unknown；将关键 PG/固定向量结果纳入候选可提交的同版 trace。增加“保持响应与行数、只破坏业务值”的负例。仅写在非比较 report 的 Python 断言不能替代候选差分。
- 已披露限制 `known-differences.md:15` 与上述事实一致，但它不是用户批准的 oracle 豁免；最终冻结前仍需关闭这个核心判错缺口。本文不要求一轮完成所有历史业务并发分支。

### S2 [P2] 模型枚举漏掉继承模型 ManagerAssignment，覆盖报告仍为 100%

- 规格：`docs/java-migration/foundation-slices.md:12` 要求 JSON/schema 字段契约，`:19` 要求关键接口字段明确；`migration/contracts/README.md:9-11` 对消费者承诺 models 主键/fields/bases 和单 owner 映射。
- 新代码：`migration/baseline/inventory.py:143-146` 只接受直接以 `BaseModel` 为基类的类，未求传递继承；因此 `coverage()` 只能拿不完整集合校对同源映射，并仍报 100%。
- 实际遗漏：冻结源码 `backend/app/schemas/assignments.py:58-60` 的 `ManagerAssignment(AssignmentInfo)` 及新增 `submission_count/student_count` 未进入 static-inventory.models/coverage-map.models；它在 `backend/app/api/assignments.py:183` 实际用于管理端列表。现有 `route_cases.py:57-62` 在创建作业前读取列表，唯一成功样例是空数组（黄金 `:511` 附近），也未补上这个实际响应形状。
- 可复现断言：仅 AST 解析 assignments.py，文件声明 17 个模型，而 `schemas_inventory` 返回 16 个，`ManagerAssignment` 不在结果；已执行，输出 `derived_model_enumerated=false`。不需要 app 或 PG。
- 修正：静态解析 BaseModel 的传递继承，保留 bases/自有字段并映射 owner；增加继承模型漏项护栏及非空管理列表样例。

### S3 [P2] SSE 解析器把 CRLF 改写为 LF，接受冻结前端无法消费的线格式

- 规格：`AGENTS.md:21` 要求 REST/SSE 按冻结契约；`docs/java-migration/acceptance.md:100` 只允许传输分块差异；`migration/contracts/http-and-sse.md:17` 冻结唯一线格式 `event: ...\ndata: ...\n\n`，前端复用属于范围。
- 新代码：`migration/oracle/compare.py:19` 将 CRLF/CR 全部转为 LF，然后 trace 只留解析后的事件。冻结前端 `frontend/src/api/client.js:169-170` 仅用 `\n\n` 分帧，没有这项改写。
- 可复现断言：相同 start/done 内容分别编码 LF 和 CRLF，`compare(parse_sse(LF), parse_sse(CRLF))` 通过；按冻结前端实际 split 表达式，LF 有 2 个完整帧，而 CRLF 有 0 个。已用纯内存断言执行。由此候选可被差分判等，但原 React 收不到任何事件。
- 修正：在 wire→trace 适配处验证冻结前端实际可消费的帧格式，或将改变客户端兼容性作为明确审批的差异；不能只用更宽松的 SSE 标准解析结果替代当前客户端行为。保留逐字节 UTF-8 分块正例。

### S4 [P2] 强制所有 SSE 以 done 结束，拒绝 Python 已有 error-only 故障流

- 规格：`docs/java-migration/acceptance.md:98-100` 要求同时比较错误和 SSE 状态机；`migration/contracts/http-and-sse.md:27` 明确流开始后异常用 `error` 事件，不能修改 HTTP 状态。
- 新代码：`migration/oracle/compare.py:64-73` 只让 done 设置终止标志，所有没有 done 的流最终都报错。冻结 `backend/app/api/chat.py:164-165` 和 `backend/app/api/tasks.py:76-78` 的异常分支只发 error 后结束，没有 done。因此即使预期和候选都是同一个真实源错误响应，也不能自对照或冻结成故障黄金。
- 可复现断言：构造源码原形 `event: error\ndata: {"message":"服务暂时不可用，请稍后重试","retryable":true}\n\n`，分别作为 expected/actual；`compare` 抛出 `Difference('SSE 缺少 done')`。已纯内存执行；start 后 error 同样失败。
- 修正：按冻结行为区分成功 done、pending 非终态 done 与 error 结束；验证 error payload、顺序和不得再有后续业务事件，同时继续拒绝“仅截断、没有 done 或 error”的流。补 error-only 与 start→error 正例和截断负例。

## 已核对且未发现需新增问题的部分

- `run.py → app.main/route/services`、真实 PG SQL 与 LangGraph 调用存在，功能 oracle 并非 toy 业务重写；模型/embedding/MCP 工厂是合成替身。
- 生成身份按全 trace 双射、调用方 request_id 保留、对象 key 稳定遍历、数组不排序；时间逐值比较且检查 UTC/精度/局部时序；耗时单独声明。
- SSE source 累计前缀、身份关联、pending/terminal:false、done 后事件、EOF 截断 done 均有守卫。S3/S4 不否定这些已正确覆盖的断言。
- `safety.py` 在应用设置创建前禁用 env_file/清理 ambient，连接前检查显式 URL，写入前复核库身份/comment/data_directory；没有看到落回旧库路径。性能运行器还限制端口。没有动态复验这些安全预检。
- 单一 DDL owner 和空库/合成0013/等价0015受控路径已区分；当前空库 evidence 没有被冒充为旧0013升级证据。
- 1024维固定向量、语义并列 node_id ASC 与 RRF/MMR 实际结果有合成报告。其 raw ranked/tolerance 当前只在 report，正是 S1 所述可比较 trace 缺口；不把尚待最终冻结的容差值另报为新增缺陷。
- 未看到 P1 实现、原环境写入、真实模型/embedding/harness 调用、push、main 合并、部署或删除恢复资产的提交内容；`git diff --name-only ... -- backend frontend` 为空。

## 实际验证与未验证项

| 验证 | 方式/结果 |
|---|---|
| 固定点和范围 | rev-parse/log/diff-stat 成功，69个新增/修改路径；按需查看实现与相关合成 JSON 子树 |
| 模型漏项 | 标准库 AST 调用 schemas_inventory；17声明/16枚举，继承 DTO 确认遗漏 |
| trace 业务值 | 标准库 JSON 检查，state 仅三类字段，usage写后回读缺失，关键事务只在 report；黄金 ID/耗时物化自对照通过 |
| CRLF 误放宽 | 纯内存 parse_sse/compare 通过；冻结客户端分帧表达式0帧，确认不等价 |
| error-only 误拒绝 | 纯内存源形 expected==actual，仍抛 `SSE 缺少 done` |
| 动态/外部 | 未运行PG连接、app导入、oracle/selfcheck、负载/恢复和真实上游；没有重复正式长测 |

以下最小反例只读提交文件、只导入标准库及比较器；可直接在仓库根执行，不访问 PG、不写其他 evidence：

```sh
PYTHONDONTWRITEBYTECODE=1 python3 - <<'PY'
import ast, copy, importlib.util, json
from pathlib import Path
from migration.oracle.compare import compare, Difference, parse_sse

spec = importlib.util.spec_from_file_location('inventory_review', 'migration/baseline/inventory.py')
inv = importlib.util.module_from_spec(spec)
spec.loader.exec_module(inv)
path = Path('backend/app/schemas/assignments.py')
models = inv.schemas_inventory({path: ast.parse(path.read_text())})
assert 'ManagerAssignment' not in {m['name'] for m in models}  # 当前缺陷
golden = json.loads(Path('migration/fixtures/p0-v1/python-golden.json').read_text())
assert 'usage_counters' not in golden['state']['counts']
assert all(set(o['side_effect_counts']) == {'llm', 'mcp'} for o in golden['observations'])
listing = [o for o in golden['observations'] if o['case_id'] == 'GET /api/assignments::success']
assert len(listing) == 1 and listing[0]['body'] == {'assignments': []}

lf = 'event: done\ndata: {"answer_status":"answered","terminal":true}\n\n'
crlf = lf.replace('\n', '\r\n')
trace = lambda wire: {'observations': [{'media_type': 'text/event-stream', 'body': parse_sse([wire])}]}
compare(trace(lf), trace(crlf))  # 当前误放行
assert len(lf.split('\n\n')) - 1 == 1
assert len(crlf.split('\n\n')) - 1 == 0  # 冻结前端没有完整帧
error = trace('event: error\ndata: {"message":"服务暂时不可用，请稍后重试","retryable":true}\n\n')
try:
    compare(error, copy.deepcopy(error))
except Difference as exc:
    assert str(exc) == 'SSE 缺少 done'  # 当前误拒绝
else:
    raise AssertionError('当前缺陷已变化，重新检查修复后的error终止规则')
print('S1投影缺口 / S2漏模型及空列表 / S3误放行 / S4误拒绝 均复现')
PY
```

修复 S2 的动态最小补验应在主 Agent 放行的独立窗口执行：创建一份非空作业，再以 teacher/leader 调 `GET /api/assignments`，确认结果包含继承的全部 AssignmentInfo 字段和 `submission_count/student_count`，然后把删除其中任一字段的候选作为必失败负例。当前没有执行这项动态补验。

回退：只需移除本文；没有实现或测试数据需要回滚。下一步由各 owner 修正 S1–S4，主 Agent 在独立合成窗口补负例/重采版本并最终复审；本文不更新 STATUS，也不放行 P0/P1。
