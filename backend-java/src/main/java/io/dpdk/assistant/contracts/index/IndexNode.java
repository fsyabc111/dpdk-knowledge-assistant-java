package io.dpdk.assistant.contracts.index;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 已计划的节点；既有节点可不携带向量，changed节点必须由发布实现验证向量。 */
public record IndexNode(String id, String content, Map<String, Object> metadata, List<Float> embedding) {
    public IndexNode {
        metadata = Collections.unmodifiableMap(new LinkedHashMap<>(metadata));
        embedding = embedding == null ? null : List.copyOf(embedding);
    }
}
