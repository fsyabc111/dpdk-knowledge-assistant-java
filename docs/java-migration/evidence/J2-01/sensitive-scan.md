# J2-01 敏感信息扫描摘要（2026-10-25）

扫描范围（交付物）：`backend-java/src/main/java/io/dpdk/assistant/auth/**`、
`backend-java/src/test/java/io/dpdk/assistant/auth/**`、
`backend-java/src/test/resources/migration/J2-01/**`、
`docs/java-migration/evidence/J2-01/**`。

方法：模式扫描（连接串/jdbc URL/口令环境变量值/≥32 字符高强度字符串）+ 人工复核。

| 检查 | 结果 |
|---|---|
| PG 连接串/口令硬编码 | 无。测试仅经 `DPDK_TEST_PG_*` 环境变量（System.getenv），类级 `@EnabledIfEnvironmentVariable` 门控；命令行/日志未打印口令 |
| 真实账号/真实凭据 | 无。所有学号（U2026/U2027xxxxxxx）、口令（newpassword123 等，沿用冻结 Python 测试同款合成值）、`f`×32 静态兜底 token、`p0-synthetic-static-token-value-0001` 均为合成值 |
| bcrypt-compat.json | 仅含合成口令（学号/测试口令/kimi-assistant-dummy 种子）的固定盐哈希与一个 sha256 向量；无真实凭据 |
| 日志/输出 | main 代码不打印 token/口令原文（token 仅在登录/审批响应中按契约返回一次）；无原始日志落盘 |
| `.env`/部署配置 | 未新增任何配置文件；application.yml 未改动（属集成者） |
| 明文 token 存储 | 断言反向通过：api_tokens 只存 sha256（tokenStoredAsSha256OnlyAndRevocationIsImmediate） |

结论：未发现真实凭据、生产连接信息或不应提交的敏感数据。
