package io.dpdk.assistant.platform.errors;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.HttpStatus;

/**
 * FastAPI 风格业务错误：响应体固定为 {"detail": &lt;任意既有 detail&gt;}，
 * 状态码与响应头（Retry-After、WWW-Authenticate 等）由抛出方指定。
 * detail 可以是任意 JSON 值（对象/数组/字符串），不得压成统一字符串
 * （契约 http-and-sse.md）。Java 侧不使用 Spring 默认 ProblemDetail。
 */
public class ApiDetailException extends RuntimeException {

    private final HttpStatus status;
    private final Object detail;
    private final Map<String, String> headers;

    public ApiDetailException(HttpStatus status, Object detail) {
        this(status, detail, Map.of());
    }

    public ApiDetailException(HttpStatus status, Object detail, Map<String, String> headers) {
        super("api error " + status.value());
        this.status = status;
        this.detail = detail;
        this.headers = headers == null ? Map.of() : new LinkedHashMap<>(headers);
    }

    public HttpStatus status() {
        return status;
    }

    public Object detail() {
        return detail;
    }

    public Map<String, String> headers() {
        return headers;
    }
}
