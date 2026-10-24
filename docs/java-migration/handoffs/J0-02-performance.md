# J0-02 性能与恢复子任务交接

- owner/工作树：`p0_performance`；共享分支 `codex/p0-baseline-oracle`，仓库 `/Volumes/study/code/new_project/dpdk-knowledge-assistant-java`。
- 源基线：`80b29135faddd16d5e39fdbf0633291ea6a20489`；契约 `p0-v1`。只运行冻结 Python，无 Java 实现。
- 授权：主 Agent 分派的真实 Python + 本地合成 PG + 边界替身性能/恢复采集；不调用真实模型/embedding/harness，不读取旧环境或业务库，不提交/推送。
- 状态：实现完成；资格/队列/恢复自动采集完成，unknown 有安全契约失败；正式 3 轮长测采集中；场景验收仅指定合成边界；用户验收未进行；阈值未冻结。

## 完成与文件

| 路径 | 改动目的 | 是否符合owner范围 |
|---|---|---|
| `migration/oracle/performance/run.py` | 真实 ASGI/认证/服务/Agent图/PG 的负载、队列、SIGKILL恢复及600s固定时钟采集 | 是 |
| `migration/oracle/performance/summarize.py` | 完整三轮统计校验、汇总与候选门限，未采齐拒绝生成 | 是 |
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
| `.migration-artifacts/p0/venv/bin/python -m py_compile migration/oracle/performance/run.py migration/oracle/performance/summarize.py` | Python3.11.15 | 0，语法检查 | 工具输出 |
| `.../python migration/oracle/performance/run.py benchmark --rounds 1 --warmup 0 --measurement 15 --label qualification-final` | PG17.10/vector0.8.4；真实ASGI+PG；默认正常脚本 | 0；60登录、180作业读取、70问答、2提交/讲解，8项副作用/隔离/异常断言全真 | `performance/qualification-final-summary.json`；日志 `.migration-artifacts/p0/performance/qualification-final.log` |
| `.../python migration/oracle/performance/run.py queue` | 固定真实20/20/10s护栏；模型门闩 | 0；41分母：20成功、1立即429、20约10s超时429；配额20/inputs0/槽归零 | `performance/queue.json`；日志 `.migration-artifacts/p0/performance/queue.log` |
| `.../python migration/oracle/performance/run.py recovery` | 三阶段真实SIGKILL；合成固定时钟；真实PG | 0是采集器完成，不能解释为全部通过。accepted/before-terminal/断订阅/600s外墙通过；unknown双用例失败 | `performance/recovery.json`；日志 `.migration-artifacts/p0/performance/recovery.log` |
| `.../python migration/oracle/performance/run.py benchmark --rounds 3 --warmup 120 --measurement 600 --label baseline` | 同实例独立库；功能PG短测结束后启动 | 运行中，禁止提前宣称容量通过 | `performance/baseline-*.json`；日志 `.migration-artifacts/p0/performance/baseline.log` |

恢复CLI返回0表示所有故障观测完成；逐用例通过/失败和总状态以JSON为准，unknown失败没有被豁免。

## 未完成、阻塞与回退

- 长测采齐后运行 `.../python migration/oracle/performance/summarize.py`，核对8项业务断言、全部轮次、资源峰/活动会话，再交候选p95/差值/错误率/CPU/RSS/RTO/RPO；需用户确认后主Agent冻结。
- 主 Agent 已转达用户接受 COMP003（U08/P0-D13）：Java对running只查/等、unknown/查询失败停止重投并system_error终态提示待核对、保留submitted账本、终态清理输入。本子任务只记录Python现状，不改源，也不在P0实现Java。
- 真模型、embedding、真实检索质量、真实harness、容器执行隔离、TCP/nginx/浏览器断线、多副本fencing、生产RTO/RPO均未验证。
- 本次RPO仅指PG健康时应用进程SIGKILL后的已提交业务行保持，不含PG/主机故障或备份恢复；临时执行输入仍遵循24h TTL，不保证模型中间输出。
- 24h临时输入TTL由功能oracle负责；本文件只做终态即清和实际600s配置的虚拟时钟边界。
- 回退代码仅删除本owner新增路径。合成库/PG进程由主Agent管理，不停止共享PG；不涉及原环境、外部模型/harness副作用。日志/耗时数组/源码临时输入不提交。

## 恢复点

- 当前长测 `exec_command session_id=55014`；stdout/stderr均写 `.migration-artifacts/p0/performance/baseline.log`。正常目标3轮，每轮120+600s，约36分钟加种子与排空；运行中不要修改run.py或再次清空benchmark库。
- 锁定路径仅本owner三组路径；其他公共文件保持只读。未commit。
- 继续先看 `baseline-summary.json` 和 `baseline-round-*`。完整3轮后再运行汇总器；中断/缺轮会拒绝生成阈值候选，保留全部失败/未完成证据。
- 主Agent最小决定：三轮完成后的候选阈值。COMP003安全差异已由用户接受，Java实现仍未开展；任何性能候选均未获得用户验收。
