# 主Agent恢复点

## 2026-10-26 U12，P5进行中

- 授权：P3→P4→P5隔离工程交付/J5-04准备，最多3worker，小步提交推送。禁止live/main合并/生产切换/Python退役/删除恢复资产/原环境写入；不代签用户验收。
- P3已过5d0d6a3/591项/双轴4阻断关闭，收口708128c。P4已过f020efa/707项/同jar CLI2项16JVM/CI40198306859四job/双轴复审全过。最终evidence/P4/phase-report.md、validation-reviewed.json。
- 集成codex/p3-p5-longrun，最近核验远程f020efa，随后J5准备dda13f4/14b8272/1cd1615/cfd6619已本地集成，需推送核验。

## 当前owner和下一动作

1. root独占共享配置/契约/平台/STATUS/MAIN/前端/部署fake；池上限可配置默认8、容量显式32，冻结docs/openapi资源已编译，补测试提交。修HEAD/OPTIONS、真实用量与指标。
2. acceptance_matrix原j5-01 worktree：full03实际186HTTP/198步、25表delta，strict FAIL；本地fake chunked等未提交，先定位用量差异并补6引用真实finish外层事务回滚helper，申请PG窗口。不能填黄金结果。
3. capacity原j5-02：52a6e96/e589e1e待集成，TCP/故障/队列脚本纯12Python+2Java通过；真实PG/故障/72分钟容量均未执行，等核心门禁与root窗口。
4. operations原j5-03：e601431已集成，后续二片未提交。P4镜像源码label f020efa，backend c6f4799e、frontend a862bcc，jar54b12585，archives保留；Docker socket当前消失，未重启旧环境。恢复执行器/guard/runbook纯准备继续，实际场景待J5-01核心/J5-02门禁。root写fake，worker不动共享Compose。
5. 浏览器真实多角色11场景未执行，root用browser-harness、自有Chrome profile、独立p5_ui库。J5-04交付准备与最终P5报告/双轴/全量/remoteSHA一致仍待做。

## 资源

- PG127.0.0.1:55481/postgres，p1_java，期望主checkout/.migration-artifacts/p1/pgdata；密码只解析主checkout/.migration-artifacts/p1/local-credentials.txt且不输出，四项DPDK_TEST_PG_*显式。建删/DDL先身份预检；不FORCE、不删恢复资产。full03已释放库/进程。
- JAVA_HOME=/opt/homebrew/opt/openjdk@21；Python依赖主checkout/.migration-artifacts/p0/venv/bin/python；Node24.18.1。
- 新Colima java-u12 4CPU/4GiB/20GiB，context colima-java-u12，socket /Users/fsy/.colima/java-u12/docker.sock，daemonID8f66e5ea-9c07-4379-b27d-7a5759cc2cd3。旧dpdk停止，全局context不改；当前socket不存在，实际栈未启动。
- 上份P4 MAIN已归档archive/MAIN-U12-P4-integration-20261026.md。不得删除任何worktree、失败证据、镜像或恢复资产。

## 最新检查点（2026-10-26，覆盖上文旧待办）

- Git最新已推2735147，ccf5e97过程指标端口待随下一片push；production时间修复全集：ab9c603 auth缓存；ec5af87共享created/空flush；a9f0aff inputs同基准UTC；6dc2044 workflow事件时间；2735147 finish后终态前usage finally；84e76b4 Spring7逐帧flush。
- matrix已授权merge2735147并fresh full06/225：186/198 raw只剩metrics 34/6，全部时间/业务值对齐；11专例、14向量/semantic、auth15/15相同。225在跑/待提交。J5-01原rawFAIL保留，不修改黄金。
- 用户已回复“补齐源过程指标”，不接受12 unavailable/持久gauge单项豁免。root ccf5e97 contracts.observability.ProcessMetrics已定义；operations正写platform.observability.ProcessMetricsRegistry + OperationsMetrics exporter/纯测试。root接ChatTaskExecutor/TaskRunner/UsageLedgerStore/Retrieval/ProductChat/GroundedVerifier实际hook与共享@Bean。先不要称metrics/fullmatrix通过。
- capacity同步main有3共享冲突已授权全取integration，merge fc41616最终树与273一致；正在补单端Python baseline_complete/valid、candidate_pass=null与后续组合器，防缺Java布局误判。尚无PG/负载/故障/容量执行。root下一步在UI/matrix释放后给Python资格→36分钟3轮正式独占窗口，Java另36分钟待最终指标源码。同阈值/布局/保留失败与全部恢复资产。
- operations新增metrics过程collector owner，其他auth/KWS均交付并已集成；公共Bean root单写。新Colima java-u12当前运行但runtime栈未启动；正式测量前只停新profile/自有Chrome/UI进程，旧dpdk/context不动，镜像/归档留存。
- root浏览器UI02 source5c8623c，20个断言文件；UI03 source2735147新增真实首帧/取消2场景，总22。browser-progress.json保存去敏摘要/哈希；原PNG/CSV/log全ignore。UI02 owned DB已dropped/进程停，P4原jar ef1b4f...已归档到.migration-artifacts/recovery/jars/ef1b4f...jar，clean不会删该恢复目录。
- UI03 session31901，资源metadata .migration-artifacts/j5-ui/browser-03/session.json（含owned库/children PID/端口，不含口令），即将SIGTERM其父UI Python做清理。Chrome自有PID68883/exec64498，profile .migration-artifacts/j5-ui/chrome-profile/CDP19222；caffeinate旧UI02绑定68869已随结束退出。正式前停全部本轮浏览器，禁止误停用户Chrome。
- 根README/backendREADME与acceptance/ui/**及browser-progress未提交，root收口后提交；Root还需完整PG verify/native jar/最终CI、UI最终指标源小复验、J5-02实际故障/72分钟容量、J5-03实际冷启升级备份恢复回退、最终P5报告/双轴独立复审/交付manifest与remoteSHA一致。不能因当前单卡绿而提前结束。

## 最新执行点（2026-10-26 19时后，优先本节）

- integration最新ff59cdab65c2c8977f3f9c45701b773ad9116ccd，已push并用SSH ls-remote核验相等；HTTPS GitHub偶发SSL/EOF，SSH443有效，不修改网络配置。
- Python正式基线已完整3×120+600结束，source0f03c14/backend树38efd冻结，clock/其他DB隔离有效，CPU p95 31.15/31.95/31.61%、RSS397.53/405.42/412.06MiB，所有冻结观察通过；13测量传输错均保留且每组/轮≤0.1%，原因待定。10485任务answered、无pending/input，177grade/扣次/MCP一致，candidate容量仍null。证据359e63d已集成REPORT+summary，原始DB/资产保留。
- 用户明确选补齐源过程指标：ProcessMetricsRegistry+HTTP collector已集成15a17e2/332aefe、根业务hook f1c5bb5，正counter/histogram，无12unavailable默认豁免；旧persistent诊断仍仅历史。正式HTTP/实际afterCommit known/unknown回归fd2cd74、Root产品因果25项过，不把合成port事件当全业务覆盖。
- 两轴首审固定f1共5代码阻断，报告evidence/P5/review-*-first.md。修复全集：4847d9e恢复产品engineunknown/token只独立Agent、图异常error/cancelled；44d58fc Reader503词法降级+marker；713cdb0 late usage checkpoint/锁外sink/producer ack/100ms握手+CandidateServer settled等ack；8b91935 backup全public retained row multiset/列结构hash、同count错grade拒绝，真实native SQL已fd2验证。O01/O02独立观测范围和SIGKILL未回执durable票据不保证见DECISIONS。
- 全量r1在83b65fd为775发现1失败、3跳过：旧P3预算测试没算源恢复的rewrite local/cache_hit零调用，被误当第三模型；ff59仍严格保留实际model次数/token/扣次/grade0，单独锁住rewrite0call；目标10/10过。3skip仅DefaultBoundaryPgTest需显式J5_BOUNDARY_DB_NAME/COMMENT（J5-02 clock入口必须实际执行，未当pass）。当前r2 full verify跑主checkout exec1264，log p5-review-fixes-full-verify-r2.log，sourceff59，新2个EmbeddingFallbackProductPgTest已含；不要同时在本checkoutclean编译。
- 当前3worker：acceptance_matrix授权mergeff59/fresh full07+225+metrics过程validator，原oracleCLI原样保留，另metrics单case语义map只准descriptor无序+明确JVM runtime4，拒重复/错TYPE，其他业务数组绝不归一；review_standards与review_spec独立只读复审f1→ff59，写各recheck报告。其他owner均已交回，无生产写锁。
- operations所有片已集成：9d4collector验证、151实际HTTP、368backup proof、246容器库存（main83b65fd）。7锁定镜像实际728 OS installed、1390 license文件hash、Node198 package声明；P4继承JRE/nginx4域一致。实际P5image/缺LICENSE文本/完整NOTICE仍明确pending，不作法律签认。java-u12已恢复Running仅离线库存query，实际stack尚未启动；旧dpdk/context不动。下一Java正式窗口前必须再停新VM。
- User UI22场景+新SSE真实首帧/取消已保存browser-progress，UI02/03所有进程和owned库已停/drop，Chrome自有68883已退出；无需沿旧端口15174继续。后续最终源码重点UI回归需新owned轮，禁止借基线库。
- 待办：接r2完整结果/同jar CLI/CI；两轴5阻断复核+metrics真实fresh报告；J5-02先accepted故障资格→全11故障/queue41/lifecycle/clock默认600/24h实际门禁→Java资格→3×120+600正式(36分钟)+与Python离线严格组合。期间仍需保持无并行负载。J5-03候选不可变镜像构建后实际cold/0013/backup_restore/rollback带完整业务proof，J5-04最终manifest、README独立复现、版本/恢复资产/风险/交接、remoteSHA一致性均未完成。不要提前最终收口。

### r2回执与当前短窗口

- exec1264已exit0：ff59完整verify发现777/执行774过，3skip只是J5_BOUNDARY_DB_NAME/COMMENT显式门禁DefaultBoundaryPgTest，尚未跑；log p5-review-fixes-full-verify-r2.log。新jar SHA14caee2d2ef692e8fd7bbe9ca119aaba6e78a615ea0ce6b2f2200b81f2ca722e，root正在同jar OperationsBootJarAcceptance追加2/16JVM（log p5-reviewed-native-cli.log）。
- 两轴ff59复审原5阻断全关，报告evidence/P5/review-*-recheck.md；尚未收P5完整场景。matrix full07/225进行；operations补正式app真实业务→16源过程域因果观测（禁止直接emit假数据），独立PG短窗口已授权。capacity/model_adapter此前已结束交回所有源码锁；等这两个worker/CLI完成，给capacity clock/accepted→全部故障独占，再Java资格→36分钟正式。
- 官方Prometheus规范独立metric group顺序不强制；metrics单case比较只能按唯一name/TYPE schema+明确JVM runtime4映射，重复metric/series、bucket反序/错type/未知family必须拒过；业务数组仍exact。matrix写独立policy，不改原oracle/golden，原raw CLI差异保留。

- native CLI同jar追加已exit0，2/2、16子JVM，SHA14caee...722e；evidence/P5/validation-code.json绑定ff59，源码/分组pass/3boundary skip分开。当前root无在跑Maven/PG/HTTP，只有matrix fresh07与ops实际16域因果短窗口；两轴已结束复审，5代码阻断全关。下一实际fault/Javaformal必须待这2worker短窗口归还。
