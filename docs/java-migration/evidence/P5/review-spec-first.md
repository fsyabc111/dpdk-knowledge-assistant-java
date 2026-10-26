# P5 第一轮规格轴审查

BASE=`f020efa951b816cc3315f169c483af0998875800`；TARGET=`f1c5bb5584e5ee5c71ed9f6fbd3f383e92b3c22f`。已核实ref、三点差分与提交链；只读源码/证据，未编译、测试、访问PG/HTTP/浏览器/容器或外网，未提交。结论：**存在规格阻断，不能放行P5**。

Java相对包路径以下统一位于 `backend-java/src/main/java/io/dpdk/assistant/`。

## (c) 实现错误

**SP-P5-01 [P1] embedding故障未按源降级。** J5-01原句“零未解释差分”；冻结 `backend/app/services/retrieval_service.py:698-711` 明确“embedding上游失败不得让查询整失败”，保留词法/路径候选并标fallback。TARGET的 `retrieval/PgReleaseReader.java:187-198,224-229` 直接传播queryEmbedding异常，未进入真实候选检索；产品随后返回retrieval_failed。触发：有效release存在词法命中，embedding替身抛错。影响：Python仍可答/引用，Java整次失败。关闭：保留失败usage、仅关闭语义腿并记录fallback；真实PG+外部失败替身对照候选、引用与指标，禁止伪造chunk。

**SP-P5-02 [P2] 新过程hook改变源事件归属。** 用户裁决原句“补齐源过程指标（保持冻结兼容默认）”。`chat/ChatTaskExecutor.java:413-416` 固定LLM engine=llama_index；源产品 `backend/app/services/rag_product_service.py:365,613` 未设置rag_engine，`backend/app/core/metrics.py:153-157` 应落unknown。`agent/graph/AgentGraph.java:189-191` 又让每个成功产品任务发agentTokens；源该hist仅由 `backend/app/agent/service.py:400-405` 的独立编排日志发，产品直接消费graph、不走该入口。触发：一次正常带usage的产品问答；标签及hist count均漂移，新增PG测试反而硬编码了漂移。关闭：按实际源入口记录，补正常/失败/取消/重放的双端事件增量反例；不能只核family/type。

## (a) 缺失/部分与待门禁

新registry/hooks尚未编译或fresh验证；full06仅旧树185观察相等，metrics34/6仍失败。225扩展、17反例、22浏览器断言保持各自历史SHA，不覆盖TARGET。J5-02故障/Java正式容量、J5-03冷启/0013升级/实际备份恢复/应用回退、J5-04最终manifest与独立README复验均pending。单端完整采集与双端容量pass在statistics/compare中已分离，不据Python运行中或单端退出0放行。

## (b) 范围与保留边界

未发现需另列的越范围修改。ccf5e97已明确Python运行时项按JVM真实能力映射；GC次数/耗时、内存和jvm_info未冒充Python对象数，也不能豁免领域缺项。COMP003工具实现未在本差分改变；SSE首帧已有真实流测试入口，源码/备份排临时输入和恢复执行器仍须上述实际演练，不以静态物料代签。
