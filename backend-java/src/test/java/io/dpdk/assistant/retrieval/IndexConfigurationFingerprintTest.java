package io.dpdk.assistant.retrieval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import io.dpdk.assistant.contracts.index.ConfigurationFingerprint;
import io.dpdk.assistant.contracts.index.EmbeddingConfig;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * 配置指纹逐字节兼容（源 index_configuration 是唯一依据；
 * 另含非法配置与表名校验）。
 */
class IndexConfigurationFingerprintTest {

    private final ConfigurationFingerprint fingerprint = new IndexConfigurationFingerprint();

    @Test
    void defaultFingerprintMatchesGolden() {
        JsonNode golden = Fixtures.load("golden-retrieval.json");
        String actual = fingerprint.pythonCompatibleConfiguration(new EmbeddingConfig(
                "text-embedding-v4", "", 1024, "bounded-lines-v2"));
        assertThat(actual).isEqualTo(golden.get("fingerprint").asText());
        // 与 Python json.dumps(sort_keys=True) 的冻结串逐字节一致。
        assertThat(actual).isEqualTo(
                "{\"api_base\": \"\", \"chunker\": \"bounded-lines-v2\","
                        + " \"dimensions\": 1024, \"model\": \"text-embedding-v4\"}");
    }

    @Test
    void nonAsciiAndUrlAreEscapedNotNormalized() {
        String actual = fingerprint.pythonCompatibleConfiguration(new EmbeddingConfig(
                "模型", "http://host:8080/中文?x=1", 1024, "bounded-lines-v2"));
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("api_base", "http://host:8080/中文?x=1");
        payload.put("chunker", "bounded-lines-v2");
        payload.put("dimensions", 1024);
        payload.put("model", "模型");
        // URL 不得规范化（identity-and-data.md）；非 ASCII 按 ensure_ascii 转义。
        assertThat(actual).isEqualTo(PythonJson.dumps(payload));
        assertThat(actual).contains("\\u6a21\\u578b").contains("http://host:8080/\\u4e2d\\u6587?x=1");
    }

    @Test
    void nullConfigRejected() {
        assertThatThrownBy(() -> fingerprint.pythonCompatibleConfiguration(null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void settingsValidateTableAndWeightsLikeSource() {
        assertThatThrownBy(() -> new RetrievalSettings("bad-table;drop", "simple",
                "hybrid_fusion_v2", false, 40, 0.38, 0.24, 0.18, 0.46, 0.26, 60, 14.0, 0.22,
                0.18, 0.04, 2, 0.5, 0.3, 0.95, 0.85, 0.9, 0.8, 0.6, 0.5, 1.0, 0.45, 0.10, 0.04))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("PostgreSQL identifier");
        assertThatThrownBy(() -> new RetrievalSettings("li_knowledge_v1", "english",
                "hybrid_fusion_v2", false, 40, 0.38, 0.24, 0.18, 0.46, 0.26, 60, 14.0, 0.22,
                0.18, 0.04, 2, 0.5, 0.3, 0.95, 0.85, 0.9, 0.8, 0.6, 0.5, 1.0, 0.45, 0.10, 0.04))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("simple/zh");
        assertThatThrownBy(() -> new RetrievalSettings("li_knowledge_v1", "simple",
                "hybrid_fusion_v1", false, 40, 0.38, 0.24, 0.18, 0.46, 0.26, 60, 14.0, 0.22,
                0.18, 0.04, 2, 0.5, 0.3, 0.95, 0.85, 0.9, 0.8, 0.6, 0.5, 1.0, 0.45, 0.10, 0.04))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("RETRIEVAL_STRATEGY");
        assertThatThrownBy(() -> new RetrievalSettings("li_knowledge_v1", "simple",
                "hybrid_fusion_v2", false, 40, 0.38, 0.24, 0.18, 0.46, 0.26, 0, 14.0, 0.22,
                0.18, 0.04, 2, 0.5, 0.3, 0.95, 0.85, 0.9, 0.8, 0.6, 0.5, 1.0, 0.45, 0.10, 0.04))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("RETRIEVAL_RRF_K");
        assertThatThrownBy(() -> new RetrievalSettings("li_knowledge_v1", "simple",
                "hybrid_fusion_v2", false, 40, 0.38, 0.24, 0.18, 0.46, 0.26, 60, 14.0, 0.22,
                0.18, 0.04, 0, 0.5, 0.3, 0.95, 0.85, 0.9, 0.8, 0.6, 0.5, 1.0, 0.45, 0.10, 0.04))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("PER_FILE_CAP");
        assertThatThrownBy(() -> new RetrievalSettings("li_knowledge_v1", "simple",
                "hybrid_fusion_v2", false, 40, -0.1, 0.24, 0.18, 0.46, 0.26, 60, 14.0, 0.22,
                0.18, 0.04, 2, 0.5, 0.3, 0.95, 0.85, 0.9, 0.8, 0.6, 0.5, 1.0, 0.45, 0.10, 0.04))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("权重不允许负数");
    }

    @Test
    void physicalTableNames() {
        RetrievalSettings defaults = RetrievalSettings.defaults();
        assertThat(defaults.physicalTable()).isEqualTo("data_li_knowledge_v1");
        assertThat(defaults.quotedTable()).isEqualTo("\"data_li_knowledge_v1\"");
    }
}
