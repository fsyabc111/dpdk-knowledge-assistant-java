# J1-03 未验证项（live 未授权）

以下指标本卡**未验证**，不得宣称等价（卡片验收 5 明确要求）：

- 真实题库 recall/MRR/nDCG（`backend/tests/rag_eval/queries.yml` 及生产语料）
  ——需冻结语料/题目/模型/参数/重复次数/预算/评审与 live 授权后另行执行。
- 真实 embedding 供应商（1024 维）在线语义腿质量；本卡仅 1024 维冻结向量合成。
- zh regconfig（zhparser 自定义镜像）下的 FTS 行为；合成环境仅 simple/english。
- 高并发/大数据量下的性能与 HNSW 近似索引实建（B0015 无 ANN 索引；
  hnsw.ef_search 仅验证 set_config 语句路径与结果不变）。
- llama-index PGVectorStore 的 ingest 写路径（非目标：写索引属 J1-02/J4 范围外）。
