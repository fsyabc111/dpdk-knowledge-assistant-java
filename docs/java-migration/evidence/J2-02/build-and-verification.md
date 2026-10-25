# J2-02 构建与验证记录

## 环境

- worktree：`.migration-artifacts/worktrees/j2-02`（分支 worker/j2-02，基线 c4f305a）。
- JDK：openjdk 21（/opt/homebrew/opt/openjdk@21）；构建 `./backend-java/mvnw -f backend-java/pom.xml`。
- 测试 PG（显式专用实例，测试类缺环境变量即跳过）：`127.0.0.1:55481`，维护用户 p1_java；
  每测试类 `CREATE DATABASE p1_t_j202< tag>_<nanotime>`（comment=java-migration-p1-test），
  结束 WITH (FORCE) 删除；data_directory 前缀 `.migration-artifacts/p1/pgdata`。
  库内经 MigrationController 施加 B0015+V0014 后建 fixture；未连接任何旧 .env/宿主默认库。
- 凭据/源码运行输入不入库：测试口令均为合成学号/固定串；日志与证据无 token 明文。

## 命令与结果

| 命令（工作目录=worktree 根） | 退出码 | 结果 |
|---|---|---|
| `./backend-java/mvnw -f backend-java/pom.xml -B -q compile` | 0 | admin 包 11 个主源码文件编译通过 |
| `./backend-java/mvnw -f backend-java/pom.xml -B -Dtest='AdminTreeContractTest,AdminDeletionTransactionTest,PasswordResetApprovalTest' test` | 0 | 35/35 通过（20+10+5），断言含 golden 36 条 observation |
| `./backend-java/mvnw -f backend-java/pom.xml -B test` | 0 | 218/218（既有 183 + 本卡 35，零回归） |

## 验收覆盖对照（任务卡 A–G）

| 验收 | 测试（类#方法） | 结果 |
|---|---|---|
| A 树隔离 | TreeContract#teacherListsOnlySelfAndOwnStudents / teacherCannotManageOtherTeachersStudents / golden viewer-hidden | 通过 |
| B 防提权/自保护 | TreeContract#apiCannotGrantLeaderRole / teacherCannotCreateTeacherOrOperator / cannotDisableSelfOrChangeOwnRoles | 通过 |
| C 批量与转班目标 | TreeContract#createUsersSkipsInvalidAndDuplicateIds / assignStudentsRules / golden invalid-input 5 条 | 通过 |
| D 删除密码确认/静态 token | DeletionTransaction#deleteRequiresCorrectPassword / staticTokenCannotDelete | 通过 |
| E 级联/成绩保留/审计隔离 | DeletionTransaction#deleteCascadesDataAndWritesAudit / deleteTeacherUnassignsStudents / auditLogScopedByActor | 通过 |
| F 停号交错 | DeletionTransaction#accountWriteGuardDecidesByCommittedState / concurrentGuardAndDisableSerializeOnAccountRow / disableCascadesTokensTasksAndCallsCancellationPort（取消端口时序；真实执行体 integration_pending） | 通过（库内部分）；跨模块取消项登记未完成 |
| G 中段故障回滚 | DeletionTransaction#midTransactionFailureRollsBackDeletesAndAudit（真实 PG 事务 + SQLException 注入） | 通过 |
| 重置审批 | PasswordResetApprovalTest 5 项（隔离/一次性/TTL/孤儿/规则文案） | 通过 |

## 未验证项

- 差分 suite J2-02（oracle runner）未运行（integration-pending.md IP-3）。
- Pydantic 非 golden 分支未与 oracle 逐字节比对（golden-diff.md §3）。
- 停号→任务取消真实执行体（IP-1）、auth 接口缺口（IP-2）。
