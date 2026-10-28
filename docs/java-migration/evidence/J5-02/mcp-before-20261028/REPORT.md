# J5-02 mcp_before修复后真实资格（2026-10-28）

source80c9bd1；新目录`j5-02-mcp-before-20261028-01`，退出0。首次生产预查询新job unknown后正常记录submitted账本，进入真实MCP出门前门栓；producer44495→recoverer44551SIGKILL -9，新独立JVMattach同owned库恢复。恢复远端unknown，安全终态system_error，保留submitted账本、临时源清零、无grade_plugin提交、chat只扣一次。恢复执行60.201ms仅观察；本例不是冻结1s RTO评判对象。

生产预查询/unknown安全策略未改，修复仅外部测试替身按实际持久提交事件返回状态。真实kill前后已提交RPO投影一致，源码长期表/日志/真实pg_dump均零命中，全部checks与sentinel通过。精确PID、库名、执行范围、原始SHA见summary；原始库/日志/账本/备份保留。本轮和all01库后验均精确核验comment/身份，所有连接0，Java/Maven/caffeinate进程0；all01失败库终态仍可审，不改写为成功。

此处只判mcp_before资格，完整all尚未通过；下一步同独占窗口fresh all14。没有Java容量通过声明。
