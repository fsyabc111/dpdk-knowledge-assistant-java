# P0 Spec 修复复审（代码与纯内存）

- 审查者：`/root/p0_review_spec`；日期：2026-10-24。
- 上次审查提交：`08158c6`；本次固定提交：`c94f43d42838699e60d961d6c6f4f4e066a1`。
- 差分：`git diff 08158c6...c94f43d`；仅一个修复提交 `c94f43d 修正 P0 枚举与差分判定，扩展业务状态样例为 v2`。
- 依据：原 `review-spec.md` S1–S4、两份 `J0-01/review-fixes.md` / `J0-02/review-fixes.md`、修复代码和新测试；没有读取 Standards 复审结论。
- 权限：仅写本文，保留原审查报告。未连接 PG、未导入 app、未运行动态 oracle/负载、未修改实现/契约/fixture/STATUS，未提交或推送。

## 结论

| 原问题 | 本次结论 | 范围与条件 |
|---|---|---|
| S1 [P1] 数据库业务值缺少可比较投影 | **条件关闭** | 实现与纯内存判错已通过；白名单 SQL、实际逐 case 状态链与两库等价须由 p0-v2 动态采集补证 |
| S2 [P2] 继承 DTO 漏项、管理列表样例为空 | **条件关闭** | 静态模型漏项已关闭；非空管理/学生列表已接入真实路由编排，实际响应与 v2 黄金尚待采集 |
| S3 [P2] CRLF 被宽松解析为 LF | **已关闭（代码/纯内存）** | CRLF/CR 拒绝，保留 LF/UTF-8 任意分块与 EOF 不 flush；未来候选适配器仍须从真实 wire 走同一检查 |
| S4 [P2] 合法 error-only SSE 被强制要求 done | **已关闭（代码/纯内存）** | error-only/start→error 可正确结束且分类为 error；成功、pending 与错误结束保持区分 |

本次没有确认新增回归，也没有仍待代码修复的原问题。这里的“已关闭”不代表 p0-v2 动态证据或阶段验收通过。正式三轮容量、阈值确认和有效 token 停用补验已计划后补，不作为本次新增问题。

## S1：实现已进入可比较 trace，等待真实投影补证

原规格 `acceptance.md:93/98/125/184` 要求前置状态、状态迁移、副作用与数据库业务意义，不能只比较行数，重复业务扣费必须为零。

- `migration/oracle/business_state.py:7-32` 显式 SELECT 白名单业务列：usage 的主体/day/kind/chat_count、upstream 的 unknown/null/整数费用、成绩与 accepted_assignment、job.submission_done/result/关联、权限树、消息/引用/任务终态等。密码、令牌及其摘要、源码原文没有进入投影；消息用字段指纹与字节数验证持久化内容，不用 whole DB hash 代替业务列。
- `business_state.py:75-82` 在单个 repeatable-read/read-only 事务内取快照；`:95-117` 逐表保存真实 removed/added 行并保留前后行数，未变表也声明空 delta。`compare.py:229-260` 从完整初态重建每一步，拒绝删除不属于前置状态的行，最终以业务行多重集核对完整终态。
- `route_cases.py:26-35/59-70` 在每次请求前后采集状态、检查请求间没有未登记变更；异步受理回执保持原响应，after 明确是 accepted tasks settled checkpoint。该边界已说明，未冒充“受理瞬间”事务证明。
- `run.py:66-69` 把 business_initial、HTTP steps、auth step、transaction_cases、完整 business final 放入被比较 trace。`transactions.py:25-29/108-119` 的原语结果、raw 向量顺序/分数、六引用及最终清理已经进入同一 trace；不再只放入独立 report。
- `route_cases.py:17-18/37-47/74-77` 从版本和基线确定性构造运行时源码，保存 code_sha256 并校验执行源/受理 input_hash 与持久化值。`compare.py:174-176` 对这些 hash 逐值比较；本次没有通过归一化源码 hash 消除差异。
- 固定向量比较现为 exact（`transactions.py:108-110`），未把旧观察性 `1e-12` 自动变成允许误差；未来启用容差须另行确认和版本化。

除了既有 9 种业务值破坏反例，本审查额外保持 **business_initial、HTTP body、逐表行数全不变**，只令终态扣次 2→3，并一致重建 observation/auth delta，比较仍抛 Difference。另向 removed 注入前置状态不存在的行，状态链验证也拒绝。两项均纯内存执行，排除了“仅因初态不同才失败”的疑义。

条件关闭的剩余条件：双库实际运行 SQL 与所有 checkpoints，得到可冻结的 p0-v2；验证完整业务初态/逐 case delta/终态可重建，实际额度/成绩/job/引用值被比较，动态正例退出 0，破坏业务值反例退出 1；检查新 manifest/归一化路径和隐私断言。未做这些之前，不能用本次纯样例证明真实 PG 投影已可用。

## S2：静态漏项关闭，非空分支待动态补证

原规格 `foundation-slices.md:12/19` 要求 JSON/schema 和关键接口字段明确；契约模型要有定义、继承来源及唯一去向。

- `migration/baseline/inventory.py:135-228` 静态解析导入别名、跨模块/相对导入、re-export、传递继承和本地 C3；没有导入 Pydantic/app 来执行类体。`:243-273` 保留原自有 fields/bases，额外给出继承/有效字段与引用来源。
- `inventory.py:441-450` 校对继承定义及引用；丢失子类映射或破坏继承字段均使覆盖失败。静态测试确认现为64模型；ManagerAssignment 为2个自有字段、10个继承字段、12个有效字段，来源与 J2-03 owner 正确。
- `route_cases.py:123-130` 在正式提交之后使用 teacher 实际调用非空列表，要求 submission_count=1/student_count≥1；同时核对 student 的80分→16.0折算。该调用位置不再只能取得创建作业前的空数组。
- `test_review_fixes.py:98-103` 证明非空响应中任一 Manager 字段缺失都会被比较器拒绝；`test_inventory.py:59-113` 覆盖真实继承模型、别名/多层/菱形、漏映射/错字段和循环拒绝。

剩余条件：取得真实非空管理/学生列表响应并进入 p0-v2 manifest；现场对它删除 submission_count/student_count 的候选必须失败。此动态结果本轮未执行，不能把手写纯样例当作真实路由验证。

## S3/S4：SSE 判定修复关闭

原规格 `AGENTS.md:21`、`acceptance.md:98-100`、`http-and-sse.md:17/27` 要求对齐冻结客户端，并区分错误与成功/非终态语义。

- `compare.py:78-95` 按冻结 React 的 LF 空行与首个 event/data 行解析；`:86` 拒绝 CRLF/CR，EOF 仍丢弃未终止尾帧。原 S3 反例现在在解析阶段失败，UTF-8 逐字节分块正例仍通过。
- `compare.py:98-129` 明确返回 done/pending/error。合法 error-only/start→error 可结束；payload 要求非空 message/bool retryable，结束后事件失败；没有 done/error、截断 done、虚构终态失败。sources 累计前缀和跨帧身份一致性守卫仍保留。
- `route_cases.py:66-69` 把 sse_end 与 wire_contract 写入 trace，避免错误流仅因 HTTP 200 被称作成功回答。
- 本审查额外复验常规 answered done 省略 terminal 仍返回 done，pending/terminal:false 仍返回 pending；两者未因 S4 修复被合并成错误结束或成功终态。

S3/S4 的原缺陷都是解析器/状态机纯函数问题，本轮有充分代码与纯内存证据关闭。动态 v2 中新增 SSE 用例与未来候选 wire→trace 适配仍属于最终证据审查范围。

## 实际执行的验证

工作目录均为仓库根；命令设置 `PYTHONDONTWRITEBYTECODE=1`。7组测试内 CLI 使用独立临时文件，未触发 app/PG。

| 命令/检查 | 结果 |
|---|---|
| `.migration-artifacts/p0/venv/bin/python -m unittest discover -s migration/baseline -p 'test_*.py' -v` | 10项，退出0；含64模型继承/映射护栏 |
| `.migration-artifacts/p0/venv/bin/python -m unittest migration.oracle.test_review_fixes -v` | 7组，退出0；含9种业务值反例、ID/hash、非空Manager字段、SSE正反例、CLI/隐私 |
| 额外纯内存：保留原初态/HTTP/行数，只改终态扣次并重建delta | compare拒绝，符合S1要求 |
| 额外纯内存：removed含不存在前置行 | 状态链拒绝 |
| 额外纯内存：常规done/pending done | 返回done/pending，语义区分保留 |
| 额外纯内存：检查sys.modules | 没有app或app.*导入 |

未执行：p0-v2 SQL/双库采集、真实非空列表、freeze/manifest、有效token停用、正式长测或任何真实上游。当前 p0-v2 只有等待补验 README；没有把不存在的黄金视作通过。

## 交还与最终复核条件

本文不放行 P0/P1。主 Agent 待正式长测结束后，按已登记顺序采集双库 p0-v2、冻结新版本、差分、补停用，再交最终证据复审。届时需按实际 trace 检查 SQL 可执行性、状态链与归一化清单、nonempty ManagerAssignment、raw 检索/引用、隐私和终态清理；三轮容量与用户确认阈值另按阶段门禁审查。

回退仅涉及新增复审文件；未改变任何实现、数据库或外部状态。原报告保持不变。
