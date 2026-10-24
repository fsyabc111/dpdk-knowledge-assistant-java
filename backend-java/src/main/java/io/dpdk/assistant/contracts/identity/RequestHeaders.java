package io.dpdk.assistant.contracts.identity;

import java.util.Map;

/** 请求头快照（Authorization 等）；仅承载 wire 事实，不含解析结果。 */
public record RequestHeaders(Map<String, String> headers) {
}
