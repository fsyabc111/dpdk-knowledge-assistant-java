package io.dpdk.assistant.contracts.knowledge;

import java.util.List;

/** 知识模块向索引公开的只读发布快照，按id排序；未发布内容不得进入该接口。 */
@FunctionalInterface
public interface PublishedKnowledgeReader {
    List<Entry> published();
    record Entry(String id, String title, String body, int revision) {}
}
