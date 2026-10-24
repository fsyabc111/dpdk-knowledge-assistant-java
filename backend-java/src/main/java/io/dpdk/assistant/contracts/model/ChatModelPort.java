package io.dpdk.assistant.contracts.model;

import java.util.concurrent.Flow;

/**
 * 聊天模型端口（owner J1-02；契约 transaction-ports.json：model.ChatAndEmbeddingPort）。
 *
 * 不变模型/参数/超时与重试分类；usage 未提供用 null/unknown；流读边界≠SSE 边界；
 * 独立上游并发门。P1 仅本地替身，无真实调用。
 */
public interface ChatModelPort {

    ChatResult complete(ChatInput input);

    Flow.Publisher<ModelDelta> stream(ChatInput input);
}
