# J1-03 证据：只读检索与 LI 数据面适配

## 环境与版本

- 源基线：f84d2c9（J1-01 后），冻结 Python 副本为行为基线。
- PG：本机隔离实例 127.0.0.1:55481（Homebrew PostgreSQL 17.10 + pgvector），经
  `DPDK_TEST_PG_URL/USER/PASSWORD/DATA_DIR` 显式启用；测试库动态建删
  （comment=java-migration-p1-test）。生成 golden 用的中间库已删除。
- schema：B0015 baseline（Flyway，含 `data_li_knowledge_v1`、`rag_releases`、
  `rag_release_nodes`、`rag_index_state`、`knowledge_chunks/documents`）。
- fixture 版本：`j1-03-v1`（fixture-spec.json）；golden 版本 `j1-03-v1`。

## golden 生成链（可复现）

工作目录 = worktree 根。Python 3.14（`/opt/homebrew/bin/python3.14`）。

```bash
# 1) 改写阶段（真实 HeuristicLLMQueryRewriter，stub 重依赖后导入冻结源）
/opt/homebrew/bin/python3.14 docs/java-migration/evidence/J1-03/generate_golden.py \
  --stage rewrite \
  --spec backend-java/src/test/resources/migration/J1-03/fixture-spec.json \
  --out docs/java-migration/evidence/J1-03/rewrite-intermediate.json

# 2) 真实 PG 上按 li_retrieval_store 原句 SQL 提取四腿（临时库，事后删除）
/opt/homebrew/bin/python3.14 docs/java-migration/evidence/J1-03/extract_legs.py seed \
  --database p1_j103_scratch2
/opt/homebrew/bin/python3.14 docs/java-migration/evidence/J1-03/extract_legs.py legs \
  --database p1_j103_scratch2 \
  --intermediate docs/java-migration/evidence/J1-03/rewrite-intermediate.json \
  --out docs/java-migration/evidence/J1-03/legs.json

# 3) golden 阶段（真实 RetrievalService 跑融合/重排/MMR）
/opt/homebrew/bin/python3.14 docs/java-migration/evidence/J1-03/generate_golden.py \
  --stage golden \
  --spec backend-java/src/test/resources/migration/J1-03/fixture-spec.json \
  --legs docs/java-migration/evidence/J1-03/legs.json \
  --out docs/java-migration/evidence/J1-03/golden-retrieval-draft.json

# 4) 行回读顺序（fetch_rows_by_ids 原句，无 ORDER BY，冻结 PG 实际顺序）
/opt/homebrew/bin/python3.14 docs/java-migration/evidence/J1-03/extract_legs.py rows \
  --database p1_j103_scratch2 \
  --golden docs/java-migration/evidence/J1-03/golden-retrieval-draft.json \
  --out docs/java-migration/evidence/J1-03/row-order.json
# 合并 pg_row_order 后写入 backend-java/src/test/resources/migration/J1-03/golden-retrieval.json
```

golden 中的 Python 侧均为**冻结源码本体**（仅 stub pydantic/openai/psycopg 依赖导入），
不是手工转写；LLM 腿以 `chat_model=None` 触发真实 except 回退路径，与无 LLM 环境一致。

## 冻结数值容差（运行前冻结）

- 排序（腿内序、融合序、重排序、最终 Top-K 序）：**精确相等**。
- rank、去重、Top-K 数量：精确相等。
- 分数（RRF、先验、五信号、总分、最终 score）：**绝对容差 1e-12**
  （golden-retrieval.json:float_tolerance）。fixture 向量分量仅取二进制精确值
  （1.0/0.5）保证 float32==double 位级一致，实测 Java vs Python 差值 0。

## 关键断言（SQL release 约束）

- 所有腿 SQL 带 `JOIN rag_release_nodes r ON r.node_id=c.node_id WHERE r.release_id=?`：
  q9（rel-beta）四腿均不含 alpha 独占节点（li_a2..li_a7）；q1（rel-alpha）四腿不含
  li_b1/li_b2；共享节点 li_a1 在两个 release 中均可命中。行回读在 rel-alpha 上请求
  li_b1 被 JOIN 过滤（rowLoadColumnsAndJsonbCanonicalText）。
- FTS 腿：`ORDER BY ts_rank(...) DESC, c.node_id`（ts_rank 并列按 node_id 升序，
  q1/q9 已覆盖）；regconfig 参数化 `CAST(? AS regconfig)`。
- 路径腿：ILIKE 通配符转义（`%`/`_`/`\` 不扩大匹配，a%b.c→空、a_b.c→仅 li_a6、
  axb.c→仅 li_a7、大小写不敏感）；片段截 20、LIMIT 封顶 512。
- 语义腿：`ORDER BY c.embedding <=> ?::vector, c.node_id`；HNSW 开启时同连接先
  `set_config('hnsw.ef_search', ?, true)`（事务级），结果与精确检索一致。
- 行回读列别名与 jsonb 规范文本：metadata_::text 与冻结文本逐字节一致
  （jsonb 键序 = 长度后字节序，如 li_a1 为
  `{"title": ..., "ordinal": ..., "section": ..., "source_key": ..., "source_path": ...}`）。

## 文件清单

- `fixture-spec.json`（同 test resources 副本来源）、`rewrite-intermediate.json`、
  `legs.json`、`row-order.json`、`golden-retrieval.json`（draft + row order 合并）。
- `generate_golden.py`、`extract_legs.py`：生成工具（只读提取）。
- `stage-diff-table.md`：逐阶段排序表摘要。
- `differences.md`：登记的良性差异。
- `unverified-live.md`：未验证项（真实 recall/MRR 等需 live 授权）。
