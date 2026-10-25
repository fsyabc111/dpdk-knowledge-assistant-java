# P3 模型富消息适配验证

- 日期：2026-10-25；实现提交：`20615e8`，工作树 `codex/p3-model-adapter`，基线 `90fae3d`。
- 验证范围：Java 客户端与 Agent 模型边界；所有 HTTP 都是测试进程创建的 `127.0.0.1` 随机端口 JDK HttpServer。无真实模型、embedding 或 harness 调用，无数据库操作。
- 实现：`model/AgentModelAdapter` 实现 `AgentChatModel`；`OpenAiCompatibleChatClient.chatTools` 使用原有同一套 HTTP、并发门、重试、中断等待和一次记账机制。旧 `chat`、`complete`、`streamChat`、`stream` 签名与正文严格解析不变。

## 协议对照与断言

| 项 | 实际断言 |
|---|---|
| 四角色 | SYSTEM/HUMAN/AI/TOOL 转为 system/user/assistant/tool；正文 UTF-8 保留 |
| 历史工具配对 | assistant 的 tool_calls id/name/arguments 与 tool 消息 tool_call_id/name 保留 |
| 工具声明 | 五工具顺序按调用方；query、protocol、plugin_source 均字符串，required 对齐冻结 Python build_tools 签名；未知绑定工具在 HTTP 前拒绝 |
| finalize | 空 toolNames 时请求不含 tools；不启动自动工具循环 |
| 富回复 | content 与 tool_calls 同时返回均保留；有效工具调用允许 content=null |
| 用量 | 已知 token 映射，input/output aliases 推导 total；未知保持 null，已知 0 保持 0 |
| 参数畸形 | 非法 JSON、数组、null、多个 JSON 值全部拒绝，零传输重试 |
| 响应畸形 | 空choices/缺message/null正文无工具/非数组tool_calls/缺arguments等失败；client账本保留一次unknown |
| 重试 | 503 后成功，共两次 HTTP 只记一笔上游 usage，call_count=2 |
| 中断 | 供应商已收到请求后调用线程被中断，及时抛取消异常；不再次请求，账本记unknown，远端不伪称取消 |
| 公共SSE | 此适配器只返回完整候选，不发浏览器正文；保存后展示由task/chat集成负责 |

## 实际执行

工作目录：`.migration-artifacts/worktrees/p3-model`；`JAVA_HOME=/opt/homebrew/opt/openjdk@21`。

```sh
./backend-java/mvnw -f backend-java/pom.xml -B test \
  -Dtest='AgentModelAdapterHttpTest,ChatClientHttpTest,ChatClientStreamHttpTest'
# 退出0，39/39，失败0、错误0、跳过0；新增富接口16项。

./backend-java/mvnw -f backend-java/pom.xml -B test \
  -Dtest='io.dpdk.assistant.model.*Test'
# 退出0，84/84，失败0、错误0、跳过0。
```

原始日志：工作树内 `.migration-artifacts/model/{adapter-test,all-model-test}.log`，gitignored。

## 限制与集成要求

- 主 Agent 统一装配 Bean：`new AgentModelAdapter(OpenAiCompatibleChatClient, modelName)`；此 worker 不写 platform/wiring 或共享配置。
- 真实供应商兼容性、长流式 tool-call chunk 合并不在本切片验证范围；产品 SSE 使用完整候选保存后展示，不要求供应商 token 直通。
- `AgentUsage/AgentUsageSink` 当前只携带 token，没有供应商重试 callCount 字段；适配器保持该已冻结消费接口。`chatTools` 的可选 `ModelUsageLedger` 能精确记录重试次数，但 Agent 侧若需持久化此维度，须由集成者登记跨模块契约扩展，不能把一次图节点调用误称一次 HTTP 尝试。
- 未执行 Spring Bean 集成、PG 集成或产品 HTTP/SSE；不据本报告宣称 P3 门禁通过。
