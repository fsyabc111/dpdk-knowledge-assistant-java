# J5 16领域过程指标因果交接

- 固定集成ff59cdab65c2c8977f3f9c45701b773ad9116ccd已merge；生产src/main/pom与其相同，保留已集成OS库存。仅新增acceptance/metrics/ProcessMetricsCausalSmoke.java、CAUSAL_ACCEPTANCE.md、固定期望与metrics-mapping-causal-ff59证据、本handoff；无生产/黄金/STATUS改动。
- 真实Application+owned TestPg+loopback传输替身，经公开chat/start-cancel/replay429与独立AgentOrchestrator.run，使16领域全获真实观测；未直接调用registry事件。已知usage5/合成cost6000、missing usage unknown；产品engine unknown，error/cancel不增success，取消不伪finish；5重放无模型/quota/message新增；Agent tokens4/count1仅独立facade产生。
- 当前source编译+collector纯测14/14(0skip)、Python指标22/22；实际wire v2验证16域/25断言，46families/236series，SHA在对应json，rawprom只含白名单标签及JVM版本。首fixture NULL误用失败保留本地，合法空串修正后绿；无生产修复。
- Application/loopback替身已关闭，动态TestPg库finally清理，PG窗口释放；无Docker/stack/真实上游，不占容量/RTO窗口，未删除任何恢复资产。
- 结果只属于该源真实过程观测。full strict矩阵/JVM与Python runtime差异、真实MCP/模型质量、capacity/RTO-RPO、部署/用户/live仍由root其他证据裁决。交付后结束worker释放slot供fault独占。

## 2026-10-28恢复核对（无新负载）

0847bda已包含完整7文件交付，不是待提交WIP。只读核对wire/probe/固定期望SHA与结果JSON一致；worktree及主集成9250d76的production src/main/pom均与ff59cda无差异。未重跑编译/纯测/PG/HTTP，不把历史运行标成新日期实跑。README已补可发现proof索引，matrix已获路径与SHA。

J5-03/04剩余准备只做文件检查：P4两个不可变image archives仍存在（backend140800512B、frontend21966336B；本次未重读大归档计算全量hash）；已提交容器库存SHA与summary一致。owned deployment manifest只记录P4 backend/frontend，candidate两个槽位未填；scenario-state/results尚不存在。执行器默认plan不连接Docker/PG，实际--execute受root窗口/前置门禁调度，daemon endpoint+ID/Name、owned新库/空库恢复、全public行摘要与临时数据排除/保结构、真实不同构建回退守卫均保留；不把plan列出门禁称为脚本自动放行。最终候选构建/库存差异、缺失许可证全文/NOTICE、真实冷启/升级/备份恢复/应用回退依旧pending。未调用Docker、构建或runtime，无新工作负载，所有恢复资产保留。
