package io.dpdk.assistant.platform.wiring;

import io.dpdk.assistant.platform.observability.ProcessMetricsRegistry;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 唯一正式过程registry；默认HTTP导出与业务hook消费同一实例。 */
@Configuration
public class ObservabilityWiring {
    @Bean ProcessMetricsRegistry processMetricsRegistry() { return new ProcessMetricsRegistry(); }
}
