# J0-02 Python 合成性能与恢复采集

仅运行冻结 Python `80b29135faddd16d5e39fdbf0633291ea6a20489`，P0 没有 Java。所有命令在本仓库根目录执行，不读取旧 `.env`，不启动复制来的开发/部署入口。

## 调用边界

问答是 `httpx.ASGITransport → app.main.app → 真实安全中间件/认证 → api.chat → RagProductService.prepare → PG 原子受理/配额 → TaskService → AgentOrchestrator → 真实 LangGraph planner/tools 循环 → validate_answer 确定性校验 → PG 终态/消息/引用/usage`。正式提交走真实 `api.assignments.submit_assignment → harness_grading → GradingService/AssignmentService → PG`。讲解走真实 explain 受理与后台任务，不重新评测。

只替换外部 I/O 工厂：模型每次调用固定 1s，检索固定 50ms、一个合成片段，MCP 固定 200ms 与全通过合成结果。模型真实经过两次调用和 `kb_search` 工具循环。模型/检索/MCP 的返回只验证编排，不能证明模型质量、真实检索质量、embedding、真实 harness、容器隔离或评测耗时。

正常采集启动真实 app startup/shutdown、300s 维护与 10s 心跳；检索预热因整个检索 I/O 边界为替身而跳过。ASGI 同进程测量不含 TCP、TLS、nginx、浏览器和多进程 ASGI worker。恢复用例为定向服务调用，未启动自动扫描。

## 隔离与数据

复用 `migration/oracle/safety.py`，在导入应用前清理 ambient 配置、关闭 Settings `.env`、注入死上游。唯一可写库为 `postgresql://p0_oracle@127.0.0.1:55473/p0_benchmark_oracle`；每次核对库名/角色/host/port/comment/data_directory 后才应用冻结 DDL。性能运行器进一步限制 Python socket 为专用 PG 55473 与死上游 9 端口。数据库驱动还使用显式 DSN 和现场身份检查；没有通用生产凭据或配置回退。

每个命令清空该专用 benchmark 库的业务表并产生 60 个合成学生、1 个合成组长、1 份合成作业；不会操作其他 oracle 库。源码输入每次运行生成随机标记，只保存在运行时内存/受理临时表；故障阶段所需临时信息只进 `.migration-artifacts/p0/performance/`。不要同时对该库运行两个本运行器。

## 精确命令

依赖必须由主 Agent 在 `.migration-artifacts/p0/venv` 安装冻结 backend 锁定依赖与 dev 组。主 Agent 管理 PG 生命周期，本运行器不启动、停止或清理 PG。

```sh
.migration-artifacts/p0/venv/bin/python migration/oracle/performance/run.py benchmark --rounds 1 --warmup 0 --measurement 15 --label qualification > .migration-artifacts/p0/performance/qualification.log 2>&1
.migration-artifacts/p0/venv/bin/python migration/oracle/performance/run.py queue > .migration-artifacts/p0/performance/queue.log 2>&1
.migration-artifacts/p0/venv/bin/python migration/oracle/performance/run.py recovery > .migration-artifacts/p0/performance/recovery.log 2>&1
.migration-artifacts/p0/venv/bin/python migration/oracle/performance/run.py benchmark --rounds 3 --warmup 120 --measurement 600 --label baseline > .migration-artifacts/p0/performance/baseline.log 2>&1
```

长测为 3 轮，每轮先预热 120s、测量 600s；在窗口截止前启动的请求允许完成，另报含排空的墙钟时长。60 账号每阶段各登录一次、每 5s 读作业；10 个问答 worker 连续发起独立会话，轮转 60 账号。正式提交每完成一轮后等 10s，再发起下一次；每次成功提交随后发起讲解并等待终态。问答和讲解共用 10 个请求槽上限，避免讲解另增峰值。提交/讲解各自分组，处于同一固定 mix 的资源竞争环境，不视为隔离的纯端点最大吞吐。

这是固定并发闭环负载。端点耗时从发起 ASGI 请求计算，讲解终态从讲解请求发起计到终态查询完成；不包括负载生成器在10槽信号量前的等待和读作业/提交间隔。`active/waiting` 记录生产 Admission 的槽位，不能把闭环等待峰值0解释为开放到达流量不排队。

保持真实默认 bcrypt 12、每主体每分钟 20、PG 连接池 32、20 活动/20 等待/10s 队列超时/600s 任务外墙。合成账号 daily_quota=100000，仅避免测试总量触顶；原子扣次仍真实执行。正式评测日配额保持 20。

## 统计与证据

`docs/java-migration/evidence/J0-02/performance/` 保存每轮分组统计、1s 资源样本、环境/版本、队列和恢复断言；运行日志和逐请求耗时数组只保存在 `.migration-artifacts/p0/performance/`。

分组耗时包含成功、拒绝与异常，总分母及每状态数明确保留。p50/p95 使用最近秩；仅 n≥1000 时报告 p99，其他填 null。背压 429 与异常错误分开。资源只采本运行器进程、专用 PG 主进程和直接子进程；CPU 是累计 CPU 的相邻采样差，100% 表示一核；PG 子进程退出可能导致低估。PG RSS 求和含共享页重复，也包括同实例其他 oracle 库，不可解释为 benchmark 独占内存。资源样本附 UTC 和每库活动会话，便于标识功能测试交叠。

SIGKILL 恢复分别在受理持久化后、成绩落库后而答案终态前、MCP提交进入后而响应返回前中断新子进程。恢复控制器把合成行 `updated_at` 拨老后调用真实 `ensure_managed`。恢复故障用例为加速脚本，每个模型调用/检索/MCP 均 10ms，另列在恢复证据；与正常性能负载的 1s/50ms/200ms 分开解释。实测值是**重驱动执行耗时**，未含默认 30s grace、最长 300s 扫描间隔；按实现推导调度等待上界为 330s，自动循环的实际最坏 RTO 仍需后续集成验证。600s 外墙使用真实 TaskService/PG，将事件循环时钟推进至 599s 和 600.5s，并在终态写入前挂起工作线程；不改变 600 配置，也不等同于实际 10 分钟墙钟容量试验。

`submitted/unknown` 的当前源码会先查状态，再对相同 `job_id` 调 `grade_plugin`。该行为与拟冻结“不盲重试”边界冲突时记录失败；不得用替身返回成功或同 job 名称冒充真实外部 exactly-once。

主 Agent 已转达用户接受 COMP003（U08 / P0-D13）：未来 Java 对 running 只查/等，对 unknown/查询失败停止重投，使用现有 system_error 终态及待核对提示、保留submitted账本、终态清理输入。本子任务保留 Python 现状与失败证据，不实现 Java，也不把 baseline 的 running 观察通过写成该安全策略通过。

阈值只能在三轮实测后提出候选，由用户确认后冻结。采集成功不代表 P0 用户验收或 Java 性能/兼容通过。真实质量始终未验证。
