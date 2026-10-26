# P4 独立规格轴审查

固定基线 `708128cad447dabc48c028e19af8933db0e81e11`，快照 `69dad0f6cc9fc73e321b17a2d52ccd53a6434594`；已核实 ref、非空 `git diff 708128c...69dad0f` 与区间提交链。依据 J4-01 六项验收、J4-02 A–H、J4-03、LONGRUN §4/5、SCOPE、冻结 Python 与当前证据。业务只读；未运行 PG、构建或测试，未提交推送；未与规范轴交换发现。

**结论：暂不放行 P4 工程门禁。发现一项输入契约阻断；其余检查未发现需要增加的规格阻断。** `validation.json` 的698项、Boot CLI 2项/16 JVM、Node 5项及组合4项是已有执行证据，本审查不冒称重新执行。

## 阻断 SP-P4-01 [P2] JSON 尾随内容被忽略，非法请求可发布知识

要求：SCOPE 纳入项8原句“旧接口的错误状态码、空值、字段名、分页、时间、CSV与SSE等细节；差异必须登记并批准”；J4-02 目标原句“迁移……共享知识发布/修订”。这些是本阶段已交付路由的输入行为，不是要求提前完成 P5 全矩阵。

位置：`backend-java/src/main/java/io/dpdk/assistant/knowledge/workflow/WorkflowBody.java:10-19` 使用默认 `ObjectMapper.readTree(raw)`，未启用 `FAIL_ON_TRAILING_TOKENS`。`KnowledgeWorkflowController.java` 的发布/修订/反馈/更新路由以 `@RequestBody String` 接收原始正文，框架不会在此之前替它校验完整 JSON。首个对象通过后即继续调用工作流写入。

确定性触发：account admin 向 `POST /api/rag/knowledge` 发送 `Content-Type: application/json`，正文为 `{"title":"合成标题","body":"合成正文","shared_confirmed":true} {}`（或首对象后附垃圾）。Java 默认树解析只消费首对象，随后发布知识并写修订史；冻结 Python `backend/app/api/rag.py:137-141,181-183` 使用 FastAPI/Pydantic 请求体解析，完整 JSON 解码拒绝尾随内容，返回422且没有业务写入。PATCH/feedback 等共用解析器同受影响。该结论来自源码/解析器语义，未在本审查另行启动 HTTP 或数据库。

影响：把本应失败且零写入的请求变成成功写入，不属于已批准兼容差异；五条缺字段422黄金与现有绿色套件没有覆盖这个反例。

关闭条件：在统一工作流解析入口校验正文已完整消费；补冻结 Python 对照和真实 Java HTTP/PG回归，覆盖第二对象、尾随垃圾、截断及正常尾随空白。非法输入应422且知识/修订/问题/事件表不变；合法发布、修订、反馈仍保持原结果。错误体的定位/类型也应沿冻结错误契约收口，不能仅改为泛化500。

## 其余验收检查与建议

- J4-01：已对照扫描/切块与 Python 源，检查最长root、realpath、排序、metadata/config、逐项增量、事务外embedding/失败用量、CAS、健康/受校验回滚和旧节点保留；52项证据及组合引用断言与这些实现相符。健康读取与回滚更新分段是冻结基线边界，不据此擅增分布式协议。
- J4-02：本人反馈、teacher树404、事件事务、合并约束、updated_at/revision CAS、shared_confirmed/作者权限、当前release强制judge、先限流/扣配额再读问题的顺序均有相应实现与并发证据。人工作者title/body按原文保留，不新增脱敏语义；运行反馈/note继续脱敏。
- J4-03：CLI强制关闭启动迁移/任务后台；默认预览与显式apply、24h输入/180天留存、备份排除临时输入、ready完整性与404/405/500安全头均有源码对应。领域持久账本按gauge导出，过程指标显式unavailable，不能解释成完整源counter兼容。
- 后续建议：P5严格重放继续覆盖 JSON 标量强制转换、错误位置及CORS拒绝差异；远程CI、真实浏览器、Compose冷启/恢复/回退、容量均保持未执行状态。本审查不把这些已明确留给 P5 的演练列为 P4 阻断，也不把静态物料视作部署通过。

修复 SP-P4-01 后应针对新快照独立复审并复验，再由主 Agent 汇合两轴决定工程门禁；用户验收与 live/生产放行始终独立。
