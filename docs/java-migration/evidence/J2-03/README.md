# J2-03 证据索引（作业定义、成绩汇总与 CSV）

- 基线：bed823a（worktree worker/j2-03）；源 `backend/app/api/assignments.py` + `services/assignment_service.py`（冻结只读）。
- fixture：`migration/fixtures/p0-v2/python-golden.json`（J2-03 路由切片 28 条 observation 已提取到 `backend-java/src/test/resources/migration/J2-03/golden-assignments.json`）。
- 测试：AssignmentCatalogContractTest 13、AssignmentReportContractTest 4、CsvExportContractTest 5；全量 `./backend-java/mvnw -f backend-java/pom.xml -B test` = 240/240（既有 218 未破坏）。

| 文件 | 内容 |
|---|---|
| golden-diff.md | 28 条 observation 的逐条比对口径（逐字节 vs 归一化映射）与有意差异登记 |
| multi-class-roles.md | 多班级（A/B/未分配）× 角色（leader/operator/admin/viewer/静态 token）可见性与权限矩阵结果 |
| dual-score-scope.md | 受理快照与汇总动态折算双口径样例（验收 E） |
| csv-bytes.md | CSV 原始字节摘要（golden sha256/length 逐字节一致）、解析结果与字节级规则验证 |
| pg-rollback.md | PATCH 协议项整体替换的事务回滚记录（重复协议触发 UNIQUE 冲突） |
