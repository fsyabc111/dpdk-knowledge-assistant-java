package io.dpdk.assistant.contracts.platform;

/** UUID熵源；合成验收可注入合法确定UUID，业务仍负责实体前缀和截取。 */
@FunctionalInterface
public interface UuidPort {
    java.util.UUID next();
}
