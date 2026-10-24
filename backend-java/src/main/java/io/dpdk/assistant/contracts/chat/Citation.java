package io.dpdk.assistant.contracts.chat;

/** 引用（顺序=模型证据编号；score 为 Python round(x,4)；engine 由 li_ 前缀判断）。 */
public record Citation(String chunkId, String engine, double score, int rank) {
}
