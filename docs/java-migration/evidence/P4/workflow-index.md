# P4 知识闭环、索引与平台 HTTP 组合验证

- 日期：2026-10-25（Asia/Taipei）；实现对象：主集成 `81f3757`；worker工作树 `codex/p3-model-adapter`。
- 新增文件：`test/knowledge/workflow/P4WorkflowIndexIntegrationTest.java`、`test/platform/wiring/P4PlatformHttpTest.java` 与本文件。业务实现、现有测试、公共配置、DDL均未改动。
- 当前结果：组合场景2/2、完整Spring HTTP场景2/2分别实际通过，共4项。全量verify和阶段审查由主Agent继续；用户验收/live/Compose部署不因本证据计为完成。

## 知识闭环与索引断言

两场景复用ChatTestHarness真实PG事务、任务、会话、权限、配额、TaskStore以及真实KnowledgeWorkflowService、IndexService、PgReleaseReader、GroundedQuestionVerifier。替身仅在模型、embedding外部边界；索引和查询使用固定非零1024维向量。临时合成源yaml明确包含独立DPDK目录和assistant目录，无旧环境/默认源回退。

| 场景/测试入口 | 实际执行与断言 |
|---|---|
| `feedbackPublicationFullSnapshotForcedJudgeAndCurrentReleaseResolution` | 初次全源基础摄取后，真实chat的固定回答故意缺引用→unanswered/missing_citation；TaskStore自动issue.id=request.taskId；本人反馈复用同issue，其他admin作用域返回404，所属admin认领后owner为manager |
| 同场景：人工发布与生效分离 | sharedConfirmed=true的人工作品写为published，返回index_status=pending；未摄取前active查询没有该published路径；scan同时包含dpdk/base.md、assistant/guide.md、knowledge_id人工条目；plan→ingest后health完整、活动检索出现knowledge/published/<id>.md |
| 同场景：强制原题验证 | ChatSettings.onlineJudgeEnabled=false仍通过GroundedQuestionVerifier调用真实证据裁决的judge边界；judge调用从0到1，验证结果answered/release=当前active；随后update resolved成功且verified_release_id相同 |
| 同场景：下一发布封锁旧验证 | 修改合成源并摄取产生下一release；原verified_release_id不能直接resolved，返回409；重新强制原题验证后judge累计2次、返回新release，resolved才成功 |
| `withdrawingKnowledgeReindexesActiveWhilePinnedSessionsAndHistoricalReferencesRemainReadable` | 从同样的真实issue→人工published→摄取链启动；真实chat保存该published引用并固定session release；知识修订为draft/revision2、index_status=pending，此时活动检索仍包含旧条目；全源reindex后新active不含该published路径 |
| 同场景：历史可读与版本固定 | oldCitation经historical(llama_index,chunkId)仍读到旧内容；旧会话继续问答仍引用旧chunk、session.rag_release_id仍为旧release；新旧release health均完整 |
| 两场景：源码生命周期 | 用户问题含合成C源码哨兵；永久消息content、rag_requests.question/context_text、issue.feedback中哨兵均为零；初始终态临时输入为零。人工共享正文为独立教学说明，未复制用户源码 |

## 完整Spring、typed环境绑定与HTTP断言

P4PlatformHttpTest使用SpringApplicationBuilder(Application.class)实际启动嵌入式HTTP服务，地址127.0.0.1、随机端口；数据库先经TestPg身份预检再建唯一合成库。模型/embedding上游是随机loopback端口JDK HttpServer，embedding响应非零1024维。未用MockMvc替代真实装配。

| 测试入口 | 实际断言 |
|---|---|
| `deploymentStyleEnvironmentCreatesTypedClientsInActualApplication` | 模型配置仅置入真正SystemEnvironmentPropertySource（无高优先级Map模型属性）：DPDK_MODEL_CHAT_BASE_URL/API_KEY/MODEL_NAME 与 DPDK_MODEL_EMBEDDING_BASE_URL/API_KEY/MODEL_NAME；实际ModelChatProperties/ModelEmbeddingProperties字段逐项为合成配置，两个typed client Bean存在，health HTTP200 |
| `readyRequiresCompleteActiveIndexAndFrameworkErrorsKeepFrozenBodiesAndAllSecurityHeaders` | 无active：ready503/detail=not ready，而health200；通过Spring Bean IndexService实际HTTP embedding摄取合成全源后ready200，embedding请求确实发生 |
| 同场景：完整性负例 | 删除一个release member→ready503；恢复member并使物理node_id失配→503；恢复node并修改release配置指纹→503；恢复配置→ready200；health始终只表达存活200 |
| 同场景：框架404/405/500 | 未匹配路径404/detail=Not Found；POST /api/health返回405/detail=Method Not Allowed且Allow含GET；测试专用异常端点触发全局500，正文严格Internal Server Error，不回显合成私有异常细节 |
| 全部正常/失败响应 | 200、503、404、405、500响应均断言四安全头：X-Content-Type-Options=nosniff、X-Frame-Options=DENY、Referrer-Policy=no-referrer、Content-Security-Policy=default-src 'self' |

此处只证明Spring真实环境属性绑定，未声称Compose env_file或容器网络已经演练。500用测试类提供的只在测试classpath存在的端点触发，未向生产主代码新增诊断路由。

## 命令、失败诊断与当前结果

工作目录 `.migration-artifacts/worktrees/p3-model`；JAVA_HOME=/opt/homebrew/opt/openjdk@21；四个DPDK_TEST_PG_*显式指向专属127.0.0.1:55481实例及期望data_directory。口令从gitignored本地凭据文件读入子进程环境，未输出/提交。所有CREATE/DROP前TestPg只读核验维护实例身份；应用另有数据库身份校验。

```sh
./backend-java/mvnw -f backend-java/pom.xml -B test-compile
# 退出0。

./backend-java/mvnw -f backend-java/pom.xml -B test \
  -Dtest=P4WorkflowIndexIntegrationTest,P4PlatformHttpTest
# 首轮：平台2/2通过，组合2项因夹具起点预期错误失败，非通过。

./backend-java/mvnw -f backend-java/pom.xml -B test \
  -Dtest=P4WorkflowIndexIntegrationTest
# 夹具调整后：19:58:21结束，退出0，2/2，无失败/错误/跳过；测试1.702秒/构建5.011秒。
```

首轮失败保留在 `.migration-artifacts/model/p4-workflow-platform.log`：夹具将无active时的产品响应预期为unanswered/no_evidence，实际既有逻辑为system_error/retrieval_failed。本卡目标明确允许“无证据或缺引用”起点，故改成先摄取基础源，再用固定回答缺引用落issue；业务实现和兼容断言未放宽。后续组合2/2通过；平台测试与业务源在重跑前后均未改动，其2/2证据仍绑定同一实现对象。

最终日志：`.migration-artifacts/model/p4-workflow-index-rerun.log`；编译日志：`p4-workflow-platform-compile.log`（均gitignored）。PG排队锁已明确归还主Agent，应用/fake/执行体已finally停止、唯一测试库已安全删除；没有运行全量verify、真实上游、旧服务写入或恢复资产删除。回退仅撤销本切片新增的两个测试类和本证据。
