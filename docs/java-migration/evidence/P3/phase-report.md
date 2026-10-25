# P3阶段报告：完整产品问答与已有成绩讲解

日期2026-10-25。最终状态：**P3隔离工程门禁通过**，修复源码5d0d6a3、显式PG全量591/591、独立规范/规格复审均通过。用户验收/live/生产资格未登记。下文保留首次585项审查快照事实；最终指纹见validation-reviewed.json。

## 快照与范围

- 源码审查快照3718d21，比较基线19a2d47（R段收口后P3开始）；远程分支codex/p3-p5-longrun。构建/fixture/前端原client精确SHA与逐套件计数见validation.json。
- Java21、Spring Boot4.0.9、Spring AI BOM2.0.2；显式独立PG17.10+pgvector0.8.4，127.0.0.1:55481；TestPg维护连接身份预检先于任何建删库。无DDL变更，无原环境写入。
- 模型/embedding/MCP仅固定外部替身或本机HTTP替身；身份/权限/受理/任务/检索/配额/计分/事务/持久化为真实Java实现。

## 实现与证据

| 切片 | 实际交付与合成验证 | 证据入口 |
|---|---|---|
| J3-01 | 任务受理原子性、并发/取消/恢复/进程账本；富契约与附件临时寄存；真实Spring取消单Bean和heartbeat；初发并发赢家接续 | handoffs/J3-01.md、J3-03/task-wiring.md |
| J3-02 | 五工具受控循环、上下文预算、供应商富tool_calls适配、严格产品入口；真实job/grade/quota集成与COMP003安全差异 | handoffs/J3-02.md、P3-MODEL.md、J3-03/agent-ledger.md |
| J3-03 | 产品JSON/SSE/上传/任务查询取消续传；确定性裁决与judge；提交后展示、旧release引用、真实重放；用量开关和定价 | handoffs/J3-03.md、J3-03/README.md |
| J3-04 | 本人成绩→确定性派生身份→任务；可见性与权限404；标题漂移重放；真第二JVM重建可信context、零重评与成绩不变 | handoffs/J3-04.md、J3-04/README.md |

- 三项历史跨模块接线（停号取消、活动会话删除、TaskGradeParticipant）已接真实Java实现；会话删除导致收口FK失败/pending留输入的源现状明确保留，不擅修Python缺陷。
- COMP003遵循用户批准：submitted/running只查等；unknown/查询失败以及首次SUBMITTED/UNKNOWN丢响应停止重评，保存system_error与harness_result_unknown，保留账本并清输入；10项真实PG回归包含消息与SSE failure_code。Python黄金未改。
- 新附件结构仅进入rag_task_inputs；永久任务/消息不存源码，取消/终态清理与TTL运维剩余接线由P4/P5验证。

## 本轮实际验证

- 显式PG全量verify：**585/585，0失败/0错误/0跳过，退出0**；包含真双JVM任务认领/讲解恢复、真实事务故障与并发。
- ProductApplicationHttpTest启动完整Spring应用和真实嵌入式HTTP：无上游配置503且零任务；固定上游的生成/保存/重放/SSE；启动原frontend/src/api/client.js经esbuild正常env替换后执行JSON、重放、任务查询、active、task SSE、chat SSE、multipart。替身仅外部端点，无前端源码改动。
- 前端既有测试102/102和npm build通过。浏览器真实操作、完整进程kill/restart HTTP、故障矩阵与容量仍留P5；API客户端直连不是浏览器验收。
- 输入Pydantic黄金17条、裁决20条、证据预算4条与讲解身份/context黄金均由冻结Python函数生成。具体差异/字段策略在各卡证据和interface-changes.md中明列；不将局部黄金代替P5全路由严格差分。

## 失败与修复纪律

- r1全量581项5失败：前端探针强要求正常done携terminal:true（冻结允许省略，明确改为正常done或terminal:true、拒terminal:false）；占槽测试硬编码旧8容量（冻结配置20）且无释放闩造成3项连锁失败；旧SessionPort测试仍期待占位异常。全部保留原日志，修为实际容量+显式释放和真实caller事务断言。
- r2测试编译缺throws已修；r3全量通过。整链期间另发现并修首次空账本误接续/初发竞争查错会话/TaskGradeLedger缺事务装配/旧恢复盲重投/产品空答案归因/NextStep默认字段与配置桥接等，均纳入回归。
- Python即时结果next_steps含继续提问与source_path空串，DB重建next_steps=[]；内存done可省terminal、DB重建附terminal=true。这些源行为在探针精确断言，非放宽比较器忽略未知差异。

## 门禁、回退与下一步

- 规范轴与规格轴首次各发现2阻断，见review-standards.md/review-spec.md；修复处置见review-disposition.md。原两位只读worker在5d0d6a3分别复审通过，见review-standards-recheck.md/review-spec-recheck.md。
- P3代码可按开发提交逆序revert回19a2d47；不操作旧数据库或部署，专属测试恢复资产保留。
- 审查与工程门禁通过后自动推进J4-01索引、J4-02知识工作流和J4-03平台/运维；P5隔离交付与J5-04准备继续。
- 本批次始终不含真实调用验收、合并main、生产切换、Python退役和删除恢复资产。用户验收保持独立。

## 最终复验与工程放行

- 5d0d6a3完整verify **591/591、0失败/0错误/0跳过、退出0**，原前端客户端直连仍通过；日志.migration-artifacts/p3-reviewed-full-verify.log。该源码已推送并核验origin一致。
- SP-P3-01/02：每reply预算拦截先于工具副作用、真实历史语境贯通检索；4项真实PG回归通过，已发生usage保留。
- ST-P3-01/02：成绩results原始数组单点包装，真实数组逐项等值与失败画像通过；chat成绩SQL迁回grading域，非task写入明确拒绝并验证零成绩行。单卡20单元+18PG及最终全量通过。
- 两轴均关闭阻断；工具schema元数据重复作为非阻断后续维护项。2026-10-25主Agent据直接批次预授权登记P3工程门禁通过，自动推进P4，无用户验收代签。
