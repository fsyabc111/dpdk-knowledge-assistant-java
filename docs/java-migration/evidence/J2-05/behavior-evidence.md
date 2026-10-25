# J2-05 行为证据（验收 A–G）

执行环境：MockMvc standalone + 独立测试 PG（动态建删库，`DPDK_TEST_PG_URL` 指向
p1 合成实例）；TestClock 墙钟 `2026-10-24T04:00:00Z`。命令模板与结果见
handoffs/J2-05.md 验证表。

## 验收 A：会话可见性与排序（SessionQueryContractTest）

- 列表只含本人（student_a 5 个 golden 会话按 updated_at DESC, id DESC）；
  他人/不存在会话统一 404 `session not found`（foreign-tree golden 用例）。
- 最近 N 条：`limit=2` 时取**最后** 2 条消息且 `truncated=true`（msg_b_02/03）。
- 取消消息：历史展示路径（include_cancelled=true）保留 `answer_status=cancelled`
  终态；会话记忆口径（getMessages(false)）过滤（2/3 条）。
- metadata 字段：confidence 覆盖默认（high）、answer_status/answer_reason/
  issue_id/request_id/task_id 原样透传；缺省 confidence=medium、其余 null。

## 验收 B：删除与 release 绑定

删除前后摘要（SessionLifecycleTest.goldenMaintenanceThenDeleteFlow，种子
SESS_DEL 含 2 消息 + 1 引用 + 1 成绩行）：

| 表 | 删除前 | 删除后 |
|---|---|---|
| assistant_sessions(id=SESS_DEL) | 1 | 0 |
| assistant_messages(session_id=SESS_DEL) | 2 | 0 |
| assistant_citations(message_id=msg_del_02) | 1 | 0 |
| grading_submissions(id=gra_del_01) | 1（session_id=SESS_DEL） | 1（**session_id=NULL**） |

- 他人删除 → 404 且他人会话不受影响；local 模式删除跳过归属检查（独立 local 库验证）。
- release CAS：并发两线程（ThreadLocal 候选 rX/rY）读到**同一赢家**且与库中值一致；
  active 变更后旧绑定不漂移（新 SessionService 实例同读 r1）；删除重建（请求同 id
  得新 id）重新绑定当前 active r2；无活动发布时未绑定会话返回 empty；会话不存在抛
  IllegalStateException（源 ValueError 同为内部错误路径）。

## 验收 C：来源回读（SourceTemplateContractTest + SessionQueryContractTest）

- `/api/sources`：li_ 前缀走 `data_li_knowledge_v1` 直查（node_id/text/metadata_，
  title 回落 source_path）；非 li_ 走 knowledge_chunks LEFT JOIN knowledge_documents
  （无 title 时同样回落）；两路径缺失统一 404 `source chunk not found`。
- 历史引用顺序：乱序写入 rank 2/1/3 → 回读恒 rank 升序（不按当前检索重排）；
  缺失来源行占位空字段（source_path/title/section=""），不报错不重排。

## 验收 D/E：用量视图（UsageQueryContractTest、QuotaTransactionTest）

角色 × 端点矩阵（account 模式，树：leader→teacher A/B→各自学生）：

| 主体 | /usage/me | /usage/subjects | /usage/cost | /grading/* |
|---|---|---|---|---|
| viewer（学生） | 自己 7 天计数 | 404 | 404 | 404 |
| teacher（admin） | 自己 | 本人+自建学生（A1/A41/A51），不见 B 树/未分配/遗留主体 | 默认回显可见主体集（排序）；B 树 subject → 404；B 树 request_id → 空账 | 仅自建学生（**不含本人**提交） |
| leader | 自己 | 全部 8 主体 | 全部（subjects=null） | 全部 5 行 |
| operator | 自己 | 404（非 admin/leader） | 404 | 全部（grading 口径不受树限） |
| 静态兜底 token（admin） | emergency-admin | 全部 | 全部（subjects=null） | 全部 |

- 日期窗口：days=1 排除 2 天前账行（created_at UTC 文本比较，滚动窗口非自然日）。
- request_id 只收窄：树内精确匹配 1 行；他人树 request_id → 空账（不可越权）。
- 空账本：totals 各 token 字段与 duration_ms 均 **null 非 0**；known_entries=0；
  未知 request_id 同为空账。
- 未定价模型：`unpriced_models=["unpriced-model"]`，cost/currency/price_version=null。
- 非 token 操作：operations 分列（entries=2, calls=3, duration=220, priced=false），
  不进 totals/by_model。
- grading 计数不进聊天面板（/usage/me 只统计 kind='chat'）。
- 遗留主体（不在 users 表）：roles=[]、daily_quota=默认 10000、计数照常聚合。

配额/账本数据面（QuotaTransactionTest）：

- quota=0：countAndCheck 与独立版均不记行。
- 事务内超额：第三次 countAndCheck 抛 `QuotaExceededException`（detail=
  `daily chat quota exceeded (2/day)`、Retry-After>0），同事务先行的 marker INSERT
  一并回滚；计数保持 2；kind 维度独立（grading 与 chat 分行）。
- 并发 8 线程同 (subject, day, kind) 增量：PG upsert 原子串行化，终值 8 无丢失；
  第 9 次越限回滚。
- 独立版 checkAndCountDaily：超额抛出时计数已提交（2 留存）——源现状，登记差异。
- 写入面（UsageCore→LedgerStore）：定价在写入时完成（per-1k 单价 1000*0.0008+500*0.002
  → 1800 微单位）并盖 price_version；未定价不出价；unknown token 保持 null；
  零调用 call_count=0 留痕；operation 单列；重复 id 主键冲突如实抛出（不静默丢账）。

## 验收 F：模板与成绩只读

- 模板：protocol 必填（缺失/非字符串/空体/非对象/非法 JSON 分别 422：missing/
  string_type/missing@body/model_attributes_type/json_invalid）；空白协议 →
  MyProtocol/myprotocol；template_type/difficulty/requirements 只校验不改变输出；
  多余字段忽略；symbol 归一（My Protocol! → my_protocol；全非法 → myproto；
  中文按 Unicode 字母小写保留）。
- 成绩：明细字段最小集（无 results_json/code_hash）；newest-first（created_at DESC,
  id DESC）；limit 截 500、offset 分页；非 account 模式与 viewer 一律 404；
  by_user（count/avg/best/last）与 by_protocol（passed/total 分子分母聚合、去重学生数）
  班级隔离；与作业汇总不混用过滤逻辑（本端点不识别 assignment 参数，等值过滤
  user_id 对别班主体返回空列表而非 404——过滤语义与门禁语义分离）。

## 验收 G：维护端点与配额原子性

| 请求 | 结果 |
|---|---|
| key 未配置（任意 header） | 503 `maintenance endpoint is not configured` |
| key 已配置 + 无/错 key | 403 `forbidden`（恒时比较） |
| key 正确 GET session-storage | 200 {storage_bytes>0, session_count, database_backend=postgres} |
| purge 默认（dry_run=true） | 200 只计数不删除（sessions/messages/citations 全量） |
| purge dry_run=false | 200 仅删过期（旧会话三表清空，近期保留）；retention_days 覆盖默认 30 |
| retention_days=0 | 422（先于 key 校验） |

配额原子增量与失败回滚见上文 QuotaTransactionTest；ledger unknown 可验证
（查询口径 + 写入口径双侧断言）。
