package io.dpdk.assistant.contracts.index;

/** embedding完成后的待发布快照；发布事务不跨外部调用。 */
public record PreparedIndex(String releaseId, String configuration, String createdAt,
                            java.util.List<IndexNode> desiredNodes,
                            java.util.List<IndexNode> changedNodes) {
    public PreparedIndex {
        desiredNodes = java.util.List.copyOf(desiredNodes);
        changedNodes = java.util.List.copyOf(changedNodes);
    }
}
