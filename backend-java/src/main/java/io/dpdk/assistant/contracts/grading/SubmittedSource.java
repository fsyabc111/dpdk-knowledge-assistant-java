package io.dpdk.assistant.contracts.grading;

/** 正式提交来源（JSON 粘贴或 .c/.so 上传，编码替换后文本与原件 hash）；字段由 J2-04 登记补全。 */
public record SubmittedSource(String protocol, String codeText, String fileName, byte[] originalBytes) {
}
