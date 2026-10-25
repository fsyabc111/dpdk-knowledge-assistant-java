package io.dpdk.assistant.knowledge.workflow;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.*;

/** 知识工作台的冻结 Pydantic 输入形状；字段顺序决定错误数组顺序。 */
final class WorkflowBody {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final JsonNode root;
    private final List<Map<String, Object>> errors = new ArrayList<>();

    WorkflowBody(String raw) {
        if (raw == null || raw.isEmpty()) throw KnowledgeWorkflowService.error(422,
                List.of(entry("missing", List.of("body"), "Field required", null, null)));
        JsonNode parsed;
        int consumed;
        try (var parser = JSON.createParser(raw)) {
            parsed = JSON.readTree(parser);
            consumed = (int) parser.currentLocation().getCharOffset();
        }
        catch (Exception e) { throw KnowledgeWorkflowService.error(422, List.of(entry("json_invalid",
                List.of("body", 0), "JSON decode error", Map.of(), Map.of("error", "Invalid JSON")))); }
        int after = consumed;
        while (after < raw.length() && " \t\r\n".indexOf(raw.charAt(after)) >= 0) { after++; }
        if (parsed != null && after < raw.length()) {
            throw KnowledgeWorkflowService.error(422, List.of(entry("json_invalid",
                    List.of("body", raw.codePointCount(0, after)), "JSON decode error", Map.of(),
                    Map.of("error", "Extra data"))));
        }
        root = parsed;
        if (root == null || !root.isObject()) throw KnowledgeWorkflowService.error(422, List.of(entry(
                "model_attributes_type", List.of("body"), "Input should be a valid dictionary or object to extract fields from",
                plain(root), null)));
    }

    String string(String key, String fallback, boolean nullable, int min, int max, String... choices) {
        JsonNode value = root.get(key);
        if (value == null && fallback != null) return fallback;
        if (value == null && nullable) return null;
        if (value == null) { add("missing", key, "Field required", plain(root), null); return null; }
        if (value.isNull() && nullable) return null;
        if (choices.length > 0) {
            String text = value.isTextual() ? value.asText() : null;
            if (text == null || !Arrays.asList(choices).contains(text)) {
                String expected = choices.length == 1 ? "'" + choices[0] + "'"
                        : String.join(", ", Arrays.stream(choices).limit(choices.length - 1).map(s -> "'"+s+"'").toList())
                        + " or '" + choices[choices.length - 1] + "'";
                add("literal_error", key, "Input should be " + expected, plain(value), Map.of("expected", expected));
            }
            return text;
        }
        if (!value.isTextual()) { add("string_type", key, "Input should be a valid string", plain(value), null); return null; }
        String text = value.asText();
        int size = text.codePointCount(0, text.length());
        if (size < min) add("string_too_short", key, "String should have at least " + min + " character" + (min == 1 ? "" : "s"), text, Map.of("min_length", min));
        if (size > max) add("string_too_long", key, "String should have at most " + max + " characters", text, Map.of("max_length", max));
        return text;
    }

    boolean bool(String key, boolean fallback) {
        JsonNode value = root.get(key);
        if (value == null) return fallback;
        if (value.isBoolean()) return value.asBoolean();
        if (value.isNumber() && (value.asText().equals("1") || value.asText().equals("0"))) return value.asInt() == 1;
        if (value.isTextual()) {
            String s = value.asText().toLowerCase(Locale.ROOT);
            if (Set.of("true", "t", "yes", "y", "on", "1").contains(s)) return true;
            if (Set.of("false", "f", "no", "n", "off", "0").contains(s)) return false;
        }
        boolean parse = value.isNumber() || value.isTextual();
        add(parse ? "bool_parsing" : "bool_type", key, parse ? "Input should be a valid boolean, unable to interpret input" : "Input should be a valid boolean", plain(value), null);
        return false;
    }

    int revision() {
        JsonNode value = root.get("revision");
        if (value == null) return 0;
        try {
            int n = value.isBoolean() ? (value.asBoolean() ? 1 : 0)
                    : new BigDecimal(value.asText().strip().replace("_", "")).intValueExact();
            if (n < 0) add("greater_than_equal", "revision", "Input should be greater than or equal to 0", plain(value), Map.of("ge", 0));
            return n;
        } catch (Exception e) {
            String type = value.isFloatingPointNumber() ? "int_from_float" : value.isTextual() ? "int_parsing" : "int_type";
            String message = type.equals("int_from_float") ? "Input should be a valid integer, got a number with a fractional part"
                    : type.equals("int_parsing") ? "Input should be a valid integer, unable to parse string as an integer" : "Input should be a valid integer";
            add(type, "revision", message, plain(value), null);
            return 0;
        }
    }

    void finish() { if (!errors.isEmpty()) throw KnowledgeWorkflowService.error(422, errors); }

    static int query(String raw, String key, int min, int max) {
        String type; String message; Map<String, Object> ctx;
        try {
            int value = Integer.parseInt(raw.strip());
            if (value >= min && value <= max) return value;
            boolean low = value < min;
            type = low ? "greater_than_equal" : "less_than_equal";
            message = "Input should be " + (low ? "greater than or equal to " + min : "less than or equal to " + max);
            ctx = Map.of(low ? "ge" : "le", low ? min : max);
        } catch (NumberFormatException e) {
            type = "int_parsing";
            message = "Input should be a valid integer, unable to parse string as an integer";
            ctx = null;
        }
        throw KnowledgeWorkflowService.error(422, List.of(entry(type, List.of("query", key), message, raw, ctx)));
    }

    private void add(String type, String key, String message, Object input, Map<String, Object> ctx) {
        errors.add(entry(type, List.of("body", key), message, input, ctx));
    }

    private static Map<String, Object> entry(String type, List<Object> loc, String message, Object input, Map<String, Object> ctx) {
        Map<String, Object> error = new LinkedHashMap<>();
        error.put("type", type); error.put("loc", loc); error.put("msg", message); error.put("input", input);
        if (ctx != null) error.put("ctx", ctx);
        return error;
    }

    private static Object plain(JsonNode node) { return node == null ? null : JSON.convertValue(node, Object.class); }
}
