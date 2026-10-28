# Java迁移唯一任务状态

更新时间：2026-10-28。主Agent单写。**本轮按用户新指令收束，U12批次未完成，P5未放行。**

## 当前指令与边界

- 原U12允许P3→P4→P5隔离工程交付/J5-04准备，最多3 worker、小步提交及时推送；P3/P4工程门禁已通过。
- 用户于本轮17:38左右追加收束：30–60分钟内形成可审阅可恢复交接；停止JNI/Rust等新技术支线，保留原型/工具链/失败证据；不启动72分钟或其他长验证，不为凑完成新增排障/架构改造。仅收尾现有修复、必要验证、提交推送与资源核验。
- 已完成上述安全收尾；后续动作等用户新指令，不默认恢复JNI/Rust或正式容量。原始指令及过程状态保留于handoffs/archive/。
- 禁止项继续：真实模型/embedding/真实harness验收、合并main、生产切换、Python退役、删除恢复资产、原项目/部署/业务库写入。Agent不代签用户验收。

## 当前源码与代码验证

- 分支codex/p3-p5-longrun；当前stock Java生产源码固定 **2a950d7f660dba4b4ac0d70cf6e3485a0cde668b**，之后只有证据/交接。src/main tree **6e1ec8a514e9ea0151fbc9848b8e4c6f1e6be3e3**；Java subtree **e77ed11ba386eb047dbff10eed0e1a3f3f78ec43**。密码仍Spring BCrypt7.0.8/cost12，无Rust/JNI生产集成。
- CI40656367531曾暴露SingleFlight终态发布竞态；managed future先收口cache/inflight再唤醒、clear仅当前identity可填缓存已对齐冻结Python。确定性两组红保留；最终相关纯109/109，独立规范/规格窄复审无阻断。
- 当前完整verify本次 **789发现、786执行通过、0失败/0错误、3显式DefaultBoundary跳过**；没有累加旧BootJar XML。3项需新owned clock入口，未在当前源码补跑，不当pass。
- 新jar SHA **9393c8dd25101e0f83d814061d2cc38d4a8ea61efed20a7f198df382d7e6f5f3**，额外同jar真实CLI2/2、16子JVM通过。独立README读者另跑789/786/3skip、前端102/102+build、部署工具36/36及CLI帮助通过；仅build/test/help，不是完整部署复现。
- 最新代码CI **40662932995**固定2a950d7，unit/postgres/frontend/deployment-materials四job全部success，已实际查询。此前失败证据不覆盖。
- 权威摘要evidence/P5/validation-code.json；前版ff59摘要独立保留。原Python/backend、oracle、黄金和阈值本轮未改。

## 任务与阶段

| ID/阶段 | 当前实现与工程证据 | 尚未完成/用户状态 |
|---|---|---|
| P0/J0-01～02 | 冻结契约与oracle | U10用户通过保留 |
| P1/P2、J1/J2 | 已实现；纳入当前组合回归；R段8阻断已关闭 | 用户验收未登记 |
| P3/J3-01～04 | 5d0d6a3、591/591、两轴4阻断关闭 | 工程通过；用户未验 |
| P4/J4-01～03 | f020efa、707/707、CLI2/16JVM、CI四job与双轴通过 | 工程通过；用户未验 |
| J5-01 | 当前stock源fresh full08：186观察/198步/25表；原raw CLI rc1仅metrics runtime4差异，限定政策后全trace相等；225扩展/原17及policy28反例全合预期；实际16领域过程指标25断言重采通过 | 历史27保存浏览器场景各自绑定旧源，未在最新缓存源再跑；不以policy结果冒称原CLI DIFF=0；用户未验 |
| J5-02 | 历史ff59生产树对应all02完整14场景（11SIGKILL+clock/41queue/lifecycle）通过；旧Python正式3轮有效但协议指纹与修helper后的候选不同，不能配对；当前Java登录充分预热仍超冻结门槛 | 最新源码clock/故障重绑未做；同协议完整双端正式未跑；容量未通过；用户未验 |
| J5-03 | 备份活动库守卫/OCI身份链修复已测，工具36纯绿；P4真实两归档完整blob/diffID/jar/assets与原SHA重验通过且manifest不变 | P5候选image未构建；cold/0013升级/真实dump恢复/应用回退均未执行，不能过门禁 |
| J5-04 | Maven100/npm213声明与原文索引，7锁定容器库存及1390许可文件原字节归档；README独立build/test/help已验证；恢复/限制/停止支线交接已交 | 最终交付manifest、完整独立部署复现、最终P5阶段签认未完成；用户验收/切换/退役另行授权 |
| P5总门禁 | **未通过** | 不以局部绿、登记差异或未验原型代签 |

## 当前阻塞与未执行

- 冻结login有效p95上限312.443ms（绝对500ms也须同时满足）；Java原配置120s预热+30s诊断p95900.224ms，窄错峰无采样997.596ms；串行233.091ms。Python同窄driver错峰约235–279ms；JFR支持bcrypt并发CPU成本放大。短资格入口/正确性通过不等容量通过，短CPU峰值也不当正式600s资源判定。
- 四纯Java实现/JIT内联/线程调度探索没有足够收益，均未采用。bcrypt12、冻结阈值、原负载、测量分母均未放宽。正式同协议三轮双端未执行，不能宣称正式容量已判pass或已跑完。
- JNI/Rust同源适配仅ignored源码草稿，未编译/互验/性能验收/生产集成。隔离Rust1.99.1准备未就绪（cargo文件有、rustc缺失）；所有下载/安装安全退出，partial/日志/来源/恢复命令保留。未经新指令不继续。
- J5-02不通过及本轮收束阻止继续实际J5-03；其准备/归档验证不能替代业务演练。最终交付与完整用户接手仍缺上述强制证据。

## 本轮资源终态

主Agent在专属PG55481只读核验role/data_directory/端口、确认其他client0后smart停止该实例；恢复/故障/容量/诊断资产及数据目录/备份未删；普通临时测试库按已有owner生命周期清理并记录。java-u12与旧dpdk均Stopped，全局Docker context保持colima-dpdk-ext；本轮应用/Chrome/测试/下载进程均已退出。所有恢复资产与旧worktree保留，不做清理。精确路径、后续条件与命令见handoffs/MAIN.md；最终本地/远程HEAD核验回执随收束提交生成，最终回复给出已推送SHA。
