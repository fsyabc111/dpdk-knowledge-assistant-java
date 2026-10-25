# P3 规格轴修复复审

固定快照 `5d0d6a3a0c01bfc1fc0af01dbf5b73bf98674f61`；比较 `3718d21...5d0d6a3`，原审查报告保留。业务源码只读，本审查未运行测试或 PG，未提交推送；未将旧树时序分析冒称旧树实际执行。

**规格轴结论：SP-P3-01、SP-P3-02 均可关闭，未发现修复引入的新规格阻断。** 工程门禁最终放行仍需规范轴复核及主 Agent 当前全量 verify 结果；本结论不代表用户验收。

- **SP-P3-01 关闭。** `chat/ChatTaskExecutor.java:321-356` 在每次模型回复返回后累计独立产品预算，超额在回复交给图层前抛出；`agent/graph/AgentGraph.java:208-225` 的异常路径直接退出，不能进入工具或下一次 finalize。`finally` 仍记录已发生用量，产品层保存受控 generation_failed 终态；保留原 Agent 预算的独立语义。
- **SP-P3-02 关闭。** `chat/ChatTaskExecutor.java:225-238` 完整传递 conversation → `contracts/retrieval/SearchInput.java` → `retrieval/PgReleaseReader.java:130` → `QueryRewritePort` 双参数方法 → `HeuristicQueryRewriter.java:56-73`，历史追问消解可达。两参数 SearchInput 构造及单参数改写器的默认兼容路径保留。

已独立阅读 `P3ReviewRegressionTest.java:37-149`：首轮11>10和跨轮6+6>10均经真实产品/任务/PG仓储断言 MCP、评测扣次、job、成绩为零，模型调用不额外增加，实际 usage 保留；DNS/HTTP 场景从真实会话表读取历史，断言消解 prompt、embedding 输入、首引用及固定 release。替身位于模型、改写模型、embedding/MCP边界，未直接伪造业务结果。执行证据 `spec-regression.md` 记载定向 **4/4通过、零跳过**；这是 worker 执行证据，本审查未重跑。

同时浏览本次成绩载荷/统计端口变更，未发现与这两项修复相关的新规格阻断；另一规范轴的关闭裁决不由本报告代签。浏览器、live、部署和容量保留既定后续阶段，不据此阻断 P3。
