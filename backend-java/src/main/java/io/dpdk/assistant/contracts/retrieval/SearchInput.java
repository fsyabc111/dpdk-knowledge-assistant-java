package io.dpdk.assistant.contracts.retrieval;

/** 检索输入（问题向量/改写文本等）；字段由 J1-03 登记补全。 */
public record SearchInput(String question, io.dpdk.assistant.contracts.model.Vector1024 queryVector,
                          String conversationContext) {
    public SearchInput(String question, io.dpdk.assistant.contracts.model.Vector1024 queryVector) {
        this(question, queryVector, null);
    }
}
