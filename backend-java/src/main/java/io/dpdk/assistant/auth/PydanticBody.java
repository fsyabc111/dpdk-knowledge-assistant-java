package io.dpdk.assistant.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.dpdk.assistant.platform.errors.ApiDetailException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;

/**
 * FastAPI/Pydantic v2 风格的请求体校验（冻结版本 fastapi 0.115.6 / pydantic 2.10.4，
 * 行为按冻结 venv 实测探针固化；探针记录见 evidence/J2-01/pydantic-probe.md）。
 *
 * 固化行为：
 * - 空/缺体 → 422 [{"type":"missing","loc":["body"],"msg":"Field required","input":null}]；
 * - JSON 语法错误 → 422 [{"type":"json_invalid","loc":["body",pos],"msg":"JSON decode error",
 *   "input":{},"ctx":{"error":&lt;Python json msg&gt;}}]（pos 语义对齐 json.JSONDecodeError.pos）；
 * - 非 JSON 对象 → 422 [{"type":"model_attributes_type","loc":["body"],
 *   "msg":"Input should be a valid dictionary or object to extract fields from","input":&lt;值&gt;}]；
 * - 字段错误按字段定义序：missing / string_type / string_too_short / string_too_long；
 *   missing 的 input 为整个 body 对象（含 null 字段与多余字段），长度错误带 ctx，
 *   多余字段忽略。校验先于端点逻辑（422 优先于 401/404/429）。
 * 已知差异（登记）：NaN/Infinity 字面量 Python 接受后走字段校验，Java 侧 Jackson
 * 拒绝 → json_invalid。
 */
final class PydanticBody {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    record StringField(String name, int minLength, int maxLength) {
    }

    private PydanticBody() {
    }

    /** 解析并校验；失败抛 422（detail 为数组）；成功返回字段名→字符串值。 */
    static Map<String, String> parseStringObject(String rawBody, List<StringField> fields) {
        JsonNode root = parseRoot(rawBody);
        if (!root.isObject()) {
            throw invalid(List.of(entry("model_attributes_type", List.of("body"),
                    "Input should be a valid dictionary or object to extract fields from",
                    plainValue(root), null)));
        }
        Map<String, String> values = new LinkedHashMap<>();
        List<Map<String, Object>> errors = new ArrayList<>();
        for (StringField field : fields) {
            JsonNode node = root.get(field.name());
            if (node == null) {
                errors.add(entry("missing", List.of("body", field.name()), "Field required",
                        plainValue(root), null));
                continue;
            }
            if (!node.isTextual()) {
                errors.add(entry("string_type", List.of("body", field.name()),
                        "Input should be a valid string", plainValue(node), null));
                continue;
            }
            String value = node.asText();
            int chars = value.codePointCount(0, value.length());
            if (chars < field.minLength()) {
                errors.add(entry("string_too_short", List.of("body", field.name()),
                        "String should have at least " + plural(field.minLength(), "character"),
                        value, Map.of("min_length", field.minLength())));
                continue;
            }
            if (chars > field.maxLength()) {
                errors.add(entry("string_too_long", List.of("body", field.name()),
                        "String should have at most " + plural(field.maxLength(), "character"),
                        value, Map.of("max_length", field.maxLength())));
                continue;
            }
            values.put(field.name(), value);
        }
        if (!errors.isEmpty()) {
            throw invalid(errors);
        }
        return values;
    }

    /** FastAPI 语义：空 body 字节按“缺体”报 missing；语法错误按 json_invalid。 */
    private static JsonNode parseRoot(String rawBody) {
        if (rawBody == null || rawBody.isEmpty()) {
            throw invalid(List.of(entry("missing", List.of("body"), "Field required", null, null)));
        }
        PythonJsonScanner.DecodeError error = PythonJsonScanner.validate(rawBody);
        if (error != null) {
            throw invalid(List.of(entry("json_invalid", List.of("body", error.pos()),
                    "JSON decode error", Map.of(), Map.of("error", error.msg()))));
        }
        try {
            JsonNode node = MAPPER.readTree(rawBody);
            // readTree 对空输入返回 null；scanner 已拒绝空串，这里防御性兜底。
            return node == null ? MAPPER.nullNode() : node;
        } catch (Exception e) {
            // scanner 接受但 Jackson 拒绝的残余差异（NaN/Infinity 等）→ 登记为已知差异。
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

    /** JsonNode → 普通值（保持数字类型），供错误 input 序列化。 */
    private static Object plainValue(JsonNode node) {
        return MAPPER.convertValue(node, Object.class);
    }

    private static String plural(int n, String noun) {
        return n + " " + (n == 1 ? noun : noun + "s");
    }
}
