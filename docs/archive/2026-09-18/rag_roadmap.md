> 历史归档：2026-09-18。原路径：`docs/rag_roadmap.md`。
> 适用版本：正文各条记录注明的日期、提交与环境；归档所据助手仓库快照为 `7048d8c`，不代表整篇已在该版本复验。
> **不再作为当前实施指令。** 正文保留当时的决策、完成声明和测试数据；未注明版本或存在冲突的结论不能外推为当前验收结果。
> 当前入口：[文档导航](../../README.md) · [统一路线](../../roadmap.md) · [当前决策](../../decisions.md)。
> 正文中的纯文本旧路径与行号用于还原历史上下文；本次仅修正可点击的相对链接。

# RAG 产品化路线图

> 日期：2026-09-05
> 状态：A 阶段已完成；B1 已完成（2026-09-08 收口）；B2 已判定（2026-09-12：
> simple/zh 对照实验逐项打平，**不达切换线、保持 simple**，zhparser 能力保留，
> 结论见「三」）；B3 已完成（2026-09-12：23 个权重进 Settings + 7 组扫描，
> 接纳 `mmr_per_file_cap=1` + `topic_boost_specific=1.15`，46 例题库首次
> 0 FAIL，MRR 0.8991→0.9342，结论见「四」）。
> E 阶段首批已落地（2026-09-13：runner 环境元信息+性能/回归门禁收紧、
> 产品答案验收工具、rewrite/embedding/judge 分项耗时指标；全链路 usage/成本
> 拆为下一批，清单见 `rag_product_progress.md`）。
> 最新实施状态与部署顺序见 `docs/rag_product_progress.md`；下文 B1 为原方案，HNSW 等改为按实验决定。
> 相关：`docs/runbook.md` §10（评估跑法、B2 对照实验与 B3 参数扫描）、`backend/tests/rag_eval/`（评估基建）

## 〇、现状基线（A 阶段，2026-09-05）

评估体系已落地（题库 46 例 + recall@k/MRR 指标 + 基线漂移 runner）。首版基线
（部署环境，llama_index_hybrid 引擎，`backend/data/rag_eval_baseline.json`，gitignore）：

| 指标 | 基线 | B 阶段目标 |
|---|---|---|
| recall@1 | 0.316 | ≥ 0.55 |
| recall@15 | 0.873 | ≥ 0.95 |
| MRR | 0.537 | ≥ 0.70 |
| must_top 命中率 | 63.6% | ≥ 85% |
| 46 例硬断言 | 36 过 | 全绿 |

关键结构性事实（已实库验证）：`data_li_knowledge_v1` 3583 节点，
`knowledge_chunks` **0 行**——部署环境只跑了 LlamaIndex ingest，"混合检索"
实际只剩语义一条腿，这是 10 个失败例（文件名定位/结构体/去重类）的主因。
li 表自带 `text_search_tsv`（simple 配置 + GIN 索引）与 `metadata_`
（含 source_path/section），词法信号移植的全部前提已具备。

## 一、长期路线图

- **A 评估体系 ✅**：题库、量化指标、基线漂移、runner 双形态（CLI/pytest）
- **B 检索质量攻坚**：B1 单数据面收敛 ✅ → B2 zhparser 中文 FTS ✅（2026-09-12
  对照实验打平，判定保持 simple、能力保留）→ B3 启发式权重进 Settings ✅
  （2026-09-12 扫描接纳 cap=1 + topic=1.15，题库 0 FAIL）→ B4 qwen3.7-text-rerank A/B 实验。
  达标线：上表目标
- **C 测试硬化**：`search()` 主路径离线 fixture 测试 ✅（2026-09-12，
  `tests/test_search_pipeline_fixture.py`：预置 embedding 小语料 + 自洽 fake
  store，排序/文件名支配/MMR 多样性/语义腿降级/零证据空回五路断言进默认集）、
  ingest 幂等性测试 ✅（2026-09-12，`tests/test_ingest_idempotency.py`：同快照
  零嵌入零发布/逐项比较/指纹漂移全量重嵌/纯删除重发）、检索性能基准——调整为
  runner 耗时统计（已产出 avg/p95）+ 阈值告警，不进 CI（CI 无 PG 服务）
- **D PG-only 收敛**：删 SQLite 方言层与 legacy ingest、`retrieval_store`
  双实现清理、alembic 删 `knowledge_chunks` 系列表（连带完成 E 的 fallback
  全表扫描治理；上线验收通过后再做，保留回退面）
- **E 产品化打磨**：~~评估可复现性与门禁~~ ✅（2026-09-13，runner 环境元信息 +
  环境不一致拒绝判定 + 性能门禁 + 回归阈值默认收紧为 0）、~~产品答案验收工具~~
  ✅（2026-09-13，100 题候选集的选题/落盘/人工评分合并/失败分类汇总，人工
  标注仍未做）、~~检索与证据校验分项耗时指标~~ ✅（2026-09-13，
  `assistant_chat_stage_duration_seconds{stage}` + `assistant_rag_validation_duration_seconds`）；
  仍待做：答案事实性评估集（LLM-judge 辅助）、负例置信度门槛、全链路 usage
  与成本计量（下一批，清单见 progress 文档）；~~llama_index 引擎补 answer cache~~
  ✅（2026-09-12，与旧管线同形同键、默认关，`tests/test_li_answer_cache.py`
  覆盖命中/旁路/键隔离）

依赖关系：B1 是 B2/B3/B4 的前提（信号先回到线上再调）；C 的 fixture 测试在
B1 后做（测新结构）；D 在 B、C 稳定后做。

**升级验收后的后续阶段 K（规划，未启动）**：保留源码证据，补充经审核、可追溯的
中文教学资料。先做 5–10 篇资料的语料对照，再根据答案质量决定是否调整检索；
不插入当前 RAG 升级批次，也不以采用 rerank 或删除 legacy 为启动前提。
启动条件、agent 生成与审核、来源管理、验收及维护方案见
[RAG 升级后的知识资料建设计划](../../knowledge_enrichment_plan.md)。

## 二、B1 实施方案：单数据面收敛 + HNSW + 健壮性（✅ 已完成，2026-09-08 验证收口）

> 实施状态（2026-09-08）：单数据面收敛与 `HybridDpdkRetriever` 重构已随
> 产品化提交 fb071ed 落地（`LiNodeRetrievalStore` 在独立文件
> `storage/li_retrieval_store.py`；fallback 采用恒空策略而非 LIMIT 扫描，
> 注释明言「没有证据时保持空结果」；citation 以 `assistant_citations.engine`
> 列分流为主、`li_` 前缀为兜底路由；版本化 ingest 的节点 id 为 `li_<hex32>`
> 格式（见验证 4））。本轮补齐剩余项：HNSW（开关默认关）、语义腿降级结构化日志
> （`query_embedding_failed` 事件）、非产品 agent 路径 kb_search 的
> `conversation_context` 接线（`agent/service.py`）、ingest legacy 标注、
> store 默认集 mock 单测（`tests/test_li_retrieval_store.py` /
> `test_li_hnsw.py` / `test_retrieval_degradation.py`）。
>
> HNSW 落地形态（对照实验，默认精确检索）：`LLAMA_INDEX_HNSW_ENABLED`
> （默认 false）+ `LLAMA_INDEX_HNSW_M/EF_CONSTRUCTION/EF_SEARCH`（16/64/40）
> 进 Settings；开启后 `_new_pgvector_store` 传 `hnsw_kwargs`，ingest 路径
> 幂等确保索引；alembic 0009 对存量 `data_<table>` 条件建索引（开关关闭时
> no-op 但记录 revision）。注意：0009 若在开关关闭时已应用，开启开关后
> `alembic upgrade head` 不会重放——需 `alembic downgrade 0008_rag_product`
> 再 `upgrade head`，或开关开启时跑一次 `ingest_llama_index.py`（同效果），
> 或手工执行同等 CREATE INDEX。回退：`alembic downgrade`（DROP INDEX，
> 不依赖开关）。裸 SQL 语义腿在开关开启时用事务级 `set_config`
> 固定 `hnsw.ef_search`。

### 目标（原案）

让词法/文件名/主题召回信号直接作用于 LlamaIndex 节点表，`HybridDpdkRetriever`
两层 RRF 收敛为「单表四路召回 → RRF → 重排 → MMR」一层；顺手修掉两个已证实
的健壮性缺陷。

### 步骤

1. **`storage/retrieval_store.py` 新增 `LiNodeRetrievalStore`**（只实现 PG）：
   - FTS：`text_search_tsv @@ websearch_to_tsquery('simple', ...)`（沿用现有
     查询构造），`ts_rank` 排序
   - 路径召回：`metadata_->>'source_path' ILIKE %s`
   - 语义：`embedding <=> %s::vector` ORDER BY LIMIT
   - 行读取：`node_id / text / metadata_->>'source_path' /
     metadata_->>'section' / embedding::text`，映射为现有候选行结构（id 带
     `li_` 前缀，citation 解析已按前缀分流，天然兼容）
   - fallback 候选扫描加 LIMIT 治理（不再无界拉全表）

2. **`HybridDpdkRetriever` 重构**（`llama_index_service.py`）：第一层
   `RetrievalService` 指向 `LiNodeRetrievalStore`；删除第二次 RRF 与
   `sha256(path+content)` 跨索引去重（单表后身份统一为 node_id）；保留
   `_diverse` 每文件 ≤2 截断。`RetrievalService` 本身不动算法（RRF/重排/MMR
   权重原样搬，调优属 B3）。

3. **HNSW 开启**：`_new_pgvector_store` 传 `hnsw_kwargs`（m=16、
   ef_construction=64、ef_search=40 起步，参数进 Settings）；alembic 新增
   revision 对存量 `data_li_knowledge_v1.embedding` 建 HNSW 索引（li 表 DDL
   由 llama-index 管理，索引由我们补，注意与库版本兼容）。

4. **健壮性修复**（评估首轮实跑证实的问题）：
   - `HybridDpdkRetriever` 语义腿加 try/except：embedding/向量检索失败时降级
     为纯词法结果并记结构化日志（对齐 fallback 哲学；当前上游 401 会整查询
     抛错）
   - `kb_search` 工具传 `conversation_context`（agent 路径追问恢复指代消解）

5. **ingest 侧**：`ingest_llama_index.py` 保持唯一生产入口；
   `ingestion_service.py` / `ingest_repo.py` 保留但标注 legacy 回滚专用；
   `auto_ingest.sh` 不变。

### 不做（后续阶段）

- zhparser 中文分词（B2，需自定义 PG 镜像 + text_search_tsv 换 regconfig）
- 重排权重调优（B3，信号回归线上后用题库跑参数扫描）
- 删 SQLite/legacy 表（D 阶段）

### 验证（全部必过）

1. `pytest -m "not live and not postgres"` 全绿 + ruff（新增 store 的 SQL 用
   mock/fixture 单测覆盖）——✅ 2026-09-08 完成：`tests/test_li_retrieval_store.py`、
   `tests/test_li_hnsw.py`、`tests/test_retrieval_degradation.py` 进默认集，
   agent 黄金场景集含新增场景 26（追问轮 kb_search 携带会话上下文）。
   门禁：314 过 / 覆盖率 81.57% / ruff 全绿 / 黄金 26 过。
2. runner 前后对比——✅ 2026-09-08 部署副本实测（基线文件已丢失，对照本节
   顶部文档化旧基线）：recall@1 0.316→**0.7939**、recall@15 0.873→**1.0**、
   MRR 0.537→**0.8991**、must_top 63.6%→**100%**（11/11）、硬断言 36→**45** 过。
   四项 B 阶段目标全达，仅余 dedup_two_large 一 FAIL（MMR 哨兵，属 B3 调优）。
   注：首轮跑分时发现部署副本 deploy/.env 的 DashScope key 已失效（401），
   当时成绩是纯词法腿（recall@1 0.7149 / MRR 0.8415 / 43 过）；换 key 后
   上表为语义腿正常参与的最终成绩。
3. `--update-baseline` 落新基线——✅ 2026-09-08 已落（HNSW 开启态，与部署
   副本运行配置一致；文件在 WSL 部署副本 `backend/data/rag_eval_baseline.json`，
   gitignore 不入库）。人工抽查 top5：edge_short_chinese（"日志系统"）语义腿
   命中 logging 设计文档准确，proto_dns top1 稳定。
   两轮报告存 `backend/data/b1_semantic_on.json` / `b1_hnsw_valid.json`
   （容器内 /tmp 随重建易失，务必 docker cp 导出）。
4. 部署环境重建索引验证幂等——✅ 2026-09-08 完成（已授权首次全量重嵌入）：
   首次版本化 ingest 发布 `d33217fc`（3737 节点全量嵌入，5m37s，新节点 id
   为 `li_<hex32>` 格式；旧 3583 节点按设计保留，表现共 7320 行）；
   立即重跑 `unchanged=3737 / inserted=0`、同 release_id、12s 零嵌入调用，
   幂等性成立。发布后 runner 对照：45/46、recall@15=1.0、MRR 0.903，
   版本固定链路（rag_release_nodes JOIN）无漂移。
   附带修复：部署副本 DPDK 挂载源因 compose 变量插值不读 `env_file` 而回落
   成 `/root`（容器内无权读取），已在仓库根 `.env` 置
   `DPDK_REPO_HOST_PATH=/mnt/d/dpdk` 修复——`auto_ingest.sh` 同受此修复。
   注意：runner 基线文件在容器内位于 `/app/backend/data/`（无卷挂载，容器
   重建即失），持久副本在 WSL 仓库 `backend/data/`，重建容器后需
   `docker compose cp` 回灌再做 `--fail-on-regression`。
5. HNSW 对照实验——✅ 2026-09-08 有效完成：开关开启 + alembic 0009 建索引
   （m=16/ef_construction=64，秒级），runner 指标与精确检索**完全一致**
   （recall@15 1.0、MRR 0.8991、45/46；avg 3.33s vs 3.37s，耗时差为噪声——
   3583 节点规模下 ANN 无加速收益也属预期）。逐用例对比：11/46 例 top15 内
   同路径片段有轻微排名洗牌（ANN 近似噪声），无通过状态变化。**结论：无回归，
   部署副本保留开启**（代码默认仍 false，不作为默认升级）；回退路径：
   `alembic downgrade 0008_rag_product` + 移除 override 环境变量。
   （当日早些时候的首次实验因 embedding key 401 语义腿未跑、结论无效，
   已回退后重做，即本条。）

### 风险

- li 表 DDL 归 llama-index 管：只加索引不改列，库升级时复核 `PGVectorStore`
  版本行为
- 单表后 candidate 规模变化可能影响 MMR 多样性——以题库去重类用例
  （dedup_*）为哨兵
- kb_search 改动后必须重跑 agent 黄金场景集

## 三、B2 实施方案：zhparser 中文 FTS（✅ 已判定 2026-09-12：打平不切换，保持 simple）

### 目标

li 表 `text_search_tsv` 的 regconfig 从 `simple`（中文整段单 token，中文单词
FTS 腿零命中）切换为 `zh`（zhparser 分词），以题库量化净效应后决定是否上线。
**默认不切换**：代码以 `LLAMA_INDEX_FTS_CONFIG=simple` 为默认，行为与切换前
完全一致。

### 已落地（2026-09-12）

- 自定义 PG 镜像 `deploy/postgres/Dockerfile`（pgvector/pgvector:pg17 +
  scws-1.2.3 + zhparser v2.3 + scws 简体词典 dict.utf8.xdb，全部 tarball +
  sha256 校验，多阶段构建）；`initdb/00-zhparser.sql` 对新空库建扩展 +
  `zh` 配置；`docker-compose.yml` postgres 服务已指向该镜像。
- **镜像已实证构建与自检**（2026-09-12，本机 docker）：新空库 entrypoint
  完整通过；`to_tsvector('zh','流量检测平台')` 词级切分为 `流量/检测/平台`。
  实证发现并修复 initdb 两处 bug：词性类型 `as` 是 SQL 保留字须双引号
  引用；映射表只能是 zhparser v2.3 词法类型表的 26 类（超集直接报错，
  scws 细标签由 zhparser 内部归并）。标识符行为实测：下划线标识符
  （`session_entry_t`）完整保留为单 token；文件名按扩展名拆开
  （`engine_ftp` + `c`），查询侧同构切分，匹配不受影响。
- `LLAMA_INDEX_FTS_CONFIG`（白名单 `{simple, zh}`，注入防护）：`_new_pgvector_store`
  建表与 `LiNodeRetrievalStore` FTS 召回腿（`CAST(%s AS regconfig)` 绑定参数）
  共用同一设置。
- alembic `0010_li_fts_zhparser`：zh 开关下确保扩展/配置存在并把存量表生成列
  换成 zh + 重建 GIN 索引（`<table>_idx`，与 llama-index 同名）；全幂等 DO 块；
  默认 simple 时 no-op；downgrade 无条件换回 simple（扩展/配置保留不删）。
- 错配防护：ingest 在 `store._initialize()` 后核对存活 regconfig，不一致硬失败
  （指向 alembic upgrade head）；检索侧进程级缓存核对，不一致记
  `rag_fts_config_mismatch` 告警一次并跳过 FTS 腿（其余三路不受影响）。
- 默认集测试 `tests/test_li_fts_config.py`（配置校验/from_params 透传/0010
  门控与 SQL/错配降级/ingest 硬失败），`tests/test_li_retrieval_store.py`
  同步适配；PG 端到端 `tests/test_li_fts_zhparser_postgres.py`（postgres mark，
  无 zhparser 镜像自 skip）——2026-09-12 已在自定义镜像 scratch 库实测通过：
  simple 建表 ingest → 0010 换列 → 中文单词「流量」命中（simple 对照零命中）
  → downgrade 复原；迁移 downgrade/upgrade 往返幂等。

### 设计要点（为什么不动指纹/不重嵌入）

换 regconfig 只影响词法腿：生成列换定义即全量重算，embedding 与存量 release
不受影响，故 `index_configuration()` 指纹不纳入 fts 配置（纳入会强制全量重
嵌入，纯浪费）。最终重排的 `keyword_score`/`identifier_score` 是子串匹配，
与分词无关——FTS 腿只影响候选召回（RRF），zhparser 对长标识符的潜在切碎
风险由对照实验量化。

### 切换判定（✅ 已完成 2026-09-12，部署副本实测）

前提：运行中的 zhparser 镜像库 + 已完成版本化 ingest + 有效 embedding key。
步骤见 `docs/runbook.md` §10「B2 对照实验」。

- A 组：`LLAMA_INDEX_FTS_CONFIG=simple` 全量 runner → 报告存档；
- B 组：env 切 zh → `alembic upgrade head` → 重启 backend → 全量 runner；
- **达线才切**：整体 recall@15 不降，且中文密集分类（协议检测/主题召回/边界）
  recall@15 提升 ≥ 0.03。达线 → 保留 zh 并 `--update-baseline` 落新基线；
  不达标 → env 回 simple + `alembic downgrade -1` + 重启（数据无损，无需
  重 ingest）。

**实验结论（2026-09-12，本机部署副本，3583 节点，46 例题库全量）**：

| 指标 | A 组 simple | B 组 zh | Δ |
|---|---|---|---|
| recall@1 | 0.7939 | 0.7939 | 0 |
| recall@5 | 0.9693 | 0.9693 | 0 |
| recall@15 | 1.0000 | 1.0000 | 0 |
| MRR | 0.8991 | 0.8991 | 0 |
| must_top 命中率 | 100% | 100% | 0 |
| avg latency | 2.512s | 2.452s | -0.06s |

用例级差异仅两个去重用例排名微洗牌（不影响通过性）；A 组唯一 FAIL
`dedup_two_large` 两组同状。**判定：不达切换线**——中文密集分类 recall@15
已达 1.000，「提升 ≥ 0.03」数学上不可能，且逐项指标无一改善。**保持 simple，
已回退**（downgrade + upgrade head 核实生成列回 `simple` regconfig；zhparser
镜像/扩展保留在库中无害，未来语料结构变化时可低成本复验）。

打平原因（记录备查）：改写腿已把中文 query 译成英文代码 token，FTS 腿只负责
候选召回，路径/主题/全库 embedding 三路早已把正确候选拉进 RRF 池——zh 分词
改善的是「中文整段单 token 零命中」，而该短板在融合架构下不暴露。

### 不做

- B3 权重调优、legacy 词法栈（`retrieval_store.py`/`search_vector`）换配置、
  PGVectorStore 自带 hybrid 查询路径（请求链路不使用）、题库新增分词敏感用例
  （先观察 live 结果再按题库纪律增补）、ready 探针纳入 fts 核对（错配是降级
  不是故障，靠 ingest 硬失败 + 告警闭环）。

### 风险

- 换列持 ACCESS EXCLUSIVE 锁并全表重写：语料千级行秒级完成，仍建议维护窗口执行；
- 镜像构建外部依赖（xunsearch.com / codeload.github.com）：本机已实证可达
  （2026-09-12），换网络环境重建时需重新确认；
- 词典切词粒度：如「结构体」切为「结构」+「体」——查询侧同构切分，匹配一致；
  个别词的词典行为差异由对照实验兜底；
- stock PG 开发机无 zhparser：0010 默认 no-op 不受影响；误开 zh 会在 CREATE
  EXTENSION 处明确报错（fail-fast，符合预期）。

## 四、B3 实施方案：启发式权重进 Settings + 题库调优（✅ 已完成 2026-09-12，扫描接纳 2 旋钮上线）

### 目标

把 `retrieval_service.py` 里全部启发式重排权重从裸常量搬进 Settings（env 覆盖），
让 runner 参数扫描不需要改代码；随后用题库扫描决定更优值。**本阶段纯参数化：
默认值一律等于线上现值，行为不变**（现有测试原样全绿即回归证据）。

### 已落地（2026-09-12）

- 23 个 `retrieval_*` 权重进 Settings（`backend/app/config.py`），四组：
  - 合并权重 5：`w_semantic=0.38` / `w_lexical=0.24` / `w_ident=0.18`（语义腿在）；
    `w_lexical_no_semantic=0.46` / `w_ident_no_semantic=0.26`（语义腿缺）
  - RRF 先验 3：`rrf_k=60`、`rrf_prior_scale=14.0`、`rrf_prior_cap=0.22`
  - MMR 3：`mmr_redundancy_penalty=0.18`、`mmr_per_file_penalty=0.04`、`mmr_per_file_cap=2`
  - boosts 12：filename 精确/部分 0.50/0.30；topic 特定/通用 ×（endswith 带扩展名
    0.95/0.85、无扩展名 0.90/0.80、infix 0.60/0.50）；identifier 路径+栏目/正文
    1.0/0.45；definition typedef/函数签名 0.10/0.04
- 校验：float 权重 `>= 0`，`rrf_k >= 1`、`mmr_per_file_cap >= 1`；误配在启动即失败。
- 接入点：四个模块级打分函数（`filename_boost`/`topic_boost`/`identifier_score`/
  `definition_boost`）加可选 `settings` 参数；RRF 融合与 source_prior 共用
  `retrieval_rrf_k`；`_retrieve_sync` 合并权重与 `_select_diverse_chunks` MMR 读
  `self.settings`。
- 默认集测试 `tests/test_retrieval_weights.py`（12 例）：校验拦截、默认值契约逐项
  锁定、boosts 自定义读取、合并权重两分支公式分差、rrf_k/prior 接线、MMR 上限生效。

### 扫描与判定（✅ 已完成 2026-09-12，手册见 runbook §10「B3 权重参数扫描」）

- **时序**：权重最优值依赖 FTS 腿口径（B2 simple/zh 改变候选分布）——参数扫描
  等 B2 切换判定后按生效配置跑；若 B2 迟迟未判定，先在 simple（线上现值）下扫描，
  zh 切换后复扫核心组。
- **判定规则**：整体 recall@15 不低于当前基线；`dedup_two_large` 哨兵（B1 唯一 FAIL）
  与目标分类改善；接纳后固化 `deploy/.env` + `--update-baseline` 落新基线。

**扫描结论（2026-09-12，B2 判定后按生效的 simple 口径执行，46 例全量/组）**：

| 扫描组（单旋钮） | recall@1 | recall@5 | MRR | 哨兵/分类 | 判定 |
|---|---|---|---|---|---|
| `w_semantic=0.30` | 0.7939 | 0.9693 | 0.8991 | 哨兵 12→13 | 打平微负，弃 |
| `w_semantic=0.46` | 0.7939 | 0.9693 | 0.8991 | 零变化 | 打平，弃 |
| `mmr_redundancy_penalty=0.30` | 0.7675 | 0.9693 | 0.8904 | 哨兵 12→13 | **回归**，弃 |
| `mmr_per_file_cap=1` | 0.7939 | **0.9868** | **0.9167** | **哨兵 12→7 翻转 PASS**，去重 r@5 0.42→0.75 | **接纳** |
| `filename_boost_exact=0.70` | 0.7939 | 0.9693 | 0.8956 | 哨兵 12→9 但 ja3 3→5 | 混合，弃 |
| `topic_boost_specific=1.15` | **0.8202** | 0.9693 | **0.9167** | 子系统 cert 3→1，r@1 +0.111 | **接纳** |

组合验证（`cap=1` + `topic=1.15` 同开）：两者收益完全可加、零冲突——
**recall@1 0.8202（+0.0263）/ recall@5 0.9868（+0.0175）/ recall@15 1.0（不降）
/ MRR 0.9342（+0.0351）/ must_top 100% / avg 2.412s，46 例题库首次 0 FAIL**。

**判定与落地**：接纳两旋钮，已写 `deploy/.env` + 重启 backend 生效 +
检索基线已落新值（`backend/data/rag_eval_baseline.json`，gitignore）。
代码默认值未动（runbook 纪律：单轮单库证据先落 env，跨部署复验一致后再
考虑改默认值并同步 `test_retrieval_weights.py` 默认值契约）。回退 = 删
`deploy/.env` 两行 + 重启 backend。

### 不做

- 召回广度常量（`_fts_terms` 截 10、候选上限 `limit×28/160`、各腿 `max(limit//2,40)`）
  参数化——属召回规模护栏不是重排权重；
- 批量扫描的实际执行（部署副本窗口任务）；B4 rerank 实验；`search()` 离线 fixture
  （C 阶段）。

### 风险

- 23 个旋钮自由度高，误配可劣化检索——Settings 启动校验 + 「默认值永随线上现值」
  纪律 + runner 指标准入门槛约束；
- 扫描组合爆炸：手册按组扫（先合并权重、再 MMR、后 boosts），不正交全枚举。
