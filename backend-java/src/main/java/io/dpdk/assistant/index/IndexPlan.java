package io.dpdk.assistant.index;

import io.dpdk.assistant.contracts.index.PreparedIndex;

/** 计划保留扫描时 active，用于发布 CAS；changed 节点尚未带向量。 */
public record IndexPlan(String expectedActive, PreparedIndex prepared, int removed) {}
