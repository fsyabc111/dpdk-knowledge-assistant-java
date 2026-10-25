package io.dpdk.assistant.operations;

import io.dpdk.assistant.Application;
import java.io.PrintWriter;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.MapPropertySource;

/** 独立 CLI 入口；锁定只读启动条件，用户参数不能覆盖迁移/后台禁用开关。 */
public final class OperationsMain {
    private OperationsMain() { }
    public static Map<String, Object> startupSafety() {
        return Map.of("spring.main.web-application-type", "none", "spring.flyway.enabled", false,
                "spring.sql.init.mode", "never", "dpdk.migration.run-on-startup", false,
                "dpdk.task.background-enabled", false, "task.background-enabled", false,
                "spring.main.banner-mode", "off", "logging.level.root", "OFF", "logging.level.io.dpdk", "OFF");
    }
    public static void main(String[] args) {
        AtomicReference<ConfigurableApplicationContext> context = new AtomicReference<>();
        int exit;
        try {
            exit = OperationsCli.run(args, () -> {
                SpringApplication app = new SpringApplication(Application.class);
                app.setWebApplicationType(WebApplicationType.NONE);
                app.setBannerMode(org.springframework.boot.Banner.Mode.OFF);
                app.setLogStartupInfo(false);
                // 日志初始化早于Context initializer，必须在环境事件阶段先锁住stdout。
                app.addListeners(new EarlySafety());
                app.addInitializers(ctx -> ctx.getEnvironment().getPropertySources().addFirst(
                        new MapPropertySource("operations-safety", startupSafety())));
                // 即使外部日志配置为某子包单独启用，框架输出也不能污染JSON stdout。
                java.io.PrintStream output = System.out;
                try {
                    System.setOut(System.err);
                    context.set(app.run());
                } finally { System.setOut(output); }
                return context.get().getBean(OperationsPort.class);
            }, new PrintWriter(System.out, true), new PrintWriter(System.err, true));
        } finally {
            if (context.get() != null) context.get().close();
        }
        System.exit(exit);
    }

    private static final class EarlySafety implements
            org.springframework.context.ApplicationListener<org.springframework.boot.context.event.ApplicationEnvironmentPreparedEvent>,
            org.springframework.core.Ordered {
        @Override public int getOrder() { return org.springframework.core.Ordered.HIGHEST_PRECEDENCE + 5; }
        @Override public void onApplicationEvent(org.springframework.boot.context.event.ApplicationEnvironmentPreparedEvent event) {
            event.getEnvironment().getPropertySources().addFirst(
                    new MapPropertySource("operations-early-safety", startupSafety()));
        }
    }
}
