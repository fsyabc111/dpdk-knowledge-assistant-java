# J3-03 产品聊天与 SSE 集成证据

日期：2026-10-25。owner：chat worker，worktree `.migration-artifacts/worktrees/j3-03`，分支 `codex/j3-03-chat`。这是工程验证记录，**不是用户验收**。外部模型、embedding、MCP 均为合成替身；身份、会话、任务、检索、配额、成绩、事务和持久化使用真实 Java 实现与显式独立 PG。

## 实现与提交边界

- `c001de1`：产品受理/执行体、JSON/multipart 输入、HTTP/SSE 及任务路由初稿；compile 通过，初稿不代表验收。
- `f512d1a`：公开 `ChatTestHarness`，供 J3-04 复用真实业务链。
- `2598ad1`：首轮 PG 失败后修 fixture 字段；修账本无行语义、完整 judge 提示/预算/序列化；增加 Python 黄金裁决和真实 PG 回归。
- `71c1b72`：COMP-003 结果未知保存专门终态、`failure_code`。
- `5a2daf1`：严格产品入口、Pydantic 输入/SSE 对照、准备与校验时限、摘要记账、二进制精确 round4；纯验证 42/42。
- `2c93259`：用量记录开关和写入时定价接线；新增真实账本断言，test-compile 通过，PG 待主 Agent 集成复验。

集成分支的等价 cherry-pick SHA 和最终全量结果以主 Agent 的 P3 阶段报告为准；worker 不复制维护另一套阶段状态。

## 已实际执行的验证

| 入口 | 本轮结果 | 断言与限制 |
|---|---|---|
| `mvnw ... -DskipTests compile`（c001de1 前） | 退出0 | 产品初稿可编译；未测试 |
| `-DskipTests test-compile`（2c93259） | 退出0 | 包含最新用量策略测试；不能当测试通过 |
| `-Dtest=ProductChatIntegrationTest` 首轮 | 5项：2通过、1失败、2错误 | 保留失败：fixture metadata 用错 file_path 导致检索失败；并发首发败方用自身新 session 探查，找不到赢家 |
| 修fixture后指定4个场景 | **4/4，0失败/错误/跳过** | 正文/重放/身份/配额；保存失败无delta且保留临时输入；release与旧引用固定；缺模型零受理 |
| `-Dtest=ChatInputGoldenTest,AnswerValidatorGoldenTest,ChatSseContractTest,ProductExecutionModeTest,GoldenScenarioTest`（5a2daf1前同树） | **42/42，0失败/错误/跳过** | 11个本卡测试（含向量循环）+31个已有Agent黄金场景；没有PG/真实HTTP |

原始日志保留在该 worktree `.migration-artifacts/j303-{compile,testcompile,pg-first,pg-fixturefix,validator,input,unit}.log`，均 gitignored。失败没有改写为通过。

## Python 黄金来源

`backend-java/src/test/resources/migration/J3-03/`：

- `generate_request_golden.py`：通过冻结 Python oracle 的 pydantic **2.10.4** 导入 `ChatRequest`，生成17个请求边界（字段顺序、类型、null、UUID、码点长度、空白保留）。`request-golden.json` SHA-256：`57ecf39a6c9a6bc58a6a08b1cb3461ea1851c2823ce5f2e37d9df15681e5df51`。
- `generate_validation_golden.py`：AST加载冻结 `rag_product_service.py` 的真实 `validate_answer/bound_evidence` 函数，去除仅指标导入并注入无副作用指标sink；生成20个裁决向量、4个Unicode证据预算向量。`validation-golden.json` SHA-256：`febe49ec880e35845dc9b096ed3775bf03da44bc8afec60063289572063292ea`。
- 完整 judge 系统提示从同一 Python AST 字符串逐字提取。Java以默认分隔符的 `PythonJson.dumps(..., false)`计量码点预算，保留所有 chunk 的位置。
- 仅生成本卡对照夹具，未修改 P0 冻结黄金或用 Java 输出反推 Python 期望。

复现（仓库根）：

```sh
/Volumes/study/code/new_project/dpdk-knowledge-assistant-java/.migration-artifacts/p0/venv/bin/python \
  backend-java/src/test/resources/migration/J3-03/generate_request_golden.py
python3 backend-java/src/test/resources/migration/J3-03/generate_validation_golden.py
JAVA_HOME=/opt/homebrew/opt/openjdk@21 ./backend-java/mvnw -f backend-java/pom.xml -B test \
  -Dtest=ChatInputGoldenTest,AnswerValidatorGoldenTest,ChatSseContractTest,ProductExecutionModeTest,GoldenScenarioTest
```

PG命令同 `backend-java/README.md`，四项 `DPDK_TEST_PG_*` 必须显式指向本副本专属55481实例，`TestPg`在任何建删库前验证维护实例身份；不回退 `.env` 或默认库。口令不在可提交文档中。

## 逐条验收映射

1. **非流式/流式/续传/重放身份**：worker真实PG验证首次、重放、SSE重放身份与正文相同，重放模型计数不增、任务/用户消息/配额各一次。注意冻结 COMP-010：首次引用title/score和next_steps，与DB终态重建不同；不得强行全字段相等。主 Agent 实际应用HTTP及原前端client对Java请求入口为 `ProductApplicationHttpTest`（执行结果由主 Agent补）。
2. **提交后展示**：触发器在assistant消息INSERT前抛错，真实TaskRunner回滚后，事件无delta、无assistant行、任务pending、临时输入保留；不发布供应商带tool_calls的正文。取消与迟到完成基础竞态另由J3-01真实组合测试覆盖，最终集成树仍须复验。
3. **SSE**：纯wire测试钉死双LF、UTF-8、error-only安全结束、累计sources同文件多chunk不合并、pending terminal:false、断线只退订。主Agent真实HTTP测试负责容器实际响应头/帧和客户端解析。
4. **release**：真实PG首个回答绑定r1，切active=r2后同会话仍引用r1，历史节点回读仍可用。并发绑定的J2-05基础测试由最终全量复验；失配返回受控 `system_error/retrieval_failed`。
5. **现有前端client**：由主Agent的 `ProductApplicationHttpTest` 加载原 `frontend/src/api/client.js`、正常构建期env替换后真请求Java（含multipart），worker未运行该测试，不计通过。浏览器断线/刷新仍属P5。
6. **裁决与故障**：20 Python裁决向量覆盖empty/invalid/no_evidence/missing_citation、online judge五状态、supported真假及非法JSON/类型/状态；严格产品入口避免旧Agent fallback误吞空答案；缺模型或检索装配在新受理写库前503。新添PG空答案/生成失败、prepare墙、judge墙、用量定价场景须最终集成复验。

## 已识别并移交主 Agent 的集成问题

- 初发并发受理败方的task探查错误：`RagTaskService.admitDetail`应按 `identity.initialRequest()`无session探赢家，主Agent在 `f4e933c`修复；并发场景不删除、不弱化。
- COMP-003：旧Agent对running/unknown/query_failed盲重投。主Agent负责agent工具/图/task终态修复，本卡仅接专门结果未知终态；真实账本10项由独立worker补验，见 `agent-ledger.md`。
- first `next_steps.source_path` 默认空串遗漏，主Agent按 Python `schemas/chat.py:39`补TaskEventStreams，非本卡越权修改。
- Spring容器默认async墙、真实model/retrieval/heartbeat、env桥由主Agent单写；本卡不改共享装配。

## 未执行与保留边界

- 最新集成树PG全量、原前端client真实HTTP最终结果、J3-04完整恢复、独立双轴审查由主Agent统一排队，不能用worker历史通过替代。
- 真实模型/embedding/真实harness、浏览器硬件行为、P5容量/部署故障、用户验收/切换/退役未在本卡执行。
- `chat/poc/**`保留作为J1-04可重放测试入口，正式路由消费新的产品执行体，无双生产路径。
