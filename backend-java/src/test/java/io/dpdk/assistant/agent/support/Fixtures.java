package io.dpdk.assistant.agent.support;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.util.Map;

/**
 * J3-02 测试夹具装载（backend-java/src/test/resources/migration/J3-02）。
 *
 * <p>harness-payloads.json 冻结黄金场景的固定模型/harness 响应形状；常量与
 * Python tests/agent/golden_support.py 一致。
 */
public final class Fixtures {

    public static final String DNS_SOURCE =
            "/* 2024001 dns plugin */\n#include <stdint.h>\nint plugin_main(void){return 0;}\n";

    private static final ObjectMapper JSON = new ObjectMapper();
    private static volatile Map<String, Object> payloads;

    private Fixtures() {
    }

    public static Map<String, Object> payload(String key) {
        Map<String, Object> all = load();
        Object value = all.get(key);
        if (value == null) {
            throw new IllegalStateException("夹具缺失: " + key);
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> cast = (Map<String, Object>) value;
        return cast;
    }

    private static Map<String, Object> load() {
        Map<String, Object> local = payloads;
        if (local != null) {
            return local;
        }
        try (InputStream in = Fixtures.class.getResourceAsStream(
                "/migration/J3-02/harness-payloads.json")) {
            if (in == null) {
                throw new IllegalStateException("找不到 migration/J3-02/harness-payloads.json");
            }
            local = JSON.readValue(in, new TypeReference<Map<String, Object>>() {
            });
            payloads = local;
            return local;
        } catch (IOException e) {
            throw new IllegalStateException("夹具解析失败", e);
        }
    }
}
