# U12 续接任务书：P3 集成窗口 → P3 收口 → P4 → P5 隔离工程交付

交接日期：2026-10-25。状态：**交接点任务书，待用户发送启动指令后执行**。

上一轮（U12）已完成 R 段收口与 P3 的 J3-01/J3-02 实现，用户指令于 J3-01 完成后暂停交接。本文供下一会话主 Agent 续接；完整历史见 `STATUS.md`、`handoffs/MAIN.md`。原始批次规则（协作/边界/验收纪律）以 `LONGRUN_P3_P5_DRAFT.md` 为准，继续有效，本文不重复。

## 续接提示词（发给新会话主 Agent）

你是 DPDK Knowledge Assistant Java 迁移项目的主 Agent，负责依赖、所有权、调度、集成、验证和交接。这是 U12 长时批次的续接会话。

工作目录：`/Volumes/study/code/new_project/dpdk-knowledge-assistant-java`。
远程仓库：`https://github.com/fsyabc111/dpdk-knowledge-assistant-java.git`。
续接基线：集成分支 `codex/p3-p5-longrun` @ `b600533f21db2cb167a31bd5d5dde829a91894bc`（与远程一致，工作区干净）。

### 1. 恢复读取（按序，勿超预算）

1. 根 `AGENTS.md`、`docs/java-migration/START_HERE.md`、`STATUS.md`（唯一任务状态）、`handoffs/MAIN.md` 当前节（完整恢复点：提交链/待办/资源）。
2. `handoffs/J3-01.md`、`handoffs/J3-02.md`（两卡实现与未验证项）、`migration/contracts/interface-changes.md` 的「J3-01 集成裁决」「J3-02 集成裁决」两节（9 项申请的受理结论与分类）。
3. 按需：`migration/contracts/http-and-sse.md`（SSE 冻结契约）、`evidence/U12-R-segment-20261025/README.md`（R 段报告）、`LONGRUN_P3_P5_DRAFT.md`（批次规则）。

### 2. 已完成状态（勿重做）

- **R 段已收口**：验收 8 项问题（ST-01～03、SP-01～05）全部关闭，双轴独立审查通过（B-1 已关闭），全量 316/316、探针 32/32、严格重放 DIFF=0。
- **J3-02 已合并**（3d426e9）：Agent 五工具受控循环，自有测试 124/124，黄金场景 Python 可达子集对齐。
- **J3-01 已合并**（d927fb1）：任务受理事务/账本/进程托管/事件流/恢复，8 类 50/50，真双 JVM+合成 PG 认领实测；integration_pending 三项卡内闭环（IP-1 停号取消、IP-J205-2 会话删除、TaskGradeParticipant）。
- **合并树全量 verify 490/490**（316 基础 + 124 Agent + 50 task），集成分支为可构建快照。

### 3. 续接任务（顺序即优先级；工程门禁通过后自动推进）

1. **契约字段补全**（接口裁决受理项，集成者单写 contracts）：
   - Probe/AdmissionResult/CompletedAnswer/TaskRow 占位字段补全（富形态已在 `io.dpdk.assistant.task` 包公开：TaskProbeOutcome/TaskAdmitOutcome/TaskRecordRow）。
   - **AdmissionInput 附件四字段**（attachment_name/suffix/source_text/source_origin，建议 TaskTemporaryInput 子结构）——这是 J3-03 阻断项：缺则上传附件任务的恢复丢执行源。
2. **Spring 装配**（集成者窗口）：
   - SessionPortAdapter.appendUser 改委托 SessionService.appendMessage（参照 J3-01 的 TaskSessionPortBridge）。
   - `admin.NoOpLocalTaskCancellation` 让位 `task.TaskLocalCancellation`（单 Bean）——**装配完成前不得宣称 IP-1 运行时闭环**。
   - TaskSettings 注入 + heartbeat loop 启动接线；AgentChatModel 真实模型适配器（OpenAiCompatibleChatClient → AgentChatModel，含 tool-calling 形态；若现契约不足，按流程登记变更）。
3. **J3-03 chat/SSE 集成**（任务卡 `ai-slices.md` J3-03 节）：/api/chat* 路由、SSE 帧格式（双 LF、done/terminal:false 区分、error-only 合法结束、累计 sources）、正文/引用/终态一次提交后展示、重放不重复扣次；把 J3-02 的 AgentScenario/TaskLedgerTest 替身换真实仓储回归。
4. **J3-04 讲解**（任务卡 `business-slices.md` J3-04 节）：/explain 路由、purpose=explain 任务、恢复重建 context、grade_plugin 零调用断言。
5. **P3 阶段报告 + 双轴独立审查**（复用 R 段模式：规范轴+规格轴只读 worker）→ 门禁通过后 **P4**（J4-01 索引、J4-02 问题/共享知识、J4-03 平台缺口含框架 404/405/500 与安全头）→ **P5**（J5-01 全矩阵与浏览器、J5-02 故障/恢复/容量、J5-03 隔离部署/迁移/备份恢复/回退、J5-04 交付准备）。

### 4. 资源与环境

- 专属 PG 55481 运行中（`.migration-artifacts/p1/pgdata`，角色 p1_java；口令在 `.migration-artifacts/p1/local-credentials.txt`，600，gitignored）。TestPg 身份预检已闭环，动态建删库安全；全量集成测试由集成者统一排队。
- worker worktrees `.migration-artifacts/worktrees/{j3-01,j3-02}` 已无用可清理（分支 `worker/j3-01@40cb46c`、`worker/j3-02@3d426e9` 均已推送）。
- JAVA_HOME=/opt/homebrew/opt/openjdk@21；PG 测试环境变量四项见 `backend-java/README.md`。

### 5. 纪律与边界（不变项摘要）

- 协作规则沿用 `LONGRUN_P3_P5_DRAFT.md` §6：主 Agent 统筹、最多 3 个 worker、一个任务一个写入所有者、公共契约/装配/STATUS 由集成者单写、小步提交及时推送并核验 SHA、持续写交接、重复失败先换诊断方法。
- **仍不在本批次**：真实模型/embedding/真实 harness 调用验收、合并 main、生产切换、Python 退役、删除恢复资产、原项目/原部署/业务库写入。工程门禁 ≠ 用户验收，不得代用户登记验收。
- 严格差分、业务链真实（替身只放外部边界）、双进程实测等验收约束按原任务书 §5 执行。

### 6. 收口

P5 隔离工程交付 + J5-04 交付准备完成后，按原任务书 §7 交付（8 项缺陷关闭记录已交付于 R 段；补 P3/P4/P5 阶段报告、11 张任务卡 handoff、完整矩阵与豁免依据、构建/部署/恢复手册、远程分支一致性），声明「隔离工程交付完成，live 与用户验收/切换另行安排」，不以套件全绿替代严格验收。

## 建议的启动指令

核对本文后发送：

```text
现在按 docs/java-migration/LONGRUN_P3_RESUME.md 所述续接 U12 批次。
从 P3 集成窗口待办开始（契约字段补全与 Spring 装配先行），J3-03/J3-04 完成后
做 P3 阶段报告与双轴审查，门禁通过后自动推进 P4、P5 隔离工程交付和 J5-04 交付准备。
主Agent统筹、最多3个worker，小步提交及时推送，持续写交接。
真实调用验收、合并main、生产切换、Python退役及删除恢复资产仍不在本批次。
有硬阻塞时继续独立可做工作，全部受阻才保存交接并报告未完成。
```
