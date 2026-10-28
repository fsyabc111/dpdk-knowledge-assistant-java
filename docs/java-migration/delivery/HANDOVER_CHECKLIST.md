# 接手验收步骤

本清单用于接手，当前未替用户验收签字。交付manifest中的null/pending必须以实际证据补齐，不能从“准备完成”自动推导“迁移完成”。部署与回退操作只引用现有cutover-runbook。

1. 核对仓库、分支、候选源码SHA及远程SHA相等；检查工作区未混入凭据、env、私钥、源码运行输入、DB、原始模型输出和日志。原项目/部署/业务库保持只读。当前候选源码与远程一致性仍待主Agent最终冻结验证。
2. 核对Java21、锁定pom、实际resolved依赖、frontend lock、许可证声明清单与构建输入hash；校验真实jar/image/静态资源摘要。P4回退资产已有证据；核对CONTAINER_DEPENDENCIES.md对应的P4/锁定base实物库存与license文件hash；P5候选差异、缺失文本与完整NOTICE仍pending。
3. 按明确环境运行后端纯单测、隔离PG测试、前端test/build，记录本次实际pass/fail/skip。历史P4结果不能替代候选重新执行；PG跳过、CI未运行、源缺失不能计通过。
4. 查看J5-01严格矩阵及J5-02故障/容量门禁。当前J5-01核心已过、J5-02故障已过，同协议双端正式容量待完成，不开放J5-03实际stack步骤。冻结阈值和测量域，不能以改阈值或过滤失败请求获取通过。
5. 门禁及主Agent资源窗口具备后按唯一runbook执行新round冷启、合成0013受控升级、真实业务写入、备份排临时数据、另一空库实际恢复、旧应用回读新业务并新写。核对TLS/SSE/API、角色权限、旧引用、用量unknown、任务恢复、指标和临时源码哨兵。当前全部pending。
6. 校验备份/恢复库manifest、业务计数/身份/active release与source指纹、源码哨兵零泄漏；保留P4/P5真实image归档、备份和新数据。报告RTO/RPO边界；1秒局部恢复门禁不能套用到含DB恢复/启动/TLS的整段耗时。
7. 阅读已知限制和未测项；维护人能独立找到CLI帮助、默认dry-run与显式apply、健康/ready、内部metrics和备份恢复入口。记录交付owner、支持边界和精确下一步，不复制另一套切换流程。
8. 用户验收独立记录；live模型/embedding/真实harness需另有账号/预算/数据/版本和人工复核安排。生产切换、Python退役、恢复资产删除各有独立放行点，本准备包未授权或执行。

建议接手记录字段：验收人/时间、候选SHA和imageID、契约/fixture/schema/knowledge版本、实际命令与环境、各项结果、已接受限制、未接受阻断、RTO/RPO范围、资产与owner、后续授权边界。未测项保留pending，不代填“用户已接受”。
