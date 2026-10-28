# J5-02 accepted 双JVM恢复资格（2026-10-28）

本轮真实单例通过，source `2434137`（业务/驱动同merge9250d76），已先fresh编译。生产者41168在真实multipart受理、临时源落PG后，于模型首次调用前门栓被控制器SIGKILL，退出-9；独立新JVM41270 attach同一owned库恢复。kill前后已提交task/源hash/账本投影完全一致，最终answered、输入清空、chat扣次1。

恢复执行段73.834ms≤冻结1s。该段只从已ready新JVM的ensureManaged计到终态，PG健康、槽可用、外部脚本10ms；启动/31s孤儿grace拨旧/扫描不在范围。kill→ready另记1.590s；不能称完整进程重启RTO≤1s。两进程均真实OS进程，producer PID41168和recoverer41270不同。

全部29张长期表源码哨兵零命中，日志零命中；真实pg_dump退出0、备份源码标记零命中。外部grade提交前后均0（本例为chat），未调用真实上游。模型/向量/MCP仅同进程合成外部边界，其余身份/权限/检索/PG/事务/扣次/恢复真实。

命令为`acceptance/resilience/run.py --case accepted --classpath <clock轮fresh生成classpath> --pg-bin /opt/homebrew/opt/postgresql@17/bin --output <全新.migration-artifacts/j5-02-accepted-20261028-01>`，四项显式PG与精确comment/身份核验保持。driver41160已退出，两JVM停止；后验库连接0、精确Java/Maven/caffeinate进程0。owned库`p5_resilience_c612594732d8ea0c_accepted`及原始日志/manifest/外部账本/备份全部保留。summary含安全投影、源SHA、文件SHA。

此处只判accepted资格，不判完整11故障/41队列/lifecycle通过，也不判Java容量通过。下一步在主Agent已给独占窗口内执行全新all矩阵。
