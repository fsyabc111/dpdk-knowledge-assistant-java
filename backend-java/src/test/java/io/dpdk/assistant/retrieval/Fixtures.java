package io.dpdk.assistant.retrieval;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** J1-03 冻结 fixture/golden 加载器（classpath: migration/J1-03/*）。 */
public final class Fixtures {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private Fixtures() {
    }

    public static JsonNode load(String name) {
        try {
            return MAPPER.readTree(Fixtures.class.getResourceAsStream("/migration/J1-03/" + name));
        } catch (IOException e) {
            throw new UncheckedIOException("读取冻结 fixture 失败: " + name, e);
        }
    }

    public static List<Map<String, Object>> casesOf(JsonNode golden) {
        List<Map<String, Object>> out = new ArrayList<>();
        golden.withArray("cases").forEach(c -> out.add(mapper().convertValue(c,
                new com.fasterxml.jackson.core.type.TypeReference<LinkedHashMap<String, Object>>() {
                })));
        return out;
    }

    public static ObjectMapper mapper() {
        return MAPPER;
    }

    /** spec 节点 → node_id 到节点字段的映射。 */
    public static Map<String, JsonNode> nodesById(JsonNode spec) {
        Map<String, JsonNode> out = new LinkedHashMap<>();
        spec.withArray("nodes").forEach(n -> out.put(n.get("node_id").asText(), n));
        return out;
    }

    /** 由 nonzero 构造 1024 维 double 向量。 */
    public static double[] vectorOf(JsonNode nonzero) {
        double[] out = new double[1024];
        if (nonzero != null && !nonzero.isNull()) {
            for (JsonNode pair : nonzero) {
                out[pair.get(0).asInt()] = pair.get(1).asDouble();
            }
        }
        return out;
    }

    /** legs.json 的 cases[caseId] → 简单访问。 */
    public static JsonNode legCase(JsonNode legs, String caseId) {
        return legs.path("cases").path(caseId);
    }
}
