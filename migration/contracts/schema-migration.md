# Schema 清单、接管策略与单一 owner

`static-inventory.json.production_tables` 列出 27 项应用固定表 + 动态 LI 表 + Alembic 版本表，共29项。`ddl_operations` 提供所有生产 SQL/Alembic/PG 适配的 CREATE/ALTER/INDEX/EXTENSION 源位置与定义片段；**不是可直接拼接执行的迁移脚本**。SQLite 只作非生产行为对照，不用于证明 PG 类型/锁/约束兼容。静态映射100%只证明无表/配置漏项，不证明实际生产 schema 已核对。

每表业务 owner/java_target 在 coverage-map；唯一 DDL/迁移 owner 是 **J1-01 集成者，建议由主 Agent 预留**。P0 不创建 Java DDL、不 stamp Java baseline、不让 Alembic/Flyway 同时持有一个运行库。工具候选 Flyway 仍按主 Agent 决策记录和 P1 构建证明。

## 三条未来受控路径

| 起点 | 合成验证与目标 | 放行前必备证据 |
|---|---|---|
| 真空库 | J1-01 在专用 candidate 库创建经审核的完整 Java baseline；所有应用表、auth 表、LI 动态表、生成列、扩展、约束/索引/种子 active 行均需核对；重复执行应成功/明确无变化 | 完整 catalog 差分、默认/nullability/FK动作/索引谓词/生成表达式/向量维数、样例业务读取；不存在旧业务数据 |
| 明确构造的合成0013 | 按列/约束/数据精确定义0013 fixture，再执行0014 task_job_id部分唯一约束与0015 purpose安全回填的 Java 等价迁移；保留旧 input_hash=null，禁止猜测原件 hash/成绩 job 关联 | 0013 fixture完整指纹、0014前已有submission_done=1但成绩无job关联、合法/伪造explain身份、迁移后读写；不只是 alembic_version 字符串 |
| 等价0015 | 先逐项检查完整结构和身份语义；全部匹配才由唯一owner登记 Java baseline；多余/缺失/不同约束必须显式解释，不能盲目 baselineOnMigrate | 从纯合成实例导出的结构指纹、15个 revision链、LI库生成DDL快照、校验失败负例；Java不自动修旧库 |

`0001_baseline.upgrade()` 实际是 pass（`backend/alembic/versions/0001_baseline.py:25`），文件说明要求先 `init_db()` 建基础再 stamp。**它不读取当前 SQL 文件。** 但 oracle 若按当前 PostgresProvider.init 建库，当前 `postgres_schema.sql` 在已有 grading 表时会加入 task_job_id/索引（`:191`），`rag_schema.sql:24` 已含 purpose；因此“init_db + 标0013”不能代表真实旧0013。真实旧部署的完整 schema 仍未验证，禁止由 revision 名推断。

## 必保结构

- users/API token/密码申请的 bool/TIMESTAMPTZ/nullable 与外键；users.created_by 树关系；停号与写入锁使用同一事务。删除账号保留成绩的历史 user_id，无 grading→users FK，不能让 ORM cascade删除历史成绩。
- assistant_messages/citations 的 session/message 级级联；citation.chunk_id 跨 LI/legacy，不能恢复 legacy-only FK；engine+rank+score 必保。
- usage_counters PK `(subject,day,kind)`；upstream_usage_records token可空/整数成本/无敏感正文；quota=0 与null配置语义分别处理。
- rag_requests 两个唯一身份索引及 initial_request 谓词；purpose 默认chat；input_hash nullable（历史原件无法还原时保留）；status/时间字段的 TEXT 现状。
- rag_task_inputs 原文仅临时、终态删除、TTL24小时兜底、备份排除；rag_task_jobs `(task_id,job_id)` unique、submission_done；grading_submissions.task_job_id 部分唯一索引（非null）；不推导业务外部 exactly-once。
- assignment_items `(assignment_id,protocol)` unique、FK cascade；grading_submissions.assignment_id 与用户历史身份脱钩；现状 accepted_assignment 嵌在结果 JSONB，而非新增列。
- rag_knowledge_revisions `(knowledge_id,revision)` unique；知识revision和问题updated_at做条件更新；活动发布指针CAS、release成员和物理节点完整性。
- 旧 knowledge_*、teaching_faqs、knowledge_indexes 等表仍保留迁移去向，不能因当前 LI 召回不读它们而静默删除。legacy knowledge_chunks 仍供历史引用。
- LI `public.data_<LLAMA_INDEX_TABLE>` 由锁定库 `llama-index-vector-stores-postgres==0.2.6` 动态生成，源码适配固定 embed_dim=1024/use_jsonb/hybrid_search/text_search_config；列/ID/JSONB/vector/tsvector生成表达式及索引需独立合成PG catalog实测。不能将文档推测当已核对完整 schema。
- HNSW与zh FTS为条件变更。0010默认simple可no-op，revision已记录不表示zhparser已安装；simple替身不证明zh分词。开启条件变更需新受控迁移，不盲目downgrade生产。

当前仅有源码枚举/映射证据。生产 schema、实际部署版本、旧数据迁入和生产切换全部未验证/未执行；P0/P1合成验证不能替代P5独立授权。
