package io.dpdk.assistant.chat;

import com.fasterxml.jackson.databind.*;

import io.dpdk.assistant.assignment.submission.PythonUtf8;
import io.dpdk.assistant.platform.errors.ApiDetailException;
import io.dpdk.assistant.task.TaskInputHash;

import jakarta.servlet.http.*;

import org.springframework.http.HttpStatus;

import java.util.*;

/** JSON 与 multipart 两条冻结输入路径；手工 Pydantic 错误无 body 前缀。 */
public final class ChatRequestParser {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final ObjectReader STRICT =
            JSON.readerFor(JsonNode.class).with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    private ChatRequestParser() {}

    public static ChatInput parse(HttpServletRequest request, int maxBytes) throws Exception {
        String type = Objects.toString(request.getContentType(), "");
        if (!type.startsWith("multipart/form-data")) {
            JsonNode body;
            try {
                body =
                        STRICT.readValue(
                                PythonUtf8.strictDecode(request.getInputStream().readAllBytes()));
            } catch (Exception bad) {
                throw error(422, "request body must be valid JSON");
            }
            if (body == null || !body.isObject())
                throw new IllegalStateException("chat body TypeError parity");
            return validate(body, null);
        }
        String message =
                TaskInputHash.pythonStrip(Objects.toString(request.getParameter("message"), ""));
        String session =
                TaskInputHash.pythonStrip(Objects.toString(request.getParameter("session_id"), ""));
        JsonNode context;
        try {
            context = STRICT.readValue(Objects.toString(request.getParameter("context"), "{}"));
        } catch (Exception bad) {
            throw error(400, "context must be a JSON object");
        }
        if (context == null || !context.isObject())
            throw error(400, "context must be a JSON object");
        ChatInput.Attachment attachment = null;
        Part file = request.getPart("file");
        if (file != null
                && file.getSubmittedFileName() != null
                && !file.getSubmittedFileName().isEmpty()) {
            String name = file.getSubmittedFileName();
            String leaf = name.substring(name.lastIndexOf('/') + 1);
            int dot = leaf.lastIndexOf('.');
            String suffix =
                    dot <= 0 || dot == leaf.length() - 1
                            ? ""
                            : leaf.substring(dot).toLowerCase(Locale.ROOT);
            if (!Set.of(".c", ".so").contains(suffix))
                throw error(
                        400, "仅支持上传 .c / .so 文件（收到 " + (suffix.isEmpty() ? "无扩展名" : suffix) + "）");
            byte[] bytes = file.getInputStream().readAllBytes();
            if (bytes.length > maxBytes) throw error(413, "文件超过大小上限（" + maxBytes + " 字节）");
            String[] pieces = leaf.substring(0, dot).split("_", -1);
            attachment =
                    new ChatInput.Attachment(
                            name,
                            suffix,
                            ".c".equals(suffix) ? PythonUtf8.replaceDecode(bytes) : null,
                            pieces.length >= 3 && !pieces[2].isEmpty()
                                    ? pieces[2].toLowerCase(Locale.ROOT)
                                    : null,
                            TaskInputHash.sha256Hex(bytes));
        }
        if (message.isEmpty() && attachment != null)
            message = "请帮我评测这个插件（" + attachment.filename() + "）";
        if (message.isEmpty()) throw error(422, "message must not be empty");
        var node = JSON.createObjectNode();
        node.put("message", message);
        node.set("context", context);
        if (!session.isEmpty()) node.put("session_id", session);
        if (request.getParameter("request_id") != null)
            node.put("request_id", request.getParameter("request_id"));
        return validate(node, attachment);
    }

    static ChatInput validate(JsonNode body, ChatInput.Attachment attachment) {
        List<Map<String, Object>> errors = new ArrayList<>();
        String session = optionalString(body, "session_id", errors);
        String requestId = optionalString(body, "request_id", errors);
        if (requestId != null) {
            try {
                String v = requestId.replace("urn:uuid:", "").replace("{", "").replace("}", "");
                if (v.matches("[0-9a-fA-F]{32}"))
                    v = v.replaceFirst("(.{8})(.{4})(.{4})(.{4})(.{12})", "$1-$2-$3-$4-$5");
                if (!v.matches(
                        "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"))
                    throw new IllegalArgumentException();
                requestId = UUID.fromString(v).toString();
            } catch (Exception bad) {
                errors.add(
                        entry(
                                "uuid_parsing",
                                "request_id",
                                "Input should be a valid UUID, invalid length: expected length 32"
                                    + " for simple format, found "
                                        + requestId.length(),
                                requestId));
            }
        }
        JsonNode messageNode = body.get("message");
        String message = null;
        if (messageNode == null)
            errors.add(
                    entry(
                            "missing",
                            "message",
                            "Field required",
                            JSON.convertValue(body, Object.class)));
        else if (!messageNode.isTextual())
            errors.add(
                    entry(
                            "string_type",
                            "message",
                            "Input should be a valid string",
                            JSON.convertValue(messageNode, Object.class)));
        else {
            message = messageNode.asText();
            int n = message.codePointCount(0, message.length());
            if (n < 1)
                errors.add(
                        entry(
                                "string_too_short",
                                "message",
                                "String should have at least 1 character",
                                message));
            if (n > 12000)
                errors.add(
                        entry(
                                "string_too_long",
                                "message",
                                "String should have at most 12000 characters",
                                message));
        }
        JsonNode contextNode = body.get("context");
        Map<String, Object> context = Map.of();
        if (contextNode != null) {
            if (!contextNode.isObject())
                errors.add(
                        entry(
                                "dict_type",
                                "context",
                                "Input should be a valid dictionary",
                                JSON.convertValue(contextNode, Object.class)));
            else
                context =
                        JSON.convertValue(
                                contextNode,
                                new com.fasterxml.jackson.core.type.TypeReference<
                                        LinkedHashMap<String, Object>>() {});
        }
        if (!errors.isEmpty()) throw error(422, errors);
        return new ChatInput(session, requestId, message, context, attachment);
    }

    private static String optionalString(
            JsonNode body, String key, List<Map<String, Object>> errors) {
        JsonNode n = body.get(key);
        if (n == null || n.isNull()) return null;
        if (!n.isTextual()) {
            errors.add(
                    entry(
                            "string_type",
                            key,
                            "Input should be a valid string",
                            JSON.convertValue(n, Object.class)));
            return null;
        }
        return n.asText();
    }

    private static Map<String, Object> entry(
            String type, String field, String message, Object input) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("type", type);
        out.put("loc", List.of(field));
        out.put("msg", message);
        out.put("input", input);
        return out;
    }

    static ApiDetailException error(int status, Object detail) {
        return new ApiDetailException(HttpStatus.valueOf(status), detail);
    }
}
