package io.dpdk.assistant.platform.errors;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 全局错误渲染：把 {@link ApiDetailException} 渲染为 {"detail": ...} 精确字节。
 * 框架层 404/405/500 与 Pydantic 422 的逐字段兼容属于后续业务卡与 oracle 对照范围，
 * 本类不擅自把其他异常映射成兼容响应（未覆盖面在 J1-01 交接中登记为未验证项）。
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @ExceptionHandler({org.springframework.web.servlet.resource.NoResourceFoundException.class,
            org.springframework.web.servlet.NoHandlerFoundException.class})
    public ResponseEntity<?> handleNotFound(Exception ex) {
        return ResponseEntity.status(404).body(java.util.Map.of("detail", "Not Found"));
    }

    @ExceptionHandler(org.springframework.web.HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<?> handleMethodNotAllowed(org.springframework.web.HttpRequestMethodNotSupportedException ex) {
        String[] allowed = ex.getSupportedMethods();
        return ResponseEntity.status(405)
                .header("Allow", allowed == null ? "" : String.join(", ", allowed))
                .body(java.util.Map.of("detail", "Method Not Allowed"));
    }

    @ExceptionHandler(ApiDetailException.class)
    public ResponseEntity<String> handleApiDetail(ApiDetailException ex) throws Exception {
        String body = MAPPER.writeValueAsString(java.util.Map.of("detail", ex.detail()));
        ResponseEntity.BodyBuilder builder = ResponseEntity.status(ex.status())
                .contentType(MediaType.APPLICATION_JSON);
        ex.headers().forEach(builder::header);
        return builder.body(body);
    }

    /**
     * 未捕获异常兜底：对齐 FastAPI/Starlette ServerErrorMiddleware 默认行为——
     * 500 + 纯文本 "Internal Server Error"（p0-v2 未钉死 500 体，P5 全矩阵复核；
     * 见 interface-changes.md D2 关闭记录）。不向客户端回显异常细节。
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<String> handleUnexpected(Exception ex) {
        org.slf4j.LoggerFactory.getLogger(ApiExceptionHandler.class)
                .warn("未捕获异常（500 兜底）: {}", ex.getClass().getSimpleName());
        return ResponseEntity.status(500)
                .contentType(MediaType.TEXT_PLAIN)
                .body("Internal Server Error");
    }
}
