package io.dpdk.assistant.model;

/**
 * 聊天端点显式配置（对齐 Settings 中 LLM_* 项与 OpenAICompatibleClient 的硬编码）。
 * 与 embedding 配置分离；无环境回退：baseUrl/apiKey 缺失即 {@link ModelConfigurationException}。
 *
 * @param baseUrl               例 https://dashscope.example/v1（尾斜杠会被剥掉后拼 /chat/completions）
 * @param apiKey                Bearer 凭据；不进日志、不进账本
 * @param modelName             payload 的 model 字段（ChatInput.model 可覆盖）
 * @param maxTokens             payload 的 max_tokens（对应 llm_max_tokens）
 * @param maxRetries            失败后重试次数上限（不含首次，对应 llm_max_retries）
 * @param retryBaseSeconds      指数退避基数（对应 llm_retry_base_seconds；源无抖动）
 * @param requestTimeoutSeconds 单请求读超时（源 90.0）；连接超时固定 10s（源 connect=10.0）
 * @param streamUsageEnabled    流式是否显式索取 usage（stream_options.include_usage）
 */
public record ChatClientSettings(
        String baseUrl,
        String apiKey,
        String modelName,
        int maxTokens,
        int maxRetries,
        double retryBaseSeconds,
        long requestTimeoutSeconds,
        boolean streamUsageEnabled) {

    public ChatClientSettings {
        requireConfig(baseUrl, apiKey, "LLM");
    }

    /** httpx 超时形态（90, connect=10）映射为：读超时 90s / 连接超时 10s。 */
    public static final long CONNECT_TIMEOUT_SECONDS = 10L;

    /** Python rstrip("/")：剥掉全部尾部斜杠后拼路径。 */
    public String chatCompletionsUrl() {
        return stripTrailingSlashes(baseUrl) + "/chat/completions";
    }

    static String stripTrailingSlashes(String url) {
        String s = url;
        while (s.endsWith("/")) {
            s = s.substring(0, s.length() - 1);
        }
        return s;
    }

    static void requireConfig(String base, String key, String label) {
        if (isBlank(base) || isBlank(key)) {
            throw new ModelConfigurationException(
                    label + " API is not configured. Set " + label + "_API_BASE and " + label
                            + "_API_KEY in .env.");
        }
    }

    static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
