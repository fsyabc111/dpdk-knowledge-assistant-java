package io.dpdk.assistant.retrieval;

import io.dpdk.assistant.contracts.index.ConfigurationFingerprint;
import io.dpdk.assistant.contracts.index.EmbeddingConfig;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 冻结配置指纹实现（契约 index.ConfigurationFingerprint；J1-03 落地于本包）。
 *
 * 与 Python backend/app/services/rag_index_service.py:index_configuration 逐字节
 * 兼容：json.dumps({'model','api_base','dimensions','chunker'}, sort_keys=True)，
 * 默认 ensure_ascii 与默认 ", "/": " 分隔，键序 api_base/chunker/dimensions/model。
 */
public final class IndexConfigurationFingerprint implements ConfigurationFingerprint {

    @Override
    public String pythonCompatibleConfiguration(EmbeddingConfig config) {
        if (config == null) {
            throw new IllegalArgumentException("EmbeddingConfig 不能为空");
        }
        // sort_keys=True 的固定键序（ASCII 字典序）。
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("api_base", config.apiBase());
        payload.put("chunker", config.chunker());
        payload.put("dimensions", config.dimensions());
        payload.put("model", config.model());
        return PythonJson.dumps(payload, true);
    }
}
