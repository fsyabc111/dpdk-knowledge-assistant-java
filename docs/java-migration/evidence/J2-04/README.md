# J2-04 证据索引

正式提交、MCP 评测、受理快照与成绩持久化（`/api/assignments/{id}/submit`）。

| 文件 | 内容 |
|---|---|
| behavior-evidence.md | 验收 A–G 行为证据（测试类 ↔ 验收映射、调用计数/配额/行数） |
| golden-diff.md | python-golden.json 三条 submit observation 差分与归一化映射 |
| utf8-parity.md | CPython↔JVM UTF-8 替换编解码差分与冻结电池（验收 F） |
| pg-race-order.md | 停号×成绩落库竞争顺序与 pg_locks 断言（验收 E） |

## 敏感扫描（提交前）

- runtime source 哨兵（`1c3277…` marker 与 `int plugin_main(void){return 0;}` 文本）
  不出现在任何测试资源、证据与日志：golden 请求的 code 以 sha256
  `1d6051bd…` 断言身份，测试内由 BASELINE 常量程序化重建（仅内存）；
  `plugin_main` 字面量仅存在于构造函数代码行（与 oracle route_cases.py 同形）。
- 替身只记录调用计数与参数指纹（tool/protocol/plugin_source 长度）；
  FakeMcpServer 无任何请求体日志；真实 StreamableHttpHarnessClient 日志仅
  error_type/payload_len（J1-02 交付语义）。
- 无 System.out/printStackTrace 于 submission 包；无凭据/连接串落文档
  （PG 地址为合成实例非秘密，与卡面一致）。
- 证据中的成绩/计数均为合成数据。

## 运行命令（非秘密）

```
export JAVA_HOME=/opt/homebrew/opt/openjdk@21; export PATH="$JAVA_HOME/bin:$PATH"
export DPDK_TEST_PG_URL='jdbc:postgresql://127.0.0.1:55481/postgres'
export DPDK_TEST_PG_USER=p1_java DPDK_TEST_PG_PASSWORD=p1-java-synthetic
export DPDK_TEST_PG_DATA_DIR=<repo>/.migration-artifacts/p1/pgdata
./backend-java/mvnw -f backend-java/pom.xml -B test \
  -Dtest='AssignmentSubmitContractTest,GradingQuotaIntegrationTest,GradingPersistenceRaceTest'
```

结果：21/21 通过（见 handoffs/J2-04.md 验证表）；全量 298/298。
