# J4-03 worker验证证据

- 工作树：`.migration-artifacts/worktrees/j4-03`；分支 `codex/j4-03-operations`；源基线 `5d0d6a3`。
- CLI首片：`d715837`；未改共享pom/platform/contracts/security/Application/STATUS/前端。
- 运行环境：macOS、Java21（显式JAVA_HOME；宿主默认Java17不用于构建）、Maven wrapper；Docker只运行Compose配置展开，无daemon/容器/Colima操作。

| 实际命令 | 结果/边界 |
|---|---|
| JAVA_HOME=<本机21> ./backend-java/mvnw -f backend-java/pom.xml -Dtest=OperationsCliTest,OperationsMetricsTest test | 10 tests / 0 failures / 0 errors / 0 skipped，2026-10-25 19:41 +08；不连接PG，未配置模型/embedding/harness |
| for script in scripts-java/*.sh deploy-java/postgres/*.sh; do bash -n "$script"; done | 所有脚本语法通过 |
| node --check deploy-java/fake-upstream/server.mjs | 通过；未启动替身或访问上游 |
| 显式合成env docker compose -f docker-compose.java.yml --profile rehearsal --profile harness-authorized config --quiet | 通过；仅配置展开，不代表部署 |
| generate-cert.sh 对本轮独立临时目录；openssl x509；stat模式 | 通过：SAN localhost/127.0.0.1，私钥0600、证书0644，覆盖目录被拒绝；只生成自签合成证书，无外部访问，私钥不提交 |
| git diff --check / 白名单与秘密扫描 | 见handoff提交结果 |

原始构建输出保留本机 `/tmp/j4-03-test.log`（不提交），JUnit结果在worker `backend-java/target/surefire-reports/`（ignore）。证书试验产物仅在本轮 `/tmp/j4-03-cert.hzVkrZ/certs/`，不入库，不属于恢复资产。

真实PG、OperationsPort业务适配、启动后无DDL/后台写、CLI boot jar、健康/就绪负例、指标HTTP端点、真实nginx HTTPS/SSE、容器权限、CI远程执行和备份恢复均未在本worker执行；这些必须由主集成者/J5绑定集成提交验证。未执行项不能算通过。

## MCP固定替身追加验证（2026-10-25）

主Agent追加授权固定MCP外部替身，替代早期“fake没有MCP”的限制；原有真实harness/live未验证声明继续有效。

- `node --test deploy-java/fake-upstream/server.test.mjs`：5/5，零失败/跳过。覆盖JSONRPC初始化、Mcp-Session-Id、initialized通知、tools/list、五工具、grade并发同job只建1条、finished回放、unknown、源哨兵不进入响应/固定账本/子进程stdout与stderr；模型/向量仅loopback固定响应。
- Java21 `StreamableHttpHarnessClient` 通过同一loopback临时服务调用全部五种工具，规范化payload `ok=true`，grade后status=finished，提交的 `JAVA_SMOKE_SOURCE_SENTINEL` 不在任何回复中。实际输出 `PASS: Java StreamableHttpHarnessClient five-tool MCP loopback smoke; no PG/real upstream`。可复现调用源 `deploy-java/fake-upstream/J4FakeHarnessSmoke.java`（仅允许loopback HTTP），classpath由现有Maven dependency:build-classpath取得；没有PG连接或真实调用。
- Compose只读展开通过：fake默认服务、backend的DPDK_HARNESS_URL明确指向fake /mcp且依赖fake healthy，真实harness-authorized仍默认off/pull never；无Docker/Colima启动。
- 备份脚本只读核查：参数为 `pg_dump --format=custom --exclude-table-data=public.rag_task_inputs`，没有`--exclude-table`或`--schema-only`。这会保留表结构并排除该表数据；实际custom dump目录/恢复后表存在且空、其余业务表完整由主Agent J5-03实跑，当前不据静态检查宣称恢复通过。
