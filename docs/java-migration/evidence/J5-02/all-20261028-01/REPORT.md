# J5-02 all首轮失败与替身状态修复（2026-10-28）

source b9816bb，driver41494，计划14项（clock+queue+lifecycle+11故障），实际执行6项后按失败停止、退出1。前5项clock、41真实队列、TCP lifecycle、accepted、model_after通过。mcp_before未在45s内触发门栓，后续8项未执行，完整矩阵不通过。所有原始日志、6个owned库、备份与失败资产保留；没有删除/覆盖/重算绿色。

外部账本只出现get_task_status，没有grade_plugin。生产`AgentTools.resumeGradingJob`在首次无账本时真实预查询；旧测试替身无条件用默认finished回应新job，生产因此复用远端结果。它没有真正进入预期提交故障点。该失败不能宣称产品恢复通过，也不是RTO阈值超限。

仅修`CapacityServer`外部HarnessPort测试替身：未出现实际grade_plugin事件的job始终unknown，真实首次提交事件fsync后才允许finished/running/unknown/query_failed配置。新RuntimeState读取相同外部账本识别既有提交，普通容量无持久文件时使用进程内集合。不改生产预查询/重投策略/共享契约/冻结阈值。故障门栓位置仍在外部真实调用前后。

纯测试先以公开HarnessPort边界观察新job：明确红为expected unknown、actual finished（1失败，不把此前getter拼写编译错误算因果红）。最小修复后5/5绿、0跳过：新job→首次提交→finished；用真实临时外部账本重建RuntimeState后只识别确有提交的job，单次查询不算提交；unknown/running/query_failed等模式仅作用既有提交。另原ManualScheduler2项保持通过。

root UI短窗口期间未启动任何新PG或服务；driver/JVM已结束，库连接/精确身份后验需窗口归还再查。本轮原始hash与纯红绿命令/日志见summary。下一步等root归还窗口后新目录mcp_before资格，再fresh all；不重做已通过clock/accepted独立证据，不启动Java正式。
