# P1消费交接（当前未授权实施）

P0工程、合成验证与独立审查已收口；P1仍需用户直接放行，不能因本文件开始Java实现。状态以docs/java-migration/STATUS.md为准。

## 固定输入

- 功能源码：初始提交80b29135faddd16d5e39fdbf0633291ea6a20489中的Python副本；baseline-manifest经378文件校验。原目录只作来源，不同步新变化。
- 契约p0-v1：coverage-map的66路由/29表/103配置/64模型；transaction-ports的18端口；identity-and-data/http-and-sse/schema-migration及compatibility-differences账本。
- 当前fixture p0-v2：manifest逐文件SHA保护，golden SHA8d0658661fedb31c6a0690ea97cb3167534504049c65861b3c67e6691337de14；包含186 HTTP观测、25业务表198步状态链和11 PG/检索/清理专例。历史p0-v1不可覆盖。
- 数值门槛：thresholds.json / DECISIONS U09、P0-D15；COMP-003批准安全目标独立于Python黄金。不得将Python unknown重投复现通过当作Java安全策略通过。

## 获准后的起步顺序

1. J1-01单写backend-java构建、platform/contracts、共享配置、DDL。包根io.dpdk.assistant、Java21/Boot4.0.9/AI2.0.2；Spring JDBC/Flyway及精确BOM见P0-D14。先实际resolve/build并固定JDK补丁、Wrapper、有效POM和依赖树，不能把官方兼容资料当构建通过。
2. 任何DB/Flyway写前校验目标身份；禁用ambient/.env回退、ORM/VectorStore自动DDL。DDL唯一owner主Agent/root，B15/V14/V15空库/合成13/等价15三路径和重复启动由J1-01实测，不看标签盲baseline。
3. J1-02只实现模型/embedding/MCP边界，保留tool_call_id、usage unknown、submitted未知失败分类。显式Agent/授权循环不交给框架默认Advisor。
4. J1-03读取固定release/配置与原LI物理节点，消费p0-v2的pg-vector-rrf-mmr及六引用用例；排序精确、raw score仅明列路径容差1e-12，引用round4/业务得分精确。
5. J1-04只验收PLAN规定的synthetic最小问答及只读工具替身；持久业务、完整task/chat/SSE按J2/J3接线，不扩大P1完成声明。

## 差分如何使用

- 全量verify入口：`.migration-artifacts/p0/venv/bin/python -m migration.oracle.verify --fixture p0-v2 --candidate <候选trace>`；它只读黄金，不能写或反向生成黄金。
- 纯组件/阶段切片可以只读提取fixture中明确case/端口向量，建立限定断言并报告未实现项；不删黄金字段、不填Python结果冒充Java、不把限定切片PASS写成186条全量PASS。全量候选在对应功能实现后验收，完整业务组合按各卡/J5要求补齐。
- 默认全部使用合成数据/固定向量/本地模型MCP替身；真实模型、embedding、harness和生产切换仍需各自授权。
- 资源门槛有严格测量域：本次Python应用+负载/采样器合计，同进程ASGI不含TCP；Java需等价adapter按时间合计Java+负载进程。域未对齐只观察，不判容量通过。
- 后续扩展fixture向owner提出新版本请求，不覆盖当前版本。RTO1秒仅10ms脚本重驱动执行，330秒默认调度等待仅推导，不能用作生产恢复承诺。

## 环境恢复与未验证

本任务PG已正常停止，pgdata/venv/原始证据全部保留；准确恢复命令见docs/java-migration/handoffs/MAIN.md。先启动并运行oracle身份预检，不能运行复制来的start_dev、local_harness、deploy或旧Compose。

Java构建、JDK补丁、Flyway实际三路径、真实旧部署/schema、zhparser、真实DPDK/harness/模型质量、React全页与完整业务并发组合均未验证。本文件不豁免后续门禁，也不授权推送、合并或部署；每批以用户直接授权及当前AGENTS约定为准。
