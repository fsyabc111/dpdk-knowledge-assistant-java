# J2-01 并发一次性消费与撤销前后请求证据

执行环境：独立测试 PG（env `DPDK_TEST_PG_URL` 指向 p1 专用实例，身份预检由 TestPg/
MigrationController 执行；连接串/口令不落盘于代码）。测试报告：
`backend-java/target/surefire-reports/TEST-io.dpdk.assistant.auth.CredentialLifecycleTest.xml`
（14/14 通过，0 失败 0 错误）。

## 并发一次性消费（验收 D）

用例 `resetTokenConcurrentConsumptionIsStrictlyOnce`（通过，elapsed 0.028s）：

1. 审批签发一个真实一次性重置令牌（30 分钟有效）；
2. 6 线程 CyclicLatch 同时调用 `UserAuthRepository.consumeResetToken`（各自独立 PG
   事务，READ COMMITTED）；
3. 断言：恰好 1 个 true；`password_reset_requests` 该行 status='used' 计数恰 1；
   该账号无未撤销 token。

结果：successes=1、used 行=1、存活 token=0。

行为差异（已登记 endpoints-matrix.md §6、golden-diff.md）：Python
`_consume_reset_token_sync` 的置 used UPDATE 无 `status='approved'` 守卫，同并发下
理论可双消费；Java 守卫版保证严格一次（任务卡验收 D 明确要求）。顺序流行为
（首次 204 / 二次 400 / 无效 400 / 过期 400）与 Python 一致，由
`resetTokenSequentialOneTimeAndTtl` 覆盖。

## 撤销/改密前后请求（验收 C）

| 用例 | 撤销前 | 撤销/失效动作 | 撤销后 |
|---|---|---|---|
| tokenStoredAsSha256OnlyAndRevocationIsImmediate | resolve 命中（60s 缓存内） | logout（revoke_token + 清缓存） | resolve 空；change-password 401 |
| revokedTokenStaysRevokedAfterReEnable | — | logout → 停用 → 清缓存 → 重启用 | resolve 仍空（撤销不可逆） |
| changePasswordKeepsCurrentRevokesOthersAndRotatesCredentials | token1/token2 均命中（缓存内） | 持 token1 改密 | token1 仍命中；token2 空；旧密码 401、新密码 200；must_change 清除 |
| expiredTokenFails | — | 直接落过期行 | resolve 空 + HTTP 401 invalid token + WWW-Authenticate |
| credentialInvalidationPortSemantics | token 命中 | 管理员设密/重置初始密码 | token 全撤、must_change 置位、旧密 401 新密 200 |

## bcrypt Python→Java 兼容

`pythonBcryptFixtureVerifies`（通过）：fixture（Python bcrypt 4.3.0 生成，固定盐，
含 cost12 样本与 $2a/$2y/$2b 交叉验证记录）逐向量 Java checkpw 通过、错口令失败；
`loginSeedWithPythonFixtureHashWorks`（通过）：Python $2b$04$ 哈希直接落库后
HTTP 登录成功（读兼容全链路）。token sha256 摘要向量一致。
