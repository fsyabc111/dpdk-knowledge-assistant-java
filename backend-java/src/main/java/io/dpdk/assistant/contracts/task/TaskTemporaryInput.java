package io.dpdk.assistant.contracts.task;

/** 只存入带 TTL 的临时输入表，终态清理；不得写入长期消息或日志。 */
public record TaskTemporaryInput(String attachmentName, String attachmentSuffix,
                                 String sourceText, String sourceOrigin) {
    public static TaskTemporaryInput empty() {
        return new TaskTemporaryInput(null, null, null, null);
    }
}
