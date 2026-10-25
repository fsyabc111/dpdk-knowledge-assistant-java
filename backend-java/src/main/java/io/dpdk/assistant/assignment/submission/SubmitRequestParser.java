package io.dpdk.assistant.assignment.submission;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import io.dpdk.assistant.platform.errors.ApiDetailException;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.Part;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * 提交请求解析（冻结 backend/app/api/assignments.py 的 `_parse_submit_request`，
 * 兼容 multipart（.c/.so 文件）与 JSON（粘贴代码）；返回 (protocol, code, origin)。
 *
 * <p>分支与错误序（照源）：
 * <ul>
 *   <li>Content-Type 不以 {@code multipart/form-data} 开头（区分大小写）→ JSON 路径：
 *       严格 UTF-8/JSON 解析失败 → 422 "request body must be valid JSON"；
 *       非对象 body（Python `**body` 对 list/str/None 的 TypeError）→ 500；
 *       手工构造 AssignmentSubmitRequest 的 pydantic 422（loc 无 "body" 前缀、
 *       include_url/include_context 均关闭、missing 的 input=整个 body 字典）；
 *       origin="pasted"；protocol/code 不做 strip/大小检查（后者在 grade_code）。</li>
 *   <li>multipart 路径：protocol 取表单字段（strip；空 → 422 "protocol must not be
 *       empty"）→ file 部件须带非空文件名（否则 422 "需要上传 .c/.so 文件，或用 JSON
 *       粘贴代码"）→ 扩展名（{@code Path(filename).suffix.lower()}，点文件/尾点为空）
 *       不属 {.c,.so} → 400（在快照/截止判定之前）→ 大小超限 413（"文件超过大小上限"）
 *       → UTF-8 替换解码，origin="upload"。</li>
 * </ul>
 */
@Component
public final class SubmitRequestParser {

    /** ALLOWED_UPLOAD_SUFFIXES（assignments.py 模块常量）。 */
    private static final Set<String> ALLOWED_UPLOAD_SUFFIXES = Set.of(".c", ".so");

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** json.loads 语义：完整文档 + 尾随 token 拒绝（SP-02）。 */
    private static final ObjectReader STRICT_JSON = MAPPER
            .readerFor(JsonNode.class)
            .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    private SubmitRequestParser() {
    }

    /** 解析结果：(protocol, code, origin)；fileName 仅上传路径非空。 */
    public record Parsed(String protocol, String code, String sourceOrigin, String fileName) {
    }

    public static Parsed parse(HttpServletRequest request, int maxCodeBytes)
            throws IOException, ServletException {
        String contentType = request.getHeader("Content-Type");
        if (contentType == null) {
            contentType = "";
        }
        if (!contentType.startsWith("multipart/form-data")) {
            return parseJson(request);
        }
        return parseMultipart(request, maxCodeBytes);
    }

    /* ------------------------------------------------------------------ */
    /* JSON 粘贴路径                                                        */
    /* ------------------------------------------------------------------ */

    private static Parsed parseJson(HttpServletRequest request) throws IOException {
        byte[] raw = request.getInputStream().readAllBytes();
        String text;
        try {
            text = PythonUtf8.strictDecode(raw);
        } catch (IllegalStateException e) {
            // UnicodeDecodeError → 422（照源同一条 detail）。
            throw invalidJson();
        }
        JsonNode body;
        try {
            // Python request.json() = json.loads：空/纯空白/多文档/尾随垃圾都抛
            // JSONDecodeError → 422；SP-02（2026-10-25 验收修复）以
            // FAIL_ON_TRAILING_TOKENS 恢复该语义，readTree 只取首个文档会放过垃圾。
            body = STRICT_JSON.readValue(text);
        } catch (Exception e) {
            throw invalidJson();
        }
        if (body == null || !body.isObject()) {
            // Python `AssignmentSubmitRequest(**body)` 对非映射（JSON null/标量/数组）
            // 抛 TypeError → 500。
            throw new IllegalStateException(
                    "submit body is not a JSON object (TypeError parity)");
        }
        List<Map<String, Object>> errors = new ArrayList<>();
        String protocol = stringField(body, "protocol", 1, 64, body, errors);
        String code = stringField(body, "code", 1, null, body, errors);
        if (!errors.isEmpty()) {
            throw new ApiDetailException(HttpStatus.UNPROCESSABLE_ENTITY, errors);
        }
        return new Parsed(protocol, code, "pasted", null);
    }

    /**
     * pydantic v2 手工模型字段校验：loc=["<field>"]（无 body 前缀）、
     * errors(include_url=False, include_context=False) → 仅 type/loc/msg/input。
     */
    private static String stringField(JsonNode body, String name, int minLength,
            Integer maxLength, JsonNode wholeInput, List<Map<String, Object>> errors) {
        JsonNode node = body.get(name);
        if (node == null) {
            errors.add(entry("missing", List.of(name), "Field required",
                    MAPPER.convertValue(wholeInput, Object.class)));
            return null;
        }
        if (!node.isTextual()) {
            errors.add(entry("string_type", List.of(name),
                    "Input should be a valid string",
                    MAPPER.convertValue(node, Object.class)));
            return null;
        }
        String value = node.asText();
        int chars = value.codePointCount(0, value.length());
        if (chars < minLength) {
            errors.add(entry("string_too_short", List.of(name),
                    "String should have at least " + minLength + " character"
                            + (minLength == 1 ? "" : "s"), value));
            return null;
        }
        if (maxLength != null && chars > maxLength) {
            errors.add(entry("string_too_long", List.of(name),
                    "String should have at most " + maxLength + " characters", value));
            return null;
        }
        return value;
    }

    private static Map<String, Object> entry(String type, List<Object> loc, String msg,
            Object input) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("type", type);
        out.put("loc", loc);
        out.put("msg", msg);
        out.put("input", input);
        return out;
    }

    private static ApiDetailException invalidJson() {
        return new ApiDetailException(HttpStatus.UNPROCESSABLE_ENTITY,
                "request body must be valid JSON");
    }

    /* ------------------------------------------------------------------ */
    /* multipart 上传路径                                                   */
    /* ------------------------------------------------------------------ */

    private static Parsed parseMultipart(HttpServletRequest request, int maxCodeBytes)
            throws IOException, ServletException {
        Part protocolPart = request.getPart("protocol");
        String protocol = protocolPart == null ? "" : readText(protocolPart);
        protocol = pythonStrip(protocol);
        if (protocol.isEmpty()) {
            throw new ApiDetailException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "protocol must not be empty");
        }
        Part upload = request.getPart("file");
        String filename = upload == null ? null : upload.getSubmittedFileName();
        if (filename == null || filename.isEmpty()) {
            throw new ApiDetailException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "需要上传 .c/.so 文件，或用 JSON 粘贴代码");
        }
        String suffix = pathSuffix(filename).toLowerCase();
        if (!ALLOWED_UPLOAD_SUFFIXES.contains(suffix)) {
            throw new ApiDetailException(HttpStatus.BAD_REQUEST,
                    "仅支持上传 .c / .so 文件（收到 " + (suffix.isEmpty() ? "无扩展名" : suffix)
                            + "）");
        }
        byte[] data = upload.getInputStream().readAllBytes();
        if (data.length > maxCodeBytes) {
            throw new ApiDetailException(HttpStatus.PAYLOAD_TOO_LARGE,
                    "文件超过大小上限（" + maxCodeBytes + " 字节）");
        }
        return new Parsed(protocol, PythonUtf8.replaceDecode(data), "upload", filename);
    }

    private static String readText(Part part) throws IOException {
        return new String(part.getInputStream().readAllBytes(),
                java.nio.charset.StandardCharsets.UTF_8);
    }

    /** Python `Path(name).suffix`：最后一个点后缀；点开头或点结尾为空串。 */
    static String pathSuffix(String filename) {
        int i = filename.lastIndexOf('.');
        if (0 < i && i < filename.length() - 1) {
            return filename.substring(i);
        }
        return "";
    }

    /** Python str.strip()：去除首尾 Unicode 空白（近似 Java strip()，测试域内一致）。 */
    private static String pythonStrip(String value) {
        return value.strip();
    }
}
