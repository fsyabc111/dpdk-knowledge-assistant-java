# P1 规范轴独立审查（流程、范围、证据、纪律）

- 审查人：P1 规范独立审查员（独立上下文，未参与实现）
- 日期：2026-10-25。审查基准：分支 `codex/p1-p2-longrun` 提交 `bb07ab7`（工作区另见"发现 1"的未提交文件）。
- 依据：`docs/java-migration/foundation-slices.md` J1-01、`ai-slices.md` J1-02/J1-03/J1-04、仓库根 `AGENTS.md`、`migration/contracts/schema-migration.md`、`STATUS.md`。
- 方法：只读核查。git 提交逐文件比对（`git show --name-only`、merge 双父差集）；抽查 12 个测试类断言体（每卡 ≥3）；核对本地 surefire 残留汇总；读 `platform/config`、`platform/pg`、`platform/migration` 源码；uuid5 向量独立复算；未运行任何构建/测试（避免写入）。

## 一、逐项核查表

### 1. 写入范围与单写所有者

| 检查点 | 证据 | 结论 |
|---|---|---|
| J1-02 worker 只写独占路径（main/test:model、harness，migration/J1-02 资源，evidence/J1-02，handoff） | 提交 `9d3e3b7` 完整文件清单 37 个：`model/**` 16、`harness/**` 3、test model/harness 10、`src/test/resources/migration/J1-02/` 1、evidence 5、handoff 1；无 pom/contracts/platform/db-migration/STATUS | 通过 |
| J1-03 worker 只写独占路径（retrieval） | 提交 `251b65f` 清单 41 个：`retrieval/**` main 17 + test 8、`migration/J1-03/**` 4、evidence 12、handoff 1；未触碰公共文件 | 通过 |
| J1-01/J1-04 公共文件仅由集成者（主Agent）修改 | `pom.xml`、`platform/**`、`db/migration/**` 仅出现于 `f84d2c9`（主Agent J1-01 集成窗口）；`contracts/model/ChatInput.java`、`HarnessReply.java` 仅在 `bb07ab7`（主Agent，随 interface-changes.md 登记签收）；STATUS 仅 `f84d2c9`/`bb07ab7`；MAIN 仅 `1e1cdba`，均主Agent提交 | 通过 |
| 集成 merge 提交不夹带额外变更 | `1627178`（父 f48e01b+9d3e3b7）、`c00eb99`（父 1627178+251b65f）：相对 worker 分支的 diff 文件均为另一父分支既有内容，无第三来源文件 | 通过 |
| 接口变更先登记再消费 | J1-02 handoff 提出请求 → 集成者在 `migration/contracts/interface-changes.md` 登记（bb07ab7）→ J1-04 消费 `ModelClients`/`PgReleaseReader` | 通过 |

### 2. 证据质量与"自动验证通过"声明

| 检查点 | 证据 | 结论 |
|---|---|---|
| J1-01 测试真实断言 | `MigrationThreePathsIntegrationTest`：五路径断言 path/applied/catalogVerified + 拒绝路径断言不写 `flyway_schema_history`（L132-140）+ V0015 回填四行分桶断言（rr_valid→explain、伪造/跨用户→chat，L89-94）；`DatabaseIdentityGuardNegativeTest` 四负例+正例；`ConfigValidatorTest` 7 项负例（缺 URL/缺身份/非 PG 协议/非回环/5432/隐式端口） | 通过 |
| J1-02 测试真实断言 | `HarnessClientHttpTest`：提交后 500/超时均断言 `toolCalls` 仅一次（L194、L208）、initialize 恰 3 次不多不少（L175）、畸形参数断言 `requestCount()==0`（L230）；`ChatClientStreamHttpTest`：SSE 逐段 delta、尾帧 usage 记账断言；`UpstreamGateTest`：并发峰值恰为上限 2（L54） | 通过 |
| J1-03 测试真实断言 | `RetrievalPipelineGoldenTest`：12 case 逐腿 ID 序、rank、RRF 分、Top-K id+score（容差 1e-12）；`PgRetrievalIntegrationTest`：SQL 腿对照冻结 legs、两 release 不串版本、fail-closed 三态（no_active_release/config_mismatch）、LIKE 转义 6 断言、HNSW 同序、历史 li/legacy 回读；golden 文件含溯源字段（golden_version=j1-03-v1、generated_by=evidence/J1-03/generate_golden.py、fingerprint、tol=1e-12、12 cases） | 通过 |
| J1-04 测试真实断言 | `MinimalQaChainIntegrationTest`：q2 断言 6 引用 chunkId/rank/score=pythonRound4(golden)；q7 双向反例（无引用→no_evidence、伪引用→invalid_citation）锁定 Python 裁决顺序（对照 `backend/app/services/rag_product_service.py:157→164` precheck 先于 postcheck，属实）；`MinimalQaSliceTest` 9 项裁决/绑定/round 边界断言 | 通过 |
| 全量数字链自洽 | 各包 @Test 计数（排除 @TestInstance 干扰）：platform 26、model+harness 83、retrieval 29（19 单测+10 PG）、chat 12（9+3）= 150；本地 surefire 残留汇总 `run=150 fail=0 err=0 skip=0`（24 类）与 bb07ab7 声明一致；26+83=109、109+29+9（J1-04 骨架 f48e01b 的 9 项）=147，与 STATUS/J1-03 handoff 的"147/147"一致 | 通过 |
| golden 期望值不可伪造为通过 | 期望值由冻结 Python 源生成：`generate_golden.py` `importlib.import_module("app.services.retrieval_service")` 真实驱动源服务（L107、L183-275）；测试资源与 evidence 双份 golden 同源 | 通过 |

### 3. 安全纪律

| 检查点 | 证据 | 结论 |
|---|---|---|
| 无凭据/秘密提交 | `backend-java/.env.example` 密码为空、仅模板变量；`evidence/J1-02/samples.md` 全部 `Bearer ***`；`WireSamplesTest` 断言样本不含 `sk-`/`Bearer `/`api_key`；全仓扫描无真实 token | 通过 |
| 数据库只指向隔离实例（55481、动态库） | `TestPg`：`DPDK_TEST_PG_URL/USER/PASSWORD/DATA_DIR` 四变量缺一整类跳过（`@EnabledIfEnvironmentVariable`），每测试动态建删库 `p1_t_<tag>_<hex>`（comment=java-migration-p1-test）；`.env.example` 与 evidence/J1-01 均指向 `127.0.0.1:55481` 独立实例；无 55473/P0 资产与宿主 5432 访问 | 通过 |
| fail-closed 配置真实存在 | `application.yml`：dpdk.datasource/database-identity 无默认值，`spring.flyway.enabled=false`、`sql.init.mode=never`（迁移仅由 `MigrationController` 在预检后触发）；`ConfigValidator.validate` 强制 PG 协议、回环地址、显式端口、禁 5432、身份四字段齐全，错误信息不含密码；7 项负例测试 | 通过 |
| 身份预检四要素真实存在 | `DatabaseIdentityGuard.verify`（platform/pg/DatabaseIdentityGuard.java:25-80）：`current_database()`、`current_user`、`shobj_description` 读库 comment、`SHOW data_directory` 且实际路径必须位于期望目录下（isUnder L83-88）；错库/错用户/缺 comment/错目录四负例测试全过 | 通过 |
| 无真实模型/MCP 出网 | pom 无 spring-ai starter 构件（仅 BOM import，无自动配置出网）；J1-02 测试全部 JDK `HttpServer` mock + fake MCP server（127.0.0.1 随机端口）；无生产 base URL 硬编码（`HarnessProperties` 的 compose 网名默认值属未提交文件，见发现 1） | 通过 |
| 日志/异常不回显源码哨兵与 payload | `dependencies.md` 与 `unverified.md` 差异表 #2：`mcp_call_failed` 等只记类型与长度（对源 `payload[:500]` 的收紧），`ChatClientSettings` 注释明确 apiKey 不进日志/账本 | 通过 |

### 4. 迁移纪律

| 检查点 | 证据 | 结论 |
|---|---|---|
| 三路径与 schema-migration.md 一致 | `CatalogProbe.classify`：EMPTY/AT_0013/AT_0015/HISTORY/REFUSE，其余一律 REFUSE 不盲 stamp；`MigrationController`：空库 `baselineOnMigrate=true`+`baselineVersion=15` 只执行 B0015；0013 显式 `baseline(13)` 后仅 V0014+V0015（测试断言 applied=2）；0015 显式 `baseline(15)` 空转（applied=0，catalog 逐字节不变）；部分表目标拒绝且不写 flyway_schema_history（集成测试 L126-141 断言） | 通过 |
| 无 Alembic/Flyway 双写 | Java 迁移链仅 Flyway（B0015/V0014 SQL + V0015 JavaMigration）；`B0015__baseline.sql` 零 alembic 引用，唯一 INSERT 为 active 种子行；alembic_version 只在测试 fixture 中构造 0013 起点 | 通过 |
| cleanDisabled/validateOnMigrate 语义 | 四个 Flyway 配置点全部显式 `validateOnMigrate(true)`、`cleanDisabled(true)`；`baselineOnMigrate` 仅空库分支为 true（非空无历史已被 CatalogProbe 归入 REFUSE，不会隐式打标），与契约"默认 false"一致 | 通过 |
| catalog 差分基准来源可追溯 | `expected-catalog-b15.json`（28 表/65 索引/41 约束）由 P0 合成 oracle 库真实 catalog 生成；B0015 由该库 pg_dump 变换（剥离版本表、补 vector 扩展与种子）；见 evidence/J1-01/build-and-paths.md 第 46-48 行；`CatalogVerifier` 比对时排除两张版本表并在差分非空时抛异常（MigrationController L88-92） | 通过 |
| V0015 讲解回填向量精确 | uuid5 独立复算：`uuid5(NAMESPACE_URL,"assignment-explain:abc123")=7a60c8a2-…`（rr_valid→explain）、sub2→`431b6599-…`（rr_wrong_user→chat）、grade_synthetic_001→`2bb5bfe5-…` 与冻结 `identity-vectors.json` 一致；`PythonUuid5` 的 NAMESPACE_URL 常量与 version/variant 位设置正确；跨用户/伪造反例有测试 | 通过 |

### 5. 状态一致性

| 检查点 | 证据 | 结论 |
|---|---|---|
| STATUS 任务表与交付一致 | J1-01 in_progress（切片1，集成窗口持续）有 handoff 支撑；J1-02/J1-03/J1-04 implemented 与提交、evidence、handoff 三方对应；证据路径列均实际存在 | 通过 |
| 未执行项未计为通过 | 四卡"场景/用户验收"均标未执行；live 指标、真实供应商冒烟在 `unverified.md`/`unverified-live.md` 保留限制；阶段放行表 P1/P2 用户验收"未验收"；J1-04 chain-samples 明确"不声明完整问答迁移完成" | 通过 |
| 授权边界遵守 | U11 预授权范围（P1+P2、替身、合成数据、隔离 PG、分支集成）与实际动作相符；无 main 合并、无部署、无真实模型调用、原 Python 副本未改动（d982f3a..bb07ab7 无 backend/ 变更） | 通过 |

## 二、发现清单

1. **[建议]** 工作区存在未提交的公共路径新文件（不在 bb07ab7 基线内）：`backend-java/src/main/java/io/dpdk/assistant/platform/config/{HarnessProperties,ModelChatProperties,ModelEmbeddingProperties}.java`、`platform/wiring/`（`git status` 显示 untracked）。属集成者进行中的 Spring 装配，路径归属合规（platform/** 为集成者独占），但在批次收口/推送核验前应提交或移除，避免与"无未提交文件"的恢复点叙述（handoffs/J1-04.md"无未提交文件"）不一致。
2. **[建议]** STATUS.md 阶段放行表 P1 行"自动/场景结果：未执行"与任务表 J1-01..J1-04"自动验证通过（150/150）"并存。两者语义分别指 P1 五项工程门禁实测与任务级自动验证，方向保守无误报，但建议在 P1 行注明"任务级自动验证已通过、阶段门禁实测未执行"以免误读。
3. **[建议]** worker 工作树 `.migration-artifacts/worktrees/j1-02`、`j1-03`（分支 worker/j1-02、worker/j1-03）仍保留。STATUS 称当前无在跑 worker，属可恢复资产；建议批次收口时清理或登记保留理由。
4. **[建议]** J1-03 登记差异 #4（改写/嵌入 TTL 缓存与用量账本未实现，属 J1-02 装配侧）已在 differences.md/unverified-live.md 留痕；提醒集成者在 P2 集成窗口接线后关闭该差异项，避免长期悬挂。

## 三、总结论

**通过（无阻断项）。** 四张任务卡的写入范围、公共文件单写所有者、证据真实性（12 个抽查测试类断言实质且与声明数字自洽至 150/150）、安全纪律（fail-closed 配置、身份预检四要素、55481 隔离动态库、零凭据、零真实出网）、迁移纪律（三路径与契约一致、单一 Flyway 链、差分基准可追溯、uuid5 逐位吻合）与状态一致性全部核查通过；未执行项均如实登记未计为通过。4 条建议项（未提交公共文件、STATUS 措辞、worktree 残留、J1-03 差异 #4 跟踪）交集成者处置，不阻断 P1 工程门禁评估。
