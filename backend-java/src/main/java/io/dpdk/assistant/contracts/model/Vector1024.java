package io.dpdk.assistant.contracts.model;

/** 固定 1024 维向量（冻结 embedding 维度）。 */
public record Vector1024(float[] values) {

    public Vector1024 {
        if (values == null || values.length != 1024) {
            throw new IllegalArgumentException("向量维度必须是 1024");
        }
    }
}
