# 固定本地上游替身

这是 P5 隔离工程演练的外部边界替身。`/health` 明确 `synthetic=true`、`executes_source=false`、`live_quality=false`。服务没有外部请求、编译/执行接口、文件写入或请求日志。不能用于声称真实模型质量、评测正确性或生产可用。

```bash
node --test deploy-java/fake-upstream/server.test.mjs
# 单独本机启动时显式仅loopback；Compose固定运行在内部java-harness网络。
FAKE_UPSTREAM_HOST=127.0.0.1 FAKE_UPSTREAM_PORT=18091 node deploy-java/fake-upstream/server.mjs
```

`POST /mcp` 按现有 Java `StreamableHttpHarnessClient` 流程：initialize（2025-06-18）→响应`Mcp-Session-Id`→携带会话的notifications/initialized（202）→tools/call。支持tools/list，结果同时携带structuredContent与文本JSON。工具如下：

| 工具 | 固定行为 |
|---|---|
| list_protocols | dns/http/ssh，各一个synthetic用例 |
| list_cases | 固定synthetic_fixed_pass |
| grade_plugin | 不读取源码含义；固定score100、passed1、单PASS及空failures；只把job_id与固定结果放入内存 |
| get_task_status | 已知job返回finished与原结果，未知job返回unknown；无自动重执行 |
| get_reference_answer | 返回固定合成参考源码，不回显提交源码 |

同job_id并发重复提交回放首次固定结果，不创建第二条账本。不传job_id时分配合成序号。无源码哈希、输入持久化或原始请求日志；响应/快照仅含协议白名单、固定结果和合法job_id。body上限1MiB；会话/作业数量有显式容量上限，超额不静默驱逐既有账本。

`/v1/chat/completions` 和 `/v1/embeddings` 仍为固定本地回答及1024维向量，不代表真实模型或embedding检索质量。Compose默认启用此fake服务，backend的 `DPDK_HARNESS_URL=http://fake-upstream:18091/mcp`，依赖健康检查；没有宿主端口。`harness-authorized` profile仍默认关闭且pull_policy=never。

内存账本在替身重启后丢失，结果为unknown。这个简单固定实现不覆盖真实harness内存丢失、运行中作业、编译器隔离、deadline、结果异步落盘、MCP提交后连接断裂等验收。P5应单列这些真实边界/故障域，不能把本替身测试作为对应场景通过。

Java现有客户端的可复现smoke（先以loopback启动替身，使用Java21；命令没有PG配置）：

```bash
./backend-java/mvnw -f backend-java/pom.xml -q -DskipTests compile dependency:build-classpath -Dmdep.outputFile=/tmp/j4-harness-cp
java --class-path "backend-java/target/classes:$(cat /tmp/j4-harness-cp)" deploy-java/fake-upstream/J4FakeHarnessSmoke.java http://127.0.0.1:18091/mcp
```

smoke源限制目标只能是localhost/127.0.0.1的HTTP地址；五工具回包均需ok，grade后的status需finished，且响应不能包含源码哨兵。
