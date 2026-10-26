# DPDK Knowledge Assistant · Java

Java 21 + Spring Boot + React + PostgreSQL/pgvector 的独立迁移仓库。Java后端位于 `backend-java/`，保留 `backend/` 为冻结Python行为参考；teaching_harness继续使用独立Python评测容器。文档与新增注释使用中文。

当前P3/P4工程门禁已通过，P5隔离工程验收进行中；当前授权、通过与未执行项以 [STATUS](docs/java-migration/STATUS.md) 和 [主交接](docs/java-migration/handoffs/MAIN.md) 为准。真实模型/embedding/harness验收、用户验收、合并main、生产切换、Python退役和恢复资产删除尚未执行。

## 构建和测试

需要JDK21。使用仓库Maven wrapper，不需要系统Maven。

```sh
export JAVA_HOME=/path/to/jdk21
./backend-java/mvnw -f backend-java/pom.xml -B verify
cd frontend
npm ci
npm test
npm run build
```

完整PG验证必须显式设置 `DPDK_TEST_PG_URL`、`DPDK_TEST_PG_USER`、`DPDK_TEST_PG_PASSWORD`、`DPDK_TEST_PG_DATA_DIR`，指向独立的 PostgreSQL17.10 + pgvector0.8.4 合成实例，维护URL须包含端口和postgres库。每次建删库先核验现场身份。未提供配置时PG套件会跳过；这不代表完整验证通过。确切环境/命令见 [后端运行手册](backend-java/README.md)。密码只在本地环境中提供，不写命令日志或提交。

## 启动独立环境

推荐使用 [隔离部署手册](deploy-java/README.md) 和 [演练与回退手册](docs/java-migration/cutover-runbook.md)。默认外部上游为固定合成模型/向量/MCP，不执行上传源码，不代表真实评测质量。新project/端口/卷/网络/DB与旧部署隔离；Docker调用必须经过profile/endpoint/daemon守卫。不要运行复制来的Python启动、Compose或切换脚本。

原生启动需显式数据库URL、账号、库名、comment、data_directory身份配置；缺失或不匹配直接拒绝。三路径迁移在身份预检后运行，不用Hibernate自动改库。React开发端口5174，产品API与JSON/SSE保持冻结契约。

- [后端构建、启动、认证、配置](backend-java/README.md)
- [Java运维CLI](docs/java-migration/delivery/OPERATIONS.md)；默认dry-run，显式apply才写
- [索引/知识全源配置](docs/java-migration/delivery/OPERATIONS.md)
- [严格差分与扩展场景](docs/java-migration/evidence/J5-01/)
- [容量入口](acceptance/load/README.md)、[故障恢复入口](acceptance/resilience/README.md)
- [部署演练入口](acceptance/deployment/README.md)

## 接手与来源

交付manifest、依赖与许可证声明、恢复资产清单及独立接手步骤见 [交付入口](docs/java-migration/delivery/README.md)。最终候选与验证摘要会绑定源码和工件SHA；模板中的null或pending不是通过。源LICENSE及第三方许可均须随实际分发物保留。

范围/契约/决策分别见 [SCOPE](docs/java-migration/SCOPE.md)、[冻结契约](migration/contracts/README.md)、[DECISIONS](docs/java-migration/DECISIONS.md)。协作任务入口为 [START_HERE](docs/java-migration/START_HERE.md)。

Python初始源码基线为 `80b29135faddd16d5e39fdbf0633291ea6a20489`；[复制报告](docs/java-migration/COPY_REPORT.md) 保留来源证据，[Python README](docs/java-migration/reference/README.python.md) 只供行为参考。旧项目、部署、数据库和恢复资产不因本仓库交付而变更。
