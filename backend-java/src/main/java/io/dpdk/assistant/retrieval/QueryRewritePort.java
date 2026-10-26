package io.dpdk.assistant.retrieval;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 查询改写端口（J1-03 自有端口；正式模型/改写端口归 J1-02）。
 *
 * 语义 = Python QueryRewriter.rewrite(original)：返回检索友好的关键词串；
 * 失败退回原查询，不因改写让检索失败。
 */
public interface QueryRewritePort {

    String rewrite(String original);

    /** 兼容纯改写器；支持追问的实现覆写此入口。 */
    default String rewrite(String original, String conversationContext) {
        return rewrite(original);
    }

    /** 请求级计量显式增量入口；旧纯改写器不被迫实现计量。 */
    default String rewrite(String original, String conversationContext,
            io.dpdk.assistant.usage.UsageCore.UsageRecorder usage) {
        return rewrite(original, conversationContext);
    }

    /**
     * LLM 腿客户端（可选注入）：对应 Python chat_model.generate 的两处调用
     * （追问消解与关键词改写）。抛异常/返回 null 等价于上游失败 → 启发式回退。
     */
    @FunctionalInterface
    interface LlmClient {

        /** 返回模型输出文本；任何异常都按 Python except 分支处理。 */
        String generate(String systemPrompt, String userPrompt) throws Exception;

        /** 富客户端按实际请求/重试记录；旧纯文本lambda不伪造上游用量。 */
        default String generate(String systemPrompt, String userPrompt,
                io.dpdk.assistant.model.ModelUsageLedger usage, String stage) throws Exception {
            return generate(systemPrompt, userPrompt);
        }
    }
}
