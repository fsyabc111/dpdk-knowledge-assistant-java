package io.dpdk.assistant.platform.web;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * CORS 装配（U11：React 与 Java 联调最小接线，集成者单写）。
 *
 * 对齐 Python main.py 的 CORSMiddleware：仅显式来源、方法 GET/POST/PATCH/DELETE、
 * 头 Content-Type/Authorization/X-Maintenance-Key、credentials=true；框架默认行为
 * 不自动构成兼容实现（如需差异以契约与 oracle 为准，J3-03 收口）。
 */
@Configuration
public class CorsConfig {

    @Bean
    WebMvcConfigurer corsConfigurer(@Value("${dpdk.cors-origins:}") String corsOrigins) {
        return new WebMvcConfigurer() {
            @Override
            public void addCorsMappings(CorsRegistry registry) {
                if (corsOrigins == null || corsOrigins.isBlank()) {
                    return; // 未配置即不放开任何来源（fail-closed）
                }
                registry.addMapping("/**")
                        .allowedOrigins(corsOrigins.split(","))
                        .allowedMethods("GET", "POST", "PATCH", "DELETE")
                        .allowedHeaders("Content-Type", "Authorization", "X-Maintenance-Key")
                        .allowCredentials(true);
            }
        };
    }
}
