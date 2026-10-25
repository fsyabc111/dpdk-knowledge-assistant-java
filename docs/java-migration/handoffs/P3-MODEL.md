# P3-MODEL 交接

- owner/工作树：model_adapter worker；`.migration-artifacts/worktrees/p3-model`，分支 `codex/p3-model-adapter`。
- 源基线：`90fae3d`；实现提交 `20615e8`。
- 当前授权/契约：用户 U12 P3 续接；主 Agent 已接受富接口签名方案。公共契约登记、Bean 与 STATUS 均由主 Agent 单写。
- 状态：实现=完成；自动验证=模型包 84/84；场景验收=本地替身协议边界完成、产品 HTTP/PG 待集成；用户验收=未执行。

## 完成与文件

| 路径 | 目的 | owner范围 |
|---|---|---|
| `backend-java/src/main/java/io/dpdk/assistant/model/AgentModelAdapter.java` | Agent四角色消息、工具schema、回复工具调用和usage转换 | 是 |
| `backend-java/src/main/java/io/dpdk/assistant/model/OpenAiCompatibleChatClient.java` | 新增chatTools；与旧chat共享gate/transport/retry/cancel/accounting；保留原接口 | 是 |
| `backend-java/src/test/java/io/dpdk/assistant/model/AgentModelAdapterHttpTest.java` | 新增16项真实loopback HTTP断言 | 是 |
| `docs/java-migration/evidence/J3-03/model-adapter.md` | 脱敏验证摘要与局限 | 是 |

## 接口与决策

```java
new AgentModelAdapter(OpenAiCompatibleChatClient client, String modelName);
// implements AgentChatModel.call(List<AgentMessage>, List<String>)

client.chatTools(List<Map<String,Object>> messages,
                 List<Map<String,Object>> tools,
                 String model, ModelUsageLedger usage, String stage);
// RawToolChatResult(content, toolCalls, rawUsage)
// RawToolCall(id, name, arguments JSON文本)
```

工具 schema 固定五工具签名；未知绑定名本地拒绝。返回未知工具名保留给既有 AgentGraph 的 unknown_tool 结构化行为。只发一次逻辑非流式调用，传输重试按原客户端策略，工具执行留受控图，候选答案留 task 持久化后展示。

## 实际验证

- `JAVA_HOME=/opt/homebrew/opt/openjdk@21 ./backend-java/mvnw -f backend-java/pom.xml -B test -Dtest='AgentModelAdapterHttpTest,ChatClientHttpTest,ChatClientStreamHttpTest'`：退出0，39/39。
- 同命令 `-Dtest='io.dpdk.assistant.model.*Test'`：退出0，84/84，无失败/错误/跳过。
- 所有测试访问随机本机端口，未调用真实上游、未启动PG或全量verify。
- `git diff --check` 通过；提交白名单仅模型主代码/同形测试/本handoff/evidence；无凭据/运行输入/日志交付。测试key均为固定合成占位。

## 未完成、回退与恢复

- 主 Agent 按以上构造器注入 AgentChatModel；产品/PG/Spring 整体集成待主线验证。
- AgentUsageSink 无供应商重试计数字段；如需此维度须登记扩展，详见 evidence，不擅改 agent/**。
- 回退：撤销 `20615e8` 与后续文档提交；无 DDL、真实上游或业务数据副作用。
- 原始日志在本工作树 `.migration-artifacts/model/`（gitignored）；测试服务器与模型客户端均 teardown，未留服务端口。
- 未修改/持有任何共享文件锁；实现提交已通知主 Agent 可 cherry-pick。worker未推主集成分支，推送由主 Agent统一执行。
