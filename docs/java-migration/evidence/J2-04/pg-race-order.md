# J2-04 PG 停号×成绩落库竞争顺序（验收 E）

竞争面：`AuthAccountWriteGuard.lockEnabled`（业务写入事务内
`UPDATE users SET enabled=enabled WHERE id=? AND enabled=true RETURNING id`）
与停号事务（`UPDATE users SET enabled=false …`）争用同一 users 行；
参与者不独立提交（共享 TransactionRunner 事务），先提交者裁决。

## 用例 1：停号事务先提交（GradingPersistenceRaceTest.disableFirstWinsGradeRejected）

1. 连接 A（autocommit off）执行守卫同款 UPDATE（持锁）+ `enabled=false`，不提交。
2. 主线程提交 `recordSubmission`（事务 B）：`lockEnabled` 的 UPDATE 在行锁上排队。
3. 主线程 sleep 200ms 确认 B 未完成（Future.isDone=false）。
4. A 提交 → B 的 UPDATE 经 EvalPlanQual 重评：行已 `enabled=false`，`WHERE
   enabled=true` 不命中 → RETURNING 空 → 回查行存在 → 返回 false →
   ApiDetailException(403, `账号已停用，本次评测不记成绩`)。
5. 终态：账号 disabled、grading_submissions 0 行。

## 用例 2：成绩事务先提交（gradeFirstWinsThenDisableApplies）

1. 事务 G（autocommit off）：守卫同款 UPDATE（RETURNING 命中）+ 插入
   grading_submissions 一行，不提交。
2. 事务 D（独立线程）：`UPDATE users SET enabled=false` → 排队等待 G。
3. `pg_locks WHERE NOT granted AND locktype='transactionid'` 轮询断言 ≥1
   （D 确在等待 G 的提交序），随后 G 提交、D 提交。
4. 终态：成绩行保留（不删已落成绩）+ 账号 disabled——两个先后续都成立，
   谁先提交谁赢。

## 用例 3（端到端）：评测中途停号（disableDuringGradingRejectedAtRecordGate）

- 脚本钩子在 MCP grade_plugin 调用内停号 → 提交请求 403（成绩 0 行）、
  MCP 1 次、配额 +1：停号发生在「资格检查后、落库前」时，唯一门禁是
  受理事务内的 lockEnabled——被停号用户不能绕过落库门禁。

## 用例 4：已停号账号（disabledAccountTokenRejectedAtAuth）

- 停号后登录/token 解析 401（J2-01 resolveToken 的 enabled 过滤），请求不进入
  提交业务面；403 分支仅在「请求开始后停号」的竞争窗口可达（与源一致）。
