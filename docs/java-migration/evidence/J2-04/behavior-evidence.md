# J2-04 行为证据（验收 A–G）

执行环境：MockMvc standalone + 独立测试 PG（动态建删库，`DPDK_TEST_PG_URL` 指向
p1 合成实例 127.0.0.1:55481）；TestClock 墙钟 `2026-10-25T04:00:00Z`（配额日计数
键 2026-10-25，与 golden 的 oracle tick 日映射登记见 golden-diff.md）。命令模板与
结果见 handoffs/J2-04.md 验证表。

替换纪律：契约测试用脚本化 `HarnessPort` 替身（只记调用计数与参数指纹
`tool/protocol/plugin_source 长度`，不落不打印源码文本）；配额/超时/未知结果用
**真实 `StreamableHttpHarnessClient` 连 JDK HttpServer fake MCP**（`FakeMcpServer`，
initialize/initialized/tools/call 三步 Streamable HTTP 形态，只记 initialize 与
tools call 计数）。

## 验收 A：JSON/multipart 受理与边界（AssignmentSubmitContractTest）

- golden `POST /api/assignments/{assignment_id}/submit::success`：200 body 8 字段
  逐值（score 80/passed 4/total 5/points 20/earned 16.0/best_earned 16.0/results
  与 golden 全等/submission_id 归一化 `grade_[a-f0-9]{16}`）；401/422 observation
  逐字段（422 loc 无 body 前缀、missing 的 input=整个 body 字典、include_url/
  include_context 关闭 → 仅 type/loc/msg/input）。
- JSON 路径校验序：非法 JSON → 422 `request body must be valid JSON`；非对象
  body（`[]`）→ 500（Python `**body` TypeError 面，异常未渲染登记差异）；
  protocol 数字 → string_type；"" → string_too_short；65 字 → string_too_long；
  code 同族；双缺失按定义序（protocol, code）。
- multipart：protocol strip（`"  dns  "` → dns）且空 → 422 `protocol must not be
  empty`；缺 file/文件名空 → 422 `需要上传 .c/.so 文件，或用 JSON 粘贴代码`；
  扩展名 `Path.suffix` 语义（`.c`/`x.C`/`x.SO` 通过；`.txt`→400 收到 .txt；
  `noext`/`dotfile.`/点文件 `.c` → 400 无扩展名）；大小上限 multipart 解析期
  413 `文件超过大小上限（262144 字节）`。
- 资格序：撤回/别班/未知作业 → 404 `not found`；过期 → 400 `已过截止时间，不能
  提交`；未知协议 → 400 `协议 ssh 不属于该作业`；静态 token（created_by=null）
  对 class 作业 404、对 scope=all 作业可提交。

## 验收 B：grading 配额与映射（GradingQuotaIntegrationTest，真实 harness 客户端）

- 共享计数面：与聊天评测同 `usage_counters` 表，`kind='grading'`（chat 面板读取
  只统计 kind='chat'，互不污染；J2-05 fetchCounters 已锁该语义）。
- 豁免角色一致：{operator, admin, leader}（agent/tools.py GRADING_QUOTA_EXEMPT_
  ROLES）——静态 operator token 提交成功、`usage_counters` 无该主体 grading 行、
  MCP 恰 1 次。
- 429：预置计数 19 → 第 2 次成功达 20，第 3 次 429
  `今日评测配额已用完（20 次/天），请明天再试`；**失败计数保留**（counter=21，
  独立事务先递增后检查的源现状）；超限请求零 MCP 调用。
- 504：Deadline 在 tools/call 字节发出前到期（initialize 拖慢 1.2s、timeout=1s）
  → `评测超过 1 秒未完成`，tools/call 0 次，配额已计、成绩不落。
- 502（NOT_SUBMITTED）：建连不可达 → harness_unavailable（Python call_tool 连接
  失败同码），成绩不落。
- 502（结构化失败）：payload `{"ok":false,"error":...}` 非 code_too_large/
  quota_exceeded/grading_timeout → 默认 502 + detail 透传；detail 缺失 →
  `评测服务返回失败`。

## 验收 C：调用计数与配额副作用固定（两测试类）

- 大小/资格拒绝（413/404/400）：MCP 调用数 0、配额计数不变
  （AssignmentSubmitContractTest.sizeLimitOrdering 断言 counter 保持测试前值）。
- 已发起后响应丢失：tools/call HTTP 500 → UNKNOWN → 502；**tools/call 恰 1 次
  不重发**；成绩 0 行；配额副作用已发生（counter +1，配额在 MCP 之前计数，与源
  一致）。

## 验收 D：受理快照（AssignmentSubmitContractTest）

- 中途修改（脚本钩子在 MCP 调用内把 dns 20→40）：响应用受理 20 分（earned
  16.0）；落库 `accepted_assignment.points=20`；汇总视图（J2-03 report）按当前
  40 分动态折算 32.0——双口径互不混淆。
- 快照留存：accepted_assignment 六字段（points/scope/created_by/deadline/
  assignment_updated_at/accepted_at）逐字段断言；deadline 为 Python str(datetime)
  形态 `2099-01-01 00:00:00+00:00` 逐字节；assignment_updated_at 同形态；
  accepted_at 为 isoformat（T 分隔，微秒可选）。

## 验收 E：停号×落库竞争（GradingPersistenceRaceTest）

- 停号事务先提交：守卫 UPDATE 在 users 行锁上等待（pg 阻塞由提交序裁决），提交
  后 EvalPlanQual 重评 `enabled=true` 不命中 → 403 `账号已停用，本次评测不记成绩`
  、成绩 0 行、账号终态 disabled。
- 成绩事务先提交：成绩行保留（不删已落成绩）、停号事务在其提交后完成——
  `pg_locks NOT granted` 轮询断言停号事务确实排队等待成绩事务。
- 端到端：MCP 评测中途停号（脚本钩子）→ 403；MCP 1 次、配额 +1、成绩 0 行
  （被停号用户不能在检查后绕过落库门禁）。
- 已停号账号：token 解析层拒绝（登录 401），不进入业务面。

## 验收 F：无源码落库与 hash 身份（AssignmentSubmitContractTest）

- 落库最小集：grading_submissions 只存 code_hash/score/passed/total/results_json
  + accepted_assignment；session_id/task_job_id 恒 NULL；无源码全文列。
- **hash 是编码替换后文本的 sha256，不是上传原始字节**：`.so` 非法 UTF-8 上传
  （ED A0 80）→ 落库 hash `a73f4cb9…`（Python CPython 冻结值）≠ 原始字节 hash。
- UTF-8 替换解码电池 13 例 + JSON 孤立代理路径（`\ud800` → `?`）与 CPython
  逐字节一致，见 utf8-parity.md（JVM `new String` 在 ED 代理区分支与 Python
  1↔3 个 U+FFFD 不一致，故用自实现 `PythonUtf8.replaceDecode`）。

## 验收 G：畸形 payload 与舍入（AssignmentSubmitContractTest.malformedPayloadSemantics）

- score 缺失 → 0；`79.5 → 80`、`78.5 → 78`（HALF_EVEN 现状）；字符串 "90" → 90；
  "abc" → 500（Python float() ValueError 面，异常未渲染登记差异）。
- results 缺失 → []；字符串 → list("ab") 拆字符；payload protocol 覆盖存储协议
  （dns-v2）而 best 仍按请求协议（dns-v2 行不计入 dns 的 best）。
- 低分/全通过经 setSuccessPayload 覆盖分数验证 earned/best_earned 折算
  （PythonScore.round1/calcEarned，J2-03 共用实现）。
