# 主Agent恢复点

## 当前：2026-10-25 U12续接，P3集成验证收口

- 用户直接启动LONGRUN_P3_RESUME；解除此前暂停。P3→P4→P5隔离工程交付/J5-04准备，门禁通过自动推进。
- 禁止真实上游验收、合并main、生产、Python退役和删除恢复资产；旧项目/部署/业务库只读。
- 热状态以STATUS为准；本文件为准确恢复动作。此前完整历史已存archive/MAIN-U12-pre-P3-integration-20261025.md。

## Git/所有权

- 集成分支codex/p3-p5-longrun，恢复90fae3d，最近已推送并核验b760ea4。此后已集成7278691/1c95430真实账本、c5c643d/c297eef讲解、3e186a6严格产品入口、ef490ea用量策略；均未合main。
- 主Agent单写公共契约/配置/装配/DDL/STATUS/MAIN；chat worker窄获AgentOrchestrator.runProduct所有权已交付，主线已接收。
- 三个实现worker工作树：p3-model（模型/账本验证已交付）、j3-03（chat收口handoff中）、j3-04（讲解已交付）。无worker在跑PG。
- 原j3-01/j3-02工作树保留，不删除恢复资产。所有临时日志/数据库/口令留gitignored目录。

## 当前证据与失败处置

- P3公共契约已补富快照与附件四字段；SessionPort真实委托、真实取消单Bean、TaskSettings/heartbeat/orphan扫描与Agent/model/retrieval/product完整Spring装配。
- 实际局部通过：模型worker84/84；真实Spring+PG18/18；真实Agent账本10/10；讲解23/23+新1黄金；HTTP嵌入式服务1/1（原前端客户端新增Node直连项待最新全量）。
- COMP003：running只get_task_status轮询；unknown/queryfail和首次SUBMITTED/UNKNOWN丢响应终态system_error+harness_result_unknown；消息/SSE failure_code、保留账本/清输入实测通过。Python黄金不变。
- initial_request并发败方按初发身份查赢家、findJob空行null、TaskGradeLedger真实guard/tx已修。
- r1全量581项5失败：前端探针强断言正常done必须terminal:true（契约允许省略，已显式修）；TaskIp测试硬编码8槽与未等待释放导致3连锁失败（改settings容量与释放闩）；SessionLifecycle旧端口占位抛错断言（改真实caller事务写入）。失败日志p3-full-verify.log。
- r2测试编译缺throws（已修）；r3全量verify已通过585/585（0失败/错误/跳过），日志.migration-artifacts/p3-full-verify-r3.log。原前端client直连也通过；测试进程已退出。
- 原前端102/102与npm build通过，日志p3-frontend-tests.log/build.log。源码未改。
- 新Node直连探针resources/migration/J3-03/frontend-client.mjs用esbuild替换构建期env后导入原client.js，验证JSON/replay/status/active/task-sse/chat-sse/multipart；由ProductApplicationHttpTest启动真实嵌入式Java与本机模型替身。

## 环境/恢复

- PG127.0.0.1:55481专属实例运行，数据.migration-artifacts/p1/pgdata；口令文件local-credentials.txt仅进env不输出。
- JAVA_HOME=/opt/homebrew/opt/openjdk@21；四个DPDK_TEST_PG_*见backend-java/README，TestPg先验维护连接身份才建删动态库。
- 全量使用主Agent排队。原始日志均.migration-artifacts/p3-*。临时模型HTTP和嵌入式服务由测试finally停止。
- Docker只读核查：colima-dpdk-ext socket不存在；原dpdk profile Stopped。后续只能建全新隔离profile/context，不启动/改旧profile。外盘可用约75GiB（核查时）。

## 下一准确动作

1. 审查源码已冻结3718d21（已推送并核验），构建jar SHA76ba685c77f1c80e34b2381561ca59a4f32bed636d52a7dbaccfcb5a50f78edf。阶段报告evidence/P3/phase-report.md与validation.json已写。
2. 独立规范worker p3_review_standards与独立规格worker p3_review_spec运行中，比较19a2d47...3718d21，业务只读，各自仅写evidence/P3/review-*.md，不跑PG。
3. 等审查结果，阻断交原owner或主Agent修复，针对复验后必要全量；不得仅以585绿色自动放行。
4. 报告与审查后提交推送/SHA核验；门禁通过才进入P4。最多3worker。
5. P4 J4-01索引/index/**、J4-02知识/knowledge/workflow/**可并行，J4-03平台/运维与共享物料由主Agent单写或明确授权窗口。
6. P5验收/容量/全新隔离部署/恢复及J5-04准备仍未执行；完成整个授权批次前持续推进，不因本轮中间结果而收口。
