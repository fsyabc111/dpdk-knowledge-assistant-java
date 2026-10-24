package io.dpdk.assistant.harness;

/** MCP 传输/协议层失败（建连、initialize、请求写出/读取）；调用方收敛为结构化错误，不向业务抛穿。 */
final class McpTransportException extends RuntimeException {

    McpTransportException(String message) {
        super(message);
    }

    McpTransportException(String message, Throwable cause) {
        super(message, cause);
    }
}
