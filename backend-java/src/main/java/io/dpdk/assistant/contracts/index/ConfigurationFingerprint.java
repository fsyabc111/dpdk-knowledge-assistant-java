package io.dpdk.assistant.contracts.index;

/**
 * 配置指纹端口（owner J0-01 设计/J1-01 集成者落地实现；只读检索与 ingest 共享同一实现）。
 *
 * 输出与 Python `json.dumps(sort_keys=True)` 逐字节兼容（字段顺序、默认空格、ASCII 转义）；
 * 四字段与 dimensions=1024/chunker 固定（契约 identity-and-data.md）。
 */
public interface ConfigurationFingerprint {

    String pythonCompatibleConfiguration(EmbeddingConfig config);
}
