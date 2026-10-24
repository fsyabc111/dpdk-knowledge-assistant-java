package io.dpdk.assistant.model;

/** 模型/嵌入端点未配置或配置非法（对齐 Python core.llm_client.ModelConfigError）。 */
public class ModelConfigurationException extends RuntimeException {

    public ModelConfigurationException(String message) {
        super(message);
    }
}
