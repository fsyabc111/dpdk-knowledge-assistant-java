# J5-01 契约候选复现入口

在仓库根运行。Java业务/权限/事务/任务/检索使用真实Spring实现；固定上游仅位于模型、embedding、MCP外部边界。当前已保存的严格失败与允许指标语义映射分别由阶段证据解释，不能把脚本exit 1改成通过。

## 环境

准备JDK21、Maven Wrapper网络依赖、Python3.11和专属PG17+pgvector。普通Java候选采集只需要轻量依赖：

```sh
python3 -m venv .migration-artifacts/j5-01/venv
.migration-artifacts/j5-01/venv/bin/python -m pip install -r acceptance/contract/requirements.txt
export JAVA_HOME='<JDK21绝对目录>'
export DPDK_TEST_PG_URL='jdbc:postgresql://127.0.0.1:<专属端口>/postgres'
export DPDK_TEST_PG_USER='<专属测试角色>'
export DPDK_TEST_PG_PASSWORD='<仅本轮环境中的口令>'
export DPDK_TEST_PG_DATA_DIR='<实际专属PG data_directory绝对路径>'
export PYTHONDONTWRITEBYTECODE=1
```

四项PG变量全部显式必填。运行者先确认PG窗口；脚本会再次只读验证角色、维护库、data_directory，再为本轮创建独立`p5_contract_`库并记录精确comment与600 manifest。结束时只清本manifest owned库，停止本轮子进程；PG实例与其他库/恢复资产保留。输出目录每轮新建，已有manifest拒绝覆盖。

## 编译和纯验证

```sh
acceptance/contract/build_server.sh
python3 -m unittest acceptance.contract.test_isolation acceptance.contract.test_fake_upstream acceptance.contract.test_extensions -v
./backend-java/mvnw -f backend-java/pom.xml -B -Dtest=CandidateClockTest test
```

构建先clean本工作树生成的target，再test-compile，防止已移动测试配置的旧class被Spring扫描。classpath保存在gitignored `.migration-artifacts/j5-01`，可用`J5_ARTIFACTS`显式指定其目录。以上没有PG操作；CandidateClockTest只启动本机协议替身。

## 全量候选与扩展场景

```sh
.migration-artifacts/j5-01/venv/bin/python -m acceptance.contract.run --out .migration-artifacts/j5-01/run-001
.migration-artifacts/j5-01/venv/bin/python -m acceptance.contract.run --out .migration-artifacts/j5-01/extension-001 --extended
```

第一条自动建立owned空库、启动固定上游和真实Java HTTP、种P0等价合成树，采集186观察/69 route_id/25表198步、11 PG专例、14上游协议向量、semantic/auth向量，再调用真正oracle。`--limit N`仅用于局部定位，不能代表186条通过。`--extended`使用另一全新库，覆盖三模式适用角色/实际教学链、字节/NFC、来源和源码边界；不把其额外行混入P0状态链。

主要本地产物：

- `java-candidate-trace-p0-v2.json`：真实HTTP/业务状态/外部账本，包含动态令牌，600且仅本地。
- `strict-verdict.json`、`collection-blockers.json`：严格判定与缺项；原始失败保留。
- `wire/<observation-index>.sse`、`wire/30.prom`：原始SSE/metrics字节，不先归一换行或替换family。
- `java-clock-calls.json`：按case的无参调用者类/方法/行号，无业务参数。
- `fingerprint-negative.json`：真实配置不匹配拒绝及恢复后25表不变。
- `extension-trace.json`/`extension-summary.json`：独立HTTP扩展结果。
- `isolation.json`/`instance-identity.json`：实际实例预检与本轮库所有权，不含口令。

普通结果至少区分严格golden等价、显式批准差异、组件断言、场景、浏览器与用户验收。`--keep`显式保留本轮进程/库及processes.json用于调查；清理仍调用原manifest的`PgIsolation.drop(owned)`。

独立复核：

```sh
.migration-artifacts/j5-01/venv/bin/python -m migration.oracle.verify --fixture p0-v2 --candidate .migration-artifacts/j5-01/run-001/java-candidate-trace-p0-v2.json
.migration-artifacts/j5-01/venv/bin/python -m acceptance.contract.mutations --candidate .migration-artifacts/j5-01/run-001/java-candidate-trace-p0-v2.json --out .migration-artifacts/j5-01/run-001/mutations.json
.migration-artifacts/j5-01/venv/bin/python -m acceptance.contract.differences --golden migration/fixtures/p0-v2/python-golden.json --candidate .migration-artifacts/j5-01/run-001/java-candidate-trace-p0-v2.json --out .migration-artifacts/j5-01/run-001/difference-paths.json
```

变异正例是本轮Java候选自比，变异必须被拒绝；其全绿不能代替与Python黄金等价。差分路径脚本只做归因索引，不豁免任何字段。

## 时钟诊断与主UI接口

`python_clock_probe`需要冻结Python oracle的完整依赖，可复用已隔离的P0 venv；它读取本副本backend并在新的P5 owned库执行，不启动旧部署、不运行旧P0 runner或覆盖其证据：

```sh
<oracle-python> -m acceptance.contract.python_clock_probe --out .migration-artifacts/j5-01/python-clock-001 --limit 78
```

Java `CandidateServer`手动启动配置与seed API见`docs/java-migration/handoffs/J5-01.md`“候选服务/seed接口”。主UI使用独立`p5_ui_`库及manifest；控制端口仅loopback，服务文件不进入生产artifact。Root负责浏览器验证，HTTP扩展不能替代页面点击/下载/Markdown消毒。

只有合成脱敏摘要可以提交；上述trace、模型原始输出、日志、源码输入、身份/令牌、PG数据留gitignored artifacts。黄金始终只读；扩展或批准差异由契约owner登记。
