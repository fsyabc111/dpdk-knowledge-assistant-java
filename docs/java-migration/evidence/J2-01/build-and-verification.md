# J2-01 构建与验证记录

- 工作树：`.migration-artifacts/worktrees/j2-01`（分支 worker/j2-01）
- 源基线：冻结 Python 副本 HEAD 80b2913（COPY_REPORT）；Java 基线 ce6a139（J1-01 集成后）
- 环境：JAVA_HOME=/opt/homebrew/opt/openjdk@21（Java 21）；PG=独立测试实例
  （env DPDK_TEST_PG_* 提供，data_dir=仓库内 .migration-artifacts/p1/pgdata，
  库名 p1_t_j201*_<hex>，comment=java-migration-p1-test，随测随删）

## 命令与结果

| 命令（工作目录=工作树根） | 退出码 | 结果 |
|---|---|---|
| `./backend-java/mvnw -f backend-java/pom.xml -B -q compile` | 0 | main 编译通过 |
| `./backend-java/mvnw -f backend-java/pom.xml -B -Dtest='AuthContractTest,CredentialLifecycleTest,LoginThrottleTest' test`（含 DPDK_TEST_PG_* env） | 0 | AuthContractTest 13/13、CredentialLifecycleTest 14/14、LoginThrottleTest 6/6，共 **33/33 通过** |
| `./backend-java/mvnw -f backend-java/pom.xml -B test`（全量） | 0 | **183/183 通过**（P1 既有 150 + J2-01 新增 33），无既有用例破坏 |

报告：`backend-java/target/surefire-reports/TEST-io.dpdk.assistant.auth.*.xml`。

## 提交序列（分支 worker/j2-01，未 push）

1. `4fd4f75` 步骤1：端点契约矩阵登记 + Python bcrypt 4.3.0 兼容 fixture（固定盐、
   含 cost12 样本与 $2a/$2y/$2b 交叉验证）。
2. `cc8b677` 步骤2-4：auth 包实现（AuthServerSettings/AuthSettingsProvider/PasswordHasher/
   TokenMint/TokenPrincipalCache/UserAuthRepository/SlidingWindowRateLimiter/LoginLockout/
   BearerTokens/AuthPrincipalResolver/AuthAccountWriteGuard/PythonJsonScanner/PydanticBody/
   AuthController/PasswordRuleException/CredentialInvalidationPort/PasswordResetApprovalPort）。
3. `d77bbf7` 三个测试类 33/33；修正 change-password 401→422 顺序契约（golden 佐证）与矩阵。
4. （本提交）evidence 四件 + handoff。

## 测试覆盖 → 验收映射

| 验收 | 覆盖用例 |
|---|---|
| A 不存在/错密/停用同 401；锁定对不存在账号生效 | AuthContractTest.acceptanceA_* |
| B 每 IP 限额/5 次锁定/重置独立限额/窗口边界+Retry-After | LoginThrottleTest 全部 6 用例（可控单调时钟：61/31/1/901/801 精确值、60s 与 900s 边界） |
| C 退出立即失效/过期失败/改密保当前撤其他 | CredentialLifecycleTest.tokenStored.../expiredTokenFails/changePasswordKeeps... |
| D 202 防枚举/幂等/一次性并发 | CredentialLifecycleTest.resetRequestIsAntiEnumeration.../resetTokenSequential.../resetTokenConcurrent... |
| E 静态兜底/Bearer 大小写/未知缺失 token/must_change 信息位 | AuthContractTest.acceptanceE_*、accountModeHiddenBehind404InOtherModes |
| F bcrypt 等价工作（不以耗时证明） | PasswordHasher.dummyCheck 与 checkpw 同路径（代码路径断言）+ CredentialLifecycleTest.verifyCredentialsRunsDummyBcrypt... |

## 差分 suite 说明

单卡差分命令模板要求 `<J0-02_ORACLE_RUNNER> --suite J2-01 --baseline <PINNED_ORACLE>
--candidate <ISOLATED_JAVA_URL>`：本卡以"golden 19 条 observation 逐字段断言 + 冻结
venv 探针固化 422/JSON 语义"等价覆盖（差异零或逐项登记于 golden-diff.md）；运行态
oracle runner 对接（需要真实候选服务 URL）未在本卡执行，登记为未验证项。
