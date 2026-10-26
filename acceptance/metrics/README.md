# P5 指标映射语义验收

只读依据已有J4-03/P4接受语义，不改OperationsMetrics、DomainAdapter、黄金、strict comparator、STATUS或豁免。`mapping.json`逐一覆盖冻结source34 family；4持久gauge、12过程unavailable、HTTP统计及Python runtime/created元数据差异各自明确，不能叫“34 families兼容”。

```bash
python3 -m unittest discover -s acceptance/metrics -p 'test_*.py' -v
python3 acceptance/metrics/validate.py --wire /绝对路径/raw.prom --expectations acceptance/metrics/fixtures/known-and-unknown.expectations.json
```

纯fixture已知数据：终态任务1；已知21tokens/7 microUSD；另一真实调用usage_source=unknown且tokens/cost NULL。validator验证HTTP status/media、TYPE、重复声明/series、维度白名单、固定HTTP method/status、summary/count一致、12缺项available0、已知值、NULL不伪造0、unknown为正且与真实持久行数一致。负例覆盖错误counter类型、泄露标签、unknown0/缺失、NULL补0、缺marker/假available1、伪造python_*凑数、错误已知值/多余cost币种、孤立summary、非有限/负值与重复标签。手工fixture不是PG或HTTP证据。

真实HTTP种子工具 `MetricsHttpSeedSmoke.java` 复用正式Application、OperationsDomainMetricsAdapter及UsageLedgerStore，先TestPg身份核验创建独立动态库，再写固定已知/未知/零调用种子，仅在本机随机loopback端口GET /metrics；finally关context并删本轮动态库。该工具需主Agent分配局部PG/HTTP窗口，不借用UI库、不自动启动实例、不连接模型/MCP。可复现命令（四项TestPg配置必须来自本轮显式隔离环境，不source旧.env）：

```bash
# 已显式设置JAVA_HOME与DPDK_TEST_PG_URL/USER/PASSWORD/DATA_DIR；不打印PASSWORD。
./backend-java/mvnw -o -q -f backend-java/pom.xml dependency:build-classpath -Dmdep.outputFile=/tmp/p5-metrics-cp
"$JAVA_HOME/bin/java" --class-path "backend-java/target/test-classes:backend-java/target/classes:$(cat /tmp/p5-metrics-cp)" \
  acceptance/metrics/MetricsHttpSeedSmoke.java /绝对路径/新raw.prom
python3 acceptance/metrics/validate.py --wire /绝对路径/新raw.prom --expectations acceptance/metrics/fixtures/seed-http.expectations.json
```

`capture.py`只支持显式匿名loopback HTTP /metrics，用于已获准的现有进程只读采样；不发现/启动服务，不修改数据库种子。来源SHA由调用者提供并明确标注，不从响应猜测构建身份。raw wire保留本地600；提交证据只保存hash、已知种子预期、断言与覆盖边界。

报告的`known_value_checks=0`仅表示结构及当前可见series一致性；unknown未有正行时明确not_exercised。它不能证明unknown真实路径。当前真实owned PG+HTTP种子12项检查含unknown正行通过，rootUI只读采样和full04 case另列覆盖，均不能代替最终新源码重采。

原full04 `GET /metrics::success` strict 34/6 family差异必须保留，任何semantic结果均带raw_wire_equivalence=false/strict_verdict_unchanged=true。root决定如何使用既有规格映射证据，本工具不增加豁免或宣布全矩阵通过。
