# J4-01 只读预检与接口建议

- owner/工作区：J4-01 预备 worker；`/Volumes/study/code/new_project/dpdk-knowledge-assistant-java`，当前分支 `codex/p3-p5-longrun`。
- 当前授权：仅只读分析及本文档；等待主 Agent 的 P3 门禁放行与独立工作树派工。本文不表示 P4 已实施或验收。
- 状态：实现未开始；自动验证未执行；PG/真实上游/场景/用户验收均未执行。
- 本轮唯一写入：本文档。未提交、推送、构建、启动 PG 或调用真实模型；原项目/旧库/生产未修改。

## 已读依据

根 `AGENTS.md`；`START_HERE.md`；`ai-slices.md` J4-01；`SCOPE.md`；`COORDINATION.md`；`templates/HANDOFF.md`。源码按需读取：

- `backend/app/services/rag_index_service.py`、`knowledge_sources.py`、`llama_index_service.py` 摄取入口、`usage_service.py:flush_usage_recorder`。
- `backend/data/knowledge_sources.yml`、`backend/scripts/ingest_llama_index.py`、`rag_maintenance.py`。
- `backend/tests/test_ingest_idempotency.py`、`test_rag_versioned_postgres.py`，并定位 `test_session_release.py`。
- `contracts/index/**`、`contracts/retrieval/**`、`contracts/model/Embedding*`/`Vector1024`、`contracts/platform/**`；`retrieval/IndexConfigurationFingerprint`、`RetrievalSettings`、`PgReleaseReader`；`model/OpenAiCompatibleEmbeddingClient`、`ModelUsageLedger`；`usage/UsageLedgerStore`、`UsageLedgerRow`；现有 model/retrieval wiring。
- `migration/contracts/identity-and-data.md`、`transaction-ports.json` 索引段、`external-dependencies.json` 知识源段；`B0015__baseline.sql`；现有 PG 检索测试的隔离建库方式。

## 可实现的服务与构造依赖（建议，未登记为正式契约）

建议公开一个 `index.IndexService`，提供 `scan(SourceSettings)`、`plan(SourceSnapshot)`、`ingest(SourceSettings)`、`health(releaseId)`、`rollbackChecked(releaseId)`。内部拆 `YamlKnowledgeSource`、`BoundedLinesChunker`、`IndexPlanner`、`JdbcIndexStore` 与 `IndexUsageSink`，均留在 `index/**`。

| API | 输出/语义 | 依赖 |
|---|---|---|
| scan | 有序 desired 内容和 metadata；追加按 id 排序的 published knowledge；空源/缺源拒绝 | 显式 YAML 路径/双占位符根、文件系统、只读 published knowledge reader |
| plan | expectedActive、configuration、desired/changed/removed；未变复用 id，新节点 `li_<hex32>` | JdbcIndexStore 当前 active/既有节点；ConfigurationFingerprint；可注入 id supplier |
| ingest | release_id/nodes/unchanged/inserted_or_updated/deleted/deduplicated/table | 扫描/计划、批量 embedding、usage sink、PublishPort；embedding 在事务外 |
| health | declared/members/physical/config_match；不存在为 0/0/0/false | 同一索引表配置、指纹和数据库读取 |
| publish | CAS active + changed 物理节点 + release + 全成员单事务；竞争失败全回滚 | TransactionRunner、已验证物理表名、ClockPort、完整 PreparedIndex |
| rollbackChecked | 存在、配置一致、声明/成员/物理计数一致后只改 active | 与 health 相同校验服务，不复制脚本校验 |

构造建议显式注入 `DataSource`、`TransactionRunner`、`RetrievalSettings`（复用表名校验与 FTS 配置）、`EmbeddingConfig`、`ConfigurationFingerprint`、`ClockPort`、批量 embedding 函数与用量 sink、id supplier。published knowledge 可先由 index 内 JDBC reader 读取固定 published fixtures，后续 J4-02 仅需保持同一数据库契约。

### 需要主 Agent 集中裁决/落地的共享接口

1. `PreparedIndex(releaseId)` 与 `PublishReceipt(releaseId)` 仍为占位。建议 PreparedIndex 补 configuration/createdAt/desired nodes/changed nodes；node 至少 id/content/metadata/vector，PublishReceipt 保留 releaseId 即可；统计放 index.IngestResult。只能由集成者改 `contracts/index/**` 并登记消费者 J4-02/J4-03。
2. `EmbeddingPort` 当前仅单文本、无 usage。现有 `OpenAiCompatibleEmbeddingClient.embedTexts(List<String>, ModelUsageLedger, stage)` 已支持批次 clamp 1..10、unknown/失败计费。建议 index 包先声明可注入批量函数，由主 Agent 在 wiring 适配此富 API，避免修改 model owner 或使用单文本端口丢失用量；若主 Agent 选择公共批量端口，统一变更 contracts/model 后再实施。
3. `ModelUsageLedger` 条目要映射到现有 `UsageCore.entriesToRows`/`UsageLedgerStore`，离线 request/subject/session 均可为 null，stage=`ingest_embed`，finally 刷账，成功失败均执行。Python 写账失败为告警降级；不得令原本成功发布失败，也不得在发布回滚时丢弃已经花掉的上游账。建议 `IndexUsageSink` 封装并复用既有价格/开关策略。
4. 当前 main 源码检索未发现 release health 实现。J4-01 可提供单一 `IndexHealth` 及校验服务，由主 Agent 为后续 J4-03 健康探针接线；不在本卡新增 HTTP 路由/调度脚本。

## DDL、依赖与外部源

- 预计无需新 DDL：B0015 已有 `data_li_knowledge_v1` 的 vector(1024)/JSONB/生成 FTS 列/id sequence、`rag_releases`/`rag_release_nodes`/`rag_index_state`、active 初始行及 `rag_knowledge`。不得自动建默认 VectorStore schema，也不新增 node_id 唯一约束改变基线。
- 摄取入口必须复核 live 生成列 regconfig 与配置一致；Python `LlamaIndexIngestionService.ingest` 在不一致时硬失败。可复用或在 index 内复刻只读 catalog 探测，迁移/配置变更由主 Agent 处理。
- 无需引入新的向量库/框架。当前 pom 无显式 YAML 依赖；历史 P1 构建日志显示 SnakeYAML 2.5 曾在依赖链中，本轮未构建核验。建议主 Agent 将 `org.yaml:snakeyaml` 明确为直接依赖并沿用冻结的受管版本，或确认允许消费现有传递依赖；worker 不改 pom。
- 当前副本 `knowledge/` 存在；默认 DPDK 根为副本父目录，该默认根下 README/capture/Flask_network_new 的文件存在检查均未命中。未加载 `.env`，未确认其他配置根，未读原项目正文。因此真实全源可用性仍未验证，不可将局部快照当完整源。
- 合成方案：在测试临时目录生成两个根（repo/assistant），使用自有 YAML 精确列举 Markdown、C、YAML、目录重叠和嵌套 roots；填充 published/draft 两类知识 fixture。链接越界、缺源、空源全部本地构造。全程不需要真实 DPDK、embedding 或网络。

## 必须固定的兼容细节

- 遍历先 realpath、去重、按 Python POSIX Path 各段排序，再在**全部 YAML roots** 中取最长匹配；同长度保持 YAML 首项。当前多个 DPDK source 共根，归属常为首项 `docs/<relative>`，不能改为 include 命中的 source 名。
- root/include 越界及目录内 symlink 越界均拒绝；include 仅精确文件/目录，无 glob/exclude；文本后缀集合与基线相同。相对 YAML 以明确 cwd 解析，两个占位符只做原样替换。
- 文件 UTF-8 读取沿用 Python 通用换行；splitlines/strip/Unicode regex/正则多行匹配与路径排序需黄金对照。2600 长度和截断按 Unicode code point，不能用 Java UTF-16 char 数；不做 NFC/NFD 归一化。
- Markdown 标题、函数/结构体正则、空白段落、无空行长文本、重复片段的 `find` 行号、short/long chunk metadata 均逐项保留。metadata ordinal 为数字，start_line/end_line 为字符串。
- 增量只比较 source_key+ordinal 对位后的正文、完整 metadata 与原配置字符串，不做 hash；纯删除发布但零 embedding；同快照零 release/节点/embedding。
- 复用 `IndexConfigurationFingerprint` 的 Python JSON 原串；不要重新序列化配置、规范化 URL 或改变 model/1024/chunker。
- embedding 验证数量、1024、finite、非零；`Vector1024` 当前只验证维度，因此 index 仍须显式 finite/nonzero 检查。改版失败与 CAS 失败均保留旧节点/active/引用。

## 黄金与 PG 验证范围、估时

实施获放行后先冻结 Python 源行为黄金，再写实现；黄金不得由 Java 结果反向生成。Python 基线/fixtures/oracle 属其他 owner，worker 仅交抽取建议，黄金输出放允许的 `resources/migration/J4-01/**`，是否新增可提交生成器由主 Agent 分配。

| 验证层 | 核心断言 |
|---|---|
| 跨语言黄金 | 中文/emoji/NFC-NFD、全部 splitlines 行界、CRLF/CR/末尾换行、2600/2601 边界、标题/函数/typedef/长段落、metadata 字段/类型、路径分段排序与 root 同长 tie、配置 ASCII JSON 原串 |
| 计划与 fake embedding | 同快照零操作；正文/metadata/revision 改动；配置漂移全重嵌；纯删除；批次边界；失败/数量错/维度错/NaN/Infinity/全零均不调用 publish；finally 用量保留 |
| 独立 PG | TestPg 身份守卫与临时库；首发/重放/增量；published-only；物理节点旧引用回读；FTS regconfig 失配硬失败；两发布者同 expectedActive 最多一赢；节点/成员插入故障全回滚；四类 health；合法/配置错/成员缺失/物理缺失 rollback；既有 session 固定 release 不被切换 |
| 账本 PG | 前批成功后批失败仍有 usage；unknown 不写 0；CAS 失败不回滚账；账库失败告警降级与原行为一致 |

预计分为“黄金/解析与计划”“PG发布/健康/回滚”“失败竞争/集成回归”三个可审查切片，约 1–2 个专注工作日；稳定后单元/黄金及 PG 回归预计各 5–15 分钟（仅规划值，本轮未运行计时）。前置阻塞为 P3 门禁、独立工作树、共享 DTO/embedding/usage/wiring 裁决；真实快照/真实 embedding/质量指标留 P5 另批授权。

## 恢复点

- 当前仅本文待主 Agent 接收；本 worker 未启动进程/端口/容器，未持有共享文件写锁。
- 后续 owner 预期 `main:index/**`、`test:index/**`、`resources/migration/J4-01/**` 及本卡 handoff/evidence；独立工作树由主 Agent 在门禁后分配。
- 下一步：主 Agent 明确放行后先同步共享接口决定与工作树，再开始黄金与第一个实现切片。本文不是实施交付证据。
