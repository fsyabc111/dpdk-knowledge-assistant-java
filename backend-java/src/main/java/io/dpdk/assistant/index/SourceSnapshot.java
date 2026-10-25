package io.dpdk.assistant.index;

import io.dpdk.assistant.contracts.index.IndexNode;
import java.util.List;

/** 扫描快照；节点尚无 id/embedding。空快照禁止发布。 */
public record SourceSnapshot(String expectedActive, List<IndexNode> nodes) {
    /** 合成调用方可直接供节点；真实 scan 会固定读取前的 active。 */
    public SourceSnapshot(List<IndexNode> nodes) { this(null, nodes); }
    public SourceSnapshot { nodes = List.copyOf(nodes); }
}
