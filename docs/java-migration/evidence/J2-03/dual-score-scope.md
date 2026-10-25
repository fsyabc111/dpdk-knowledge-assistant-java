# J2-03 受理/汇总双口径样例（验收 E）

端口：`assignment.catalog.AssignmentCatalogPort`（J2-04 消费）；实现 `AssignmentCatalogService` → `AssignmentCatalogRepository`。

## 分界定义

- **受理口径**（`submissionSnapshot`）：单条 SQL（assignments LEFT JOIN assignment_items ON protocol）在受理时刻读取作业与目标协议项分值。status≠active、scope≠all 且创建者非本班老师、作业不存在 → Optional.empty；协议不属于该作业 → points=null。J2-04 以此构造 accepted_assignment（points/scope/created_by/deadline/assignment_updated_at/accepted_at）与提交响应 earned/best_earned。
- **汇总口径**（`getAssignment` 当前定义 + report 汇总）：按当前 assignment_items 动态折算 `earned = round(best*points/100, 1)`（Python HALF_EVEN、二进制浮点现状：BigDecimal(double).setScale(1, HALF_EVEN)）。受理后修改作业可产生预期差异。

## 实测样例（AssignmentReportContractTest.dynamicRecalculationVersusAcceptedSnapshot）

1. 作业 dns=20 分；学生提交 dns 80 分；受理端口读到 points=20；成绩行内嵌受理快照 `accepted_assignment.points=20`（合成落库，对位 grading_submissions.results_json 现状）。
2. 老师把作业项改为 dns=40（协议项整体替换，提交记录不动）。
3. 汇总（GET /grades）：`scores.dns = 32.0`（80×40% 动态折算）；受理快照在库内仍为 20（`results_json->accepted_assignment->points` 断言）。
4. 下一次受理端口读取 points=40（新受理按新分值）。

即：`受理响应分值 = 冻结的 accepted_assignment.points`，`成绩表/CSV 分值 = 当前 items 动态折算`，两者并存且可解释差异——与 Python `test_submit_keeps_acceptance_points_when_assignment_changes_during_grading`（20 受理 vs 32 汇总）语义一致。

## 计分语义抽查（PythonScore）

- calcEarned(80,20)=16.0；calcEarned(75,30)=22.5；round1(2.25)=2.2、round1(2.35)=2.4（HALF_EVEN）。
- 未提交（best=null）earned=null（学生视图 W4，与 0 分 0.0 区分）；汇总 total_earned 聚合 null 按 0 计。
- roundToInt 供 J2-04 `score=int(round(float(score)))`。

## 汇总其余实测（验收 D）

- dns 三次 80/100/50 → best=100、earned=20.0、提交次数计 3+1（http 一次）=4、last=max(各协议 last)。
- 聊天路径（assignment_id=NULL）不计入任何作业；不同 assignment_id 不串分（作业乙 dns 90 不影响作业甲）。
