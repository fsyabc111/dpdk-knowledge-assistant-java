package io.dpdk.assistant.platform.wiring;

import io.dpdk.assistant.session.MaintenanceSettings;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 会话维护端点设置装配：未配置 key 时维护端点一律 503（源语义）。 */
@Configuration
public class SessionWiring {

    @Bean
    MaintenanceSettings maintenanceSettings(
            @Value("${dpdk.maintenance.api-key:}") String maintenanceApiKey,
            @Value("${dpdk.session.retention-days:30}") int sessionRetentionDays) {
        return new MaintenanceSettings(maintenanceApiKey, sessionRetentionDays);
    }
}
