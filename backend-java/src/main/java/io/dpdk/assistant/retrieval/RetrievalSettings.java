package io.dpdk.assistant.retrieval;

/**
 * 检索旋钮（J1-03 范围）。默认值逐项等于
 * backend/app/config.py 的 retrieval_* / llama_index_* 现值（行为不变契约）。
 *
 * @param tableName              配置的 LI 表名（物理表 data_&lt;tableName&gt;；须为合法 PG 标识符）
 * @param ftsConfig              text_search_tsv 与 FTS 腿共用 regconfig（simple/zh）
 * @param retrievalStrategy      只支持 hybrid_fusion_v2（与 Python RetrievalService 一致）
 * @param hnswEnabled            HNSW 开关（默认关 = 精确检索）
 * @param hnswEfSearch           HNSW ef_search（事务级 set_config）
 * @param wSemantic              语义权重
 * @param wLexical               词法权重（语义腿在）
 * @param wIdent                 标识符权重（语义腿在）
 * @param wLexicalNoSemantic     词法权重（语义腿缺）
 * @param wIdentNoSemantic       标识符权重（语义腿缺）
 * @param rrfK                   RRF k
 * @param rrfPriorScale          RRF 先验放大系数
 * @param rrfPriorCap            RRF 先验封顶
 * @param mmrRedundancyPenalty   MMR 冗余惩罚
 * @param mmrPerFilePenalty      MMR 同文件惩罚
 * @param mmrPerFileCap          单文件入选上限
 * @param filenameBoostExact     文件名精确命中加成
 * @param filenameBoostPartial   文件名部分命中加成
 * @param topicBoostSpecific     主题词（特定）endswith 带扩展名
 * @param topicBoostGeneric      主题词（通用）endswith 带扩展名
 * @param topicBoostSpecificBare 主题词（特定）endswith 裸名
 * @param topicBoostGenericBare  主题词（通用）endswith 裸名
 * @param topicBoostSpecificInfix 主题词（特定）包含
 * @param topicBoostGenericInfix 主题词（通用）包含
 * @param identHeaderScore       标识符命中路径/栏目得分
 * @param identBodyScore         标识符命中正文得分
 * @param definitionBoostTypedef typedef 定义加成
 * @param definitionBoostFuncsig 函数签名加成
 */
public record RetrievalSettings(
        String tableName,
        String ftsConfig,
        String retrievalStrategy,
        boolean hnswEnabled,
        int hnswEfSearch,
        double wSemantic,
        double wLexical,
        double wIdent,
        double wLexicalNoSemantic,
        double wIdentNoSemantic,
        int rrfK,
        double rrfPriorScale,
        double rrfPriorCap,
        double mmrRedundancyPenalty,
        double mmrPerFilePenalty,
        int mmrPerFileCap,
        double filenameBoostExact,
        double filenameBoostPartial,
        double topicBoostSpecific,
        double topicBoostGeneric,
        double topicBoostSpecificBare,
        double topicBoostGenericBare,
        double topicBoostSpecificInfix,
        double topicBoostGenericInfix,
        double identHeaderScore,
        double identBodyScore,
        double definitionBoostTypedef,
        double definitionBoostFuncsig) {

    private static final java.util.regex.Pattern SQL_IDENTIFIER =
            java.util.regex.Pattern.compile("^[A-Za-z_][A-Za-z0-9_]*$");

    public RetrievalSettings {
        if (tableName == null || !SQL_IDENTIFIER.matcher(tableName).matches()) {
            throw new IllegalArgumentException("LLAMA_INDEX_TABLE must be a PostgreSQL identifier");
        }
        if (!"simple".equals(ftsConfig) && !"zh".equals(ftsConfig)) {
            throw new IllegalArgumentException("LLAMA_INDEX_FTS_CONFIG 只允许 simple/zh: " + ftsConfig);
        }
        if (!"hybrid_fusion_v2".equals(retrievalStrategy)) {
            throw new IllegalArgumentException("Unsupported RETRIEVAL_STRATEGY: " + retrievalStrategy);
        }
        double[] nonNegative = {
                wSemantic, wLexical, wIdent, wLexicalNoSemantic, wIdentNoSemantic,
                rrfPriorScale, rrfPriorCap, mmrRedundancyPenalty, mmrPerFilePenalty,
                filenameBoostExact, filenameBoostPartial, topicBoostSpecific, topicBoostGeneric,
                topicBoostSpecificBare, topicBoostGenericBare, topicBoostSpecificInfix,
                topicBoostGenericInfix, identHeaderScore, identBodyScore,
                definitionBoostTypedef, definitionBoostFuncsig};
        for (double v : nonNegative) {
            if (v < 0) {
                throw new IllegalArgumentException("retrieval_* 权重不允许负数");
            }
        }
        if (rrfK < 1) {
            throw new IllegalArgumentException("RETRIEVAL_RRF_K 必须 >= 1");
        }
        if (mmrPerFileCap < 1) {
            throw new IllegalArgumentException("RETRIEVAL_MMR_PER_FILE_CAP 必须 >= 1");
        }
    }

    /** 默认值 = 冻结 Python 现值（config.py）。 */
    public static RetrievalSettings defaults() {
        return new RetrievalSettings(
                "li_knowledge_v1",
                "simple",
                "hybrid_fusion_v2",
                false,
                40,
                0.38,
                0.24,
                0.18,
                0.46,
                0.26,
                60,
                14.0,
                0.22,
                0.18,
                0.04,
                2,
                0.50,
                0.30,
                0.95,
                0.85,
                0.90,
                0.80,
                0.60,
                0.50,
                1.0,
                0.45,
                0.10,
                0.04);
    }

    /** 物理 PGVectorStore 表名（data_ 前缀），与 llama_index_service._data_table_name 一致。 */
    public String physicalTable() {
        return "data_" + tableName;
    }

    /** 带引号标识符（SQL 拼接前的校验产物），与 _quoted_data_table 一致。 */
    public String quotedTable() {
        return '"' + physicalTable() + '"';
    }
}
