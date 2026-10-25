# J5-02 TCP容量入口

当前是实现入口，尚未运行PG/资格短测/正式容量，不代表通过。运行需主Agent显式分配独占窗口；不与构建、浏览器、故障、其他PG负载并跑。

共同驱动与统计：`acceptance/load/run.py`；Python副本：`python_server.py`；Java应用：测试classpath的 `io.dpdk.assistant.acceptance.load.CapacityServer`。只绑定127.0.0.1，控制接口有随机key；进程内固定模型、向量和MCP。检索走真实PG活动发布、四腿召回、RRF/MMR/引用，不用假chunk。50ms是固定检索外部向量预算，叠加真实检索成本，因此新TCP基线与P0整段50ms替身有明确差异；保留旧证据与全部冻结阈值。

60真实合成账号、bcrypt12、稳定真实token、1作业、同一份每轮运行时随机合成源码、quota100000/正式quota20、10个共享问答槽。分组/端点/节拍/计时见预检与原 `migration/oracle/performance/README.md`；新TCP两端均10ms GET轮询观测讲解终态。Python检索仍有真实查询缓存；Java无该缓存，这是真实实现成本，不能关闭/移出采样域。

TCP来源布局：负载器是本机可信代理，发送60个不同学生的 `X-Forwarded-For:198.18.0.N`；Python Uvicorn只信127.0.0.1，Java Tomcat native remoteip只信127.0.0.1；不调大业务限流。同IP11次登录应有429，作为运行前反例。该布局必须在双端新基线声明，不能说旧ASGI包含TCP/代理。

构建与纯测试（无PG）：

```sh
export JAVA_HOME=/opt/homebrew/opt/openjdk@21
./backend-java/mvnw -f backend-java/pom.xml -B -DskipTests test-compile dependency:build-classpath -Dmdep.outputFile="$PWD/.migration-artifacts/j5-classpath.txt"
PYTHONDONTWRITEBYTECODE=1 /Volumes/study/code/new_project/dpdk-knowledge-assistant-java/.migration-artifacts/p0/venv/bin/python -B -m unittest acceptance.load.test_statistics acceptance.contract.test_isolation -v
```

PG窗口放行后，四个 `DPDK_TEST_PG_URL/USER/PASSWORD/DATA_DIR` 必填；URL指向显式隔离实例的postgres维护库。不要读取旧.env，不把凭据放命令行/证据。共享 `PgIsolation` 生成动态 `p5_capacity_` / `p5_resilience_` 库和精确 `p5-owned:` comment；每次写前核验manifest归属与现场身份。每次新输出目录，拒绝覆盖；默认不删数据库/恢复资产。

资格短测只验证入口，永不判容量通过：

```sh
PYTHONDONTWRITEBYTECODE=1 /Volumes/study/code/new_project/dpdk-knowledge-assistant-java/.migration-artifacts/p0/venv/bin/python -B acceptance/load/run.py --runtime both --qualification --rounds 1 --warmup 2 --measurement 15 --classpath "$PWD/.migration-artifacts/j5-classpath.txt" --output "$PWD/.migration-artifacts/j5-capacity-qualification-001"
```

完整正式测量（至少72分钟+启动/排空/建库；必须等独占窗口）：

```sh
PYTHONDONTWRITEBYTECODE=1 /Volumes/study/code/new_project/dpdk-knowledge-assistant-java/.migration-artifacts/p0/venv/bin/python -B acceptance/load/run.py --runtime both --rounds 3 --warmup 120 --measurement 600 --classpath "$PWD/.migration-artifacts/j5-classpath.txt" --output "$PWD/.migration-artifacts/j5-capacity-formal-001"
```

Java固定 `-Xms64m -Xmx256m -XX:MaxMetaspaceSize=128m -XX:ReservedCodeCacheSize=48m -Xss512k`，不关闭维护/心跳/usage/业务能力。记录实际JVM参数；若未来部署采用不同参数须另测并记录。资源域是应用PID+驱动/统计/采样PID，同点CPU/RSS先加再算p95/峰值；PG进程单列，不加进512MiB门禁。负载与同步ps/PG采样竞争保留。资源值不减掉driver，也不相加异时峰值。

所有预热、失败、超时、每轮原始延迟与资源留gitignored输出目录；运行manifest含临时token和control key，不提交。可提交证据只导出无凭据合成摘要、断言、版本/hash、失败列表。J0-02证据与阈值文件保持只读；新Python较慢不得重定冻结阈值。
