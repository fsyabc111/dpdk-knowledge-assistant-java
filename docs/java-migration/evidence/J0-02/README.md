# J0-02 证据索引

- fixture：`migration/fixtures/p0-v1/manifest.json`，冻结修订2；黄金SHA256 `c041d83636b31684aeef9f1497925a0ebdc3112b9a7ea781f1fd92093b943edf`。
- `auto/p0_python_oracle.json` / `auto/p0_candidate_oracle.json`：两独立PG库的29表、索引/约束、66路由映射、14上游脚本、5最小认证向量和10PG专例。
- `auto/comparator-selfcheck.json`：Python自对照、UTF8逐字节分块、对象key重排正例；19类破坏反例；7个危险URL连接前拒绝。
- `auto/differential-cli.json`：184条双库Python差分通过，故意破坏status退出1，重复freeze拒绝。
- `known-differences.md`：首次/重放、会话删除、SSE帧差异与未验证边界。
- `migration/oracle/README.md`：唯一重跑命令、替身边界和比较规则。
- 性能/进程恢复由独立worker写 `performance/`；本次功能证据不替代其长测与阈值确认。

自动验证：已执行项目通过。场景验收：基础路由与关键事务通过，完整功能重试/并发与认证组合仍有未执行项。用户验收：未进行。真实模型/embedding/harness质量：未获授权且未验证。没有Java候选对照或生产环境操作。
