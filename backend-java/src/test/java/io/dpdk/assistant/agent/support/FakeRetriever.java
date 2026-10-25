package io.dpdk.assistant.agent.support;

import io.dpdk.assistant.agent.port.KbChunk;
import io.dpdk.assistant.agent.port.KnowledgeRetriever;
import java.util.ArrayList;
import java.util.List;

/**
 * 内存假检索器（对位 golden_support.FakeRetriever）：记录查询与上下文。
 */
public final class FakeRetriever implements KnowledgeRetriever {

    public final List<KbChunk> chunks;
    public final List<String> queries = new ArrayList<>();
    public final List<String> contexts = new ArrayList<>();
    public RuntimeException error;

    public FakeRetriever() {
        this.chunks = new ArrayList<>(List.of(new KbChunk("chk_1", "docs/dns.md", "报文格式",
                "DNS", "qname 以标签序列编码", 0.9)));
    }

    public FakeRetriever(List<KbChunk> chunks) {
        this.chunks = new ArrayList<>(chunks);
    }

    public static FakeRetriever failing(RuntimeException error) {
        FakeRetriever retriever = new FakeRetriever();
        retriever.error = error;
        return retriever;
    }

    @Override
    public List<KbChunk> search(String query, int limit, String conversationContext) {
        queries.add(query);
        contexts.add(conversationContext);
        if (error != null) {
            throw error;
        }
        return new ArrayList<>(chunks.subList(0, Math.min(limit, chunks.size())));
    }
}
