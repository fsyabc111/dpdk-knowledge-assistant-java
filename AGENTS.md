# Java 迁移协作入口

本目录是 DPDK Knowledge Assistant 的独立 Java 迁移副本。Python 代码是行为基线，Java 尚未实现。文档与新增注释使用中文。

## 进入任务

1. 首次进入读 `docs/java-migration/START_HERE.md`。
2. 主 Agent 每次只先读 `STATUS.md`、当前阶段放行记录和最近一份主交接；按任务 ID 按需读取任务卡。
3. 子 Agent 只读分派任务卡、必要契约、指定源文件；交付写入自己的 handoff。完整路径均相对于 `docs/java-migration/`。

## 权威与范围

- Java 范围：`SCOPE.md`；阶段与依赖：`PLAN.md`；唯一任务状态：`STATUS.md`；决策：`DECISIONS.md`；文件归属与上下文规则：`COORDINATION.md`。
- `docs/README.md`、`docs/decisions.md`、`docs/roadmap.md` 及 `docs/archive/` 描述复制前 Python 项目，不是 Java 执行队列。旧 AGENTS 保存在 `docs/java-migration/reference/AGENTS.python.md`，仅在核查 Python 行为时读取。
- 用户已授权本轮规划和复制。Java 实施、阶段升级、真实模型/评测调用和生产切换，以 `STATUS.md` 中记录的直接用户授权为准；计划本身不代表授权。
- 原项目、现有部署和现有数据库保持只读。新代码写入本副本。`backend/` 保留为对照；修复既有缺陷须先记录差异并单独定范围。

## 实施规则

- 使用模块化单体；候选 Java 21 + Spring Boot + Spring AI，P0/P1 锁定兼容版本。后端位置 `backend-java/`，包根 `io.dpdk.assistant`，按业务功能分包。
- REST/JSON/SSE、权限、身份、计分、配额、引用、任务恢复以冻结契约和差分样例为准。框架默认行为不自动构成兼容实现。
- 一个任务一个写入所有者；公共契约、根构建文件、共享配置、DDL、前端客户端及部署入口由集成者统一修改。先登记接口变更，再让依赖任务继续。
- 数据库只使用显式的独立测试 PostgreSQL。测试启动先校验目标身份；禁止回退到旧 `.env`、默认宿主机库或生产配置。
- 运行 Java 使用后续建立的独立命令和 Compose project。复制来的 `start_dev.*`、`local_harness.sh`、`deploy/external_disk.py` 和 Compose 文件是 Python 参考，未经新环境审查不得直接执行。
- 保持评测容器隔离、源码限时寄存与终态清理、MCP 提交后不盲重试；受理事务不跨越模型或外部评测调用。
- 不提交凭据、源码运行输入、数据库、模型原始输出或原始日志。配置交付使用 `.env.example` 等模板。

## 交付

每个切片提交：变更路径、接口/决策差异、实际执行的验证与结果、未验证项、阻塞、回退和下一步。使用 `templates/HANDOFF.md`。
实现完成、自动验证、场景验收、用户验收分开记录。主 Agent 根据证据更新状态；子 Agent 不自行放行阶段。

用户已创建 GitHub 仓库，本副本已初始化 Git 并关联 origin。2026-10-24 用户明确授权首次提交与推送，本提交为源码基线和迁移规划；后续按 `COORDINATION.md` 创建独立分支/工作树。Java实施阶段仍未获放行。
