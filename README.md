# DPDK Knowledge Assistant · Java 迁移工作区

本目录包含原项目当前工作区的源码副本和 Java 迁移任务包。**目前仍是 Python 实现，尚未建立 Java 工程。Git 已初始化并关联远程，本次提交为用户授权发布的源码基线和迁移规划。**

请从 [迁移入口](docs/java-migration/START_HERE.md) 开始。把 [主 Agent 启动提示词](docs/java-migration/PROMPTS.md) 交给新仓库的主 Agent，按阶段放行实施。

- [总体范围](docs/java-migration/SCOPE.md)：迁移内容、非目标和完成标准。
- [阶段与依赖](docs/java-migration/PLAN.md)：P0–P5，22 个可分派切片。
- [任务状态](docs/java-migration/STATUS.md)：唯一进度与授权记录。
- [协作规则](docs/java-migration/COORDINATION.md)：主上下文控制、文件所有权和交接恢复。
- [业务任务卡](docs/java-migration/business-slices.md)、[AI/任务/索引任务卡](docs/java-migration/ai-slices.md)、[验收任务卡](docs/java-migration/acceptance.md)。
- [复制来源与校验](docs/java-migration/COPY_REPORT.md)：源码快照、排除项和完整性证据。

推荐目标：Java 主后端 + 现有 React + PostgreSQL/pgvector + 独立 teaching_harness。Java 版本与依赖会在第一阶段验证并锁定。

## 当前能做什么

阅读、建新仓库、分派规划中的任务。开始实施前先确认阶段授权与技术决策。当前没有可运行的 Java 命令。

初始副本未携带原仓库的 `.git`；用户创建新远程后，本目录已新建独立Git元数据。源码副本不含真实 `.env`、数据库、日志、虚拟环境和 node_modules。外部 DPDK 主仓库与 teaching_harness 未复制；完整知识摄取和真实评测需要后续显式配置依赖。

复制来的 Python 启动/部署脚本保留原样，部分具有旧目录或 Compose project 假设。**不要在新副本直接运行它们来启动 Java 或操作旧部署。** 新环境入口由 J1-01/J5-03 单独建立。

原项目使用说明保存在 [Python README 快照](docs/java-migration/reference/README.python.md)。该快照内相对链接按原仓库根目录解释。原有 LICENSE 保留；新仓库应继续保留来源与许可信息。
