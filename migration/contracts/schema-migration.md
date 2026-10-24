# Schema 清单、接管策略与单一 owner

`static-inventory.json.production_tables` 列出 27 项应用固定表 + 动态 LI 表 + Alembic 版本表，共29项。`ddl_operations` 提供所有生产 SQL/Alembic/PG 适配的 CREATE/ALTER/INDEX/EXTENSION 源位置与定义片段；**不是可直接拼接执行的迁移脚本**。SQLite 只作非生产行为对照，不用于证明 PG 类型/锁/约束兼容。静态映射100%只证明无表/配置漏项，不证明实际生产 schema 已核对。

每表业务 owner/java_target 在 coverage-map；唯一 DDL/迁移 owner 是 **主 Agent（/root，后续 J1-01 集成窗口）**，已登记 STATUS/DECISIONS。P0方案确定为**单一Flyway迁移链**，不让Alembic/Flyway同时持有一个运行库；本轮只交付策略，不创建Java DDL、不stamp Java baseline、不运行三条Java路径。

选型依据见 `docs/java-migration/evidence/J0-01/java-stack-selection.md`：Java21 + Boot4.0.9 + AI2.0.2；Spring JDBC/Framework7.0.10、Flyway11.14.1、PGJDBC42.7.14按Boot BOM管理。原Boot3.5/AI1.1候选的社区维护/工具调用安全限制已记录，不作为默认回退。J1-01须在获得P1授权后固定JDK补丁/镜像、Maven Wrapper、有效POM与依赖树并实测构建；此处未把官方资料核对写成构建通过。Flyway PostgreSQL模块与core同版，Spring AI/vector store不得拥有业务DDL。

## 三条未来受控路径

| 起点 | 合成验证与目标 | 放行前必备证据 |
|---|---|---|
| 真空库 | 单目录包含 `B0015__baseline.sql` 完整baseline以及 `V0014` / `V0015` 等价增量；空库直接migrate选择最新B0015（数值版本15）。所有应用表、auth表、LI动态表、生成列、扩展、约束/索引/种子active行均纳入完整baseline | 完整catalog差分、默认/nullability/FK动作/索引谓词/生成表达式/向量维数、样例业务读取；重复migrate无业务变化；此路径留J1-01实际验证 |
| 明确构造的合成0013 | 独立身份与严格catalog核验通过后，由/root显式baselineVersion=13，再migrate执行V0014/V0015；已有baseline标记时B0015不执行。V14增加task_job_id及部分唯一索引；V15增加purpose并安全回填。保留旧input_hash=null，不猜测原件hash/成绩job关联 | 0013 fixture完整指纹、0014前已有submission_done=1但成绩无job关联、合法/伪造explain身份、迁移后结构/业务读取；不能只看alembic标签；实际链路未验证 |
| 等价0015 | 独立身份与完整结构/身份语义核验通过后，由/root显式baselineVersion=15；B0015及<=15的V不重执行，后续只走同一Flyway链。多余/缺失/不同约束均须显式解释 | 从纯合成实例导出的结构指纹、历史revision与LI库生成DDL快照、错误目标/错误catalog拒绝负例；不得把仅baseline或validate成功当结构证明；实际链路未验证 |

默认 `baselineOnMigrate=false`、`validateOnMigrate=true`、`cleanDisabled=true`；数据库身份/comment/data_directory预检必须先于Boot自动触发Flyway。显式baseline只登记版本，validate只检查迁移记录/校验和，都不能替代完整catalog核对。Flyway自有schema-history增加也由唯一owner在J1-01明确核对，不能据此放过任意多余表。上述B/V三路径依据锁定11.14.1源码/官方文档作设计，三路径实际执行、校验和与最终catalog等价仍留J1-01验证，不代表P0已运行Java迁移。

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

P0 已取得专用合成空库的当前29表与关键约束动态证据，见 `docs/java-migration/evidence/J0-02/auto/p0_python_oracle.json` 的 schema；此处不把空库验证扩展成0013升级验证。生产 schema、实际部署版本、旧数据迁入和生产切换全部未验证/未执行；P0/P1合成验证不能替代P5独立授权。
