package io.dpdk.assistant.knowledge.workflow;

import io.dpdk.assistant.contracts.knowledge.PublishedKnowledgeReader;
import io.dpdk.assistant.contracts.platform.TransactionRunner;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/** 发布快照SQL属于知识模块；索引只消费契约，不查询工作流表。 */
public final class PublishedKnowledgeRepository implements PublishedKnowledgeReader {
    private final TransactionRunner transactions;
    public PublishedKnowledgeRepository(TransactionRunner transactions) { this.transactions = transactions; }
    @Override public List<Entry> published() {
        return transactions.required(tx -> {
            try (var statement = tx.connection().prepareStatement(
                    "SELECT id,title,body,revision FROM rag_knowledge WHERE status='published' ORDER BY id");
                 var rows = statement.executeQuery()) {
                List<Entry> entries = new ArrayList<>();
                while (rows.next()) { entries.add(new Entry(rows.getString(1), rows.getString(2), rows.getString(3), rows.getInt(4))); }
                return List.copyOf(entries);
            } catch (SQLException failed) { throw new IllegalStateException("已发布知识快照读取失败", failed); }
        });
    }
}
