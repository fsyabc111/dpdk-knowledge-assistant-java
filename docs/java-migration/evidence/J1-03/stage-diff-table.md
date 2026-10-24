# J1-03 阶段排序差异表（冻结 golden 摘要）

完整逐信号数据见 golden-retrieval.json（与本表同源生成）。所有 case 的 Java 实测与下表逐项一致（见 handoff 验证节）。

| case | pinned | 四腿命中 | 融合序 | 最终 Top-K(id@score) |
|---|---|---|---|---|
| q1-chinese-filename-topic | ✓ | FTS改写=['li_a1'] FTS原问=['li_a1'] 路径=['li_a1'] 语义=['li_a1', 'li_a2', 'li_a3', 'li_a4', 'li_a5', 'li_a6', 'li_a7'] | li_a1 > li_a2 > li_a3 > li_a4 > li_a5 > li_a6 > li_a7 | li_a1@1.950000000000 > li_a2@0.260000000000 > li_a3@0.260000000000 > li_a4@0.218750000000 |
| q2-filename-ascii | ✓ | FTS改写=['li_a3'] FTS原问=[] 路径=['li_a3'] 语义=['li_a3', 'li_a1', 'li_a2', 'li_a4', 'li_a5', 'li_a6', 'li_a7'] | li_a3 > li_a1 > li_a2 > li_a4 > li_a5 > li_a6 > li_a7 | li_a3@1.790000000000 > li_a1@0.320000000000 > li_a2@0.260000000000 > li_a4@0.218750000000 > li_a6@0.212121212121 > li_a7@0.208955223881 |
| q3-specific-topic | ✓ | FTS改写=['li_a2'] FTS原问=[] 路径=['li_a2'] 语义=['li_a2', 'li_a1', 'li_a3', 'li_a4', 'li_a5', 'li_a6', 'li_a7'] | li_a2 > li_a1 > li_a3 > li_a4 > li_a5 > li_a6 > li_a7 | li_a2@1.740000000000 > li_a1@0.320000000000 > li_a3@0.260000000000 > li_a4@0.218750000000 > li_a6@0.212121212121 > li_a7@0.208955223881 |
| q4a-nfc-cafe | ✓ | FTS改写=[] FTS原问=[] 路径=[] 语义=[] |  |  |
| q4b-nfd-cafe | ✓ | FTS改写=['li_a6'] FTS原问=['li_a6'] 路径=[] 语义=[] | li_a6 | li_a6@0.337000000000 |
| q5-underscore-path | ✓ | FTS改写=['li_a3'] FTS原问=['li_a3'] 路径=['li_a6'] 语义=[] | li_a3 > li_a6 | li_a6@1.210000000000 > li_a3@0.490000000000 |
| q6-empty-query | ✓ | FTS改写=[] FTS原问=[] 路径=[] 语义=[] |  |  |
| q7-zero-evidence | ✓ | FTS改写=[] FTS原问=[] 路径=[] 语义=[] |  |  |
| q8-semantic-all-ties | ✓ | FTS改写=[] FTS原问=[] 路径=[] 语义=['li_a1', 'li_a2', 'li_a3', 'li_a4', 'li_a5', 'li_a6', 'li_a7'] | li_a1 > li_a2 > li_a3 > li_a4 > li_a5 > li_a6 > li_a7 | li_a1@0.320000000000 > li_a2@0.260000000000 > li_a3@0.260000000000 > li_a4@0.218750000000 > li_a6@0.212121212121 > li_a7@0.208955223881 |
| q9-beta-isolation | ✓ | FTS改写=['li_b1', 'li_b2'] FTS原问=['li_b1', 'li_b2'] 路径=['li_b1'] 语义=['li_b2', 'li_a1', 'li_b1'] | li_b1 > li_b2 > li_a1 | li_b1@1.694666666667 > li_b2@0.740000000000 > li_a1@0.320000000000 |
| q10-config-mismatch | 失败关闭 | — | — | []（release 缺失或配置指纹失配：fail-closed 返回空（对齐 HybridDpdkRetriever）） |
| q11-missing-release | 失败关闭 | — | — | []（release 缺失或配置指纹失配：fail-closed 返回空（对齐 HybridDpdkRetriever）） |
