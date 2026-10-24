package io.dpdk.assistant.contracts.task;

/** 请求身份（契约 transaction-ports.json：task.AdmissionPort.data_fields）。 */
public record RequestIdentity(String requestId, String inputHash, boolean initialRequest, Purpose purpose) {

    public enum Purpose {chat, explain}
}
