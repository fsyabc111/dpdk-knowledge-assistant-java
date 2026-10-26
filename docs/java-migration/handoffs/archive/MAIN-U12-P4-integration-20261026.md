# 主Agent恢复点

## 当前：2026-10-25 U12连续批次，P4集成中

- 直接用户授权LONGRUN_P3_RESUME解除暂停；工程门禁后自动P3→P4→P5隔离工程交付/J5-04准备，最多3worker，小步提交推送，持续交接。
- 禁止真实模型/embedding/harness验收、合并main、生产切换、Python退役、删除恢复资产，原项目/原部署/业务库只读。用户验收不可代签。
- P3工程已通过：修复快照5d0d6a3，全量591/591，两轴复审关闭4阻断。报告evidence/P3/phase-report.md、validation-reviewed.json；jar SHA02829d493ce390aea84ccc6a6e5f475f840818b1ffb67785ff2f16d55bb00e6e。阶段收口708128c。

## Git/权属

- 集成codex/p3-p5-longrun，origin用户新仓库；最近已核验推送81f3757。当前已验证生产源码1ed547b，后续仅证据/CI物料；待P4审查快照推送。
- 主Agent独占contracts/pom/配置/wiring/DDL/STATUS/MAIN/平台web/errors；root已装配Index/Knowledge/GroundedQuestionVerifier/OperationsPort/Metrics/health-ready。
- J4-01 index worker已交付：c94bcbb→70e574e→b59c631→fcb3e26→ad277bf，已全cherry-pick；52/52（39单元黄金+13PG）。无运行任务，源码锁释放。
- J4-02 knowledge worker已交付：7d9d1be→c1b4a7e→49b649a，已集成；19/19PG，含真实原题强制judge。无运行任务，锁释放。
- J4-03 operations worker已交付d715837/1312f72/3c79a70，已集成；CLI/HTTP指标/独立Compose/HTTPS/CI/备份脚本+固定MCP五工具，10Java单测+5Node协议+Java client loopback smoke通过。真实harness profile默认off；fake默认新隔离栈，绝不执行源码。
- ops_integration子片37987d9已集成711d84e：9命令真实业务适配/持久指标/owner公开维护聚合，7PG通过。当前追加离线judge正确映射（只写ops/adapter及OfflineEvidenceJudge/Port/CLI/测试，不动共享wiring）；根Main会接9参构造。
- model_adapter组合测试923e996已集成24b0ee7：workflow→index闭环2项+完整Spring HTTP平台2项实跑通过，含真实DPDK_MODEL_*环境绑定。
- 独立worktrees保留于.migration-artifacts/worktrees/{j4-01,j4-02,j4-03,j4-adapters,p3-model,...}。不用删除任何恢复资产。

## P4已实现/正在验证

- Index公共PreparedIndex/IndexNode补全，批量client→ModelUsageLedger→UsageCore保留每批成功/失败/unknown，finally刷账；公开activeRelease/health/rollback共用校验。
- Knowledge作者title/body保留Python原文，不引入脱敏新语义；运行问题/反馈/note/事件用既有Redact。verify顺序限流/日配额→scope issue读取→gate→真实检索/生成/强制judge→CAS，不新建chat任务。
- 真实新跨链发现insertIssue随机id与消息issue_id不符。cab2bdf初次窄修误命中insertJob，已由ada65f6精确恢复job随机独立id、insertIssue=taskId；新增TaskStoreIdentityIntegrationTest（两job独立+issue身份）防回归，不能覆盖原失败证据。
- health存活/ready 5秒墙+实际DB/当前release节点完整性/config；404/405/500及四安全头已真实HTTP2项验证。原题桥不使用try-with-executor.close阻塞超时。
- CLI task.background-enabled=false与migration.run-on-startup=false锁定，默认dryrun；root修retain-issues默认180天，purge-sessions仍30天。Operations reader专门new HeuristicQueryRewriter()、embeddingPort=null，固定queryVector必填，绝不复用产品可能外发的rewrite。
- 领域指标为持久账本gauge，未知不折0，未实现过程指标明确unavailable（不伪称同名counter）。需阶段审查确认完整交付边界，不能当生产观测全覆盖。
- 离线judge当前worker正纠正：原adapter误把检索命中率叫judge；将保留为retrieval-eval，judge读取源cases的answer/evidence/mode经AnswerValidator+显式模型judge。默认modelJudgeEnabled=false，未授权skip不算pass；loopback测试可启true。Pythonmismatch报告-only/exit0保持，非法执行仍非零。

## 当前验证/资源

- P4首轮684项1调度测试失败已确定性修复；最终完整698/698，源码1ed547b，日志p4-final-full-verify.log；同jar f57f8045cafeab09a5d8098b5ebea09e6e7d188d15229b2879073bfa73e93bf3 额外native CLI2/2含16新JVM严格通过。阶段报告evidence/P4/phase-report.md，双轴待做。当前PG空闲。
- PG55481专属实例运行；四DPDK_TEST_PG_*显式，口令只从主checkout .migration-artifacts/p1/local-credentials.txt解析，不输出。TestPg先验身份再动态建删，临时测试库finally清理；不删历史合成验收/恢复资产。
- JAVA_HOME=/opt/homebrew/opt/openjdk@21。Node24.18.1本机测试，原frontend102+build P3已通过，未改前端源码。
- 新隔离Colima java-u12（4CPU/4GiB/20GiB）启动中，仅本批次新profile，--activate=false --ssh-config=false --template=false，只挂当前repo；旧dpdk profile保持Stopped，原context仍colima-dpdk-ext。
- Colima exec session48460/PID94506，日志.migration-artifacts/p4-colima-java-u12.log；原生下载经现有localhost7897代理持续增长（最近176MiB），不是已完成VM。缓存~/Library/Caches/colima/caches/b0992...downloading。不要因慢而反复重跑/启动旧profile。
- Docker实际新栈尚未构建/部署，Compose静态通过不算演练；P5冷启动/0013升级/备份实际恢复/回退/容量与浏览器仍未做。

## 下一准确动作

1. 接离线judge新增片并完整verify；旧684项唯一调度测试失败已确定性修复，不能用局部7/7替最终全量。
2. 接收ops_integration追加offline judge提交；OperationsWiring改9参constructor注入OfflineEvidenceJudge(model,settings.validationTimeout,显式DPDK_JAVA_MODEL_JUDGE_ENABLED默认false)。需CLI真实jar help/非法退出/业务dryrun/apply启动验证。
3. 当前代码与物料及时commit/push并核验SHA；更新STATUS/本文件。完成P4报告+独立双轴审查，阻断关闭后自动进P5。
4. 新Colima继续等下载并核对profile/context，后续docker --context colima-java-u12显式运行，禁止动原profile。若硬阻塞继续其他独立工作。
5. P5按acceptance J5-01全矩阵/原客户端与浏览器→J5-02故障/三轮120s+600s固定容量（thresholds.json）→J5-03独立Compose冷启0013升级/备份排除临时输入且实际恢复/回退→J5-04交付准备；未实现/未测/未授权分开。整个授权范围完成前不收口。

### 最新资源补充

- Colima下载已完成，java-u12 Running，Docker29.5.2/4CPU/4GiB；旧dpdk Stopped，docker context show仍colima-dpdk-ext。仅显式--context colima-java-u12使用新实例。PG镜像digest dd467f03...已拉取，独立无网只读version证明17.10；应用栈未启动。
- 专属原PG20:10:36 smart shutdown来源未确定，已核对数据目录后恢复，p1_java/17.10/vector0.8.4只读身份复核通过。native CLI零写拒绝连接失败的原证据保留。
- 真jar先发现stdout日志污染与错误teacher角色白名单，已1ed547b修复（early logging/io.dpdk off+stdout框架导stderr，复用权威4角色），已16命令通过。Boot CLI证据evidence/J4-03/boot-cli.md最新回执已集成。
- P5预检handoffs/J5-01-PREFLIGHT.md、J5-02-PREFLIGHT.md只读准备完成；旧run_08薄轨迹不足完整oracle/full关联双射，必须新collector；容量旧ASGI布局不能直接对Java TCP，需新同环境Python TCP基线，冻结阈值不改。
