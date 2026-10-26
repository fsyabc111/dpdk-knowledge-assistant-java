# J5-02 TCP容量入口

当前资格入口调试中，正式容量未运行，不代表通过。运行需主Agent显式分配独占窗口；不与构建、浏览器、故障、其他PG负载并跑。

本次准备已在工作树合并集成`2735147`（merge提交`fc41616`）；3个共享冲突经主Agent授权全取integration原样，最终树与该集成源一致。冻结80b29135与当前backend tree同为`38efd2a4d4ad284726989974bbb521ec4d4801dd`。独立采集/组合改动仅做18项纯测试及CLI/本机只读物理元数据探测，未连接PG或启动服务/测量；实际运行必须绑定本次driver提交和新输出目录，等待主Agent窗口。

资格01实况：主Agent释放独占资格窗口后，`dbe4e84 --runtime python --qualification --rounds1 --warmup2 --measurement15`在全新`j5-python-qualification-20261026-01`目录执行，启动阶段因`data_li_knowledge_v1`不存在退出1，未开始预热/测量，无延迟/资源样本。失败日志、ownership和新合成库全保留。修复仅在Python测试bootstrap调用冻结`_new_pgvector_store(...,perform_setup=True)._initialize()`创建真实动态LI表（同P0 oracle prepare_node_table），不改backend/不复制第二份DDL；启动前run-manifest绑定source/物理元数据。纯测试20/20通过；随后资格02必须用新目录，不能覆盖01或当作正式容量证据。

共同驱动与统计：`acceptance/load/run.py`；Python副本：`python_server.py`；Java应用：测试classpath的 `io.dpdk.assistant.acceptance.load.CapacityServer`。只绑定127.0.0.1，控制接口有随机key；进程内固定模型、向量和MCP。检索走真实PG活动发布、四腿召回、RRF/MMR/引用，不用假chunk。50ms仅属于实际外部向量调用，叠加真实检索成本；缓存命中不调用向量、不额外加50ms。新TCP基线与P0整段50ms检索替身有明确差异，须重测双端新基线；保留旧证据与全部冻结阈值。

60真实合成账号、bcrypt12、稳定真实token、1作业、同一份每轮运行时随机合成源码、quota100000/正式quota20、10个共享问答槽。分组/端点/节拍/计时见预检与原 `migration/oracle/performance/README.md`；新TCP两端均10ms GET轮询观测讲解终态。两端都保留真实rewrite/query embedding共享缓存（TTL600s/max512）与单飞。Java只提供 `PgReleaseReader.QueryEmbeddingClient` 固定向量外部callback，reader由生产 `RetrievalWiring` 创建；不能以自定义检索服务替换生产缓存/排序。两端固定provider首次实际调用不虚填usage，后续真实cache_hit零调用仍由生产路径记录。

连接池使用生产 `dpdk.datasource.maximum-pool-size=32` 配置，并在Java就绪前断言实际Hikari上限32；不再启动后修改池。共享生产配置无显式值时仍默认8，容量不得拿默认8偷换冻结32口径。

TCP来源布局：负载器是本机可信代理，发送60个不同学生的 `X-Forwarded-For:198.18.0.N`；Python Uvicorn只信127.0.0.1，Java Tomcat native remoteip只信127.0.0.1；不调大业务限流。同IP11次登录应有429，作为运行前反例。该布局必须在双端新基线声明，不能说旧ASGI包含TCP/代理。

构建与纯测试（无PG）：

```sh
export JAVA_HOME=/opt/homebrew/opt/openjdk@21
./backend-java/mvnw -f backend-java/pom.xml -B -DskipTests test-compile dependency:build-classpath -Dmdep.outputFile="$PWD/.migration-artifacts/j5-classpath.txt"
PYTHONDONTWRITEBYTECODE=1 /Volumes/study/code/new_project/dpdk-knowledge-assistant-java/.migration-artifacts/p0/venv/bin/python -B -m unittest acceptance.load.test_statistics acceptance.contract.test_isolation -v
```

PG窗口放行后，四个 `DPDK_TEST_PG_URL/USER/PASSWORD/DATA_DIR` 必填；URL指向显式隔离实例的postgres维护库。不要读取旧.env，不把凭据放命令行/证据。共享 `PgIsolation` 生成动态 `p5_capacity_` / `p5_resilience_` 库和精确 `p5-owned:` comment；每次写前核验manifest归属与现场身份。每次新输出目录，拒绝覆盖；默认不删数据库/恢复资产。

资格短测只验证入口，六组请求/账本/时钟完整均满足时返回退出0并写 `qualification_pass=true`；正式 `evaluation.pass` 保持null，永不判容量通过：

```sh
PYTHONDONTWRITEBYTECODE=1 /Volumes/study/code/new_project/dpdk-knowledge-assistant-java/.migration-artifacts/p0/venv/bin/python -B acceptance/load/run.py --runtime both --qualification --rounds 1 --warmup 2 --measurement 15 --classpath "$PWD/.migration-artifacts/j5-classpath.txt" --output "$PWD/.migration-artifacts/j5-capacity-qualification-001"
```

Python可单独资格/正式采集，不依赖Java构建或`--classpath`，均须先取得对应独占窗口：

```sh
PYTHONDONTWRITEBYTECODE=1 /Volumes/study/code/new_project/dpdk-knowledge-assistant-java/.migration-artifacts/p0/venv/bin/python -B acceptance/load/run.py --runtime python --qualification --rounds 1 --warmup 2 --measurement 15 --output "$PWD/.migration-artifacts/j5-python-qualification-001"
caffeinate -i env PYTHONDONTWRITEBYTECODE=1 /Volumes/study/code/new_project/dpdk-knowledge-assistant-java/.migration-artifacts/p0/venv/bin/python -B acceptance/load/run.py --runtime python --rounds 3 --warmup 120 --measurement 600 --output "$PWD/.migration-artifacts/j5-python-baseline-001"
```

单端完整结果保存于`python/summary.json`和独立`baseline-summary.json`，所有预热/测量逐轮原始文件照常保留。`baseline_complete`表示三轮均有120s预热/600s测量且实际时长完整；`baseline_valid`还要求完整时钟/同步采样覆盖与业务正确性。有效采集退出0；真实超限仍在`frozen_threshold_observation.pass=false`和失败清单中原样保留，不能拿退出0解释为门槛通过。没有另一端时`candidate_capacity_pass=null`、`evaluation.pass=null`，布局门禁不通过；不更改任何冻结门槛或把新Python结果重定为新阈值。

正式采集还保存干净source commit、冻结80b29135的backend tree、负载/边界文件与thresholds指纹、去标识物理机ID、CPU/内存/OS/Python、PG实例/版本/vector/配置等证据。Java日后单独以同样三轮采集（仍需`--classpath`）；离线组合命令不启动服务或PG，必须两端完整且source/负载、布局、物理环境/PG、逐轮六组/错误分母/同步资源/正确性及冻结数值全部满足才返回候选通过，缺任何一方/证据均拒过：

```sh
PYTHONDONTWRITEBYTECODE=1 /Volumes/study/code/new_project/dpdk-knowledge-assistant-java/.migration-artifacts/p0/venv/bin/python -B acceptance/load/compare.py --python-report "$PWD/.migration-artifacts/j5-python-baseline-001/python/summary.json" --java-report "$PWD/.migration-artifacts/j5-java-candidate-001/java/summary.json" --output "$PWD/.migration-artifacts/j5-capacity-pair-001.json"
```

完整正式测量（至少72分钟+启动/排空/建库；必须等独占窗口）：

```sh
caffeinate -i env PYTHONDONTWRITEBYTECODE=1 /Volumes/study/code/new_project/dpdk-knowledge-assistant-java/.migration-artifacts/p0/venv/bin/python -B acceptance/load/run.py --runtime both --rounds 3 --warmup 120 --measurement 600 --classpath "$PWD/.migration-artifacts/j5-classpath.txt" --output "$PWD/.migration-artifacts/j5-capacity-formal-001"
```

Java固定 `-Xms64m -Xmx256m -XX:MaxMetaspaceSize=128m -XX:ReservedCodeCacheSize=48m -Xss512k`，不关闭维护/心跳/usage/业务能力。记录实际JVM参数；若未来部署采用不同参数须另测并记录。资源域是应用PID+驱动/统计/采样PID，同点CPU/RSS先加再算p95/峰值；PG进程单列，不加进512MiB门禁。负载与同步ps/PG采样竞争保留。资源值不减掉driver，也不相加异时峰值。

macOS正式入口在建库前核实本命令的直接caffeinate守护进程带 `-i`（macOS会fork守护子进程再exec命令，不假定它必须是父进程），不满足即拒绝。该守护PID也纳入两端同点CPU/RSS组合，报告实际测量域。每个采样点同时记录wall和monotonic，记录末尾排空样本。相邻单调采样间隔>5s、单调时间不递增、wall与monotonic增量差>2s时，该预热/测量轮次标为invalid；全部原始样本和失败轮次保留，不覆盖或缩短正式时长。不从采样值扣除任何成本。

所有预热、失败、超时、每轮原始延迟与资源留gitignored输出目录；运行manifest含临时token和control key，不提交。可提交证据只导出无凭据合成摘要、断言、版本/hash、失败列表。J0-02证据与阈值文件保持只读；新Python较慢不得重定冻结阈值。
