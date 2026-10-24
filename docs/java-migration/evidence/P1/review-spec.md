# P1 规格独立审查（规格轴：冻结契约与 Python 行为基线忠实度）

- 审查员：独立上下文规格审查（不参与实现）
- 审查基准：分支 `codex/p1-p2-longrun`，提交 `bb07ab7`（工作树 clean）
- 冻结契约：`migration/contracts/`（p0-v1 契约集 + `interface-changes.md` 的 P1 登记）
- 行为基线：仓库内冻结 Python 副本 `backend/`（只读）
- 方法：逐文件对照 + 关键算法机械比对（TOPIC 表 70 项脚本 diff、uuid5 三组向量实测含 CJK）。
  下文文件路径分别缩写 `PJ=/Volumes/study/code/new_project/dpdk-knowledge-assistant-java/backend` 与
  `JV=/Volumes/study/code/new_project/dpdk-knowledge-assistant-java/backend-java/src`。

## 一、J1-02 重试/失败分类

| 契约点 | Java 证据 | Python 证据 | 结论 |
|---|---|---|---|
| 可重试判定顺序：传输层超时/网络错误 → 状态码 429/≥5xx → 消息标记词 | `JV/main/java/io/dpdk/assistant/model/ModelFailureClassifier.java:32-57`（ResourceAccessException → statusCodeOf(ModelHttpException/RestClientResponseException) → 标记词） | `PJ/app/core/llm_client.py:27-41`（httpx Timeout/NetworkError → status_code/`exc.response.status_code` → 递归 cause → 标记词） | 一致（顺序与阈值相同） |
| chat 指数退避 `base*2**max(attempt-1,0)`、无抖动 | ModelFailureClassifier.java:73-75；调用点 `JV/main/java/io/dpdk/assistant/model/OpenAiCompatibleChatClient.java:184-185`（attempt=1 起） | llm_client.py:44-46；调用点 llm_client.py:229-231（`attempt+1`，attempt 0 起） | 一致（首次失败延迟均为 base，无抖动；chat 不消费 Retry-After，两侧一致） |
| embedding 重试：优先 Retry-After 数值秒，否则 `base*2**attempt + uniform(0,min(0.5,delay*0.25))` | ModelFailureClassifier.java:82-90；`JV/main/java/io/dpdk/assistant/model/OpenAiCompatibleEmbeddingClient.java:122-143` | llm_client.py:147-155 | 一致（含「缺失/非法/0 值 Retry-After 回退退避」） |
| 重试次数语义：总尝试 = max_retries+1；不可重试异常立即透传 | OpenAiCompatibleChatClient.java:180-181（`attempt > maxRetries \|\| !isRetriable`） | llm_client.py:139,225 | 一致 |
| 成功先解析内容再记账；失败/取消/重试耗尽 finally 补记一次 unknown；绝不双记 | OpenAiCompatibleChatClient.java:162-196, 252-264（先校验 choices[0].message.content）, 513-523（CAS recordOnce） | llm_client.py:210-237（先 `data["choices"][0]["message"]["content"]` 再 `_record_usage`） | 一致 |
| 流式：stream_options 索取 usage；400 且正文指向 stream_options/include_usage 才去字段重发一次（calls=2）；其余 400 原样抛 | OpenAiCompatibleChatClient.java:338-374, 423-427, 460-467 | llm_client.py:250-308（`_rejected_stream_options`） | 一致 |
| usage unknown≠0：token 缺失为 null 不记 0；三键名兼容（prompt_tokens/input_tokens）；分项缺失不可加；异常形态（bool/负数/非整浮点/非纯数字串）→null | `JV/main/java/io/dpdk/assistant/model/ModelUsageLedger.java:45-60, 149-216`（intOrNull） | `PJ/app/core/usage.py:59-89, 238-254`（`_int_or_none`/`tokens_from_usage`/`record_llm`） | 一致 |
| 零调用留痕（cache_hit/replay/local 记 call_count=0，未知 reason 降级 unknown）；totals 键集合与 None 求和语义 | ModelUsageLedger.java:63-76, 83-141 | usage.py:256-289, 296-333 | 一致（价格/落库延后属已登记范围：unverified.md 第 5 条） |
| MCP `_call_once`：initialize 完成后 submitted；提交后失败绝不重试 | `JV/main/java/io/dpdk/assistant/harness/StreamableHttpHarnessClient.java:108-146`（initialize 成功后的任何 tools/call 失败 → UNKNOWN、直接返回） | `PJ/app/agent/mcp_client.py:34-46, 48-70` | 一致 |
| MCP 建连/initialize 重试 2 次、退避 0.5*2^n | StreamableHttpHarnessClient.java:57-58, 119-130 | mcp_client.py:26-27, 65-69 | 一致 |
| `_parse_result` 四分支：structuredContent(dict) → isError→harness_tool_error(payload[:2000]) → 文本 JSON dict → `{"ok":true,"data":...}`；非 JSON → harness_bad_response | StreamableHttpHarnessClient.java:281-315（含空 payload 归入 bad_response） | mcp_client.py:72-95 | 一致（含 detail 截断 2000、错误文案「评测服务不可达：<type>」） |
| 上游并发门（每次尝试持许可） | `JV/main/java/io/dpdk/assistant/model/UpstreamGate.java:18-46` + ChatClient `exchangeOnce` | `PJ/app/core/upstream_gate.py:16-24` | 一致（进程单实例对位每 loop 信号量，语义等价） |

## 二、J1-03 检索

| 契约点 | Java 证据 | Python 证据 | 结论 |
|---|---|---|---|
| 四路召回与限额：改写 FTS 满额 candidate_limit，其余三腿 `max(limit//2,40)`；`candidate_limit=max(limit*28,160)` | `JV/main/java/io/dpdk/assistant/retrieval/RetrievalPipeline.java:38-53, 228-230` | `PJ/app/services/retrieval_service.py:575-589, 717` | 一致 |
| RRF 公式 `sum 1/(k+rank)`、k=60、rank 从 1 起、首见 rank 生效 | `JV/main/java/io/dpdk/assistant/retrieval/CandidateRanks.java:14-49` | retrieval_service.py:286-298, 541-549 | 一致 |
| RRF 排序稳定性（并列保持首见序） | RetrievalPipeline.java:55-64（LinkedHashMap + TimSort） | retrieval_service.py:599-607（dict 序 + sorted 稳定） | 一致 |
| 权重表逐项（21 项 retrieval_* + rrf_k + per_file_cap 等 23 项默认值与非负校验） | `JV/main/java/io/dpdk/assistant/retrieval/RetrievalSettings.java:79-97, 100-129` | `PJ/app/config.py:46-68, 224-241` | 逐项相等（0.38/0.24/0.18/0.46/0.26/60/14.0/0.22/0.18/0.04/2/0.50/0.30/0.95/0.85/0.90/0.80/0.60/0.50/1.0/0.45/0.10/0.04） |
| LIKE 转义 `\`→`\\`、`%`→`\%`、`_`→`\_`，两侧加 `%`，片段截前 20，ILIKE OR 连接 | `JV/main/java/io/dpdk/assistant/retrieval/LiNodeRetrievalStore.java:88-99` | `PJ/app/storage/li_retrieval_store.py:96-103` | 一致（含 PG 默认 ESCAPE 口径一致） |
| FTS regconfig 活体探查：按表进程内缓存（含 None）；与配置不一致 → 每表告警一次 + 该腿降级为空 | LiNodeRetrievalStore.java:31-37, 66-85, 253-286 | li_retrieval_store.py:15-49, 72-94 | 一致 |
| tie-break：`ORDER BY <score>, c.node_id`；`LIMIT min(limit,512)`；参数位序 | LiNodeRetrievalStore.java:204-251 | li_retrieval_store.py:57-70 | 一致 |
| release 隔离 SQL：`JOIN rag_release_nodes r ON r.node_id=c.node_id` + `r.release_id=?`（ids 与 rows 回读两处）；getChunk 不限 release | LiNodeRetrievalStore.java:131-169, 173-198 | li_retrieval_store.py:57-70, 123-141；llama_index_service.py:52-71 | 一致 |
| HNSW：开启时同连接先 `set_config('hnsw.ef_search',?,true)` | LiNodeRetrievalStore.java:102-117, 211-247 | li_retrieval_store.py:105-117 | 一致 |
| fallback 腿恒空；阈值 `len<max(12,candidate_limit//3)` | LiNodeRetrievalStore.java:119-123；RetrievalPipeline.java:66-74 | li_retrieval_store.py:119-121；retrieval_service.py:609-618 | 一致 |
| 重排五信号 max(original, rewritten)；`if semantic` 真值分支；score≤0 丢弃；全空 → rows[:limit] 记 0.01；按分降序稳定排序 | RetrievalPipeline.java:79-140 | retrieval_service.py:746-815 | 一致 |
| MMR：per-file cap 跳过、冗余=最大 token Jaccard、`score>best` 严格大于（并列首见）、全被 cap 时 break | RetrievalPipeline.java:164-209 | retrieval_service.py:626-673 | 一致 |
| 特征函数 keyword/identifier/filename/topic/definition（含 regex 与得分手工） | `JV/main/java/io/dpdk/assistant/retrieval/RetrievalFeatures.java:22-127`、`RetrievalText.java:20-36` | retrieval_service.py:27-31, 110-229 | 一致（详见差异清单 S3） |
| TOPIC_PATH_KEYWORDS 70 项 + SPECIFIC 14 项 | `JV/main/java/io/dpdk/assistant/retrieval/TopicPaths.java:22-96` | retrieval_service.py:35-135 | 机械 diff：键序、键、值全部相等；SPECIFIC 集合相等 |
| 启发式改写（优先词+长度排序、概念问句、双主题 `;` 拼接、topic_phrase 截 8、prompt 模板逐字） | `JV/main/java/io/dpdk/assistant/retrieval/HeuristicQueryRewriter.java:56-207, 209-256` | retrieval_service.py:232-283, 317-355 | 一致 |
| 配置指纹 `json.dumps({...}, sort_keys=True)` 逐字节（键序 api_base/chunker/dimensions/model、", "/": " 分隔、ensure_ascii 转义、float repr） | `JV/main/java/io/dpdk/assistant/retrieval/IndexConfigurationFingerprint.java:17-29` + `PythonJson.java:21-135` | `PJ/app/services/rag_index_service.py:35-37`；契约 identity-and-data.md「索引配置串」行 | 一致 |
| release pin/fail-closed：active 解析、配置指纹失配 → 结构化日志 + 空结果 | `JV/main/java/io/dpdk/assistant/retrieval/PgReleaseReader.java:75-127` | llama_index_service.py:428-454 | 一致（粘性差异见 J1-03 differences.md 第 3 条，已登记） |

## 三、J1-04 裁决与引用

| 契约点 | Java 证据 | Python 证据 | 结论 |
|---|---|---|---|
| precheck 顺序：empty_answer → invalid_citation（ref 越界 1..len(chunks)） | `JV/main/java/io/dpdk/assistant/chat/poc/MinimalQaSlice.java:60-69` | `PJ/app/services/rag_product_service.py:106-112` | 一致 |
| postcheck 顺序：no_evidence → missing_citation（POC 无 tools，`not tools` 恒真分支等价） | MinimalQaSlice.java:70-76 | rag_product_service.py:115-121 | 一致（N08 确定性口径；POC 无 judge 属卡片范围） |
| 引用编号提取 `\[(\d+)\]` | MinimalQaSlice.java:26, 91-101 | rag_product_service.py:152 | 一致 |
| citation：不按文件去重、顺序=证据编号、score=Python round 4 位（HALF_EVEN 于精确二进制值）、engine 由 li_ 前缀、保存 rank | `JV/main/java/io/dpdk/assistant/chat/poc/OrderedCitationBinder.java:22-35` | `PJ/app/services/citation_service.py:8-16`；契约 transaction-ports.json:449-467 | 一致（契约语义逐条满足） |

## 四、J1-01 迁移链

| 契约点 | Java 证据 | Python 证据 | 结论 |
|---|---|---|---|
| V0014 = 0014（加列 + 部分唯一索引，幂等形式） | `JV/main/resources/db/migration/V0014__grading_task_job_id.sql:3-5` | `PJ/alembic/versions/0014_grading_task_job_id.py:11-16` | 逐字一致 |
| V0015 = 0015：`ADD COLUMN IF NOT EXISTS purpose TEXT NOT NULL DEFAULT 'chat'`；回填条件=explain- 前缀 ∩ purpose='chat' ∩ request_id==uuid5(URL,"assignment-explain:<sid>") ∩ grading_submissions.user_id 一致；不动 input_hash | `JV/main/java/db/migration/V0015__Explain_purpose.java:23-62` | `PJ/alembic/versions/0015_explain_purpose.py:14-31` | 一致（见差异清单 S5 边界） |
| uuid5 逐位兼容 | `JV/main/java/io/dpdk/assistant/platform/ids/PythonUuid5.java:24-46` | 0015:23（uuid.uuid5） | 实测 3 组（ASCII/空尾/CJK）与 CPython 输出逐字符相同 |
| users：列集/类型/nullable/默认值 = alembic 0002+0003+0005（含 created_by 自引用 FK 与索引） | B0015:475-485, 1042-1045, 1113-1117 | `PJ/alembic/versions/0002:24-34`、`0003:22-24`、`0005:23-24` | 一致 |
| api_tokens：token_hash PK、user_id NOT NULL REFERENCES users(id)、时间列 nullable 口径、user_id 索引 | B0015:24-30, 794-797, 1050-1054 | 0002:36-45 | 一致 |
| password_reset_requests：status 默认 'pending' NOT NULL、无 FK、status 索引 | B0015:236-246, 1034-1037 | 0003:26-40 | 一致 |
| 0006 效果：grading_submissions 不保留 user FK；admin_audit_log + created DESC 索引 | B0015:152-165（无该 FK）、10-22, 802-805 | `PJ/alembic/versions/0006_user_delete_audit.py:23-35` | 一致 |
| upstream_usage_records = 0011（含三个复合索引、call_count 默认 1、token 列可空=未知） | B0015:436-452, 1002-1021 | `PJ/alembic/versions/0011_usage_records.py:26-51` | 一致 |
| 基线含 0015 终态（rag_requests.purpose 默认 'chat'），与 baselineVersion=15 三路径策略自洽 | B0015:356-361；`JV/main/java/io/dpdk/assistant/platform/migration/MigrationController.java:50-80, 97-109` | alembic 0015 | 一致（B 前缀基线迁移为 Flyway 11.14.1 内建能力，已在 jar 中核实 BaselineMigrationResolver） |

## 五、声明的有意差异核对（是否如实、有无扩大）

| 声明 | 核对结果 |
|---|---|
| J1-02 unverified.md 差异 1：MCP 参数序列化提前 → NOT_SUBMITTED、零 HTTP | 如实（StreamableHttpHarnessClient.java:91-101；源 submitted=True 之后才序列化）；已同步登记 interface-changes.md |
| 差异 2：mcp 日志不回显 payload | 如实（:305 只记 payload_len；:337-341 只记 detail_len）；返回给调用方 detail 仍截 2000，与源一致 |
| 差异 3：chat 取消 shield 模型、账记 unknown | 如实（exchangeOnce:212-242；recordOnce CAS 防双记） |
| 差异 4：embedding index 非数字按 0 排序 | 如实（OpenAiCompatibleEmbeddingClient.java:255-258） |
| 差异 5：新增错误码 harness_bad_request / harness_deadline_exceeded | 如实（StreamableHttpHarnessClient.java:98, 111, 135），interface-changes.md 已登记 |
| J1-03 differences.md 1-7（filename set 序、向量字面量指数格式、失配粘性、TTL 缓存/记账延后、rows 无 ORDER BY、Unicode 边缘、嵌入组合文本） | 逐条与代码核对如实，未发现超出声明范围的扩大 |
| interface-changes.md J1-03 各条（queryVector=null、富字段经 RetrievalTrace、fail-closed 异常表达、1e-12 容差） | 与 PgReleaseReader/RetrievalTrace 实现一致 |

## 六、差异清单

**[阻断]：无。** 未发现行为将偏离冻结契约且未登记的问题。

**[建议]**（均可后续处理，不阻塞阶段）：

1. **S1（J1-02）可重试判定的消息标记扫描范围**：Python `is_retriable_llm_error` 递归到最深 cause 后只检查**该层**异常消息（llm_client.py:37-41）；Java `ModelFailureClassifier.isRetriable` 对 cause 链**每一层**消息都做标记词匹配（ModelFailureClassifier.java:44-55）。Java 判定是 Python 的超集——包裹层消息含 "timed out" 而深层不含时 Java 会重试、Python 不会。当前实现内实际异常类型（ModelHttpException 带状态码、ResourceAccessException 先行短路）使该差异几乎不可达，但属于未登记的语义放宽；建议在 J1-02 差异表补一行或收窄为仅最深层匹配。
2. **S2（J1-02）负值 Retry-After**：Python `float(retry_after)` 对 "-1" 得负数并直接进入 sleep（将抛 ValueError，调用路径无此形态）；Java `> 0.0` 才采用、否则回退指数退避（ModelFailureClassifier.java:84）。Java 更稳健且方向有利，建议随 S1 一并备注。
3. **S3（J1-03）keyword_score 分词的 Unicode 空白**：Python `str.split()` 按任意 Unicode 空白切分；Java `query.split("\\s+")` 默认仅 ASCII 空白，U+3000（全角空格，中文输入法常见）不会被切开，词法命中率可能不同。differences.md 第 6 条「Unicode 边缘」已概括性覆盖 `\s`，但建议把该具体可达场景（CJK 查询含全角空格）点名，供后续语料测试补样。
4. **S4（J1-02）cause 链深度上限 8**（ModelFailureClassifier.java:23）：Python 递归无上限；链深 >8 时 Java 提前判不可重试。工程上不可达，备案即可。
5. **S5（J1-01）`session_id == "explain-"` 的空尾边界**：Python 以空 submission_id 继续（uuid5 不匹配即跳过，行为等价）；Java 直接跳过（V0015__Explain_purpose.java:46-48）。差异方向更保守且实际不可达，无需改动。
6. **S6（J1-03）改写/嵌入 TTL 缓存与 cache_hit 记账未接**：已声明延后（differences.md 第 4 条）。提醒：后续用量切片接线时需补齐 `SOURCE_CACHE_HIT` 零调用条目，否则 totals 的 `zero_call_entries` 口径将低于基线（此为实现空缺而非行为差异，按登记跟踪即可）。

## 七、总结论

在抽查范围内（J1-02 重试/失败分类与 usage 语义、J1-03 四路召回/RRF/权重/MMR/LIKE 转义/FTS 降级/tie-break/release 隔离/配置指纹、J1-04 裁决顺序与引用绑定、J1-01 迁移链与身份表结构、全部已声明差异），Java 实现与冻结契约及 Python 行为基线**同构**；权重表、TOPIC 表（70 项键序值）、uuid5（实测逐位）、RRF/退避/抖动公式等可机械比对项全部相等。未发现未登记的契约偏离，阻断数 **0**；6 条建议项中 S1/S2 建议补登记，其余备案跟踪。本审查不修改任何代码与状态文件。
