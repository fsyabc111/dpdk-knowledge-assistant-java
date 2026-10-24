package io.dpdk.assistant.platform.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** harness MCP 端点绑定（dpdk.harness.url，默认与 compose 内网地址一致）。 */
@ConfigurationProperties(prefix = "dpdk.harness")
public record HarnessProperties(@DefaultValue("http://harness:8100/mcp") String url) {
}
