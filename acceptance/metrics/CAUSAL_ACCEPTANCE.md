# 正式Application的16领域因果验收

ProcessMetricsCausalSmoke.java只经产品HTTP/公开业务facade和独立AgentOrchestrator.run触发正式共享registry；外部模型/embedding为本类自带loopback固定替身，检索/会话/任务/验证/配额/用量事务/持久化/指标filter全部真实。不要把ExportProcessFixture的port合成证明当作本入口。

运行只用root明确授权的轻量独立TestPg/HTTP窗口，配置四项DPDK_TEST_PG_*并先验证身份；不读旧.env、不启动Docker/PG实例，不调用真实模型。不修改共享CandidateServer/fake_upstream。Java21先编译当前源码+定向纯测，再用target/classes、target/test-classes与实际Maven依赖classpath编译本源到gitignored目录，运行参数为全新绝对输出目录及固定生产源SHA。TestPg结束finally只清理本次动态库，保留全部输出和恢复资产。

```bash
# 在已显式设置受控TestPg四项配置及metrics_classpath后，授权窗口才执行：
"$JAVA_HOME/bin/javac" -cp "$metrics_classpath" -d "$metrics_smoke_classes" \
  acceptance/metrics/ProcessMetricsCausalSmoke.java
"$JAVA_HOME/bin/java" -cp "$metrics_classpath:$metrics_smoke_classes" \
  ProcessMetricsCausalSmoke "$metrics_new_output_directory" "$metrics_production_source_sha"
python3 acceptance/metrics/validate.py --wire "$metrics_new_output_directory/06-final.prom" \
  --expectations acceptance/metrics/fixtures/process-causal.expectations.json
```

当前ff59cda实际16领域/25独立期望通过，证据在docs/java-migration/evidence/J5-01/metrics-mapping-causal-ff59.{json,md,prom}。需要维持相同源/环境口径才可复验；最终source变化应重采，不沿用历史pass。此入口不测真实provider质量/价格、真实MCP执行恢复、正式容量/RTO/RPO、完整strict矩阵或部署场景，不代签live/用户放行。
