# 备份恢复完整行证明修正（准备）

来源：首轮规范审查review-standards-first.md第3项。旧执行器只核11表count及active release，却把RPO描述为all retained business rows equal；该描述证据不足，当前修正不代表实际恢复已通过。

- 新business_proof动态枚举全部public表（含新增表/空表）；在单一REPEATABLE READ READ ONLY快照内，每行to_jsonb(t)::text canonical键顺序→SHA256，按固定C排序行摘要拼接再SHA256。摘要保留重复行，不依赖PK或扫描顺序；UTC/extra_float_digits=3固定显示域。
- 每表比较完整行count、multiset SHA256与列结构SHA256。后者涵盖ordinal/name/type/null/default/identity/generated；rag_task_inputs不计算数据摘要，恢复必须0行且列结构相同。表清单缺失/新增/改变立即失败。
- 输出只有表名/计数/hash/固定域；原始行、源码、密码哈希、token不出响应或日志。动态表标识严格检查；owned数据库/实例先验仍在任何SQL之前执行。原TOC排数据保结构、空库恢复、长期源码哨兵守卫不变。
- 不扩称完整catalog相等：sequence状态、约束、索引定义不由此行摘要证明，另需迁移/恢复业务验收。当前尚未启动stack、运行dump/restore或SQL。
- 纯测22/22，包括同count但score80→81拒绝、新增表缺失拒绝、临时数据泄漏/结构差异拒绝、重复行/扫描排序域、owned先验拒绝、摘要输出无正文。SQL真实执行待短窗口。

## 已授权native PG局部SQL验证

CPU窗口释放后，主明确授权轻量独立owned PG。proof_sql_smoke使用公共PgIsolation逐写前验证loopback实例/库名/role/data_dir/comment与owned manifest，新建合成3表（其中grade表含已DROP列空洞）；实际执行相同生产摘要SQL。保留行相等、tmpinput数据排除后的列结构相等、同count但score80→81的摘要差异拒绝均通过。仅输出digest/count，无原始行/源码/token。动态库按owned清理，未触碰Docker或恢复资产。详见backup-row-proof-native.json。此结果只验证摘要SQL执行，不是pg_dump/pg_restore或冷启/升级/回退演练通过。
