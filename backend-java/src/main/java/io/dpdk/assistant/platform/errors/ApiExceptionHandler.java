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

    @ExceptionHandler(ApiDetailException.class)
    public ResponseEntity<String> handleApiDetail(ApiDetailException ex) throws Exception {
        String body = MAPPER.writeValueAsString(java.util.Map.of("detail", ex.detail()));
        ResponseEntity.BodyBuilder builder = ResponseEntity.status(ex.status())
                .contentType(MediaType.APPLICATION_JSON);
        ex.headers().forEach(builder::header);
        return builder.body(body);
    }
}
