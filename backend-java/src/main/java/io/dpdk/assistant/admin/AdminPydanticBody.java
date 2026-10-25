package io.dpdk.assistant.admin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.dpdk.assistant.platform.errors.ApiDetailException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;

/**
 * /api/admin/* 请求体的 Pydantic v2 兼容校验（冻结 fastapi 0.115.6 / pydantic 2.10.4）。
 *
 * 覆盖 admin 请求模型的字段类型：字符串（长度约束）、Optional[int]（ge=0）、
 * bool、字符串列表（列表长度约束）。错误条目按字段定义序输出，golden
 * invalid-input 五条 observation 逐字节断言（missing/too_short/greater_than_equal）。
 *
 * 未被 golden 覆盖的分支（int_parsing/bool/list_type/json_invalid 语法错误等）
 * 按 pydantic v2 惯例文案输出，登记为非逐字节断言（evidence/J2-02/golden-diff.md）；
 * JSON 语法错误沿用 J2-01 已登记差异（Jackson 拒绝 Python 容忍的 NaN/Infinity）。
 */
final class AdminPydanticBody {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    enum Kind {
        STRING, INT, BOOL, STRING_LIST
    }

    /** name+类型+必填+约束；minLength/maxLength 对 STRING=字符数、STRING_LIST=元素个数。 */
    record Field(String name, Kind kind, boolean required, Integer minLength, Integer maxLength,
                 Integer ge) {
    }

    private AdminPydanticBody() {
    }

    /** 解析并校验；失败抛 422（detail 为数组）；成功返回字段名→值（缺失的可选字段为 null）。 */
    static Map<String, Object> parse(String rawBody, List<Field> fields) {
        JsonNode root = parseRoot(rawBody);
        if (!root.isObject()) {
            throw invalid(List.of(entry("model_attributes_type", List.of("body"),
                    "Input should be a valid dictionary or object to extract fields from",
                    plainValue(root), null)));
        }
        Map<String, Object> values = new LinkedHashMap<>();
        List<Map<String, Object>> errors = new ArrayList<>();
        for (Field field : fields) {
            JsonNode node = root.get(field.name());
            if (node == null || node.isNull()) {
                if (node == null && field.required()) {
                    errors.add(entry("missing", List.of("body", field.name()), "Field required",
                            plainValue(root), null));
                } else if (node != null && field.required()) {
                    // 显式 null 对必填字段按类型错误处理（pydantic v2）。
                    errors.add(typeError(field, node, List.of("body", field.name())));
                } else {
                    values.put(field.name(), null);
                }
                continue;
            }
            switch (field.kind()) {
                case STRING -> validateString(field, node, errors, values);
                case INT -> validateInt(field, node, errors, values);
                case BOOL -> validateBool(field, node, errors, values);
                case STRING_LIST -> validateStringList(field, node, errors, values);
            }
        }
        if (!errors.isEmpty()) {
            throw invalid(errors);
        }
        return values;
    }

    private static void validateString(Field field, JsonNode node,
                                       List<Map<String, Object>> errors, Map<String, Object> values) {
        if (!node.isTextual()) {
            errors.add(entry("string_type", List.of("body", field.name()),
                    "Input should be a valid string", plainValue(node), null));
            return;
        }
        String value = node.asText();
        int chars = value.codePointCount(0, value.length());
        if (field.minLength() != null && chars < field.minLength()) {
            errors.add(entry("string_too_short", List.of("body", field.name()),
                    "String should have at least " + field.minLength() + " characters",
                    value, Map.of("min_length", field.minLength())));
            return;
        }
        if (field.maxLength() != null && chars > field.maxLength()) {
            errors.add(entry("string_too_long", List.of("body", field.name()),
                    "String should have at most " + field.maxLength() + " characters",
                    value, Map.of("max_length", field.maxLength())));
            return;
        }
        values.put(field.name(), value);
    }

    private static void validateInt(Field field, JsonNode node,
                                    List<Map<String, Object>> errors, Map<String, Object> values) {
        Integer value = null;
        if (node.isInt() || node.isLong()) {
            value = node.asInt();
        } else if (node.isFloatingPointNumber()) {
            double d = node.asDouble();
            if (d == Math.rint(d) && !Double.isInfinite(d)) {
                value = (int) d;
            } else {
                errors.add(entry("int_from_float", List.of("body", field.name()),
                        "Input should be a valid integer, got a number with a fractional part",
                        plainValue(node), null));
                return;
            }
        } else {
            String type = node.isTextual() ? "int_parsing" : "int_type";
            String msg = node.isTextual()
                    ? "Input should be a valid integer, unable to parse string as an integer"
                    : "Input should be a valid integer";
            errors.add(entry(type, List.of("body", field.name()), msg, plainValue(node), null));
            return;
        }
        if (field.ge() != null && value < field.ge()) {
            errors.add(entry("greater_than_equal", List.of("body", field.name()),
                    "Input should be greater than or equal to " + field.ge(), value,
                    Map.of("ge", field.ge())));
            return;
        }
        values.put(field.name(), value);
    }

    private static void validateBool(Field field, JsonNode node,
                                     List<Map<String, Object>> errors, Map<String, Object> values) {
        if (!node.isBoolean()) {
            errors.add(entry("bool_type", List.of("body", field.name()),
                    "Input should be a valid boolean", plainValue(node), null));
            return;
        }
        values.put(field.name(), node.asBoolean());
    }

    private static void validateStringList(Field field, JsonNode node,
                                           List<Map<String, Object>> errors,
                                           Map<String, Object> values) {
        if (!node.isArray()) {
            errors.add(entry("list_type", List.of("body", field.name()),
                    "Input should be a valid list", plainValue(node), null));
            return;
        }
        List<String> items = new ArrayList<>();
        boolean elementError = false;
        for (int i = 0; i < node.size(); i++) {
            JsonNode item = node.get(i);
            if (!item.isTextual()) {
                errors.add(entry("string_type", List.of("body", field.name(), i),
                        "Input should be a valid string", plainValue(item), null));
                elementError = true;
            } else {
                items.add(item.asText());
            }
        }
        if (elementError) {
            return;
        }
        if (field.minLength() != null && items.size() < field.minLength()) {
            errors.add(entry("too_short", List.of("body", field.name()),
                    "List should have at least " + field.minLength() + " item"
                            + (field.minLength() == 1 ? "" : "s") + " after validation, not "
                            + items.size(),
                    plainValue(node), Map.of("min_length", field.minLength())));
            return;
        }
        if (field.maxLength() != null && items.size() > field.maxLength()) {
            errors.add(entry("too_long", List.of("body", field.name()),
                    "List should have at most " + field.maxLength() + " items after validation, not "
                            + items.size(),
                    plainValue(node), Map.of("max_length", field.maxLength())));
            return;
        }
        values.put(field.name(), items);
    }

    private static Map<String, Object> typeError(Field field, JsonNode node, List<Object> loc) {
        String msg = switch (field.kind()) {
            case STRING -> "Input should be a valid string";
            case INT -> "Input should be a valid integer";
            case BOOL -> "Input should be a valid boolean";
            case STRING_LIST -> "Input should be a valid list";
        };
        String type = switch (field.kind()) {
            case STRING -> "string_type";
            case INT -> "int_type";
            case BOOL -> "bool_type";
            case STRING_LIST -> "list_type";
        };
        return entry(type, loc, msg, plainValue(node), null);
    }

    private static JsonNode parseRoot(String rawBody) {
        if (rawBody == null || rawBody.isEmpty()) {
            throw invalid(List.of(entry("missing", List.of("body"), "Field required", null, null)));
        }
        try {
            JsonNode node = MAPPER.readTree(rawBody);
            return node == null ? MAPPER.nullNode() : node;
        } catch (Exception e) {
            // 沿用 J2-01 已登记差异：Jackson 对 Python 容忍字面量的拒绝归为 json_invalid。
            throw invalid(List.of(entry("json_invalid", List.of("body", 0), "JSON decode error",
                    Map.of(), Map.of("error", e.getMessage() == null ? "invalid JSON" : e.getMessage()))));
        }
    }

    private static Map<String, Object> entry(String type, List<Object> loc, String msg,
                                             Object input, Map<String, Object> ctx) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("type", type);
        out.put("loc", loc);
        out.put("msg", msg);
        out.put("input", input);
        if (ctx != null) {
            out.put("ctx", ctx);
        }
        return out;
    }

    private static ApiDetailException invalid(List<Map<String, Object>> errors) {
        return new ApiDetailException(HttpStatus.UNPROCESSABLE_ENTITY, errors);
    }

    private static Object plainValue(JsonNode node) {
        return MAPPER.convertValue(node, Object.class);
    }
}
