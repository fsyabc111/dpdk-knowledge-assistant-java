# P4 独立规范轴审查

日期：2026-10-25。已核实固定基线 `708128cad447dabc48c028e19af8933db0e81e11`、固定快照 `69dad0f6cc9fc73e321b17a2d52ccd53a6434594`；开始审查时 HEAD 与快照一致。命令为 `git diff 708128c...69dad0f`、`git log 708128c..69dad0f --oneline`，完整范围 100 文件、8431 新增/48 删除（包含报告、计划与证据）。业务只读，未运行 PG、构建、应用栈或测试，未提交/推送。

## 硬阻断：文档规则与交付缺陷

1. **[P2] 为 CI 的真实 jar 验收传入工件路径。** `.github/workflows/java-isolated.yml:72-76` 先 package，再仅用 `-Dtest=OperationsBootJarAcceptance` 启动测试；`backend-java/src/test/java/io/dpdk/assistant/operations/OperationsBootJarAcceptance.java:40-46` 强制要求系统属性 `dpdk.acceptance.jar` 非空且为实际绝对文件路径。pom 未提供该属性默认值，两项测试都先调用这条前置断言，因此当前远程 PG job 会在启动验收子 JVM 前失败。本机成功命令实际上带有此参数，见 `docs/java-migration/evidence/J4-03/boot-cli.md:50-54`。规则：`foundation-slices.md:43,52-55` 要交付 P5 可验证的独立 CI/CLI 入口。应以当前工作区生成的 jar 绝对路径传参，并把日志写入受忽略目录；保留真实工件/关键类校验。此项是交付命令缺参，不是把“远程 CI 尚未执行”本身算阻断。

2. **[P2] 将已发布知识读取交回 knowledge owner。** `backend-java/src/main/java/io/dpdk/assistant/index/JdbcIndexStore.java:64-71` 直接查询 `rag_knowledge` 的正文、状态与修订；`index/IndexService.java:74-83` 通过 index 私有仓储消费该跨域数据。发布状态与数据形状的变化因此须同时修改 knowledge 和 index，绕过了应由知识模块封装的发布读取语义。规则：`docs/java-migration/ARCHITECTURE.md:7,21-24` 要求跨模块调用使用业务接口、调用者不直接拼接其他模块 SQL；`ai-slices.md:212` 明确 J4-01 依赖 J4-02 知识读取接口。现有接口登记没有批准此处接管 knowledge 仓储。应在 knowledge 域提供发布快照读取接口/不可变 DTO，index 只消费，集成者负责接线。修复后保留 published/withdrawn、revision、排序与全源增量黄金，以及“撤回只影响新 release，旧引用可读”的真实组合回归。

## 启发式建议与边界

按完整 smell baseline 检查；本次没有单列新的 `possible` smell 建议，避免将冻结端口的必要委托、领域内 JDBC 工具或未批准重构当作硬违规。上述跨模块 SQL 是明确仓库规则违规，不仅是风格判断。

受理/发布外部调用未发现跨数据库事务；发布 CAS、节点/release/成员同事务，留存通过 `MaintenanceParticipant` 共享事务；CLI 默认预览且最高优先级禁迁移/后台，脚本不切全局 Docker context，备份排除临时输入。未发现本轮新增日志主动输出源码、prompt、凭据或模型原始结果的实证。

过程指标缺项被明确列为 unavailable，持久聚合声明 gauge；`foundation-slices.md:48,55` 要求兼容映射写清，本次不据此伪称原过程指标已全覆盖，也不将明确不可用的映射自动判成新阻断。实际新栈冷启/恢复、浏览器、容量与完整候选轨迹仍属 P5；未授权 live 不计 P4 阻断。

## 门禁结论

`phase-report.md`、`validation.json` 的 698/698、同 jar 的 2 项/16 JVM、Node 5 项及真实 PG 组合证据属于已记录验证，本 worker 未复跑；这些结果不覆盖上述 CI 缺参和模块边界。

**规范轴暂不支持 P4 工程放行：2 项 P2 阻断，新增 smell 建议 0。** 修复并复核后再判断；本结论独立于规格轴、用户验收及任何生产资格。
