package io.dpdk.assistant.platform.web;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.AsyncSupportConfigurer;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** SSE由任务墙管理生命周期，容器默认异步超时不能提前截断合法任务。 */
@Configuration
public class StreamingConfig implements WebMvcConfigurer {
    @Override
    public void configureAsyncSupport(AsyncSupportConfigurer configurer) {
        configurer.setDefaultTimeout(0);
    }
}
