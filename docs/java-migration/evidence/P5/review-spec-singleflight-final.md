# SingleFlight 最终规格轴窄复核

BASE=`ff59cdab65c2c8977f3f9c45701b773ad9116ccd`；TARGET=`2a950d7f660dba4b4ac0d70cf6e3485a0cde668b`。已核ref、非空三点diff及提交链；仅复核已定SingleFlight修复，不扩展性能或其他业务。业务只读，未构建、测试、访问PG/HTTP/Docker，未提交。

**结论：本片无规格阻断；发布顺序与clear身份闸符合冻结Python，可以按窄范围收口。P5总门禁仍未通过。**

- **终态发布已收口。** 冻结 `backend/app/core/async_cache.py:32-54` 把bookkeeping放在共享task返回之前。`backend-java/src/main/java/io/dpdk/assistant/model/SingleFlightTtlCache.java:71-88` 以raw驱动内部managed future，先执行onPopulated再完成managed，消费者只观察shielded view；因此失败后回调内立即重试不会重新取得旧failed flight，成功回调内已可命中缓存。population失败也会发布异常，避免managed悬挂。
- **clear迁移差异已关闭。** 源成功缓存写入也受current-task identity保护；`SingleFlightTtlCache.java:91-109` 对删除inflight和成功写值使用同一identity闸。clear后的旧loader不回填、不覆盖新值、不删除新flight；但仍经managed完成原waiter。`clear():125-128`没有取消旧外部执行，保持源语义。J5-CACHE已独立登记这是源对齐，不是新增安全/产品豁免。
- **兼容边界保持。** TTL从成功收口时起算、访问序LRU及maxsize逻辑未改；view取消不传播到raw/sibling。同步factory抛错仍同步传播；null future在登记前拒绝，不遗留managed。未改公开方法、usage归属、缓存调用次数、任务/成绩/权限、黄金或阈值，也没有Rust/JNI生产集成。

已独立阅读两组确定性回归：CompletionOrder五项覆盖终态回调内重入/size、同步完成、取消隔离、null/同步throw；ClearIdentity四项覆盖旧成功、旧/新两种完成顺序、旧失败与原waiter。均以未完成future/闩锁固定交错，没有sleep碰绿。`ci-singleflight-publication.md`保留CI真实失败、发布组2红及clear组3红，并绑定fresh109/109；本审查未重跑或将单卡数写成全量。

主Agent后续fullverify、同jar CLI、full08/扩展/反例、fresh16域因果及README读者结果须继续按各自源码/工件绑定；3项boundary skip不计通过。本复核不审判容量：正式容量仍未达且未完成；J5-03实际业务部署/恢复/回退场景未跑。这些未执行门禁及用户/live/生产放行不能由本次缓存关闭代签。
