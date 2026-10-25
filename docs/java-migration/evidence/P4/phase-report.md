# P4阶段报告：索引、知识工作流和独立运维工程

日期2026-10-25。当前状态：三卡实现与集成自动验证完成，**独立双轴审查待完成，P4工程门禁未放行**。用户验收/live/生产资格不代签。

## 快照与权限

- P3工程收口708128c后按用户预授权进入P4；已验证生产源码1ed547b，后续仅证据/CI物料变化。当前待审查快照在主交接登记。
- 完整显式PG verify **698/698，0失败/0错误/0跳过**。同一jar SHA `f57f8045cafeab09a5d8098b5ebea09e6e7d188d15229b2879073bfa73e93bf3` 另经真实Boot jar **2/2测试、16个新JVM命令**全部通过。逐套件/工件/环境见validation.json。
- JDK21/Boot4.0.9/Spring AI BOM2.0.2不变；数据库17.10/vector0.8.4，所有PG动态库先TestPg身份预检。无DDL变更，不读写原项目/旧部署/业务库。
- 只有模型、embedding、MCP使用固定外部替身；身份/权限/任务/持久化/事务/检索/索引/配额/成绩/知识工作流都是真实Java实现。

## 三卡与完整组合证据

| 切片 | 实际实现与验收 | 证据 |
|---|---|---|
| J4-01 | 显式全源扫描、最长root/tie/Python Unicode黄金、逐项增量、批次向量/用量、CAS发布、完整性health、受校验rollback、保留历史node与旧会话 | 单卡52/52；evidence/J4-01/、handoffs/J4-01.md |
| J4-02 | 本人反馈与teacher树作用域、认领/事件同事务、merge限制、revision CAS/修订史、shared_confirmed发布pending、当前release原题验证与旧验证防覆盖 | 单卡19/19；evidence/J4-02/、handoffs/J4-02.md |
| J4-03 | 默认dry-run/显式apply真实CLI、维护/审计、离线证据judge与固定检索评估分离、健康就绪、框架错误与安全头、指标映射、独立Compose/HTTPS/CI/备份物料 | 7真实PG+24纯测试在全量内；boot jar另2/16 JVM；evidence/J4-03/、handoffs/J4-03*.md |

- P4WorkflowIndexIntegrationTest真实组合：任务问题→本人反馈→作用域认领→人工知识发布pending→全源摄取→检索生效→onlineJudge=false仍强制judge→当前release解决；新release后旧验证不能resolve；撤回后新active不包含该知识，而旧会话/旧引用仍可读；永久表源码哨兵为零。
- P4PlatformHttpTest启动完整应用：真实DPDK_MODEL_*环境变量绑定；health200；ready无active/成员不齐/物理节点缺失/config失配503；404/405/500和四项安全头。不是仅MockMvc或页面200。
- OperationsBootJarAcceptance从可交付jar启动新JVM：无DB帮助0/非法参数2；严格唯一JSON stdout；全部public表与schema快照验证dry-run零写、老pending updated_at不动；operator真实创建/重复幂等、purge、180天retain、审计去敏；错误数据库身份exit1且零写。CLI强制关闭启动迁移和任务后台，优先级不被低层配置覆盖。
- 离线judge复用AnswerValidator评候选答案/内嵌证据；retrieval-eval才评固定向量命中率。默认模型judge关闭，未授权案例matched=null并列skip；report-only退出0忠实保留Python行为，门禁必须读summary，不能据exit0声明通过。固定模型judge/超时/畸形结果已纯测试验证，未调用真实模型。
- 固定MCP替身Node5/5及现有Java客户端loopback五工具smoke通过，同job固定回放、unknown、源码不进结果/账本/日志；不执行源码，不替代真实harness恢复/质量。

## 重要接口/决策

- PreparedIndex/IndexNode正式补全；批量客户端的ModelUsageLedger→UsageCore保留成功、失败、unknown与次数，索引finally刷账，写账异常不覆盖发布结果。
- OriginalQuestionVerifier分prepareAdmission和verify，保持原限流/日配额→问题读取→admission顺序，不借普通chat任务伪造原题验证。
- 人工作者知识title/body保留Python原文；问题/反馈/note/事件按既有Redact脱敏，不新增未授权产品安全语义。
- 运维固定检索使用独立无模型rewrite/无embedding端口的PgReleaseReader，显式query→1024向量，不复用产品可能外发的改写器。指标中持久账本明确为gauge，过程域缺项列unavailable，不伪填静态0或冒充源counter全覆盖。
- 共享pom/mainClass/配置/平台装配由主Agent单写；各worker独立分支/工作树交付，未改DDL/前端业务代码。

## 失败与修复保留

- 真实任务到反馈发现insertIssue随机id与消息issue_id不符；主Agent首次同形SQL窄修误中job主键，随后精确恢复job随机独立ID并改issue=taskId，新增双job+issue身份真实PG回归。原失败记录保留。
- 第一轮全量684项1失败：恢复注册完成后立刻断言执行次数，异步体未获调度；改执行入口闩并收集异常，恰好一次断言不变，单组7/7与最终全量通过。
- 真jar发现banner/日志污染JSON stdout及CLI硬编码teacher角色不符权威枚举；早期锁定日志并框架输出导stderr、parse复用viewer/operator/admin/leader，已在同一jar严格复验。
- PG曾20:10:36收到来源未确定的smart shutdown并正常停止；主Agent核对专属目录/端口后恢复17.10实例，只读身份核验通过，原数据/恢复资产保留；测试对不可连目标曾零写拒绝，未回退其他库。

## 隔离物料与剩余门禁

- 已建立全新Colima java-u12（4CPU/4GiB/20GiB），原dpdk保持Stopped，原Docker context仍colima-dpdk-ext；命令全部显式colima-java-u12。
- 已拉取并锁定新PG镜像digest，独立无网只读容器确认postgres17.10。Compose全新project/端口/卷/网络，默认内网固定模型/向量/MCP，真实harness profile关闭；不含生产入口改动。
- Compose静态展开/拓扑/非root/只读/tmpfs/资源及证书权限检查通过；**应用栈尚未实际冷启动、升级、恢复、回退**，留P5实证。备份排除rag_task_inputs数据而保留表结构，尚不能据参数检查声称恢复通过。
- 远程CI当前交付为工作流物料，本报告未声称远程运行通过。完整698与native CLI为本机实测；严格全路由候选轨迹、比较器反例、浏览器和三轮容量均留P5，不能继承旧72/25/89或旧ASGI布局结果。
- 下一步独立规范/规格审查；阻断关闭才自动进入P5。回退代码可回P3收口，测试数据只在隔离动态库；任何实际保留清理不以代码revert假称可恢复，原恢复资产不删除。
