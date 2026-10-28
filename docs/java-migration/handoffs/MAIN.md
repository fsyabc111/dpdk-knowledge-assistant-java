# 主Agent恢复点

## 2026-10-28 U12，P5场景验收中

授权与唯一任务状态见../STATUS.md。上份完整恢复点保存在archive/MAIN-U12-P5-pre-resume-20261028.md；P3/P4已经工程放行，不重做。

- 分支codex/p3-p5-longrun；最近已推并SSH核验7482594b9eef18f352f607c940ff119aad145aea。生产源码ff59cdab65c2c8977f3f9c45701b773ad9116ccd，后续仅验收/证据。
- P3 5d0d6a3/591项/两轴4阻断关闭；P4 f020efa/707项/CLI2及16 JVM/CI40198306859四job/双轴通过。P5两轴ff59代码阻断规范3/规格2均关，但总门禁未过。
- ff59全verify777发现/774执行通过/3 boundary skip；新jar14caee2d2ef692e8fd7bbe9ca119aaba6e78a615ea0ce6b2f2200b81f2ca722e；同jar CLI2/16 JVM全过，前端102/build过。当前GitHub CI40316421823四job全部success，已经实际查询，不继承旧CI。
- 16领域指标真实因果原worker0847bda→main eb719b6，README8585073→7482594，实际正式Application/真PG/外部loopback，16域25断言/46families236series；probe/固定expect/wire SHA绑定ff59。所有进程关闭，owned库已drop。索引evidence/J5-01/metrics-mapping-causal-ff59.{md,json,prom}；用户拒绝原12 unavailable/gauge豁免。

## 三worker和窗口

1. acceptance_matrix复用worktree j5-01，生产已同步ff59。full07完整186观察/198步/25表，raw CLI rc1只剩metrics runtime4 descriptor；其余185严格同。extensions06为225/225，两轮owned库均drop，无Java/PG资源。现在仅纯比较/metrics单case policy+反例与证据commit，原黄金/oracle/corecompare不改。
2. capacity复用j5-02，00f265b merge9250，clock fresh目录j5-02-clock-20261028-01已实跑DefaultBoundaryPgTest3/3，受控时间600s/24h/300s边界，无跳过；owned p5_resilience_4da4a807a7b105b0_clock_boundaries保留，终态3/input0/连接0。已获独占accepted资格→all11故障/41队列/lifecycle窗口。Java正式36分钟尚未授权开始，等故障/矩阵放行。
3. operations复用j5-03@8585073，指标因果已交、无runtime。现只补J5-04匹配版本许可文本/索引，轻量静态/官方源，不启动Docker/构建/PG。候选镜像和四实际部署场景仍未执行。

Root当前仅轻量文档/静态/CI读取，不与故障窗口并行构建/测试/UI。正式Java测量前停所有新VM/自有Chrome/其他PG负载，必须caffeinate+时钟与其他DB污染检查。失败与恢复资产不删，不能因单卡绿提前结束。

## Python容量基线已完成，不能再算Java通过

worker source0f03c1495f638af293547b46fe21345d96461ffa，Python backend tree38efd冻结80b不变。3×120s预热+600s测量全有效；CPU p95 31.15/31.95/31.61%一核、RSS397.53/405.42/412.06MiB，所有冻结阈值观察通过。13实际测量传输错误保留且各轮/组≤0.1%，原因未确定。10485任务answered、无pending/input、177grade/扣次/MCP一致。candidate容量pass=null。

原始目录j5-02/.migration-artifacts/j5-python-baseline-20261026-01完整保留；去敏evidence/J5-02/python-tcp-baseline-20261026/。acceptance/load/compare.py严格双端报告，缺Java不判pass。Java需新资格后完整3×120+600，禁止缩短、删失败、改阈值或同时构建/容器/UI/其他PG。

## 精确资源与恢复资产

- PG maintenance URL jdbc:postgresql://127.0.0.1:55481/postgres；user p1_java；DATA_DIR主checkout/.migration-artifacts/p1/pgdata；password只解析主checkout/.migration-artifacts/p1/local-credentials.txt的DPDK_TEST_PG_PASSWORD键，禁止打印。PgIsolation/TestPg核验先于CREATE/DDL/DROP，无FORCE。2026-10-28root已start同一实例，非新建/迁移旧库。
- PG恢复命令：/opt/homebrew/opt/postgresql@17/bin/pg_ctl -D .migration-artifacts/p1/pgdata -l .migration-artifacts/p1/postgres.log -o '-p 55481 -h 127.0.0.1 -k /tmp/dpdk-java-p1-80b29135' start 。已运行不重复执行。
- JAVA_HOME=/opt/homebrew/opt/openjdk@21，实际21.0.12.1；Python主checkout/.migration-artifacts/p0/venv/bin/python；Node24.18.1；PG17.10/vector0.8.4。
- 新java-u12 4CPU/4GiB/20GiB当前Stopped；所有Docker显式--context colima-java-u12，endpoint unix:///Users/fsy/.colima/java-u12/docker.sock，daemonID8f66e5ea-9c07-4379-b27d-7a5759cc2cd3，Name colima-java-u12。原dpdk Stopped，全局context colima-dpdk-ext不变。禁止动旧profile/context/业务库。
- P4/P5 native jar均保留主checkout/.migration-artifacts/recovery/jars/<SHA>.jar；P4 ef1b4f8e...，P5 14caee...。P4 Docker image证据evidence/J5-03/p4-images.json，归档j5-03/.migration-artifacts/j5-03/build/p4-*.image.tar，不能删。
- UI22保存场景evidence/J5-01/browser-progress.json；原UI02/03数据库均drop、进程/自有Chrome已停。最终源重点UI需全新owned round，browser-harness/独立Chrome，不复用旧端口状态。冻结源leader直属学生树缺陷另列known-source-ui-gap.md，未扩大范围修复。
- J5-03 prepared project dpdk-java-isolated-747eddb2a98a仅manifest，实际stack未启动；candidate image槽位空。0013/恢复/应用回退要真做，不能把plan/静态/库存当通过。
- HTTPS GitHub偶发SSL/EOF，SSH443 push/ls-remote有效；GODEBUG=http2client=0 gh本轮查询成功，不改持久网络配置。

## 必须完成的余项

J5-01 metrics bounded policy最终证据→J5-02全部故障/Java资格+完整36分钟/严格配对→最终源重点浏览器回归→候选不可变镜像与J5-03四实际场景→P5报告+独立双轴最终证据审查→J5-04最终manifest/许可/接手复现/11卡交接→小步push及remoteSHA一致/clean。

继续独立可做项；只有全部外部硬阻塞才报告未完成。禁止live/合main/生产切换/Python退役/恢复资产删除；用户验收不代签。
