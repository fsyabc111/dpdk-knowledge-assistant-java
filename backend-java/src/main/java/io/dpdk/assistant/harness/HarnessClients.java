package io.dpdk.assistant.harness;

import java.util.concurrent.ConcurrentHashMap;

/**
 * 进程级 harness MCP 客户端缓存（对位 get_harness_mcp_client 的 _clients 字典）：
 * 同一 URL 复用同一实例，agent 工具层与作业提交路径由此收敛到同一入口。
 */
public final class HarnessClients {

    private static final ConcurrentHashMap<String, StreamableHttpHarnessClient> CLIENTS =
            new ConcurrentHashMap<>();

    private HarnessClients() {
    }

    public static StreamableHttpHarnessClient forUrl(String url) {
        return CLIENTS.computeIfAbsent(url, StreamableHttpHarnessClient::new);
    }

    static StreamableHttpHarnessClient putForTest(String url, StreamableHttpHarnessClient client) {
        CLIENTS.put(url, client);
        return client;
    }

    static void clearForTest() {
        CLIENTS.clear();
    }
}
