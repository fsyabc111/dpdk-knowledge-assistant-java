# 从这里开始

## 本次交付与权限

2026-10-24：用户要求制定完整 Java 迁移计划、阶段切片、支持多 Agent 长时协作并保持主 Agent 上下文简洁，然后复制一份不含 Git 的代码用于新仓库。用户已确认目标目录 `/Volumes/study/code/new_project/dpdk-knowledge-assistant-java`。

本次完成的是规划与副本准备。实施任务尚未启动。阅读计划、讨论取舍无需重复确认；执行哪一批以用户的明确放行为准，批次内自主完成，下一批单独验收与放行。

## 人类使用顺序

1. 检查 `COPY_REPORT.md`，确认副本来源和排除范围。
2. GitHub仓库已由用户创建，本目录已初始化并关联 `https://github.com/fsyabc111/dpdk-knowledge-assistant-java.git`；用户已于2026-10-24明确授权首次提交与推送。原复制报告中的无Git状态是复制完成时的历史记录。
3. 阅读 `SCOPE.md` 与 `PLAN.md`，确认目标为 Java 主后端，React/PG/隔离 harness 复用。
4. 将 `PROMPTS.md` 的主 Agent 提示词发给新仓库的主 Agent，指定本轮放行 P0 或修改计划。
5. 每阶段看阶段证据与待决项；验收后单独放行下一阶段。无需逐文件审批已授权阶段中的常规实现。

## Agent 的读取预算

主 Agent 初始只读取：根 `AGENTS.md`、本文件、`STATUS.md`。需要判断方案时再读 `SCOPE.md`/`DECISIONS.md`；派工时只读当前阶段和对应任务卡。

子 Agent 从一张任务卡开始，按源码入口与契约定位，不加载全部历史聊天、全部测试或整个 docs。完整日志写文件；主 Agent 只接收最多 500 字的完成摘要与证据路径。

## 文档分工

| 文件 | 唯一职责 | 何时读取 |
|---|---|---|
| SCOPE.md | 目标、非目标、总完成条件 | 首次决策、范围变更 |
| ARCHITECTURE.md | 目标模块和跨模块契约 | 设计/接口变更 |
| PLAN.md | 阶段门禁、依赖和集成节奏 | 派工/阶段验收 |
| STATUS.md | 任务状态、当前授权、阻塞 | 每轮主 Agent 恢复 |
| DECISIONS.md | 已知约束、建议、待确认决策 | 存在架构歧义 |
| foundation-slices.md | 基线、脚手架、运维、退役任务卡 | 承接对应任务 |
| business-slices.md | 业务任务卡 | 承接 J2/J3-04/J4-02 |
| ai-slices.md | 模型、检索、Agent、任务、索引任务卡 | 承接对应任务 |
| acceptance.md | 差分样例、验收矩阵和切换任务卡 | 测试/阶段放行 |
| COORDINATION.md | 所有权、调度、上下文与恢复 | 主 Agent 首次派工 |
| PROMPTS.md | 可复制主/子/审查/续接提示词 | 新建协作会话 |
| handoffs/ | 每个任务和主 Agent 的交接 | 重启/换 Agent/接续 |
| evidence/ | 可提交的合成样例验证摘要 | 审查完成声明 |
| reference/ | 原 Python 指令/说明快照 | 回读基线行为 |

`docs/` 其他文档仍是 Python 历史材料。Java 的阶段状态只更新此处 STATUS；原 roadmap 中的旧“完成”“下一步”不会自动继承。

## 新仓库最先需要核对的事项

- 以当前工作区快照为实现目标，还是以仍运行的旧镜像为目标：推荐前者，P0正式冻结；镜像与 schema0013 是后续接管来源，不能混为同一基线。
- Spring Boot/Spring AI/JDK 的确切组合及依赖锁定：官方能力可行，尚未构建验证。
- 外部 DPDK 与 harness 路径、版本、测试隔离；当前副本不具备全部外部运行依赖。
- 性能阈值、检索质量允许差异、待修缺陷与兼容差异：在实现前按验收文档固定，避免到最后改口径。
