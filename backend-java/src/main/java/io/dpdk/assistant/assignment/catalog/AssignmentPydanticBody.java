package io.dpdk.assistant.assignment.catalog;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.dpdk.assistant.platform.errors.ApiDetailException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;

/**
 * /api/assignments 创建/修改请求体的 Pydantic v2 兼容校验（冻结
 * pydantic 2.10.4；错误条目沿 AdminPydanticBody 的同版本文案口径）。
 *
 * golden 逐字节断言的分支：POST {} 的三条 missing（title/deadline/items 按字段定义
 * 序）、PATCH {"title":""} 的 string_too_short（ctx.min_length）。其余分支
 * （datetime_parsing、items 嵌套 loc、points 边界等）按 pydantic v2 惯例输出，
 * 登记为非逐字节断言（evidence/J2-03/golden-diff.md）。
 *
 * deadline 语义：ISO-8601（T 或空格分隔，Z/偏移/无偏移均可；naive 按 UTC 入库，
 * 同 `_normalize_deadline`）；数值按 pydantic 惯例视为 epoch 秒。
 * 显式 null 对 Optional 字段等价缺失（不更新）。
 */
final class AssignmentPydanticBody {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private AssignmentPydanticBody() {
    }

    /** 解析结果；字段缺省为 null。 */
    record CreateFields(String title, String description, Instant deadline,
            List<AssignmentRow.ItemRow> items) {
    }

    /** 解析并校验创建请求（AssignmentCreateRequest）；失败抛 422（detail 数组）。 */
    static CreateFields parseCreate(String rawBody) {
        Map<String, Object> bag = new LinkedHashMap<>();
        List<Map<String, Object>> errors = new ArrayList<>();
        JsonNode root = parseRoot(rawBody);
        String title = validateTitle(root, bag, errors, true);
        String description = validateDescription(root, bag, errors);
        Instant deadline = validateDeadline(root, bag, errors, true);
        List<AssignmentRow.ItemRow> items = validateItems(root, bag, errors, true);
        throwIfAny(errors);
        return new CreateFields(title, description, deadline, items);
    }

    /** 解析并校验修改请求（AssignmentUpdateRequest；全字段可选）。 */
    record UpdateFields(String title, String description, Instant deadline,
            List<AssignmentRow.ItemRow> items) {
    }

    static UpdateFields parseUpdate(String rawBody) {
        List<Map<String, Object>> errors = new ArrayList<>();
        JsonNode root = parseRoot(rawBody);
        String title = validateTitle(root, null, errors, false);
        String description = validateDescription(root, null, errors);
        Instant deadline = validateDeadline(root, null, errors, false);
        List<AssignmentRow.ItemRow> items = validateItems(root, null, errors, false);
        throwIfAny(errors);
        return new UpdateFields(title, description, deadline, items);
    }

    /* ------------------------------------------------------------------ */

    /** title：Optional[str]，min_length=1，max_length=200（提供时校验）。 */
    private static String validateTitle(JsonNode root, Map<String, Object> bag,
            List<Map<String, Object>> errors, boolean required) {
        JsonNode node = root.get("title");
        if (node == null || node.isNull()) {
            if (required && node == null) {
                errors.add(missing("title", plainValue(root)));
            }
            return null;
        }
        if (!node.isTextual()) {
            errors.add(typeError("string_type", "title", "Input should be a valid string",
                    plainValue(node)));
            return null;
        }
        String value = node.asText();
        int chars = value.codePointCount(0, value.length());
        if (chars < 1) {
            errors.add(entry("string_too_short", List.of("body", "title"),
                    "String should have at least 1 characters", value, Map.of("min_length", 1)));
            return null;
        }
        if (chars > 200) {
            errors.add(entry("string_too_long", List.of("body", "title"),
                    "String should have at most 200 characters", value, Map.of("max_length", 200)));
            return null;
        }
        return value;
    }

    /** description：Optional[str]，无长度约束（默认 ""，显式 null=不更新）。 */
    private static String validateDescription(JsonNode root, Map<String, Object> bag,
            List<Map<String, Object>> errors) {
        JsonNode node = root.get("description");
        if (node == null || node.isNull()) {
            return null;
        }
        if (!node.isTextual()) {
            errors.add(typeError("string_type", "description", "Input should be a valid string",
                    plainValue(node)));
            return null;
        }
        return node.asText();
    }

    /** deadline：datetime；naive 按 UTC（`_normalize_deadline`）。 */
    private static Instant validateDeadline(JsonNode root, Map<String, Object> bag,
            List<Map<String, Object>> errors, boolean required) {
        JsonNode node = root.get("deadline");
        if (node == null || node.isNull()) {
            if (required && node == null) {
                errors.add(missing("deadline", plainValue(root)));
            }
            return null;
        }
        if (node.isNumber()) {
            // pydantic v2 惯例：数值按 epoch 秒。
            return Instant.ofEpochMilli((long) (node.asDouble() * 1000));
        }
        if (!node.isTextual()) {
            errors.add(typeError("datetime_type", "deadline",
                    "Input should be a valid datetime or date", plainValue(node)));
            return null;
        }
        String text = node.asText().trim().replace(' ', 'T');
        try {
            // 先按带偏移解析，失败再按 naive（→UTC）。
            try {
                return OffsetDateTime.parse(text).toInstant();
            } catch (DateTimeParseException e) {
                return LocalDateTime.parse(text).toInstant(ZoneOffset.UTC);
            }
        } catch (DateTimeParseException e) {
            errors.add(entry("datetime_parsing", List.of("body", "deadline"),
                    "Input should be a valid datetime or date", plainValue(node),
                    Map.of("error", "invalid datetime string")));
            return null;
        }
    }

    /** items：List[AssignmentItemIn]，min 1、max 50；protocol 1-64、points 1..1000。 */
    private static List<AssignmentRow.ItemRow> validateItems(JsonNode root,
            Map<String, Object> bag, List<Map<String, Object>> errors, boolean required) {
        JsonNode node = root.get("items");
        if (node == null || node.isNull()) {
            if (required && node == null) {
                errors.add(missing("items", plainValue(root)));
            }
            return null;
        }
        if (!node.isArray()) {
            errors.add(typeError("list_type", "items", "Input should be a valid list",
                    plainValue(node)));
            return null;
        }
        List<AssignmentRow.ItemRow> items = new ArrayList<>();
        boolean elementError = false;
        for (int i = 0; i < node.size(); i++) {
            JsonNode item = node.get(i);
            if (item == null || !item.isObject()) {
                errors.add(typeError("model_type", "items", "Input should be a valid dictionary",
                        plainValue(item)));
                elementError = true;
                continue;
            }
            String protocol = validateItemProtocol(item, i, errors);
            Integer points = validateItemPoints(item, i, errors);
            if (protocol != null && points != null) {
                items.add(new AssignmentRow.ItemRow(protocol, points));
            } else {
                elementError = true;
            }
        }
        if (elementError) {
            return null;
        }
        if (items.size() < 1) {
            errors.add(entry("too_short", List.of("body", "items"),
                    "List should have at least 1 item after validation, not " + items.size(),
                    plainValue(node), Map.of("min_length", 1)));
            return null;
        }
        if (items.size() > 50) {
            errors.add(entry("too_long", List.of("body", "items"),
                    "List should have at most 50 items after validation, not " + items.size(),
                    plainValue(node), Map.of("max_length", 50)));
            return null;
        }
        return items;
    }

    private static String validateItemProtocol(JsonNode item, int index,
            List<Map<String, Object>> errors) {
        JsonNode node = item.get("protocol");
        if (node == null) {
            errors.add(entry("missing", List.of("body", "items", index, "protocol"),
                    "Field required", plainValue(item), null));
            return null;
        }
        if (!node.isTextual()) {
            errors.add(entry("string_type", List.of("body", "items", index, "protocol"),
                    "Input should be a valid string", plainValue(node), null));
            return null;
        }
        String value = node.asText();
        int chars = value.codePointCount(0, value.length());
        if (chars < 1) {
            errors.add(entry("string_too_short", List.of("body", "items", index, "protocol"),
                    "String should have at least 1 characters", value,
                    Map.of("min_length", 1)));
            return null;
        }
        if (chars > 64) {
            errors.add(entry("string_too_long", List.of("body", "items", index, "protocol"),
                    "String should have at most 64 characters", value,
                    Map.of("max_length", 64)));
            return null;
        }
        return value;
    }

    private static Integer validateItemPoints(JsonNode item, int index,
            List<Map<String, Object>> errors) {
        JsonNode node = item.get("points");
        if (node == null) {
            errors.add(entry("missing", List.of("body", "items", index, "points"),
                    "Field required", plainValue(item), null));
            return null;
        }
        Integer value;
        if (node.isInt() || node.isLong()) {
            value = node.asInt();
        } else if (node.isFloatingPointNumber()) {
            double d = node.asDouble();
            if (d == Math.rint(d) && !Double.isInfinite(d)) {
                value = (int) d;
            } else {
                errors.add(entry("int_from_float", List.of("body", "items", index, "points"),
                        "Input should be a valid integer, got a number with a fractional part",
                        plainValue(node), null));
                return null;
            }
        } else {
            // 沿用 J2-02 AdminPydanticBody 的口径：字符串不宽松转 int。
            errors.add(entry("int_parsing", List.of("body", "items", index, "points"),
                    "Input should be a valid integer, unable to parse string as an integer",
                    plainValue(node), null));
            return null;
        }
        if (value < 1) {
            errors.add(entry("greater_than_equal", List.of("body", "items", index, "points"),
                    "Input should be greater than or equal to 1", value, Map.of("ge", 1)));
            return null;
        }
        if (value > 1000) {
            errors.add(entry("less_than_equal", List.of("body", "items", index, "points"),
                    "Input should be less than or equal to 1000", value, Map.of("le", 1000)));
            return null;
        }
        return value;
    }

    /* ------------------------------------------------------------------ */

    private static JsonNode parseRoot(String rawBody) {
        if (rawBody == null || rawBody.isEmpty()) {
            throw invalid(List.of(missing("body-root", null)));
        }
        try {
            JsonNode node = MAPPER.readTree(rawBody);
            return node == null ? MAPPER.nullNode() : node;
        } catch (Exception e) {
            throw invalid(List.of(entry("json_invalid", List.of("body", 0), "JSON decode error",
                    Map.of(), Map.of("error", e.getMessage() == null ? "invalid JSON"
                            : e.getMessage()))));
        }
    }

    /** body-root missing：FastAPI 空 body 的 loc=["body"]，msg=Field required。 */
    private static Map<String, Object> missing(String field, Object input) {
        if ("body-root".equals(field)) {
            return entry("missing", List.of("body"), "Field required", null, null);
        }
        return entry("missing", List.of("body", field), "Field required", input, null);
    }

    private static Map<String, Object> typeError(String type, String field, String msg,
            Object input) {
        return entry(type, List.of("body", field), msg, input, null);
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

    private static void throwIfAny(List<Map<String, Object>> errors) {
        if (!errors.isEmpty()) {
            throw invalid(errors);
        }
    }

    private static ApiDetailException invalid(List<Map<String, Object>> errors) {
        return new ApiDetailException(HttpStatus.UNPROCESSABLE_ENTITY, errors);
    }

    private static Object plainValue(JsonNode node) {
        return node == null ? null : MAPPER.convertValue(node, Object.class);
    }
}
