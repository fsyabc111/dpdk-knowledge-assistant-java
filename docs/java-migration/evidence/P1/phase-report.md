# P1 阶段报告（最小问答切片）

更新：2026-10-25。分支 `codex/p1-p2-longrun`，授权依据 U11（P1+P2 连续批次，/goal 启动）。
状态：**实现与自动验证完成；独立审查进行中；工程门禁判定见文末；用户验收待批次结束统一进行。**

## 本阶段目标与构建

- 目标（PLAN P1）：Java 对合成知识回答并给出可追溯引用，能调用无副作用工具替身；独立启动、模型/MCP 协议替身、固定向量检索、问答路径通过。
- 构建 ID：集成分支 bb07ab7 + 接线提交（见 git log）；`./backend-java/mvnw -f backend-java/pom.xml -B verify` → **Tests run: 150, Failures: 0, Errors: 0, Skipped: 0**，BUILD SUCCESS（JDK 21.0.12.1 / Maven Wrapper 3.9.12 / Boot 4.0.9 / Spring JDBC 7.0.10 / Flyway 11.14.1 / PG JDBC 42.7.14，PG 17.10+pgvector 0.8.4 专属实例 127.0.0.1:55481）。

## 完成切片

| 卡 | 交付 | 自动验证 | 证据 |
|---|---|---|---|
| J1-01 | 可构建工程、fail-closed 配置、独立测试PG身份预检（四要素+四类负例拒绝）、三路径 Flyway 迁移链（B0015/V0014/V0015，讲解 uuid5 回填+伪造反例）、重复启动不变、部分表拒绝、18 端口契约接口 | 26/26（含 5 项 PG 集成） | evidence/J1-01/build-and-paths.md |
| J1-02 | OpenAI 兼容 chat（JSON/流式）/embedding 客户端、失败分类与重试矩阵、usage 账本 unknown≠0、上游并发门、同 key 单飞合并、MCP Streamable HTTP 客户端（initialize 重试2次、提交后不重发、四分支规范化） | 本卡 83/83 | evidence/J1-02/* |
| J1-03 | LI 数据面只读检索：四路召回 SQL、RRF 融合、23 项权重重排、MMR、release 隔离、配置指纹逐字节兼容、历史引用回读 | 本卡 29 项（含 10 项合成 PG；12 冻结 case 逐阶段对照，raw score 差值实测 0） | evidence/J1-03/* |
| J1-04 | 最小问答纵向切片：真实检索→固定模型替身→裁决（Python 顺序语义）→引用绑定（round4/同文件不合并）；四联样本 | 本卡 12/12 | evidence/J1-04/chain-samples.md |

集成：J1-02/J1-03 由独立 worktree worker 交付，主 Agent 审查合并（1627178/c00eb99）；全量回归 150/150。

## 自动验证（命令）

仓库根：`export DPDK_TEST_PG_URL=...（见 .env.example）；./backend-java/mvnw -f backend-java/pom.xml -B verify`。
无 PG 环境时 PG 集成测试显式跳过（不静默使用其他库）；带环境全量 150/150。

## 场景验收（合成域）

三路径迁移、检索排序、引用绑定、问答裁决的合成场景随测试套件通过。完整业务场景（登录/作业/提交/成绩）属 P2，不在本报告冒充。

## 独立审查（进行中）

- 规范轴：evidence/P1/review-standards.md（审查中）
- 规格轴：evidence/P1/review-spec.md（审查中）
- 阻断问题修复与复验结果将在本节补记。

## 未验证 / 允许延后项

- 真实供应商/真实 harness/真实 embedding：未授权未验证（live 批次）。
- 产品 /api/chat、SSE、持久会话/任务：J3 范围。
- React 联调：P2。
- Boot 起动的 StartupMigrations 端到端：编码完成，端到端由 P2 应用启动覆盖。
- Java/真实质量（recall/MRR、容量、恢复）：未测，不宣称。

## 重大差异 / 回退条件

- 已登记有意差异：evidence/J1-02/unverified.md、migration/contracts/interface-changes.md（参数序列化提前、取消 shield、日志不回显 payload、Jackson2/3 并存规避）。
- 回退：停止独立测试 PG（pg_ctl stop，数据保留）；撤销 backend-java 提交即可整体退出；无生产/旧环境影响。

## 工程门禁判定（按 U11 五项）

1. P1 任务卡强制条件实际验证：**是**（上表）。
2. 独立测试 PG 与危险目标拒绝：**是**（J1-01 负例四类）。
3. 协议替身、固定向量、最小问答链路：**是**（83+29+12）。
4. 独立规范/规格审查：**进行中**（本节更新后判定）。
5. P1 报告/交接/提交推送并核对 SHA：**待网络恢复推送**（github.com 暂不可达，本地提交已保存，见 MAIN 记录；推送失败如实登记，不伪称已发布）。

## 请求用户验收与下一阶段授权

P2 已按 U11 预授权：P1 工程门禁全项满足并推送核验后自动进入 P2（J2-01→J2-02→J2-03∥J2-05→J2-04→集成回归），不进入 P3。用户验收在批次结束后统一进行。
