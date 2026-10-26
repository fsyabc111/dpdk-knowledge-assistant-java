# P4 规范轴独立复审

日期：2026-10-25。原 `review-standards.md` 保留。本次已核实 `69dad0f6cc9fc73e321b17a2d52ccd53a6434594` → `22c87e4da031bfe64136898cb9f9156a1078e4d6`，开始复审时 HEAD 等于后者；使用 `git diff 69dad0f...22c87e4` 和同区间提交列表。只复审原两项阻断及修复增量是否引入规范问题，不代替规格轴判断。

## 原阻断关闭情况

1. **ST-P4-01：关闭。** `.github/workflows/java-isolated.yml:72-76` 仍先 package，再运行真实 Boot jar 验收，现已传入 `-Ddpdk.acceptance.jar="$PWD/target/dpdk-assistant-backend-0.1.0-SNAPSHOT.jar"`。该步骤工作目录为 `backend-java`，`$PWD` 展开后为实际工件的绝对路径，满足 `OperationsBootJarAcceptance.java:40-46` 的前置条件。没有改成跳过验收、隐式旧 jar 或仅检查 exit0；工件与当前关键类 SHA 核对仍保留。原先违反的 J4-03 可执行 CI/CLI 交付要求（`foundation-slices.md:43,52-55`）已在命令层修复。远程重跑状态仍由主 Agent 单独核验，本复审不把远程未结束当作已成功。

2. **ST-P4-02：关闭。** `backend-java/src/main/java/io/dpdk/assistant/contracts/knowledge/PublishedKnowledgeReader.java:5-9` 定义只读有序 DTO 契约；SQL 归 `knowledge/workflow/PublishedKnowledgeRepository.java:13-22`，仍仅取 published、按 id 排序并返回不可变列表。`index/IndexService.java:45-67,79` 显式消费契约且构造期拒绝 null，没有保留隐式空知识回退。静态检索生产 `index/**` 不再包含 `rag_knowledge`、`PublishedKnowledgeRepository` 或 `knowledge.workflow` 引用。`platform/wiring/KnowledgeWiring.java:15-17` 提供唯一真实 reader，`IndexWiring.java:32-35,61` 显式注入。符合 `ARCHITECTURE.md:7,21-24` 的模块 SQL 边界及 `ai-slices.md:212` 的知识读取接口依赖。

纯单测新增 `IndexServiceTest.java:152-164` 覆盖 reader 必需、调用一次、顺序与 revision；真实 PG 索引和两个跨模块组合测试仅改为注入真实知识仓储，原黄金、修订、撤回和旧引用断言未删除或放宽。接口变更已登记于 `migration/contracts/interface-changes.md` 与 `transaction-ports.json`。

## 新问题与验证范围

本次增量未发现新的规范阻断或需独立处置的 `possible` smell。新增 reader 是实际跨模块依赖的接缝，不属于 speculative generality；必要委托受仓库接口规则支持。知识 SQL 迁移不引入写入或跨外部调用事务，未修改发布 CAS、向量校验、留存规则、部署权限或日志输出。树内完整 JSON 解码修复未见新增 SQL/装配越界；其行为等价性留规格轴独立裁决。

本 worker 未运行 PG、构建或测试，未修改业务源码、提交或推送。已只读检查主 Agent 本次日志：`.migration-artifacts/p4-reviewed-full-verify.log` 为 **702/702，0失败/错误/跳过，BUILD SUCCESS**；`p4-reviewed-native-cli.log` 为 **2/2，0失败/错误/跳过，BUILD SUCCESS**。定向62项为 `index-reader-fix.md` 登记的 worker 结果，与上述当前全量证据区分。

## 规范工程门禁

**通过：原 2 项阻断均关闭，新增规范阻断 0。** 结论限固定修复快照及当前隔离合成范围；P4 总工程门禁由主 Agent 汇合规格轴、当前验证和远程 CI 状态判断，不代用户验收、live 或生产资格。
