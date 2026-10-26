# 工作流独立持久时间契约修复

来源是matrix保留的真实Python/Java逻辑微秒差分，不是为凑tick添加读钟。只改KnowledgeWorkflowService及新增PG回归；黄金、比较器、共享wiring/契约和业务状态语义未改。

| 冻结源持久操作 | 真实调用依据 | 修复 |
|---|---|---|
| 知识发布/PATCH主行与修订 | api/rag.py166/174行now、177修订own now | 修订独立取时，主行时间仅作既有单调下限 |
| 问题PATCH主行与事件 | api/rag.py110行now、RagIssueService.event438 own now | event helper独立取时，不复用主行值 |
| 反馈insert/update/event | RagIssueService381、431、438分别now（即使insert冲突也求值） | 保留insert/update取时，事件另取own now |
| answered验证事实/主行/event | mark_verified449的两个now、event438 now | verified_at→updated_at→event各自取时 |
| 非answered验证 | mark_verified449条件表达式elseNone，只有updated now+event now | 仍写verified_at=NULL且不消费verifiedAt时钟；没有改成保留旧verified_at |

新增公开工作流PG测试使用每次nowUtc推进1微秒的ClockPort，经knowledge/revisions/issue公开读取验证已持久字段精确文字值。先红知识修订复用→绿；再红问题/反馈事件复用→绿；再红验证updated复用→绿。原固定时钟并发事件顺序、CAS拒绝旧反馈/修订、事务回滚和真实原题验证集成回归均通过。

最终单卡命令：显式Java21及root指定TestPg四项身份环境，`-Dtest=WorkflowTimestampContractPgTest,KnowledgeWorkflowContractTest,IssueConcurrencyIntegrationTest,KnowledgePublicationIntegrationTest test`，**23/23，0 failure/error/skip**。TestPg独立动态库在finally清理，局部PG窗口已释放；无实例、其他库、模型/真实MCP/旧环境操作。

逻辑时间来源、选取调用栈、红绿结果与逐类数在workflow-timestamp-contract.json。原始clock/构建日志留ignore路径，未提交原始日志。最终源码的全矩阵重放由主Agent/matrix继续，本局部通过不代签全矩阵或用户验收。
