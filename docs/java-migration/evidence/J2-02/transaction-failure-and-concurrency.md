# J2-02 事务故障与并发时间线（验收 F/G）

测试：`AdminDeletionTransactionTest.midTransactionFailureRollsBackDeletesAndAudit` /
`accountWriteGuardDecidesByCommittedState` / `concurrentGuardAndDisableSerializeOnAccountRow`。

## 1. 验收 G：删除中段故障整体回滚

- 装置：AdminUserRepository 的 SqlProbe（包内测试缝）在第二个目标的
  `delete-user-rows:U20260000072` 处抛 `SQLException("injected mid-transaction failure")`，
  异常发生在真实 PG 事务中段（第一个目标 U20260000071 的级联删除已在同一事务执行完）。
- 断言（SpringTransactionRunner 整笔回滚）：
  - users 行数回到删除前（71、72 均仍在）；
  - 71 的 usage_counters 行仍在（级联动作全部回滚）；
  - admin_audit_log 行数不变（审计与删除同事务，随之回滚）。
- 传播路径：SQLException → IllegalStateException("admin storage failure") →
  MockMvc standalone 向上抛 ServletException（真实容器为 500）；cause 链逐层断言。

## 2. 验收 F：停号与写事务按账号行顺序裁决（J2-02 侧）

### 2.1 已提交状态裁决（accountWriteGuardDecidesByCommittedState）

```
T0: users[U...91].enabled=true
T1: guard.lockEnabled(91)  → true（UPDATE...RETURNING 命中，行锁随即释放于提交）
T2: POST /users/91/status enabled=false → 204（单事务：enabled=false + 撤 token + 取消任务）
T3: guard.lockEnabled(91)  → false（提交后任何受理/记分写方被拒）
```

停号提交后，后续所有经 identity.AccountWriteGuard 的成绩落库/任务受理写事务
（J2-04/J3-01 消费）都按已提交行状态失败——账号行事务顺序即裁决顺序，
参与者不得独立提交（契约约束）。

### 2.2 行锁串行时间线（concurrentGuardAndDisableSerializeOnAccountRow）

```
T1(写方线程): runner.required{ guard.lockEnabled(92) → true; 持账号行锁等待 releaseTx }
T2(停号线程): UPDATE users SET enabled=false → 阻塞在 T1 的行锁上
T3(主线程):   确认窗口后 releaseTx → T1 提交 → T2 获得 行锁完成停号 → 204
T4: enabled=false；guard.lockEnabled(92) → false
```

两线程 + 双 CountDownLatch 实现，5s 超时；停号确实排队到写方事务提交之后完成。

### 2.3 交错窗口归属（integration_pending 项）

停号提交后、任务已在执行中的取消（本进程协程终止 + 临时输入清理）按源语义是
**提交后 best-effort**：J2-02 通过 LocalTaskCancellationPort 冻结该时序并已用记录桩
断言“停号成功 → cancelLocalForUser(userId) 恰好一次、异常自吞”；真实执行体归 J3-01
接线（见 integration-pending.md），本卡不宣称停号-任务取消集成已通过。
