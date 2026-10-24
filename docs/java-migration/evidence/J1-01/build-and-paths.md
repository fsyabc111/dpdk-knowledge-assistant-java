# J1-01 构建与三路径迁移证据（切片1：骨架/配置/迁移链/预检）

日期：2026-10-25。分支 `codex/p1-p2-longrun`。

## 工具链（P0-D14 锁定版本实测）

- JDK：OpenJDK 21.0.12.1（Homebrew，arm64），`release=21` 编译。
- Maven：系统 3.10.0 用于生成 Wrapper；交付 Wrapper 固定 Apache Maven 3.9.12（`.mvn/wrapper/maven-wrapper.properties`）。
- 依赖树（`./backend-java/mvnw -f backend-java/pom.xml dependency:tree`，与 P0-D14 逐项一致）：
  - Spring Boot 4.0.9（parent）/ spring-boot-starter-web、jdbc、validation、actuator、test
  - spring-jdbc 7.0.10、spring-web（Boot BOM 管理）
  - flyway-core 11.14.1 + flyway-database-postgresql 11.14.1
  - org.postgresql:postgresql 42.7.14（runtime）
  - spring-ai-bom 2.0.2（dependencyManagement import；本切片无 Spring AI 构件，J1-02 增补）
  - web 栈 Jackson 为 tools.jackson 3.1.7（Boot 4 默认）；flyway 自带 jackson 2.21.7，互不冲突

## 验证命令与结果

命令（仓库根）：`./backend-java/mvnw -f backend-java/pom.xml -B verify`

- 无 PG 环境变量时：26 tests run, 10 skipped（PG 集成测试显式跳过，非静默），BUILD SUCCESS。
- 带 PG 环境时：`Tests run: 26, Failures: 0, Errors: 0, Skipped: 0`，BUILD SUCCESS（总时长 57.4s）。
- 完整日志：`.migration-artifacts/p1/verify-run1.log`（不提交）。

环境变量（显式测试 PG；缺一即跳过）：
`DPDK_TEST_PG_URL=jdbc:postgresql://127.0.0.1:55481/postgres`、`DPDK_TEST_PG_USER=p1_java`、`DPDK_TEST_PG_PASSWORD=<合成实例口令>`、`DPDK_TEST_PG_DATA_DIR=<仓库>/.migration-artifacts/p1/pgdata`。

## 独立测试 PG 实例

- PostgreSQL 17.10（Homebrew postgresql@17）+ pgvector 0.8.4（`/opt/homebrew/lib/postgresql@17/vector.dylib`）。
- 实例：`127.0.0.1:55481`，data_directory=`<仓库>/.migration-artifacts/p1/pgdata`，socket=/tmp/dpdk-java-p1-80b29135。
- 角色 `p1_java`（CREATEDB；因需 CREATE EXTENSION vector 与读取 data_directory，在本专属合成实例上设为 SUPERUSER——实例身份仍由预检四要素钉死）。
- 每测试用例动态建库（库名 `p1_t_<tag>_<hex>`，comment=`java-migration-p1-test`）并回收；无任何对 55473/P0 资产、宿主 5432 的访问。
- P0 资产核对后已停回（参考 schema 导出 `.migration-artifacts/p1/reference-schema-p0-oracle.sql`，1073 行，oracle 库 alembic head=0015）。

## 三路径迁移实测（MigrationThreePathsIntegrationTest）

| 路径 | 判定 | 迁移动作 | 结果 |
|---|---|---|---|
| 空库 | EMPTY | baselineOnMigrate=true+baselineVersion=15 → migrate 应用 B0015 | applied≥1；catalog 差分清零；active 种子行为空串 |
| 合成0013 | AT_0013 | 显式 baseline(13) → migrate 执行 V0014+V0015 | applied=2；差分清零；讲解回填：合法行→explain，伪造 request_id/跨用户行保持 chat；input_hash 未改写；idx_grading_task_job_id 就位 |
| 等价0015 | AT_0015 | 显式 baseline(15) → migrate 空转 | applied=0；B0015 未重执行；catalog 逐字节不变 |
| 重复启动 | HISTORY | 常规 migrate | applied=0；业务 catalog 快照不变 |
| 部分表（仅 users） | REFUSE | 拒绝且不写 flyway_schema_history | 拒绝信息含"拒绝" |

- catalog 差分基准：`backend-java/src/test/resources/migration/J1-01/expected-catalog-b15.json`（28 表/65 索引/41 约束，源自 P0 合成 oracle 库真实 catalog 生成，排除版本表）。
- B0015 生成方式：oracle 库 pg_dump 变换（剥离 alembic_version 与 psql `\restrict` 护栏行，补 vector 扩展与 active 种子）；差分验证即"Java 迁移链产物 == Python 冻结代码产物"的独立对照。
- Flyway 行为实证：B0015 与 V0015 同为 15 版本可共存——空库路径仅执行 B0015，0013 路径仅执行 V0014/V0015，符合契约 schema-migration.md 设计（此前仅有源码级核对，本次为实际执行验证）。

## 身份预检负例（DatabaseIdentityGuardNegativeTest）

错 database / 错 user / 缺 comment / 错 data_directory 四种目标全部拒绝；正确身份通过。数据库 comment 读取使用 `shobj_description`（共享对象目录 pg_shdescription）。

## fail-closed 配置（ConfigValidatorTest）

URL 缺失、非 jdbc:postgresql、非回环地址、端口 5432、隐式端口、身份期望缺失全部拒绝启动；合法配置通过。密码不出现在任何错误信息。
