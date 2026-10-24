# J1-03 登记差异（Java vs 冻结 Python）

以下差异均已核查为不影响冻结 fixture 的排序/分值，且已在测试中避开或以更严格行为替代。

1. **filename_boost 的多文件名 set 迭代序**（RetrievalFeatures.filenameBoost）
   - Python 对 `set(FILENAME_RE.findall(query))` 迭代，首个命中即返回；当查询同时
     提及多个文件名、且对不同 source 分别触发 exact/partial 时，结果依赖 CPython
     set 序（字符串哈希随机化，进程间不确定）。
   - Java 按 findall 出现序（LinkedHashSet）取首个命中，确定可复现。单文件名场景
     （生产绝大多数）与源完全一致。
2. **语义腿向量字面量的浮点指数格式**（VectorMath.pgVectorLiteral）
   - Python `str(float(x))`：`1e-05`；Java 等价实现对二进制精确值（1.0/0.5 等）
     逐字符一致，对一般值以 `1.0E-5`→`1e-05` 规则转换。PG 解析后数值相同，
     排序不变；仅极端字面量拼写差异。fixture 全部使用二进制精确分量。
3. **配置失配的粘性**（PgReleaseReader）
   - Python `HybridDpdkRetriever` 在实例内缓存 `release_blocked=True`，DB 修复后
     也要到下一实例才恢复。Java 每次检索重新核对 release 配置指纹：失配期间行为
     一致（fail-closed 空结果），DB 修复后立即恢复，差异方向更严格（不会放出
     旧错配结果）。
4. **类级 TTL 缓存与用量记账**（rewrite/embedding cache、UsageRecorder）
   - Python 的进程级 TTL 改写/嵌入缓存只影响性能与用量账本（0 调用记账），不影响
     结果确定性。Java 本卡未实现缓存与账本（属 J1-02 装配与用量切片）；oracle 生成
     时已按 case 重置类级缓存避免跨 case 污染（真实 Python 同文本跨请求会命中缓存，
     返回同一向量——与 Java 每次显式传向量等价）。
5. **`fetch_rows_by_ids` 无 ORDER BY**（与源逐字一致）
   - 源与本实现都不排序，行序来自 PG 堆序；并列分数的稳定排序因此依赖该顺序
     （Python 亦然）。测试冻结了 PG 实际顺序（row-order.json）并断言一致。
6. **Unicode 边缘**
   - Python `str.lower()`/`\w`/`\s`/`\b` 与 Java `toLowerCase(ROOT)`/UNICODE_CHARACTER_CLASS
     在个别边缘码位（如裸组合符属于 Java IsAlphabetic 而非 Python isalnum）存在
     理论差异；fixture 的 NFC/NFD 用例（café）实测逐 token 一致。
7. **嵌入腿缺省时的组合文本**
   - Python 检索内部计算 `embedding_query = original + " " + rewritten`；契约
     SearchInput 只有 question+queryVector，Java 在 queryVector 为空且注入 EmbeddingPort
     时按同一规则组合（embeddingPortReceivesCombinedQuery 断言）。
