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
