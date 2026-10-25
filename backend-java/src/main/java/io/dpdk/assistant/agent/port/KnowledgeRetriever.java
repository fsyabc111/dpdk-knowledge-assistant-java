package io.dpdk.assistant.agent.port;

import java.util.List;

/**
 * 知识检索端口（对位 Python rag.interfaces.KnowledgeRetriever 的 agent 消费面）。
 *
 * <p>生产实现由 J1-03 ReleaseReader 适配；测试用内存替身。conversationContext
 * 为会话历史摘要（追问指代消解用），为空表示首轮。检索失败抛 RuntimeException，
 * 由 kb_search 工具转结构化错误（不抛穿为 500）。
 */
public interface KnowledgeRetriever {

    /** limit 对位 kb_search 的 limit=8；返回片段按相关度排序。 */
    List<KbChunk> search(String query, int limit, String conversationContext);
}
