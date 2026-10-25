# P3 规格审查阻断定向复验

- 日期：2026-10-25（Asia/Taipei）；worker：model_adapter。
- 审查对象旧快照：`3718d21a0144f6a6fceb6cd416ba10e528778317`；修复对象：主Agent `14d756d`；本切片仅新增 `test/chat/P3ReviewRegressionTest.java` 与本证据，不改业务主代码或其他测试。
- 结论：SP-P3-01 / SP-P3-02 的定向真实PG回归 **4/4通过**；是否关闭阻断和放行P3仍由主Agent与独立审查裁决，用户验收未执行。

## SP-P3-01：产品预算必须在工具副作用前生效

固定 `RAG_REQUEST_TOKEN_BUDGET` 对应 ChatSettings=10，Agent预算保持默认48000。测试经真实 ProductChatService→任务受理→ChatTaskExecutor→Agent 产品循环→真实PG，不直接调用预算判断函数。

| 场景 | 固定模型回复 | 实际断言 |
|---|---|---|
| 首轮超额 | 首轮11 tokens并携合法grade_plugin调用 | system_error/generation_failed；模型恰好1次，不额外finalize；MCP0、grading计数0、job0、成绩0；chat受理配额1；终态临时输入清空；上游usage真实保留1条/11 tokens/1 call |
| 跨轮累计超额 | 首轮6 tokens携kb_search（真实PG检索），第二轮6 tokens携grade_plugin | 第二轮工具执行前system_error/generation_failed；模型恰好2次；MCP0、grading计数0、job0、成绩0；chat配额1；usage保留2条/12 tokens/2 calls |

旧版可复现逻辑对照（未切主分支、未将旧版测试伪报为已运行）：

- `git show 3718d21:backend-java/src/main/java/io/dpdk/assistant/chat/ChatTaskExecutor.java`：旧344–345行把模型reply直接交回产品图；旧353–378行先执行完整runProduct，旧380行才比较产品预算。
- 因而上述首轮/跨轮reply会进入工具执行或额外后续模型调用后才发现RAG预算超额；本回归对MCP0/模型调用上限/无grading副作用的组合能拒绝该时序。
- 修复 `14d756d` 在每次模型返回后累计productTokens，超限即抛出，再由产品错误路径形成受控终态；finally仍记录这次已发生usage。

## SP-P3-02：真实历史追问语境贯通检索

两项参数化场景使用相同追问“它如何实现？”，在真实会话表分别预存DNS/HTTP历史。两份合成知识同属一个发布，配置指纹与固定1024维embedding一致。自建PgReleaseReader注入真实HeuristicQueryRewriter，唯一替身是其LLM边界与embedding边界；复用真实session/task/usage/grade仓储与12参ChatTaskExecutor，不给SearchInput或rewriter直接塞历史。

实际链路：数据库历史→ChatMemory→AgentToolContext→kb_search(query,conversation)→SearchInput第三字段→PgReleaseReader→HeuristicQueryRewriter双参数→固定LLM。

每种历史均实际断言：

- 改写LLM恰好收到1次消解请求，system prompt含消解指代要求，user prompt同时包含真实历史协议名与相同当前追问。
- embedding输入是相同追问与对应DNS/HTTP消解关键词组合；不是未消解的短追问。
- 产品回答为answered；模型2次；首引用分别为 `src/engine_dns.cpp` / `src/http_plugin.c` 对应节点，session发布固定为followup-release。

旧版逻辑对照：旧ChatTaskExecutor 225–238行虽收到conversation，但237行只构造两参 `new SearchInput(query,null)`；旧PgReleaseReader 130行只调用单参rewrite。旧树无法触发本用例所要求的含真实历史的消解prompt。`14d756d` 最小扩展SearchInput、QueryRewritePort并在消费者完整传递；两参兼容构造器保留。

## 实际命令、环境与资源

工作目录：`.migration-artifacts/worktrees/p3-model`；JDK21；四个DPDK_TEST_PG_*显式指向独立127.0.0.1:55481实例。TestPg先预检维护角色/data_directory，再创建唯一合成库、迁移、执行与删除；口令只进环境，未输出/提交。PG全量队列由主Agent维护，本卡仅独占单卡窗口。

```sh
JAVA_HOME=/opt/homebrew/opt/openjdk@21 ./backend-java/mvnw -f backend-java/pom.xml -B test-compile
# 退出0。

# 显式独立PG环境下：
JAVA_HOME=/opt/homebrew/opt/openjdk@21 ./backend-java/mvnw -f backend-java/pom.xml -B test \
  -Dtest=P3ReviewRegressionTest
# 2026-10-25 19:26:03结束；退出0，4/4，失败0、错误0、跳过0；测试1.853秒/构建总计2.987秒。
```

日志：本worktree `.migration-artifacts/model/p3-spec-regression{,-compile}.log`（gitignored）。未运行全量verify、真实模型/embedding/harness。测试库和执行体已清理；PG锁已归还主Agent统一全量验证；没有改旧部署或删除恢复资产。回退只撤销新增测试与本文件。
