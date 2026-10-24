package io.dpdk.assistant.contracts.model;

/**
 * 聊天输入；jsonPayload 约定为 {"messages":[{role,content}...],"model"?...} 的 JSON 字符串
 * （J1-02 登记固化，见 migration/contracts/interface-changes.md）。富接口用 model.ModelClients。
 */
public record ChatInput(String model, String jsonPayload) {
}
