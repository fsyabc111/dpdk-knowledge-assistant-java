# J0-02 性能与恢复子任务交接

**当前确认状态：用户已接受并冻结本组 P0 合成门槛（U09 / P0-D15）。** 权威值与适用域见 [thresholds.json](../../../migration/contracts/thresholds.json)，用户确认见 [DECISIONS.md](../DECISIONS.md)。Python正常mix通过；Java候选、P5生产容量与真实质量尚未验证。原 `threshold-candidates.json` 保留提出时历史。

- owner/工作树：`p0_performance`；共享分支 `codex/p0-baseline-oracle`，仓库 `/Volumes/study/code/new_project/dpdk-knowledge-assistant-java`。
- 源基线：`80b29135faddd16d5e39fdbf0633291ea6a20489`；契约 `p0-v1`。只运行冻结 Python，无 Java 实现。
- 授权：主 Agent 分派的真实 Python + 本地合成 PG + 边界替身性能/恢复采集；不调用真实模型/embedding/harness，不读取旧环境或业务库，不提交/推送。
- 状态：实现完成；资格/队列/3轮正常负载/最终PG核对自动验证通过，Python正常mix符合冻结门槛。合成恢复8个场景已执行，6通过、2个unknown安全断言失败，按已批准COMP003保留Python现状；真实浏览器/真实harness/部署场景未执行。用户已确认冻结本组门槛；本子Agent不自行放行阶段，也不将其扩展为Java或生产容量验收。

## 完成与文件

| 路径 | 改动目的 | 是否符合owner范围 |
|---|---|---|
| `migration/oracle/performance/run.py` | 真实 ASGI/认证/服务/Agent图/PG 的负载、队列、SIGKILL恢复及600s固定时钟采集 | 是 |
| `migration/oracle/performance/summarize.py` | 完整三轮统计校验、汇总与候选门限，未采齐拒绝生成 | 是 |
| `migration/oracle/performance/audit.py` | 完整采样后的只读PG总账与费用unknown核对，不初始化/改写数据 | 是 |
| `migration/oracle/performance/README.md` | 重跑命令、隔离和测量边界、统计方法 | 是 |
| `docs/java-migration/evidence/J0-02/performance/**` | 合成环境/统计/资源/断言；不含原始模型结果和源码输入 | 是 |
| `.migration-artifacts/p0/performance/**` | 不提交的运行日志、逐请求耗时和故障worker临时身份 | 是 |

未修改 Python 源、STATUS、公共契约、fixture、比较器、根构建配置或其他数据库。

## 决策与接口差异

- 复用功能 owner 的 `migration/oracle/safety.py`；只连接 `p0_benchmark_oracle`，严格核对回环host/55473、role、database comment和仓库pgdata目录后才运行冻结DDL。
- 性能设置恢复真实默认 bcrypt12、每主体每分钟20、PG池32、20活动/20等待/10s队列超时/600s任务外墙；合成账号日配额100000，原子扣次真实执行。
- CPU/RSS比较域为应用+负载器+统计/采样器合计，当前处于同一Python进程。后续Java若独立进程，合计Java+等价Python负载/采样进程；按时间样本对齐求和后再算p95/峰值。布局不齐只观察不判通过；P1交付等价adapter或另测server基线。同步ps/PG采样开销计入延迟，当前ASGI不含TCP；本次只支持P0 synthetic门禁，不是应用独占或P5生产容量。
- 正常负载替身固定每模型调用1s、检索50ms、MCP200ms。恢复故障脚本另用各10ms，不能把其重驱动执行RTO直接当正常用户RTO。
- MCP进入后响应未返回时SIGKILL，当前Python恢复先查unknown，再以同job提交；跨进程实际grade调用2次，本地配额/成绩各1。此为已复现的安全边界冲突，未改源、未降低断言。与主 Agent 的 COMP003 差异决定衔接。
- running用例的“通过”只表示当前Python身份/配额/唯一约束得到观察；源码仍同job再调用grade，不能证明真实harness加入既有执行，也不代表拟定Java只轮询策略已实现。
- 进程已启动、槽位可用且PG健康时，手动重驱动执行实测与30s grace + 300s扫描的保守等待上界分开；330s属于源码推导，自动循环最坏RTO未实测。

## 验证证据

所有命令 cwd 为仓库根目录，运行器通过安全入口关闭.env/ambient上游并注入本地替身或死上游。

| 命令、工作目录及非秘密参数 | 构建/环境 | 退出码与断言 | 证据路径 |
|---|---|---|---|
| `.migration-artifacts/p0/venv/bin/python -m py_compile migration/oracle/performance/run.py migration/oracle/performance/summarize.py migration/oracle/performance/audit.py` | Python3.11.15 | 0，语法检查 | 工具输出 |
| `.../python migration/oracle/performance/run.py benchmark --rounds 1 --warmup 0 --measurement 15 --label qualification-final` | PG17.10/vector0.8.4；真实ASGI+PG；默认正常脚本 | 0；60登录、180作业读取、70问答、2提交/讲解，8项副作用/隔离/异常断言全真 | `performance/qualification-final-summary.json`；日志 `.migration-artifacts/p0/performance/qualification-final.log` |
| `.../python migration/oracle/performance/run.py queue` | 固定真实20/20/10s护栏；模型门闩 | 0；41分母：20成功、1立即429、20约10s超时429；配额20/inputs0/槽归零 | `performance/queue.json`；日志 `.migration-artifacts/p0/performance/queue.log` |
| `.../python migration/oracle/performance/run.py recovery` | 三阶段真实SIGKILL；合成固定时钟；真实PG | 0是采集器完成，不能解释为全部通过。accepted/before-terminal/断订阅/600s外墙通过；unknown双用例失败 | `performance/recovery.json`；日志 `.migration-artifacts/p0/performance/recovery.log` |
| `.../python migration/oracle/performance/run.py benchmark --rounds 3 --warmup 120 --measurement 600 --label baseline` | Apple M4/10逻辑核/16GiB，PG17.10+vector0.8.4；同实例独立库；功能PG短测结束后启动 | 0，真实shutdown结束；3轮120s预热+600s测量完整；8项正常负载断言全真 | `performance/baseline-summary.json`与各轮文件；日志 `.migration-artifacts/p0/performance/baseline.log` |
| `.../python migration/oracle/performance/summarize.py` | 纯文件，完整三轮样本/分母核对 | 0；不删错误/尾部，生成候选，capacity_pass=false | `performance/aggregate.json`、`REPORT.md`、`threshold-candidates.json`；日志 `.migration-artifacts/p0/performance/summary.log` |
| `.../python migration/oracle/performance/audit.py` | benchmark专用库只读事务 | 0；10278终态=10278聊天扣次，174正式成绩=174评测扣次，inputs0，7项核对全真 | `performance/final-pg-audit.json`；日志 `.migration-artifacts/p0/performance/final-pg-audit.log` |
| 交付内容/基线哈希校验 | 仅本owner证据与冻结backend路径 | 0；正式run.py SHA未漂移，backend diff为空，证据未含源码输入/密钥/token原件 | `performance/delivery-verification.json` |

恢复CLI返回0表示所有故障观测完成；逐用例通过/失败和总状态以JSON为准，unknown失败没有被豁免。

三轮测量窗口合并：问答8419，p50/p95/p99=2100.598/2154.735/2195.212ms；读作业21600，p95=30.137ms；登录180，p95=247.916ms；正式提交144，p95=219.006ms；讲解受理/终态各144，p95=56.494/2154.977ms。n<1000的组不报告p99。正常全部组异常0；预热样本另存，未混入合并表。

组合CPU测量p95=21.992%一核，峰791.312%（含登录并发bcrypt），组合RSS峰184.625MiB；其他数据库活动会话资源样本0/1731，PG仍仅按共享实例解释。实际采样间隔p50=1047.917ms/p95=1053.291ms，按实际时间计算CPU差值。20556条合成模型usage的费用均unknown，显式0费用为0；不是实际账单。

提出并已获用户冻结的绝对p95(ms)：登录500、读作业100、问答3000、提交500、讲解受理250、讲解终态3000；每端点每一测量轮还须满足增量≤max(Python最差轮p95×20%,50ms)。每端点每轮异常率≤0.1%，正确性硬限制为0。组合CPU p95≤100%一核、组合RSS≤512MiB，测量域对齐后才可判。10ms脚本的accepted/finished重驱动RTO≤1s，running只受600s墙约束；330s默认调度等待仅推导。应用SIGKILL且PG健康的局部RPO=0，不含主机/PG故障。当前权威定义以 `migration/contracts/thresholds.json` 为准，不把历史候选JSON当最终权威。

## 未完成、阻塞与回退

- 长测、聚合与只读PG核对均完成；用户已接受该组门槛，由主Agent以U09/P0-D15与权威thresholds.json冻结。子Agent未修改STATUS/公共契约或放行阶段。
- 主 Agent 已转达用户接受 COMP003（U08/P0-D13）：Java对running只查/等、unknown/查询失败停止重投并system_error终态提示待核对、保留submitted账本、终态清理输入。本子任务只记录Python现状，不改源，也不在P0实现Java。
- 真模型、embedding、真实检索质量、真实harness、容器执行隔离、TCP/nginx/浏览器断线、多副本fencing、生产RTO/RPO均未验证。
- 本次RPO仅指PG健康时应用进程SIGKILL后的已提交业务行保持，不含PG/主机故障或备份恢复；临时执行输入仍遵循24h TTL，不保证模型中间输出。
- 24h临时输入TTL由功能oracle负责；本文件只做终态即清和实际600s配置的虚拟时钟边界。
- 回退代码仅删除本owner新增路径。合成库/PG进程由主Agent管理，不停止共享PG；不涉及原环境、外部模型/harness副作用。日志/耗时数组/源码临时输入不提交。

## 恢复点

- 长测 `exec_command session_id=55014` 已退出0，app.shutdown完成；本owner无仍运行负载进程或新监听端口。PG55473由主Agent管理，未停止/清理。动态PG窗口已通知主Agent可用。
- 锁定路径仅本owner三组路径；其他公共文件保持只读。本子Agent未执行commit/push。
- 继续先看 `performance/REPORT.md`、`threshold-candidates.json`、`delivery-verification.json`与本交接。完整三轮/全部预热/队列/恢复证据均已保留；若重跑先确认benchmark库不会与其他任务并发清空。精确命令见README。
- 主Agent最小决定：本子任务已无待决阈值问题；COMP003安全差异和本组合成门槛均由用户接受。Java实现/整体P0阶段放行/生产切换仍由主Agent按授权记录处理。
