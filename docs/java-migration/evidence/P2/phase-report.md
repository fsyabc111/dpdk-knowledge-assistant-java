# P2 阶段报告（教学业务切片）

更新：2026-10-25。分支 `codex/p1-p2-longrun`（c7cbab3 已推送核验），授权依据 U11（P1+P2 连续批次）。
状态：**五卡实现与自动验证完成；集成回归通过（零阻塞）；双轴独立审查进行中（本文件随后补终稿结论）；用户验收待批次结束统一进行。**

## 本阶段目标与构建

- 目标（PLAN P2）：原 React 可登录、管理账号、发布作业、提交替身评测、看成绩/CSV/历史；权限、事务、计分、配额及页面基本闭环。
- 构建：JDK 21.0.12.1 / Boot 4.0.9 / Maven Wrapper 3.9.12 / Flyway 11.14.1 / PG 17.10+pgvector 0.8.4（55481 隔离实例）。全量 `mvnw test` **298/298**；真实应用启动迁移（空库 EMPTY→applied=1→catalogVerified=true）与 health UP 实测。

## 完成切片

| 卡 | 交付 | 自动验证 | 证据 |
|---|---|---|---|
| J2-01 | 三模式认证、五端点、限流锁定、一次性重置（并发严格一次）、bcrypt/sha256 兼容 | 33/33；golden 19 零差异 | evidence/J2-01/ |
| J2-02 | 12 管理端点、树规则、级联删除+审计单事务（故障注入回滚）、班级范围端口 | 35/35；golden 36 对照 | evidence/J2-02/ |
| J2-03 | 作业 CRUD/撤回/可见性、双口径端口、汇总（HALF_EVEN 折算）、CSV 字节级 | 22/22；CSV sha256 一致 | evidence/J2-03/ |
| J2-05 | 会话生命周期/CAS release、配额/用量账本、来源/模板、成绩只读、维护端点 | 37/37；模板字节级 | evidence/J2-05/ |
| J2-04 | 正式提交 JSON/multipart、fake MCP 评测（UNKNOWN 不重发）、单事务落库、PythonUtf8 解码电池 | 21/21 | evidence/J2-04/ |

启动端到端：Bean 装配修复批次（9ba2378）后 health UP/迁移/422 形态实测（MAIN 记录）。

## 集成回归（真实 Java+PG 业务闭环，c7cbab3）

- 场景 1-7 全过（evidence/P2-regression/logs 01-07）：认证 401 同体/WWW-Authenticate/吊销；批量创建部分失败契约；停号 401；改密审批一次性令牌（旧 token 失效、重放 400）；作业可见性/撤回；替身评测提交→成绩仅 code_hash（源码特征串 0 命中）；默认配额第 21 次精确 429；CSV BOM/CRLF/分组；usage 空账 null 非 0；React 页面 200+CORS 预检/带 Origin 登录 200/非允许来源 403。
- golden 对照（IP-3）：**契约 DIFF=0**——PASS 78、STATE-DIFF 6（行数/账号集，登记）、SKIP 87（逐条理由）、REPLAYED 15。
- 平台差异登记：secure-headers 缺失、/api/health→/actuator/health、FastAPI 文档路由、CORS 拒绝 400→403、chat 主路由未装配（J3+/J5）。

## 未验证 / integration_pending（如实登记，不计通过）

- J3-01 接线项：停号→任务取消（IP-1）、活动会话删除×执行中任务（IP-J205-2）、TaskGradeParticipant。
- 平台层：500 异常渲染缺口（收口评估中）；浏览器全页面点击（U11 为最小联调接线；完整页面验收留 P5）。
- live 质量：真实模型/评测/recall/MRR/容量/恢复——未授权未测。
- 生产 schema/旧部署接管：P5。

## 独立审查（双轴完成，均通过）

- 规范轴：**通过，0 阻断 / 5 建议**（evidence/P2/review-standards.md，基准 c7cbab3）。处理：S-2 STATUS 滞后叙事已更正（本提交）；S-1 脚本口令字面量改环境读取；S-4 CORS 403/secure-headers 差异升级登记为 DECISIONS P2-D1（用户验收时确认）；S-3/S-5 口径与历史节说明：当前节为准，历史节保留供恢复追溯。
- 规格轴：**通过，0 阻断 / 3 建议**（evidence/P2/review-spec.md，基准 c7cbab3）。三项收口建议（D2 500 兜底 Starlette 平价+4 处断言更新、S1 登记入库、S3 SKIP 分类附注）已全部关闭（060a185），关闭后全量 298/298 回归通过。
- 无阻断问题。

## 工程门禁判定（按 U11）——**P2 工程门禁通过，用户验收待进行**

1. P2 五卡强制条件实际验证：**是**（298/298 + 各卡 golden 对照零未解释差异）。
2. 真实 Java+独立 PG 业务闭环：**是**（P2-regression 场景 1-7，evidence/P2-regression/）。
3. React 联调最小接线：**是**（页面 200/CORS 预检/带 Origin 登录/非允许来源拒绝）。
4. 独立规范/规格审查：**通过（双轴 0 阻断）**，建议全部关闭或登记。
5. 报告/交接/提交推送并核对 SHA：**是**（本批次全程 SSH 通路推送，每次 ls-remote 核对一致；收口提交见 git log）。

按 U11 任务书：**P1+P2 连续批次工程交付到此收口。不进入 P3、不合并 main、不部署**；删除恢复资产未发生；用户验收由用户统一进行。

## 重大差异 / 回退

- 差异全部登记于 migration/contracts/interface-changes.md（重置令牌并发守卫、U+FFFD PythonUtf8、504→502 挂起、CORS 403 等）。
- 回退：撤销 backend-java 提交；候选库可 DROP；无生产/旧环境影响。

## 工程门禁与批次收口（按 U11）

P2 门禁=五卡强制条件实际验证+真实 Java+PG 业务闭环+独立审查+报告交接推送核验。前两项已满足；审查结论与远程一致性核对将在终稿补记。**不进入 P3、不合并 main、不部署**；用户验收待批次结束统一进行。
