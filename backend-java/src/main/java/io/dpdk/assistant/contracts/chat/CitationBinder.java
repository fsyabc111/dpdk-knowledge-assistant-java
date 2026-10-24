package io.dpdk.assistant.contracts.chat;

import io.dpdk.assistant.contracts.retrieval.RetrievedChunk;
import java.util.List;

/**
 * 引用绑定端口（owner J3-03；契约 transaction-ports.json：chat.CitationBinder）。
 *
 * 不按文件去重，顺序=模型证据编号，score Python round 4 位；engine 由 li_ 前缀判断，保存 rank。
 */
public interface CitationBinder {

    List<Citation> bindInOrder(List<RetrievedChunk> chunks);
}
