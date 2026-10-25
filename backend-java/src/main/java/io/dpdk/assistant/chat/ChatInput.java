package io.dpdk.assistant.chat;

import java.util.Map;

/** HTTP 解析后的请求；附件 hash 来自上传原字节，源码仅寄存至任务临时输入。 */
public record ChatInput(
        String sessionId,
        String requestId,
        String message,
        Map<String, Object> context,
        Attachment attachment) {
    public ChatInput {
        context =
                context == null
                        ? Map.of()
                        : java.util.Collections.unmodifiableMap(
                                new java.util.LinkedHashMap<>(context));
    }

    public record Attachment(
            String filename,
            String suffix,
            String source,
            String protocolHint,
            String contentHash) {}
}
