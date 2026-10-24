package io.dpdk.assistant.retrieval;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * PythonJson 与真实 json.dumps 逐字节对照（期望值由冻结 Python 生成：
 * migration/J1-03/python-json-expectations.json）。
 */
class PythonJsonTest {

    @Test
    void matchesFrozenPythonJsonDumps() {
        JsonNode cases = Fixtures.load("python-json-expectations.json");
        for (JsonNode c : cases) {
            boolean ensureAscii = c.get("ensure_ascii").asBoolean();
            String expected = c.get("expected").asText();
            Object structure = Fixtures.mapper().convertValue(c.get("obj"), Object.class);
            // LinkedHashMap 保序：Jackson 对 JSON 对象默认即保持文档序。
            assertThat(structure).isInstanceOf(Map.class);
            String actual = PythonJson.dumps(structure, ensureAscii);
            assertThat(actual)
                    .as("ensure_ascii=%s 的对象 %s", ensureAscii, c.get("obj"))
                    .isEqualTo(expected);
        }
    }

    @Test
    void defaultFingerprintStringShape() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("api_base", "");
        payload.put("chunker", "bounded-lines-v2");
        payload.put("dimensions", 1024);
        payload.put("model", "text-embedding-v4");
        assertThat(PythonJson.dumps(payload))
                .isEqualTo("{\"api_base\": \"\", \"chunker\": \"bounded-lines-v2\","
                        + " \"dimensions\": 1024, \"model\": \"text-embedding-v4\"}");
    }

    @Test
    void unicodeMetadataKeepsRawWithEnsureAsciiFalse() {
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("title", "证书提取");
        assertThat(PythonJson.dumpsUnicode(meta)).isEqualTo("{\"title\": \"证书提取\"}");
    }

    @Test
    void vectorTextParsing() {
        double[] v = PythonJson.parseVectorText("[1,0.5,0]");
        assertThat(v).containsExactly(1.0, 0.5, 0.0);
        assertThat(PythonJson.parseVectorText("[]")).isEmpty();
        assertThat(PythonJson.parseVectorText(null)).isEmpty();
    }

    @Test
    void vectorLiteralIsPythonFloatStyle() {
        double[] values = new double[1024];
        values[0] = 1.0f;
        values[1] = 0.5f;
        String literal = VectorMath.pgVectorLiteral(values);
        assertThat(literal).startsWith("[1.0,0.5,");
        assertThat(literal).endsWith(",0.0]");
        assertThat(literal.split(",")).hasSize(1024);
    }
}
