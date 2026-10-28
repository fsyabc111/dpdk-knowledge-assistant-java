# SingleFlight 最终窄修：独立规范轴

2026-10-28。已核实 BASE=`ff59cdab65c2c8977f3f9c45701b773ad9116ccd`、TARGET=`2a950d7f660dba4b4ac0d70cf6e3485a0cde668b`，读取固定diff/log。复核范围仅唯一生产改动`model/SingleFlightTtlCache.java`、两个新增model测试、J5-CACHE登记与`ci-singleflight-publication.md`；不声称审阅区间内全部交付/许可材料。本复审未新增测试、构建、PG、HTTP、Docker或排障，不修代码、不提交。

## 阻断与源码判断

**无新增规范阻断，支持此窄修规范轴通过。**

- `SingleFlightTtlCache.java:71-85,91-109`用独立managed future作为唯一消费者发布面；先执行当前flight的移除/成功缓存写入，再完成managed，失败重试和成功命中不再依赖raw回调的注册顺序。population自身异常也完成managed异常，不留永久pending。符合`ARCHITECTURE.md:73`的显式原子状态约束及`interface-changes.md/J5-CACHE`冻结范围。
- `:95-98`的identity闸同时保护移除和成功写入，对照冻结`backend/app/core/async_cache.py`的populate分支一致。clear仅清values/inflight，不取消旧raw；旧managed仍发布原值/错误，却不能回填或覆盖新值、移除新flight。该迁移差异已由集成者独立登记授权，没有借修复改变TTL/LRU/maxsize或黄金。
- 同步factory throw/null在`:71-74`登记前退出；失败不缓存，后续fresh调用可重试。`:112-122`为每个caller创建shielded view，取消只影响该视图，不取消raw/其他waiter。
- 异步onPopulated释放monitor后才complete managed；同步已完成raw可能在登记时内联处理内部状态，但此时尚无已发布消费者视图，`:88`在外层锁退出后才挂view，故不会把外部消费者回调带入cache锁。未新增锁内业务回调、SQL、日志正文或原始输入保留。

## 回归与气味

`SingleFlightCompletionOrderTest`在终态callback内部立即重入/读size，并用闩锁阻止producer退栈；另覆盖同步完成、同步throw/null、view取消。`SingleFlightClearIdentityTest`覆盖旧成功、旧/新两种完成次序及旧失败，不用sleep碰绿。原失败用例和用量attempt/unknown/cache-hit/0-call断言未被删除或放宽。证据保留了发布顺序2红、clear身份3红及最终109项局部绿；这些为实现者实跑，本复审只核源码/证据。

完整Fowler baseline未发现应单列的可行动`possible`建议；managed转发层是因果发布屏障所必需，不作为Middle Man删去。

## 门禁边界

本turn前段独立README构建已有本次789发现/786执行通过/3 owned-boundary skip及新jar证据，见J5-04报告；不能累加旧BootJar XML或继承旧clock。本窄审不代签fresh矩阵、远程CI、正式容量、部署恢复或P5总门禁。**补丁规范关闭不等于批次完成，用户/live/生产验收仍独立。**
