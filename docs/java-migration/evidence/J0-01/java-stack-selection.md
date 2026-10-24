# J0-01 Java 技术栈官方资料核对

核对日期：2026-10-24（Asia/Taipei）。源码基线 `80b29135`；契约 `p0-v1`。本文件是 P0 选择建议与资料证据，Java 尚未构建或运行，不代表 P1 放行或用户验收。

## 推荐精确组合

| 层 | P0 推荐 | 官方依据与限制 |
|---|---|---|
| Java | Java 21，编译目标 `release=21`，运行环境最低 Java 21 | Boot 4.0.9 的框架最低要求是 Java 17，兼容范围包含 21；项目主动收紧为 21，不能据框架最低版本降低项目门槛。JDK 发行商、21 的补丁版本与镜像摘要留 J1-01 冻结。[S1] |
| Spring Boot | `4.0.9` | 2026-10-23 正式发布，Maven Central 存在对应 GA POM；选择已有多个维护补丁的 4.0 线，而非 4.2 RC。[S2][S3] |
| Spring AI | `2.0.2` | 2026-10-23 正式发布，Central 存在 GA BOM；官方明确 2.0.x 支持 Boot 4.0.x / 4.1.x。[S4][S5][S6] |
| JDBC / Flyway | Spring JDBC `7.0.10`；Flyway `11.14.1`；PG JDBC `42.7.14` | 由 Boot 4.0.9 BOM 管理，JDBC 版本来自其 Framework BOM；避免逐项另写版本产生漂移。这是 POM 核对，尚未执行 Maven 依赖解析。[S3][S7] |

先前候选 `Boot 3.5.16 + AI 1.1.8` 的版本线兼容有明确依据：AI v1.1.8 文档源码声明支持 Boot 3.4.x / 3.5.x。[S8] 但 Boot 3.5.16 已是该线最后 OSS 发布；AI 1.1.0–1.1.8 受 CVE-2026-59318 的未广告工具调度问题影响，该线修复 1.1.9 仅提供商业支持，OSS 修复从 2.0.1 起提供，因此不推荐把它作为本次新迁移的默认组合。[S9][S10]

这是基于维护与安全约束作出的项目建议；没有核实商业订阅，不将商业维护承诺视为现有条件。未来延长版本冻结前，应再次核对支持及安全公告。

## 依赖锁定与适配边界

- J1-01 采用 `spring-boot-starter-parent:4.0.9`（或导入同版 `spring-boot-dependencies` 并显式锁插件），再导入 `org.springframework.ai:spring-ai-bom:2.0.2`；不使用 `LATEST`、版本范围、SNAPSHOT 或 RC。AI BOM 只管理依赖，不替代 Boot 或构建插件配置；Central 的 `release` 字段当前含 RC，不能直接作为 GA 选择器。[S3][S5][S6][S11]
- J1-01 固定 Maven Wrapper、JDK 21 工具链与 `release=21`，保存有效 POM / 依赖树，并检查 SDK、Jackson 和日志依赖冲突；Boot 官方 Maven 最低为 3.6.3，具体 Wrapper 版本未在本轮选择或下载。[S1] 这些是待实现的门禁，不是本轮已通过检查。
- Spring AI 只放在模型与 MCP 适配边界。业务 Agent 循环、授权、任务恢复和受理事务由本项目显式控制，遵循本地 D03 与 ARCHITECTURE 的编排约束；不让默认 Advisor 取得隐式编排权。AI 2.0 的 `ChatModel` 返回原始工具请求，`ChatClient` 默认工具循环移至 `ToolCallingAdvisor`，需在 J1/J3 对显式控制路径做替身验证。[S12]
- Boot 4 / AI 2 使用 Jackson 3，且 MCP 默认传输发生变化；REST/JSON/SSE 与现有 MCP 请求不能按框架默认值判兼容，须由冻结 fixture 验证。[S12][S13]
- D05 建议正式选择 Spring JDBC：`JdbcClient` / `NamedParameterJdbcTemplate` 负责参数化查询与条件更新，复杂批处理使用 `JdbcTemplate`；官方 JDBC API 支持这些用法。[S14] 本地设计要求继续适配 LI 的 `node_id/text/metadata_/embedding/text_search_tsv` 与 `rag_release_nodes`，不引入第二套持久化框架；MyBatis 仅在出现具体映射缺口时另案评审。
- 不引入 `spring-ai-starter-vector-store-pgvector` 替换 LI 物理表。官方 `PgVectorStore` 的默认列为 `id/content/metadata/embedding`，且 schema 初始化会创建扩展、表和索引；若未来审查后使用该组件，须显式设置 `spring.ai.vectorstore.pgvector.initialize-schema=false`、`remove-existing-vector-store-table=false`，不得让其拥有 DDL。[S15] 表名校验开关不能代替现有 schema 的完整核验。

## Flyway 单一迁移链条件

- D06 建议选择 Flyway，DDL 唯一所有者仍为集成者。Boot 4 使用 `spring-boot-starter-flyway`；PostgreSQL 还必须加入 `org.flywaydb:flyway-database-postgresql`，与 `flyway-core` 同受 BOM 的 `11.14.1` 管理，并加入 PG JDBC 驱动。仅有 core 不足以声明支持 PG。[S3][S16][S17]
- `B0015__baseline.sql` 模式可作为设计方案：官方列 Baseline migrations 为 Community 功能；11.14.1 的开源 `flyway-core` 已含默认前缀 `B`、SQL baseline resolver 及 SPI 注册，无需依赖当前新版或商业自动生成器。[S18][S19][S20][S27]
- 同一目录保留 `B0015` 全量 SQL 与 `V0014` / `V0015` 增量。空库由 `migrate` 选最新 B0015；合成 0013 经严格 catalog 核验后显式 `baselineVersion=13` 再迁移，已有 baseline 标记会使 B0015 被忽略，并执行 V0014/V0015；等价 0015 经严格 catalog 核验后显式 `baselineVersion=15`。官方说明与 11.14.1 源码支持这一路径推导：已有 baseline 时 B 被忽略，版本等于 baseline 的 V 被忽略，较高 V 为待执行。[S21][S22][S28] **三路径的实际执行、校验和及最终 catalog 等价仍须 J1-01 验证。**
- 明确保持 `baselineOnMigrate=false`、`validateOnMigrate=true`、`cleanDisabled=true`。[S23][S26][S29] 官方指出自动 baseline 会移除误连数据库的安全网；`baseline` 仅登记版本标记，不证明实际表、约束、索引或扩展已匹配，`validate` 检查迁移记录与脚本校验和，也不能替代 catalog 对照。[S23][S24][S25]
- 项目额外门禁：在任何 Flyway 写入前校验独立测试 PG 的显式 URL / 端口、数据库名、角色与库标记；配置缺失即失败，禁止继承旧 `.env` 或默认宿主机库。先完整核验实际 catalog、历史 revision、扩展及目标 inventory，再显式 baseline；Alembic 与 Flyway 不可同时拥有 DDL。身份预检必须先于 Boot 自动调用 `Flyway.migrate()`，J1-01 应通过受控迁移入口/策略实现并验证启动顺序。[S16] 本轮未连接任何 PG。

## 本切片交付与限制

- 变更仅本文件；D03/D05/D06 的建议供主 Agent 归档，不直接修改决策、契约、构建或状态。
- 实际验证：公开官方文档、版本化源码、Central metadata/POM 的只读网络核对；未安装 Java、未下载运行工具、未执行 Maven resolve/build/test、未调用真实模型/embedding/评测、未连接 PG。
- 接口/决策影响：建议候选由 Boot 3 / AI 1 调整为 Boot 4 / AI 2；JSON、模型与 MCP 适配需按冻结契约验证。阻塞：无资料核对阻塞，P1 构建与数据库路径验证未执行。
- 回退：本文件可独立撤销；若 J1-01 发现依赖或协议不兼容，保留失败证据并重新选择受支持 GA，不自动降回上述存在已知约束的旧组合。下一步仅在 P1 获授权后执行 J1-01。

## 官方来源（均于上述核对日期访问）

[S1]: https://docs.spring.io/spring-boot/4.0/system-requirements.html
[S2]: https://spring.io/blog/2026/10/23/spring-boot-4-0-9-available-now/
[S3]: https://repo.maven.apache.org/maven2/org/springframework/boot/spring-boot-dependencies/4.0.9/spring-boot-dependencies-4.0.9.pom
[S4]: https://spring.io/blog/2026/10/23/spring-ai-2-1-0-RC1-available-now/
[S5]: https://repo.maven.apache.org/maven2/org/springframework/ai/spring-ai-bom/2.0.2/spring-ai-bom-2.0.2.pom
[S6]: https://docs.spring.io/spring-ai/reference/getting-started.html
[S7]: https://repo.maven.apache.org/maven2/org/springframework/spring-framework-bom/7.0.10/spring-framework-bom-7.0.10.pom
[S8]: https://github.com/spring-projects/spring-ai/blob/v1.1.8/spring-ai-docs/src/main/antora/modules/ROOT/pages/getting-started.adoc
[S9]: https://spring.io/blog/2026/06/25/spring-boot-3-5-16-available-now/
[S10]: https://spring.io/security/cve-2026-59318/
[S11]: https://repo.maven.apache.org/maven2/org/springframework/boot/spring-boot-dependencies/maven-metadata.xml
[S12]: https://docs.spring.io/spring-ai/reference/upgrade-notes.html
[S13]: https://spring.io/blog/2026/06/12/spring-ai-2-0-0-GA-available-now/
[S14]: https://docs.spring.io/spring-framework/reference/data-access/jdbc/core.html
[S15]: https://github.com/spring-projects/spring-ai/blob/v2.0.2/spring-ai-docs/src/main/antora/modules/ROOT/pages/api/vectordbs/pgvector.adoc
[S16]: https://docs.spring.io/spring-boot/how-to/data-initialization.html
[S17]: https://documentation.red-gate.com/flyway/reference/database-driver-reference/postgresql-database
[S18]: https://documentation.red-gate.com/flyway/learn-more-about-flyway/feature-summary
[S19]: https://github.com/flyway/flyway/blob/flyway-11.14.1/flyway-core/src/main/java/org/flywaydb/core/api/migration/baseline/BaselineMigrationResolver.java
[S20]: https://github.com/flyway/flyway/blob/flyway-11.14.1/flyway-core/src/main/resources/META-INF/services/org.flywaydb.core.extensibility.Plugin
[S21]: https://github.com/flyway/flyway/blob/flyway-11.14.1/flyway-core/src/main/java/org/flywaydb/core/api/migration/baseline/BaselineResolvedMigration.java
[S22]: https://github.com/flyway/flyway/blob/flyway-11.14.1/flyway-core/src/main/java/org/flywaydb/core/api/resolver/ResolvedMigration.java
[S23]: https://documentation.red-gate.com/flyway/reference/configuration/flyway-namespace/flyway-baseline-on-migrate-setting
[S24]: https://documentation.red-gate.com/flyway/reference/commands/baseline
[S25]: https://documentation.red-gate.com/flyway/reference/commands/validate
[S26]: https://documentation.red-gate.com/flyway/reference/configuration/flyway-namespace/flyway-clean-disabled-setting
[S27]: https://github.com/flyway/flyway/blob/flyway-11.14.1/flyway-core/src/main/java/org/flywaydb/core/api/migration/baseline/BaselineMigrationConfigurationExtension.java
[S28]: https://documentation.red-gate.com/flyway/flyway-concepts/migrations/baseline-migrations
[S29]: https://documentation.red-gate.com/fd/flyway-validate-on-migrate-setting-277579048.html
