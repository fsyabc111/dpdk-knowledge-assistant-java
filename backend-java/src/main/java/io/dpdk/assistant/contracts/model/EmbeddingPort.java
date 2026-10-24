package io.dpdk.assistant.contracts.model;

import java.util.List;

/** 嵌入端口（owner J1-02；固定 1024 维；P1 用冻结向量替身）。 */
public interface EmbeddingPort {

    List<Vector1024> embed(EmbeddingInput input);
}
