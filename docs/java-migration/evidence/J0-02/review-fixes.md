# J0-02 两轴审查修复记录

原审查报告保持原样；本文只记录修复与补验，不自行放行P0/P1。

## 纯内存阶段

未导入app、未连接PostgreSQL、未运行动态oracle。正式长测第2轮期间仅实施和运行纯内存/CLI测试。

| 审查项 | 修复 |
|---|---|
| Spec S1 | `business_state.py`显式白名单SQL；一次完整业务初态、逐case每表实际removed/added业务行及前后count、完整终态。`validate_business_chain`可重建每步状态，不能以总行数或whole DB hash代替。包含users树/角色/enabled、token生命周期元信息、session/release/message字段指纹/metadata与引用rank、usage实际扣次、upstream unknown/费用类型、作业items/成绩/accepted快照、task status/purpose/input元信息/job result及submission_done、knowledge/revisions。10项PG专例+清理、raw检索结果/六引用内容进入trace。 |
| Spec S2 | 正式提交后增加teacher非空GET assignments，断言submission_count/student_count；再取student非空视角并核对80分→16.0折算。动态待长测后运行。模型枚举修复由J0-01负责。 |
| Spec S3 | SSE严格匹配冻结React LF空行帧，不再CRLF/CR改写；保留UTF8逐字节分块正例。 |
| Spec S4 | 合法error-only/start→error可以结束，明确标为error；payload需message/bool retryable，结束后任何事件失败。缺done/error和截断done仍失败。 |
| Standards S-01 | task/session/message/assignment/grade/knowledge等先按字段和实体格式校验再双射；不能通过全部关联替换跨类型身份。固定调用者和用户保留。code/input SHA逐值；运行时实际字节hash与PG持久化断言一致。 |
| Standards S-03 | CLI显式`--fixture p0-v1|p0-v2`；顶层/observations/SSE/delta容器、嵌套深度/节点数、非有限数值和非法日历统一输入错误2。业务值差异1；稳定JSON输出，不吞程序bug。 |

纯内存命令：

```sh
PYTHONDONTWRITEBYTECODE=1 .migration-artifacts/p0/venv/bin/python -m unittest migration.oracle.test_review_fixes -v
PYTHONDONTWRITEBYTECODE=1 .migration-artifacts/p0/venv/bin/python -m migration.oracle.verify --fixture p0-v1 --candidate .migration-artifacts/p0/candidate-trace.json
```

当前结果：7组通过；包含9种“HTTP/行数相同、业务值错写”的反例、跨类型全部关联替换、全部code_hash一致替换、两个Manager字段缺失、error流正例/CRLF和截断反例，以及CLI合法JSON非object、非法日历、2000层嵌套返回2/业务差异1。旧v1实际raw候选依然通过；未覆盖原v1黄金。

## 待动态补验的准确命令

只能在主Agent确认正式长测进程退出后顺序执行：

```sh
.migration-artifacts/p0/venv/bin/python -m migration.oracle.run --fixture p0-v2 --database p0_python_oracle > .migration-artifacts/p0/oracle-python-p0-v2.log 2>&1
.migration-artifacts/p0/venv/bin/python -m migration.oracle.run --fixture p0-v2 --database p0_candidate_oracle > .migration-artifacts/p0/oracle-candidate-p0-v2.log 2>&1
.migration-artifacts/p0/venv/bin/python -m migration.oracle.freeze --fixture p0-v2
.migration-artifacts/p0/venv/bin/python -m migration.oracle.verify --fixture p0-v2 --candidate .migration-artifacts/p0/candidate-trace-p0-v2.json
.migration-artifacts/p0/venv/bin/python -m migration.oracle.check_account_disable --database p0_candidate_oracle
```

动态风险：白名单SQL字段需实际PG核验；异步受理after已明示settled checkpoint；事务组固定wallclock不承担消息单调时钟测试，HTTP组仍走真实now_iso单调保护；最终双库新的fixture、业务值反例、manifest、非空列表、有效token停用与复审尚未完成。当前不宣称审查问题全部关闭。
