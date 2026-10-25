package io.dpdk.assistant.template;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.dpdk.assistant.platform.errors.ApiDetailException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;

/**
 * TemplateRequest 的 Pydantic v2 风格校验（对齐 schemas/chat.py TemplateRequest）：
 * protocol 必填 string（min_length=1）；template_type/difficulty/requirements
 * 可选 string（默认 port_text_protocol/basic/空串）。多余字段忽略。
 * 422 detail 数组形态与 auth.PydanticBody（J2-01 探针固化）一致。
 */
final class TemplateBody {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    record Parsed(String protocol, String templateType, String difficulty, String requirements) {
    }

    private TemplateBody() {
    }

    static Parsed parse(String rawBody) {
        JsonNode root = parseRoot(rawBody);
        if (!root.isObject()) {
            throw invalid(List.of(entry("model_attributes_type", List.of("body"),
                    "Input should be a valid dictionary or object to extract fields from",
                    plainValue(root), null)));
        }
        List<Map<String, Object>> errors = new ArrayList<>();
        JsonNode protocol = root.get("protocol");
        if (protocol == null) {
            errors.add(entry("missing", List.of("body", "protocol"), "Field required",
                    plainValue(root), null));
        } else if (!protocol.isTextual()) {
            errors.add(entry("string_type", List.of("body", "protocol"),
                    "Input should be a valid string", plainValue(protocol), null));
        } else if (protocol.asText().codePointCount(0, protocol.asText().length()) < 1) {
            errors.add(entry("string_too_short", List.of("body", "protocol"),
                    "String should have at least 1 character", protocol.asText(),
                    Map.of("min_length", 1)));
        }

        String templateType = optionalString(root, "template_type", errors);
        String difficulty = optionalString(root, "difficulty", errors);
        String requirements = optionalString(root, "requirements", errors);
        if (!errors.isEmpty()) {
            throw invalid(errors);
        }
        return new Parsed(protocol.asText(),
                templateType == null ? "port_text_protocol" : templateType,
                difficulty == null ? "basic" : difficulty,
                requirements == null ? "" : requirements);
    }

    private static String optionalString(JsonNode root, String field,
                                         List<Map<String, Object>> errors) {
        JsonNode node = root.get(field);
        if (node == null) {
            return null;
        }
        if (!node.isTextual()) {
            errors.add(entry("string_type", List.of("body", field),
                    "Input should be a valid string", plainValue(node), null));
            return null;
        }
        return node.asText();
    }

    private static JsonNode parseRoot(String rawBody) {
        if (rawBody == null || rawBody.isEmpty()) {
            throw invalid(List.of(entry("missing", List.of("body"), "Field required", null, null)));
        }
        try {
            return MAPPER.readTree(rawBody);
        } catch (Exception e) {
            // JSON 语法错误：对齐 PydanticBody 的 json_invalid 形态（pos 语义 J2-01 探针）。
            int pos = e instanceof com.fasterxml.jackson.core.JsonProcessingException jpe
                    && jpe.getLocation() != null
                    ? Math.max(0, (int) jpe.getLocation().getCharOffset()) : 0;
            throw invalid(List.of(entry("json_invalid", List.of("body", pos),
                    "JSON decode error", Map.of(), Map.of("error", e.getMessage()))));
        }
    }

    private static Object plainValue(JsonNode node) {
        if (node == null) {
            return null;
        }
        if (node.isObject()) {
            Map<String, Object> out = new LinkedHashMap<>();
            node.fields().forEachRemaining(f -> out.put(f.getKey(), plainValue(f.getValue())));
            return out;
        }
        if (node.isArray()) {
            List<Object> out = new ArrayList<>();
            node.forEach(item -> out.add(plainValue(item)));
            return out;
        }
        if (node.isTextual()) {
            return node.asText();
        }
        if (node.isIntegralNumber()) {
            return node.canConvertToInt() ? node.asInt() : node.asLong();
        }
        if (node.isFloatingPointNumber()) {
            return node.asDouble();
        }
        if (node.isBoolean()) {
            return node.asBoolean();
        }
        if (node.isNull()) {
            return null;
        }
        return node.asText();
    }

    private static Map<String, Object> entry(String type, Object loc, String msg, Object input,
                                             Map<String, ?> ctx) {
        Map<String, Object> e = new LinkedHashMap<>();
        e.put("type", type);
        e.put("loc", loc);
        e.put("msg", msg);
        e.put("input", input);
        if (ctx != null) {
            e.put("ctx", ctx);
        }
        return e;
    }

    private static ApiDetailException invalid(List<Map<String, Object>> errors) {
        return new ApiDetailException(HttpStatus.UNPROCESSABLE_ENTITY, new ArrayList<>(errors));
    }
}
