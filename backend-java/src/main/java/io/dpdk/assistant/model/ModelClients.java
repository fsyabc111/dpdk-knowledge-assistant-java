package io.dpdk.assistant.model;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 进程级客户端注册表：同一 base URL 复用同一客户端实例（对位
 * OpenAICompatibleClient 的类级 httpx 客户端缓存与 agent 层的按 (base,model,key)
 * 模型缓存；Java 侧以 base URL 为键收敛入口）。聊天与 embedding 各自独立成表，
 * 共享一个 {@link UpstreamGate}（upstream_gate 的进程级对位）。
 *
 * <p>注意：httpx 客户端带连接池（max_keepalive 50）；JDK HttpClient 同样在
 * 实例内池化连接，但本类不对外承诺长连接语义——流式读取边界以读取回调为准。
 */
public final class ModelClients implements AutoCloseable {

    private final UpstreamGate gate;
    private final Map<String, OpenAiCompatibleChatClient> chatClients = new ConcurrentHashMap<>();
    private final Map<String, OpenAiCompatibleEmbeddingClient> embeddingClients = new ConcurrentHashMap<>();

    public ModelClients(int upstreamConcurrency) {
        this.gate = new UpstreamGate(upstreamConcurrency);
    }

    /** 同 URL 复用实例；配置对象只在首次创建时生效。 */
    public OpenAiCompatibleChatClient chatFor(ChatClientSettings settings) {
        return chatClients.computeIfAbsent(
                ChatClientSettings.stripTrailingSlashes(settings.baseUrl()),
                key -> new OpenAiCompatibleChatClient(settings, gate));
    }

    /** 同 URL 复用实例；与聊天客户端彼此独立。 */
    public OpenAiCompatibleEmbeddingClient embeddingFor(EmbeddingClientSettings settings) {
        return embeddingClients.computeIfAbsent(
                ChatClientSettings.stripTrailingSlashes(settings.baseUrl()),
                key -> new OpenAiCompatibleEmbeddingClient(settings, gate));
    }

    public UpstreamGate gate() {
        return gate;
    }

    @Override
    public void close() {
        chatClients.values().forEach(OpenAiCompatibleChatClient::close);
        embeddingClients.values().forEach(OpenAiCompatibleEmbeddingClient::close);
    }
}
