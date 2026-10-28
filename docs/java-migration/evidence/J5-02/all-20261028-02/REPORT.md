# J5-02 全故障矩阵实测（2026-10-28 all02）

fresh all02退出0，14/14完整通过：默认clock3项、41请求20活动20等待真实10s队列、TCP lifecycle、11个真实SIGKILL→新JVM恢复案例。运行源fa066a1（生产src/main与pom逐路径diff确认同ff59cda；80c9测试helper修复已含）。不修改生产/冻结阈值/默认600s、24h、300s配置。

## 11个真实故障点

全部生产者真实退出-9，每次使用新PID恢复JVM；kill前后已提交task/临时源hash/成绩与扣次投影不变。每例长期表/日志/真实pg_dump源码标记均零命中。外部模型/MCP为10ms合成脚本，其余身份/权限/任务/检索/PG/事务真实。

| 案例 | 生产者→恢复者PID | 终态 | 恢复执行ms | kill→ready秒 | grade提交 kill前/ready后 |
|---|---|---|---:|---:|---|
| accepted | 45461→45523 | answered | 86.006 | 1.660 | 0/0 |
| model_after | 45557→45611 | answered | 84.602 | 1.622 | 0/0 |
| mcp_before | 45645→45758 | system_error | 66.404 | 1.747 | 0/0 |
| mcp_finished | 45795→45849 | answered | 83.823 | 1.632 | 1/0 |
| mcp_unknown | 45883→45950 | system_error | 65.672 | 2.001 | 1/0 |
| mcp_queryfailed | 46047→46103 | system_error | 61.639 | 1.591 | 1/0 |
| mcp_running | 46146→46246 | answered | 665.381 | 1.895 | 1/0 |
| grade_before | 46298→46361 | answered | 56.323 | 1.481 | 1/0 |
| grade_after | 46394→46448 | answered | 51.724 | 1.480 | 1/0 |
| before_terminal | 46481→46547 | answered | 52.752 | 1.483 | 1/0 |
| after_terminal | 46579→46656 | answered | 0.000 | 1.587 | 1/0 |

冻结≤1s只适用于accepted和before_terminal：本轮分别86.006ms与52.753ms。该执行段是恢复JVM已ready、PG健康/槽可用之后ensureManaged→终态；启动时间、孤儿grace拨旧与自动扫描不算在其中。其他案例执行时间为观察，raw中rto_1s=true默认项不能解释为额外门槛已覆盖。after_terminal复用终态不重新执行。

mcp_before、unknown、query_failed均安全system_error/harness_result_unknown，保留submitted、输入清空、零重提交；running先真实查询至少两次再合成边界显式finished，未重新提交。成绩事务前后/终态前后均不重复成绩或扣次，已有完成结果复用。

## 其余真实边界

- clock3项0skip：默认600s执行墙599 pending/600.5 timeout并拒迟到保存；24h输入TTL到点失效并安全恢复；299/300s句柄到期后持久replay与只扣一次。可控Clock/ScheduledExecutor推进，不声称实际等待600s墙钟。
- queue41：20活动20等待、1立即429、20等待至少真实10s后超时、20answered；扣次20、输入0、槽0。所有响应和耗时包含预期背压保留，21背压单列。
- TCP断订阅不取消，后台持久完成；同身份重放不增模型/MCP/扣次；显式取消重复安全且迟到保存不覆盖；停号撤token/取消pending；未完成成绩不落；受理前401/422零副作用。

## 资产与后验

命令为 `acceptance/resilience/run.py --case all --classpath <clock轮fresh生成classpath> --pg-bin /opt/homebrew/opt/postgresql@17/bin --output <全新.migration-artifacts/j5-02-all-20261028-02>`，四项显式PG与每库精确comment校验保持。driver44829及全部子JVM退出，精确Java/Maven/caffeinate进程0；14个owned库核验身份/角色/数据目录/comment后连接均0、临时输入均0。保留所有库、manifest、账本、备份和日志，all01失败也保留。summary含每例全部checks、外部提交数、RPO与原始文件SHA。

该片只完成J5-02合成外部边界下故障矩阵，不宣称生产harness容器隔离或跨主机exactly-once，不代表Java容量通过。下一步已获主AgentJava短资格窗口，先资格再另等正式放行。后续双端正式组合仍须严格protocol/layout/source/同物理环境；80c9 helper变更导致协议hash与旧Python基线不同，已报告主Agent，不私改比较口径。
